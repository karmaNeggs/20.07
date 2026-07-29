package org.offlinemesh.app.ble

import android.util.Log
import kotlinx.coroutines.delay
import org.offlinemesh.app.crypto.CryptoUtils
import org.offlinemesh.app.crypto.SenderIdentity
import org.offlinemesh.app.data.EvidenceChunkEntity
import org.offlinemesh.app.data.EvidenceEntity
import org.offlinemesh.app.data.GroupRepository
import org.offlinemesh.app.data.NicknameEntity
import org.offlinemesh.app.data.PeerKeyEntity
import org.offlinemesh.app.data.SosEntity
import org.offlinemesh.app.sensors.LocationTracker
import org.offlinemesh.app.transport.wifidirect.WifiDirectHandoffCoordinator
import org.offlinemesh.app.transport.wifidirect.WifiDirectTuning
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything both the GATT server and GATT client paths need to do with a connection once it's
 * open: what to announce on connect, and what to do with each frame that arrives. Kept
 * independent of BluetoothGatt/BluetoothGattServer entirely so both roles share one
 * implementation instead of two copies that could quietly drift apart.
 */
@Suppress("LongParameterList", "TooManyFunctions")
// LongParameterList: one collaborator per constructor param, same shape MeshService already wires
// every other class in this file with (BeaconRadio/MeshGattClient/MeshGattServer all take a
// comparable number) — not a candidate for a params-object without adding an abstraction this
// codebase doesn't otherwise use. TooManyFunctions: one small handler per wire frame type (see the
// "per-frame handlers" section) mirrors WifiDirectAccelerator's own identical suppress for the same
// reason — many small, single-purpose functions instead of one large dispatcher.
class RelayResponder(
    private val repo: GroupRepository,
    private val relay: RelayEngine,
    private val hopTracker: HopTracker,
    private val positionTracker: PositionTracker,
    private val locationTracker: LocationTracker,
    // Optional, default-null — so nothing that constructs a RelayResponder outside MeshService
    // (e.g. a future test) needs to change. See the WifiDirectCap/WifiDirectHandoff/
    // WifiDirectAccept cases in handleIncoming and the Frame.Manifest case's WFD trigger for how
    // this is used; entirely additive, never gates or modifies the existing BLE chunk-push path.
    // Placed before onSosReceived, not after, so onSosReceived stays the last constructor param —
    // MeshService's existing call site uses trailing-lambda syntax for it.
    private val wifiDirectCoordinator: WifiDirectHandoffCoordinator? = null,
    // Fires once per newly-received (not duplicate/relayed-again), authenticated SOS in a group
    // we're actually a member of — MeshService uses this to post the system notification. No-op
    // default so this stays optional for anything else that constructs a RelayResponder.
    private val onSosReceived: suspend (SosEntity, groupName: String) -> Unit = { _, _ -> },
) {
    private val maxPositionRelayHops = 4

    // See framesToPushOnConnect's doc for what this default is for. 517 matches the MTU every
    // connection actually requests (MeshGattClient.onConnectionStateChange's requestMtu(517)) —
    // i.e. "assume negotiation succeeds," not a conservative floor.
    private val defaultMaxFrameBytes = ASSUMED_NEGOTIATED_MTU - MeshProtocol.ATT_WRITE_OVERHEAD_BYTES

    /** Forwarded from [RelayEngine.catalogEpoch] — see [ConnectionAttemptTracker]'s `currentEpoch`
     *  param for what this is used for. Kept as a passthrough rather than handing `relay` itself
     *  to [MeshGattClient]/[MeshGattServer], which only ever depend on [RelayResponder]. */
    val catalogEpoch: Int get() = relay.catalogEpoch

    // Per-connection cap on *responses* to a manifest (i.e. novel chunks actually pushed).
    // Keeps one busy item from starving the rotation through other peers.
    private val maxChunksPerSession = 150
    private val sessionBudget = ConcurrentHashMap<String, Int>()

    // Same fairness reasoning as maxChunksPerSession/sessionBudget above, applied to the
    // catalog-filter response path instead — caps how many sos/evidence-header/nickname
    // items get pushed to one peer in one connection, so a connection carrying an unusually large
    // catalog deficit can't monopolize the session; anything left over is simply offered again
    // next reconnect (see CatalogFilter's own class doc on why that's safe).
    private val maxCatalogItemsPerSession = MAX_CATALOG_ITEMS_PER_SESSION
    private val catalogItemBudget = ConcurrentHashMap<String, Int>()

    // Whether a peer has told us (this connection) it supports the WiFi Direct accelerator — see
    // Frame.WifiDirectCap below. Cleared in resetSessionBudget, already called at the start of
    // every connection by both MeshGattServer/MeshGattClient — zero new wiring needed there.
    // LRU-bounded, same reasoning and shape as ConnectionAttemptTracker's cooldownUntil: a BLE
    // address isn't a stable identity (rotates every ~15min), so an unbounded map here would leak
    // one entry per address ever seen, forever, most of which are never seen again — found by an
    // automated regression scan of this session's changes, fixed the same way that one was.
    private val peerWfdCapable = object : LinkedHashMap<String, Boolean>(
        WFD_PEER_MAP_INITIAL_CAPACITY, WFD_PEER_MAP_LOAD_FACTOR, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) = size > MAX_TRACKED_WFD_PEERS
    }

    @Synchronized
    private fun markWfdCapable(address: String) {
        peerWfdCapable[address] = true
    }

    @Synchronized
    private fun isWfdCapable(address: String): Boolean = peerWfdCapable[address] == true

    @Synchronized
    fun resetSessionBudget(address: String) {
        sessionBudget[address] = 0
        catalogItemBudget[address] = 0
        peerWfdCapable.remove(address)
    }

    /** True if we should accept this frame. If it belongs to a group we hold the key to, the tag
     *  must verify (forgery/tamper => false). If it's not our group, we can't verify and return true
     *  so blind relaying still works — a member downstream does the real check. [macInput] is only
     *  computed when we actually have a key, so non-member relaying stays cheap. */
    private fun authOk(groupId: String, mac: ByteArray?, macInput: () -> ByteArray): Boolean {
        val key = repo.getGroupKey(groupId) ?: return true
        return CryptoUtils.constantTimeEquals(CryptoUtils.authTag(key, macInput()), mac)
    }

    /** Result of [checkSenderKeyPin] — see that function's doc. */
    internal enum class SenderKeyPinResult { OK, MISMATCH }

    /** The ONLY place a sender's Ed25519 public key gets pinned — from the presence
     *  heartbeat's [MeshFrameCodec.Frame.Presence.senderPublicKey], never inferred from a signed
     *  content frame. Looks up any existing pin, defers the actual OK/MISMATCH decision to
     *  [checkSenderKeyPin] (kept pure/`internal` so it's directly unit-testable without a DAO —
     *  same reasoning as [presenceWithinSkew]/[selectPositionsToRelay] below), and persists a new
     *  pin on first sight. */
    private suspend fun pinOrCheckSenderKey(
        groupId: String,
        senderId: String,
        publicKey: ByteArray?,
    ): SenderKeyPinResult {
        val existing = repo.peerKeyDao.get(groupId, senderId)
        val result = checkSenderKeyPin(existing?.publicKey, publicKey)
        if (result == SenderKeyPinResult.OK && existing == null && publicKey != null) {
            repo.peerKeyDao.insert(PeerKeyEntity(groupId, senderId, publicKey, System.currentTimeMillis()))
        }
        return result
    }

    /** True = OK to proceed (either a genuine signature verified, or there was nothing to check
     *  yet); false = hard reject, same effect as an [authOk] failure. Looks up any existing pin,
     *  defers the actual pass/fail decision to [signatureCheckPasses] (pure/`internal`, directly
     *  unit-testable without a DAO). */
    private suspend fun verifySignatureIfPinned(
        groupId: String,
        senderId: String,
        signature: ByteArray?,
        signedData: ByteArray,
    ): Boolean {
        val pinned = repo.peerKeyDao.get(groupId, senderId)
        return signatureCheckPasses(pinned?.publicKey, signature, signedData)
    }

    @Synchronized
    private fun consumeBudget(address: String, want: Int): Int {
        val used = sessionBudget.getOrDefault(address, 0)
        val remaining = (maxChunksPerSession - used).coerceAtLeast(0)
        val take = minOf(remaining, want)
        sessionBudget[address] = used + take
        return take
    }

    @Synchronized
    private fun consumeCatalogItemBudget(address: String, want: Int): Int {
        val used = catalogItemBudget.getOrDefault(address, 0)
        val remaining = (maxCatalogItemsPerSession - used).coerceAtLeast(0)
        val take = minOf(remaining, want)
        catalogItemBudget[address] = used + take
        return take
    }

    /** All of our currently-HELD SOS/evidence-header/nickname item keys, across every active group
     *  — the exact set [CatalogFilter] gets built over, and the exact key format
     *  [handleIncoming]'s `Frame.CatalogFilter` case tests a peer's incoming filter against. Kept
     *  as one shared helper so the two sides can never quietly drift out of sync on key format.
     *
     *  Deliberately "held" ([RelayEngine.heldSosIds]/`heldEvidenceIds`), not "relayable"
     *  ([RelayEngine.relayableSos]/`relayableEvidenceMeta`, ttl > 0 only) — an item at ttl 0 has
     *  stopped propagating but is still held until the 48h prune, and omitting it here previously
     *  meant every peer kept re-pushing it to us for the rest of that window since our own filter
     *  never admitted to already having it. This only changes what we ADVERTISE holding; what
     *  actually gets pushed in response to a peer's filter (`handleIncoming`'s `Frame.CatalogFilter`
     *  case) still reads `relayableSos`/`relayableEvidenceMeta`, unchanged — a ttl-0 item is never
     *  sent, only acknowledged as already held. */
    private suspend fun currentCatalogKeys(): List<String> {
        val keys = mutableListOf<String>()
        for (id in relay.heldSosIds()) keys += "sos:$id"
        for (id in relay.heldEvidenceIds()) keys += "evid:$id"
        for (g in repo.groupDao.getActiveGroups()) {
            for (n in relay.nicknamesForGroup(g.id)) keys += "nick:${n.groupId}:${n.senderId}:${n.updatedAt}"
        }
        return keys
    }

    /**
     * On connect we announce: a [CatalogFilter] of everything we hold (SOS/evidence-headers/
     * nicknames), presence, position, and per-evidence-item have-bitsets. Actual SOS/evidence-
     * header/nickname *content*, and evidence chunk bytes, only move in response to something the
     * peer tells us — a received [CatalogFilter] (see `Frame.CatalogFilter` in [handleIncoming])
     * or a received manifest (`FRAME_MANIFEST`) respectively — never eagerly here. This is the
     * same "advertise state, then push only the deficit" shape the evidence-chunk manifest exchange
     * already used, generalized to the whole catalog: once both sides have synced, a connection
     * exchanges two compact filters and near-nothing else, instead of re-walking every SOS/header/
     * nickname this device has ever seen on every single connection.
     *
     * This replaced an earlier design (`PeerDeliveryTracker`) that instead remembered, per specific
     * peer address, which static items that peer had already been sent — correct at small scale,
     * but the tracking itself needed a bounded, evictable cache, and an evicted peer silently
     * reverted to "resend them everything." A [CatalogFilter] needs no memory of any specific peer
     * at all: each side freshly advertises its own current holdings every connection, so there is
     * nothing to evict and nothing that goes stale by being forgotten.
     *
     * **MTU fallback.** [CatalogFilter] sizes itself to the actual catalog (see its `sizeBitsFor`
     * doc), but a filter that still doesn't fit this specific connection's negotiated MTU falls
     * back to eagerly pushing content directly — see [framesToPushOnConnect]'s `maxFrameBytes`
     * param. Without this, a low-MTU connection would decode a truncated filter as garbage (or
     * fail to decode at all) and — since delivery is otherwise exclusively reactive to receiving a
     * peer's filter — silently deliver nothing on that connection.
     *
     * [maxFrameBytes] defaults generously (assumes a 517-byte MTU negotiation succeeded) so
     * existing callers (and every pre-existing test) see no change unless they opt into passing
     * the real tracked value. Real production callers ([MeshGattClient]/[MeshGattServer]) always
     * pass their actual negotiated MTU for this connection.
     */
    suspend fun framesToPushOnConnect(maxFrameBytes: Int = defaultMaxFrameBytes): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        val keys = currentCatalogKeys()
        val filter = CatalogFilter.build(keys)
        val filterFrame = MeshFrameCodec.encodeCatalogFilter(filter.seed, filter.sizeBits, filter.toBits())
        if (filterFrame.size <= maxFrameBytes) {
            frames += filterFrame
            // Cheap, low-volume (once per connection, not per frame) — lets a live logcat pull
            // during a "message isn't arriving" report confirm whether the item was even in this
            // device's own outgoing catalog at connect time, without needing to reproduce anything.
            Log.d("RelayResponder", "advertising catalog filter: ${keys.size} item(s) held, ${filterFrame.size}B")
        } else {
            // The filter itself doesn't fit this connection's negotiated MTU — dynamic sizing
            // (CatalogFilter.sizeBitsFor) keeps this rare for a typical short-lived group's small
            // catalog, but a hard floor (unfiltered legacy chipsets, some OEM stacks that never
            // grant more than the default 23-byte MTU) must never silently drop SOS delivery the
            // way it used to: before this fallback existed, a filter that didn't fit simply failed
            // to decode on the peer's end, and since the catalog-filter redesign made SOS/evidence-
            // header/nickname delivery EXCLUSIVELY reactive to receiving a peer's filter, that
            // failure was silent and total on this connection. Falls back to the pre-catalog-
            // filter behavior: push relayable content eagerly. Correctness (it arrives) beats
            // efficiency (a compact diff) here.
            Log.w(
                "RelayResponder",
                "catalog filter (${filterFrame.size}B) exceeds this connection's ${maxFrameBytes}B " +
                    "budget — falling back to eager push of ${keys.size} item(s)"
            )
            for (sos in relay.relayableSos()) frames += MeshFrameCodec.encodeSos(sos)
            for (meta in relay.relayableEvidenceMeta()) frames += MeshFrameCodec.encodeEvidMeta(meta)
            for (g in repo.groupDao.getActiveGroups()) {
                for (n in relay.nicknamesForGroup(g.id)) frames += MeshFrameCodec.encodeNickname(n)
            }
        }
        // Experimental, opt-in WiFi Direct accelerator — see WifiDirectAccelerator's class doc.
        // Announced fresh every connection (never cached), matching authOk's own "check live, not
        // once" style, so flipping the opt-in toggle mid-session takes effect immediately.
        if (wifiDirectCoordinator?.capabilityAdvertisable() == true) {
            frames += MeshFrameCodec.encodeWifiDirectCap(version = 1)
        }
        for (g in repo.groupDao.getActiveGroups()) {
            // A tiny authenticated "I'm a member of this group, on this connection" heartbeat, sent
            // first for every group we're in. This is what makes presence work when beacon discovery
            // is one-directional: the single GATT link carries it both ways, so a peer that can't
            // hear our beacon still learns we're here. Costs ~70 bytes and one HMAC per group.
            repo.getGroupKey(g.id)?.let { key ->
                val identity = repo.getSenderKeyPair(g.id)
                frames += MeshFrameCodec.encodePresence(
                    g.id, repo.deviceId, System.currentTimeMillis(), key,
                    senderPublicKey = identity?.publicKey, signingPrivateKey = identity?.privateKey
                )
            }
            frames += positionFramesToPush(g.id)
        }
        for (meta in relay.relayableEvidenceMeta()) {
            // Always sent — this bitset changes as chunks arrive, so it has to keep flowing every
            // connection until the transfer completes on both ends, same as before this change.
            frames += MeshFrameCodec.encodeManifest(meta.id, meta.totalChunks, relay.haveIndexSet(meta.id))
        }
        return frames
    }

    /** My own fix, plus whatever I'm holding on behalf of other group members, one hop further out
     *  — capped to the nearest [MAX_RELAYED_POSITIONS_PER_GROUP] (see [selectPositionsToRelay]'s
     *  doc for why). Every frame is AES-GCM-sealed under the group key, so this is safe to push
     *  even to a peer that turns out not to be a member — they receive opaque bytes, not our
     *  members' GPS. */
    private fun positionFramesToPush(groupId: String): List<ByteArray> {
        val key = repo.getGroupKey(groupId) ?: return emptyList()
        val frames = mutableListOf<ByteArray>()
        val myLoc = locationTracker.location.value
        if (myLoc != null) {
            val nowSec = System.currentTimeMillis() / 1000
            frames.add(
                MeshFrameCodec.encodePosition(
                    groupId, key, repo.deviceId, myLoc.latitude, myLoc.longitude, myLoc.accuracy.toInt(), nowSec, 0,
                    signingPrivateKey = repo.getSenderKeyPair(groupId)?.privateKey
                )
            )
        }
        // Only OUR OWN fix above gets signed with OUR identity — a position we're relaying on
        // someone else's behalf was already signed (if at all) by ITS original sender before it
        // ever reached us; we have no private key to sign as them, and re-signing as ourselves
        // would be indistinguishable from us fabricating their position. That relayed signature
        // isn't actually re-attached here — see PositionTracker.Record, which only ever stores the
        // fields RelayResponder.handlePositionSealed extracts from an opened PositionBody, and this
        // codebase doesn't yet carry a signature through that relay hop. Left for a later pass if
        // multi-hop position provenance turns out to matter in practice; the receiver's own
        // pin-on-first-sight check still applies to a DIRECT neighbor's own position frame either
        // way, which is this feature's primary target.
        val toRelay = selectPositionsToRelay(positionTracker.forGroup(groupId), repo.deviceId, maxPositionRelayHops)
        for ((senderId, record) in toRelay) {
            frames.add(
                MeshFrameCodec.encodePosition(
                    groupId, key, senderId, record.lat, record.lon, record.accuracyM, record.timestampSec, record.hop + 1
                )
            )
        }
        return frames
    }

    /** Proposes a WiFi Direct handoff for this evidence deficit if — and only if — the coordinator
     *  is wired in, the peer has told us it supports WFD acceleration this connection, and the
     *  deficit is large enough to be worth the overhead (see [WifiDirectTuning.
     *  MIN_DEFICIT_BYTES_FOR_HANDOFF]). Silently no-ops otherwise; never affects the caller's own
     *  BLE push either way. Only ever proposed for a group we hold the key to — a blind relay has
     *  no key to authenticate the proposal with, so it structurally never reaches this far for a
     *  group it isn't a member of (see [WifiDirectHandoffCoordinator]'s class doc). */
    private suspend fun maybeAccelerateOverWifiDirect(
        evidenceId: String,
        deficit: List<Int>,
        peerAddress: String,
        respond: suspend (ByteArray) -> Unit,
    ) {
        val coordinator = wifiDirectCoordinator ?: return
        if (!isWfdCapable(peerAddress)) return
        if (deficit.size * RelayEngine.CHUNK_SIZE < WifiDirectTuning.MIN_DEFICIT_BYTES_FOR_HANDOFF) return
        val meta = relay.evidenceMeta(evidenceId) ?: return
        val key = repo.getGroupKey(meta.groupId) ?: return
        coordinator.maybeProposeHandoff(peerAddress, evidenceId, meta.groupId, deficit, key, respond)
    }

    // ---------- per-frame handlers ----------
    // One private handler per frame type, dispatched from handleIncoming below. Each handler's own
    // early `return` abandons only that frame (exactly the same effect as the old inline `when`
    // branches had, since nothing followed the `when` inside handleIncoming's try block either) —
    // splitting these out changes nothing about behavior, only where the dispatch decision lives.

    private suspend fun handleSos(frame: MeshFrameCodec.Frame.Sos, peerAddress: String) {
        val macInput = MeshFrameCodec.sosMacInput(
            frame.sos.id, frame.sos.groupId, frame.sos.senderId, frame.sos.message, frame.sos.timestamp
        )
        // If this is a group we hold the key to, the SOS must authenticate or we neither show it
        // nor pass it on — that's what stops a phone without the key from injecting a fake
        // emergency people would run toward. If we're not a member (no key), we can't verify, so
        // we relay it blind; a member downstream will reject it if it's forged.
        if (!authOk(frame.sos.groupId, frame.sos.mac) { macInput }) {
            Log.w("RelayResponder", "SOS failed auth for a group we hold — dropping")
            return
        }
        // Additive per-sender check: the group-key mac above only proves SOME member
        // produced this; a pinned sender key catches a different member forging this one's SOS.
        if (!verifySignatureIfPinned(frame.sos.groupId, frame.sos.senderId, frame.sos.signature, macInput)) {
            Log.w(
                "RelayResponder",
                "SOS signature failed verification for a pinned sender — dropping (possible impersonation)"
            )
            return
        }
        val isMember = repo.getGroupKey(frame.sos.groupId) != null
        val isNew = relay.ingestSos(frame.sos)
        // Hop-tracking runs on every receipt, not gated behind ingestSos's dedup return — a shorter
        // path found on a later sighting of the same sos.id must still improve our estimate;
        // considerDirectHop keeps it only if better. Hop is derived from TTL consumed, not
        // hardcoded (that would mark every relayer as the origin).
        val hopsFromOrigin = RelayEngine.DEFAULT_TTL - frame.sos.ttl + 1
        hopTracker.considerDirectHop(frame.sos.groupId, frame.sos.id, hopsFromOrigin, peerAddress)
        // Only notify for groups we're actually in — a blind carrier ingests and relays SOS for
        // groups it isn't a member of too, but has no business alerting on them.
        if (isNew && isMember) {
            val groupName = repo.groupDao.getGroup(frame.sos.groupId)?.name ?: frame.sos.groupId
            onSosReceived(frame.sos, groupName)
        }
    }

    private suspend fun handleEvidMeta(frame: MeshFrameCodec.Frame.EvidMeta, respond: suspend (ByteArray) -> Unit) {
        val macInput = MeshFrameCodec.evidMacInput(
            frame.meta.id, frame.meta.groupId, frame.meta.senderId, frame.meta.timestamp,
            frame.meta.sha256, frame.meta.totalChunks, frame.meta.mimeType
        )
        if (!authOk(frame.meta.groupId, frame.meta.mac) { macInput }) {
            Log.w("RelayResponder", "evidence header failed auth for a group we hold — dropping")
            return
        }
        if (!verifySignatureIfPinned(frame.meta.groupId, frame.meta.senderId, frame.meta.signature, macInput)) {
            Log.w(
                "RelayResponder",
                "evidence header signature failed verification for a pinned sender — dropping (possible impersonation)"
            )
            return
        }
        if (relay.ingestEvidenceMeta(frame.meta)) {
            // Without this, the connection that first tells a peer an item exists could never
            // also transfer its chunks — our own manifest push already happened at connection
            // start, before we knew this item existed. Responding immediately (with our honest
            // 0%-complete manifest) lets the sender fill us in now.
            respond(MeshFrameCodec.encodeManifest(frame.meta.id, frame.meta.totalChunks, relay.haveIndexSet(frame.meta.id)))
        }
    }

    private suspend fun handleEvidChunk(frame: MeshFrameCodec.Frame.EvidChunk) {
        relay.ingestChunk(EvidenceChunkEntity(frame.chunk.evidenceId, frame.chunk.chunkIndex, frame.chunk.data))
    }

    private suspend fun handlePositionSealed(frame: MeshFrameCodec.Frame.PositionSealed, peerAddress: String) {
        // Positions only ever propagate member-to-member; a non-member has no key to open this
        // and doesn't relay it, so live GPS never travels in the clear.
        val key = repo.getGroupKey(frame.groupId) ?: return
        val body = MeshFrameCodec.openPosition(frame.sealed, key) ?: return
        if (!verifySignatureIfPinned(frame.groupId, body.senderId, body.signature, body.signedBytes)) {
            Log.w(
                "RelayResponder",
                "position signature failed verification for a pinned sender — dropping (possible impersonation)"
            )
            return
        }
        if (body.senderId != repo.deviceId) {
            // Receiving an authenticated position over GATT is itself proof this member is
            // reachable — feed presence from it too (its hop, so a relayed position also extends
            // presence outward), not just from the beacon path.
            hopTracker.considerNeighborReport(frame.groupId, "PRESENCE", body.hop, peerAddress)
            if (body.hop < maxPositionRelayHops) {
                positionTracker.offer(frame.groupId, body.senderId, body.lat, body.lon, body.accuracyM, body.timestampSec, body.hop)
            }
        }
    }

    private suspend fun handlePresence(frame: MeshFrameCodec.Frame.Presence, peerAddress: String) {
        // Replay check FIRST, before any key lookup or MAC verification: the MAC covers the
        // timestamp, so it can't be forward-dated by an attacker, but nothing previously checked
        // the timestamp was recent at all. Anyone who ever captured one valid presence frame could
        // replay it indefinitely with no key needed — it verifies as authentic forever. A group's
        // "1 hop away" reading is exactly the thing this app's core promise ("are my people near
        // me") depends on being live, not a stale capture. See presenceWithinSkew's doc for the
        // tolerance chosen.
        if (!presenceWithinSkew(frame.timestamp)) {
            Log.w("RelayResponder", "presence frame outside skew window — dropping (replay?)")
            return
        }
        // Direct-neighbor heartbeat: not stored, not relayed. Only meaningful for a group we're a
        // member of, and must authenticate (a non-member can't fake co-membership).
        val key = repo.getGroupKey(frame.groupId) ?: return
        val macInput = MeshFrameCodec.presenceMacInput(frame.groupId, frame.senderId, frame.timestamp)
        val ok = CryptoUtils.constantTimeEquals(CryptoUtils.authTag(key, macInput), frame.mac)
        if (!ok) return
        if (!presencePassesSenderIdentityChecks(frame, macInput)) return
        if (frame.senderId != repo.deviceId) {
            hopTracker.considerNeighborReport(frame.groupId, "PRESENCE", 0, peerAddress)
        }
    }

    /** The sender-identity pin/signature checks specific to presence, split out of [handlePresence]
     *  purely to keep that function's own return count within detekt's limit — both failures here have
     *  the same effect (hard reject, logged distinctly) as an ordinary [authOk] failure. See
     *  [pinOrCheckSenderKey]'s doc for why a CHANGED public key is a hard reject here specifically
     *  (this is the only frame type that carries one to pin), unlike an absent one elsewhere. */
    private suspend fun presencePassesSenderIdentityChecks(
        frame: MeshFrameCodec.Frame.Presence,
        macInput: ByteArray,
    ): Boolean {
        if (pinOrCheckSenderKey(frame.groupId, frame.senderId, frame.senderPublicKey) == SenderKeyPinResult.MISMATCH) {
            Log.w(
                "RelayResponder",
                "presence carried a public key different from the one already pinned for this sender — dropping"
            )
            return false
        }
        if (!verifySignatureIfPinned(frame.groupId, frame.senderId, frame.signature, macInput)) {
            Log.w(
                "RelayResponder",
                "presence signature failed verification for a pinned sender — dropping (possible impersonation)"
            )
            return false
        }
        return true
    }

    private suspend fun handleNickname(frame: MeshFrameCodec.Frame.Nickname) {
        val macInput = MeshFrameCodec.nicknameMacInput(
            frame.nickname.groupId, frame.nickname.senderId, frame.nickname.username, frame.nickname.updatedAt
        )
        if (!authOk(frame.nickname.groupId, frame.nickname.mac) { macInput }) {
            Log.w("RelayResponder", "nickname failed auth for a group we hold — dropping")
            return
        }
        val nickSignatureOk = verifySignatureIfPinned(
            frame.nickname.groupId, frame.nickname.senderId, frame.nickname.signature, macInput
        )
        if (!nickSignatureOk) {
            Log.w(
                "RelayResponder",
                "nickname signature failed verification for a pinned sender — dropping (possible impersonation)"
            )
            return
        }
        relay.ingestNickname(frame.nickname)
    }

    private suspend fun handleCatalogFilter(
        frame: MeshFrameCodec.Frame.CatalogFilter,
        peerAddress: String,
        respond: suspend (ByteArray) -> Unit,
    ) {
        // The peer just told us (probabilistically) what they already hold — push only what their
        // filter says they're missing. See CatalogFilter's class doc for why a false positive here
        // (skipping something they actually don't have) is safe: it costs a skipped send this
        // connection, not a lost item — the item stays in our own relayable set and gets offered
        // again, against a freshly-salted filter, on the very next reconnect.
        val peerFilter = CatalogFilter.fromBits(frame.bits, frame.seed, frame.sizeBits)

        val nicknames = repo.groupDao.getActiveGroups().flatMap { relay.nicknamesForGroup(it.id) }
        val (sosToPush, sosSkipped) = partitionByFilter(relay.relayableSos(), peerFilter) { "sos:${it.id}" }
        val (evidToPush, evidSkipped) = partitionByFilter(relay.relayableEvidenceMeta(), peerFilter) { "evid:${it.id}" }
        val (nicknamesToPush, nickSkipped) = partitionByFilter(nicknames, peerFilter) {
            "nick:${it.groupId}:${it.senderId}:${it.updatedAt}"
        }
        val filterSkipped = sosSkipped + evidSkipped + nickSkipped

        // Per-connection cap on how many of these we actually push this connection — mirrors
        // consumeBudget's role for manifest chunk pushes (see maxChunksPerSession's doc), applied
        // to this different push path. A typical short-lived group's catalog (tens of items)
        // rarely approaches this; the cap exists for the dense-crowd case where it could, so one
        // connection carrying an unusually large deficit can't monopolize the whole session
        // pushing it — anything left over is simply offered again next reconnect, same as a
        // filter-skipped item (see CatalogFilter's own class doc on why that's safe).
        val wantToPush = sosToPush.size + evidToPush.size + nicknamesToPush.size
        val allowedToPush = consumeCatalogItemBudget(peerAddress, wantToPush)
        var pushed = pushUpTo(sosToPush, allowedToPush, MeshFrameCodec::encodeSos, respond)
        pushed += pushUpTo(evidToPush, allowedToPush - pushed, MeshFrameCodec::encodeEvidMeta, respond)
        pushed += pushUpTo(nicknamesToPush, allowedToPush - pushed, MeshFrameCodec::encodeNickname, respond)
        val budgetSkipped = wantToPush - pushed

        // The single most useful line for diagnosing "messaging isn't arriving" from a live
        // logcat pull: confirms the round trip actually ran on this connection and exactly how it
        // resolved, without needing to reproduce anything or add a debugger. peerAddress included
        // since a device can hold several connections in quick succession and this is the only
        // place that ties a decision to which one.
        Log.d(
            "RelayResponder",
            "catalog filter from $peerAddress: pushed $pushed, filter-skipped $filterSkipped" +
                if (budgetSkipped > 0) ", budget-skipped $budgetSkipped (retries next connection)" else ""
        )
    }

    /** Splits [items] into what the peer's [peerFilter] says it's missing vs. what it (probably)
     *  already holds — see [handleCatalogFilter]'s doc on why a false-positive "already held" here
     *  is safe (a skipped send, not a lost item). Returns the to-push list and a skip count, since
     *  callers only need the latter for logging. */
    private inline fun <T> partitionByFilter(
        items: List<T>,
        peerFilter: CatalogFilter,
        keyOf: (T) -> String,
    ): Pair<List<T>, Int> {
        val toPush = mutableListOf<T>()
        var skipped = 0
        for (item in items) {
            if (peerFilter.mightContain(keyOf(item))) skipped++ else toPush += item
        }
        return toPush to skipped
    }

    /** Pushes [items] one at a time via [encode]/[respond] until either [items] is exhausted or
     *  [remainingBudget] items have been pushed — the shared shape behind each of
     *  [handleCatalogFilter]'s three item categories, each drawing down the same per-connection
     *  budget in sequence. Returns how many were actually pushed. */
    private suspend fun <T> pushUpTo(
        items: List<T>,
        remainingBudget: Int,
        encode: (T) -> ByteArray,
        respond: suspend (ByteArray) -> Unit,
    ): Int {
        var pushed = 0
        for (item in items) {
            if (pushed >= remainingBudget) break
            respond(encode(item))
            pushed++
        }
        return pushed
    }

    private suspend fun handleManifest(
        frame: MeshFrameCodec.Frame.Manifest,
        peerAddress: String,
        respond: suspend (ByteArray) -> Unit,
    ) {
        val myHave = relay.haveIndexSet(frame.evidenceId)
        val deficit = (myHave - frame.peerHave).sorted()
        if (deficit.isNotEmpty()) {
            // --- existing BLE push, completely unmodified ---
            val take = consumeBudget(peerAddress, deficit.size)
            if (take > 0) {
                for (chunk in relay.chunksByIndexes(frame.evidenceId, deficit.take(take))) {
                    respond(MeshFrameCodec.encodeChunk(chunk))
                    delay(15)
                }
            }
            // --- independent, fire-and-forget WiFi Direct accelerator attempt ---
            // Races the BLE push above rather than replacing it: RelayEngine.ingestChunk's
            // existing seenDao dedup already makes a chunk arriving twice (once via BLE, once via
            // WFD) a harmless no-op, so this needs no coordination with the push just above it —
            // see WifiDirectHandoffCoordinator's class doc.
            maybeAccelerateOverWifiDirect(frame.evidenceId, deficit, peerAddress, respond)
        }
    }

    private fun handleWifiDirectCap(peerAddress: String) {
        markWfdCapable(peerAddress)
    }

    private suspend fun handleWifiDirectHandoff(
        frame: MeshFrameCodec.Frame.WifiDirectHandoff,
        peerAddress: String,
        respond: suspend (ByteArray) -> Unit,
    ) {
        // not a member of this group — can't verify, drop (see class doc)
        val key = repo.getGroupKey(frame.groupId) ?: return
        wifiDirectCoordinator?.onHandoffProposalReceived(frame, peerAddress, key, respond)
    }

    private suspend fun handleWifiDirectAccept(frame: MeshFrameCodec.Frame.WifiDirectAccept, peerAddress: String) {
        val key = repo.getGroupKey(frame.groupId) ?: return
        wifiDirectCoordinator?.onHandoffAccepted(frame, peerAddress, key)
    }

    /** May call [respond] zero, one, or many times (a manifest deficit can trigger a whole run of
     *  chunk frames) — the caller supplies how a response frame actually reaches the peer. */
    suspend fun handleIncoming(bytes: ByteArray, peerAddress: String, respond: suspend (ByteArray) -> Unit) {
        val frame = MeshFrameCodec.decode(bytes) ?: return
        try {
            when (frame) {
                is MeshFrameCodec.Frame.Sos -> handleSos(frame, peerAddress)
                is MeshFrameCodec.Frame.EvidMeta -> handleEvidMeta(frame, respond)
                is MeshFrameCodec.Frame.EvidChunk -> handleEvidChunk(frame)
                is MeshFrameCodec.Frame.PositionSealed -> handlePositionSealed(frame, peerAddress)
                is MeshFrameCodec.Frame.Presence -> handlePresence(frame, peerAddress)
                is MeshFrameCodec.Frame.Nickname -> handleNickname(frame)
                is MeshFrameCodec.Frame.CatalogFilter -> handleCatalogFilter(frame, peerAddress, respond)
                is MeshFrameCodec.Frame.Manifest -> handleManifest(frame, peerAddress, respond)
                is MeshFrameCodec.Frame.WifiDirectCap -> handleWifiDirectCap(peerAddress)
                is MeshFrameCodec.Frame.WifiDirectHandoff -> handleWifiDirectHandoff(frame, peerAddress, respond)
                is MeshFrameCodec.Frame.WifiDirectAccept -> handleWifiDirectAccept(frame, peerAddress)
            }
        } catch (e: Exception) {
            Log.w("RelayResponder", "frame handling failed: ${e.message}")
        }
    }

    companion object {
        private const val MAX_TRACKED_WFD_PEERS = 200
        private const val MAX_CATALOG_ITEMS_PER_SESSION = 200
        private const val WFD_PEER_MAP_INITIAL_CAPACITY = 16
        private const val WFD_PEER_MAP_LOAD_FACTOR = 0.75f

        // See defaultMaxFrameBytes' doc — matches the MTU MeshGattClient always requests on
        // connect, i.e. "assume negotiation succeeds" rather than a conservative pre-negotiation
        // floor (MeshProtocol.DEFAULT_ATT_MTU, which is what a connection actually has until/
        // unless negotiation completes).
        private const val ASSUMED_NEGOTIATED_MTU = 517

        // Consistent with the clock agreement the rotating-ID scheme already assumes
        // (CryptoUtils.ID_WINDOW_SECONDS = 60s, tolerating +/-1 adjacent window) — two minutes is
        // generous headroom above that for a heartbeat that's meant to be "you're reachable right
        // now," not "you were reachable at some point."
        private const val PRESENCE_MAX_SKEW_MS = 120_000L

        /** Pure — no [GroupRepository]/key access, deliberately, so this can be checked (and unit-
         *  tested) before ever touching the group key. See the `Frame.Presence` case above for why
         *  the ordering matters: the MAC already covers [timestamp], so an attacker can't forge a
         *  fresher one, but nothing previously verified the timestamp was recent at all — a replay
         *  of one captured frame verified as authentic forever. `internal` so it's directly
         *  unit-testable without a Robolectric `Context` (this class's other tests need one only
         *  because [RelayEngine]/[GroupRepository] do; this function needs neither). */
        internal fun presenceWithinSkew(timestamp: Long, now: Long = System.currentTimeMillis()): Boolean =
            kotlin.math.abs(now - timestamp) <= PRESENCE_MAX_SKEW_MS

        /** Pure decision behind [pinOrCheckSenderKey] — given whatever's already pinned
         *  for a (groupId, senderId) and the key this frame just carried, decide OK vs. MISMATCH.
         *  `internal`, no DAO/repo access, so directly unit-testable (this class's other tests need
         *  a `Context`/Robolectric only because [RelayEngine]/[GroupRepository] do; this function
         *  needs neither — see [RelayResponderTest]'s class doc for that constraint, which doesn't
         *  apply here).
         *
         *  [incomingPublicKey] null (a peer not yet carrying one, or intentionally omitted after
         *  the first heartbeat — see [MeshFrameCodec.encodePresence]'s doc) is always tolerated:
         *  nothing to check, so [SenderKeyPinResult.OK]. [existingPublicKey] null means first
         *  sight — also OK (the caller persists the new pin). Both present is the real check: equal
         *  is OK, different is [SenderKeyPinResult.MISMATCH] — a legitimate sender's identity never
         *  changes mid-group by design (see [org.offlinemesh.app.data.GroupRepository.
         *  ensureSenderIdentity]'s doc for why), so a changed key is either device-swap-without-
         *  rejoin (out of scope) or impersonation, and this app's threat model treats both the same
         *  way: don't silently trust the new key. */
        internal fun checkSenderKeyPin(
            existingPublicKey: ByteArray?,
            incomingPublicKey: ByteArray?,
        ): SenderKeyPinResult {
            if (incomingPublicKey == null || existingPublicKey == null) return SenderKeyPinResult.OK
            return if (existingPublicKey.contentEquals(incomingPublicKey)) {
                SenderKeyPinResult.OK
            } else {
                SenderKeyPinResult.MISMATCH
            }
        }

        /** Pure decision behind [verifySignatureIfPinned] — `internal`, no DAO access,
         *  directly unit-testable (see [checkSenderKeyPin]'s doc for why that matters here).
         *  [signature] null is tolerated (a peer not yet signing, or on a build that predates this
         *  feature) — this, combined with [pinnedPublicKey] null also being tolerated (nothing
         *  pinned yet to check against), is what makes enforcement "mandatory once pinned, tolerant
         *  before" per-SENDER rather than a single global rollout flag: once a key IS pinned for a
         *  given sender, a present signature that fails to verify under it is never tolerated. */
        internal fun signatureCheckPasses(
            pinnedPublicKey: ByteArray?,
            signature: ByteArray?,
            signedData: ByteArray,
        ): Boolean {
            if (signature == null || pinnedPublicKey == null) return true
            return SenderIdentity.verify(pinnedPublicKey, signature, signedData)
        }

        // See selectPositionsToRelay's doc for what this bounds and why.
        private const val MAX_RELAYED_POSITIONS_PER_GROUP = 12

        /** Which of [positions] (a group's currently-known member positions) get relayed to a peer
         *  on this connection, and in what order: the nearest (fewest-hop)
         *  [MAX_RELAYED_POSITIONS_PER_GROUP], excluding [selfId] (pushed separately, always) and
         *  anything whose relay hop would reach or exceed [maxHops].
         *
         *  **Why this cap exists (deliberately scoped small).** Position frames only refresh
         *  on a GATT reconnect — there's no separate lightweight position channel — so every
         *  reconnect currently re-pushes one position frame per *other* known member in the group,
         *  uncapped. That means per-connection cost scales with total group size, not with
         *  anything bounded, which is the real reason `syncedReconnectCooldownMs` had to be
         *  neutralized back to the ordinary cooldown (see `docs/DECISIONS.md`, decision 5) instead
         *  of being tuned longer: a longer cooldown reduces reconnect *frequency*, not the
         *  per-reconnect *cost*, so it couldn't fix this on its own. Capping to the nearest members
         *  by hop is the smallest change that bounds the cost without a redesign — the larger idea
         *  (a lightweight position channel independent of reconnects, or positions carried in the
         *  beacon itself) is a real, separate design question, deliberately not attempted here.
         *
         *  `internal`, no key/repo access — pure selection logic, directly unit-testable despite
         *  [positionFramesToPush] itself needing a real group key (Keystore, unavailable under
         *  Robolectric — see [RelayResponderTest]'s class doc for the same constraint). */
        internal fun selectPositionsToRelay(
            positions: Map<String, PositionTracker.Record>,
            selfId: String,
            maxHops: Int,
            limit: Int = MAX_RELAYED_POSITIONS_PER_GROUP,
        ): List<Pair<String, PositionTracker.Record>> =
            positions.entries
                .asSequence()
                .filter { (senderId, record) -> senderId != selfId && record.hop + 1 < maxHops }
                .map { it.key to it.value }
                .sortedBy { it.second.hop }
                .take(limit)
                .toList()
    }
}
