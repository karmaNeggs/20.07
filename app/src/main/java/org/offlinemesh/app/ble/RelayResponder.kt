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
import org.offlinemesh.app.diagnostics.DiagnosticsLog
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
    // Shared with MeshGattClient (PLAN-v2.md §5.2 / P0b) — this side WRITES the address->senderId
    // mapping (see the per-frame handlers below, right alongside their existing hopTracker calls);
    // MeshGattClient reads it to key ConnectionAttemptTracker on the stable senderId once known,
    // instead of the BLE address, which rotates roughly every 15 minutes.
    private val peerIdentity: PeerIdentityResolver,
    // Shared with MeshGattClient/MeshGattServer (PLAN-v2.md P1 §5.3) — this side READS it to learn
    // the current open-link count (ForwardingPolicy's degree signal) and to push a flood-forward
    // to every other currently-open link. See ConnectionRegistry's class doc.
    private val connectionRegistry: ConnectionRegistry,
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
    // Raised from 4 to 120 (decision 33, docs/DECISIONS.md) — the original 4 had no documented
    // justification anywhere in this codebase or its decision log; it just happened to comfortably
    // cover this app's stated 3-8 person GROUP size (PLAN-v2.md §5.5). But a position/presence
    // frame's relay depth isn't bounded by group membership — ANY phone, member or not, can carry a
    // frame one hop further (the blind-relay architecture), so a long, unbroken CHAIN of relays can
    // legitimately span far more devices than the group itself has members. 120 stays comfortably
    // below the 1-byte hop field's real ceiling (255, MeshProtocol.UNKNOWN_HOP's own sentinel value)
    // while giving real multi-kilometer reach at realistic per-hop BLE range, given an unbroken
    // chain with no gaps. Reaching hundreds of kilometers would need thousands of hops, which simply
    // doesn't fit an 8-bit field — that's a wire-format change (widening to 2 bytes), deliberately
    // NOT made this pass given v0.7.0/0.7.1-dev APKs are already out on real devices. Shared (by doc
    // only, not by reference — no ble-internal type to hang a single source of truth off, same
    // pattern PositionTracker.PER_HOP_SLACK_SECONDS/HopTracker.PER_HOP_SLACK_MS already use) with
    // BeaconRadio.MAX_POSITION_RELAY_HOPS, which must stay equal. Also reused unchanged for opaque
    // (blind-carried) position AND presence custody below (takeOpaqueCustody/
    // takeOpaquePresenceCustody) — this was already sharing one ceiling for both before this
    // change, and there's no reason to fork it into two now.
    @Suppress("MagicNumber") // see the doc above — 120 is a deliberately chosen ceiling, not a stray literal
    private val maxPositionRelayHops = 120

    // Blind-relay custody for frames belonging to groups we hold no key for — see
    // OpaqueFrameRelay's class doc for why positions and presence both needed this while content
    // never did. Three stores, not one, purely so each can be reasoned about (and counted)
    // separately.
    private val opaquePositions = OpaqueFrameRelay()
    // Presence gets a shorter carry window than positions: a receiver's skew gate is
    // PRESENCE_MAX_SKEW_MS plus per-hop slack, so holding a heartbeat longer than the base skew
    // window just spends airtime on frames the far end will reject as replays.
    private val opaquePresence = OpaqueFrameRelay(maxAgeMillis = PRESENCE_MAX_SKEW_MS)
    // SOS content is now sealed (decision 37, docs/DECISIONS.md) — a non-member can no longer read
    // it, so it needs the same blind-custody treatment position/presence already had. Default
    // maxAgeMillis (matches position's own, generous enough for an SOS's own longer useful life).
    private val opaqueSos = OpaqueFrameRelay()

    // See framesToPushOnConnect's doc for what this default is for. 517 matches the MTU every
    // connection actually requests (MeshGattClient.onConnectionStateChange's requestMtu(517)) —
    // i.e. "assume negotiation succeeds," not a conservative floor.
    private val defaultMaxFrameBytes = ASSUMED_NEGOTIATED_MTU - MeshProtocol.ATT_WRITE_OVERHEAD_BYTES

    /** Combines [RelayEngine.catalogEpoch] (new SOS/evidence/nickname content) with
     *  [PositionTracker.positionEpoch] (a fresher position accepted for someone) into the one
     *  signal [ConnectionAttemptTracker]'s `currentEpoch` param actually needs: "has ANYTHING
     *  worth reconnecting early for changed since I last fully synced with this peer." Originally
     *  content-only — live testing found a relayed position had no equivalent fast path at all,
     *  sitting out the full, un-skippable cooldown no matter how quickly it was actually received,
     *  while content relayed almost immediately. Both are monotonically-increasing counters, so
     *  summing them is a safe combined "did anything change" signal for [canAttempt]'s simple
     *  inequality check — it can never coincidentally return to an earlier value. Kept as a
     *  passthrough rather than handing `relay`/`positionTracker` themselves to
     *  [MeshGattClient]/[MeshGattServer], which only ever depend on [RelayResponder]. */
    val catalogEpoch: Int get() = relay.catalogEpoch + positionTracker.positionEpoch

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
        // remove(), not set-to-0: this runs once at the start of EVERY connection (both GATT
        // roles), so a set-to-0 entry accumulated one per address ever seen, forever — a real,
        // confirmed unbounded-growth bug (PLAN-v2.md §1.3, independent of address rotation:
        // even a re-key onto a stable identity wouldn't have bounded this on its own, since these
        // two maps were never evicted at all). consumeBudget/consumeCatalogItemBudget already
        // treat a missing entry as 0 via getOrDefault, so this changes memory footprint only.
        sessionBudget.remove(address)
        catalogItemBudget.remove(address)
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

    /** [FIRST_SIGHT]/[UNCHANGED] are both "nothing to worry about". [CHANGED] means this sender is
     *  presenting a different key than the one already pinned for them — see [pinOrCheckSenderKey]
     *  for why that is a warning that re-pins, and NOT a reason to drop traffic. */
    internal enum class SenderKeyPinResult { FIRST_SIGHT, UNCHANGED, CHANGED }

    /** The ONLY place a sender's Ed25519 public key gets pinned — from the presence
     *  heartbeat's [MeshFrameCodec.Frame.Presence.senderPublicKey], never inferred from a signed
     *  content frame. Looks up any existing pin, defers the pure comparison to [checkSenderKeyPin]
     *  (kept `internal` so it's directly unit-testable without a DAO — same reasoning as
     *  [presenceWithinSkew]/[selectPositionsToRelay] below), and writes the pin on first sight OR
     *  when it has changed.
     *
     *  **Why a changed key re-pins instead of rejecting.** It originally hard-rejected the whole
     *  frame, on the reasoning that a changed key is indistinguishable from impersonation (SSH's
     *  trust-on-first-use model). Live testing showed why that's the wrong tradeoff here: a pin
     *  outlives the keypair that created it (pins are in Room, keypairs in EncryptedSharedPreferences
     *  — a reinstall, a Keystore reset, or an APK upgrade can leave the two out of step), and once
     *  stale, it silently dropped EVERY signed frame from that peer forever — presence, SOS, and
     *  positions all gone, while beacon-derived hop count kept working. That reads to a user as
     *  "connected, 1 hop away, but nothing arrives", with no way to recover short of clearing app
     *  data on both phones. A key change now re-pins and logs a warning; the frame still has to pass
     *  the group-key HMAC ([authOk]) to be accepted at all, so a non-member still can't inject
     *  anything. What's given up is detection of a *member* swapping their own identity mid-group —
     *  worth surfacing (and it is, loudly), but not worth silently breaking the app over. */
    private suspend fun pinOrCheckSenderKey(
        groupId: String,
        senderId: String,
        publicKey: ByteArray?,
    ): SenderKeyPinResult {
        val existing = repo.peerKeyDao.get(groupId, senderId)
        val result = checkSenderKeyPin(existing?.publicKey, publicKey)
        val isNewOrChanged =
            result == SenderKeyPinResult.FIRST_SIGHT || result == SenderKeyPinResult.CHANGED
        if (publicKey != null && isNewOrChanged) {
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

    /** Wraps [PeerIdentityResolver.learn], logging only the events actually worth a line — a
     *  brand-new peer resolved, or an address rotating onto an already-known one — so the P0b
     *  hardware gate (`PLAN-v2.md` Part 7: "ship the debug APK, user runs 3 phones, exports the
     *  log") has something concrete to check: `distinct=` should stay near the physical phone
     *  count even as `addresses=` climbs with rotation, instead of the two tracking together like
     *  the pre-P0b `19-prefixes-for-3-phones` diagnostics. */
    private fun learnPeerIdentity(address: String, stableKey: String) {
        if (!peerIdentity.learn(address, stableKey)) return
        DiagnosticsLog.event(
            "identity",
            "peer resolved: addresses=${peerIdentity.trackedAddressCount()} " +
                "distinct=${peerIdentity.distinctIdentityCount()}"
        )
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
    suspend fun framesToPushOnConnect(
        maxFrameBytes: Int = defaultMaxFrameBytes,
        toPeer: String? = null,
    ): List<ByteArray> {
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
            // Decision 37 (docs/DECISIONS.md): forwards the ORIGINAL sealed bytes verbatim, same
            // "never re-encrypt a relayed item" reasoning position/nickname relay already follow —
            // sealed is null only transiently during construction, never for a stored row.
            for (sos in relay.relayableSos()) {
                sos.sealed?.let { frames += MeshFrameCodec.reframeSosForRelay(sos.groupId, sos.id, sos.ttl, sos.hop, it) }
            }
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
        frames += presenceAndPositionFrames(toPeer)
        for (meta in relay.relayableEvidenceMeta()) {
            // Always sent — this bitset changes as chunks arrive, so it has to keep flowing every
            // connection until the transfer completes on both ends, same as before this change.
            frames += MeshFrameCodec.encodeManifest(meta.id, meta.totalChunks, relay.haveIndexSet(meta.id))
        }
        return frames
    }

    /** Presence + position — own, relayed for other members, and blind-carried for groups we
     *  aren't in — the LIVE, time-sensitive subset of [framesToPushOnConnect]'s full set. Called
     *  once as part of that (connection start) AND periodically thereafter on an already-open
     *  link (see [refreshFramesToPush] / `MeshGattClient`'s periodic-refresh loop, PLAN-v2.md P3 /
     *  docs/DECISIONS.md decision 20): everything else `framesToPushOnConnect` sends (the catalog
     *  filter, WFD cap, evidence manifests) either doesn't need this cadence of refreshing or is
     *  already handled by P1's event-driven flood-forward — only presence/position go stale purely
     *  from TIME passing on a link that's no longer cycling every ~45-60s the way v1's did. */
    private suspend fun presenceAndPositionFrames(toPeer: String?): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
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
            frames += positionFramesToPush(g.id, toPeer)
            // Decision 30 (docs/DECISIONS.md, hardware-confirmed 2026-08-06): nickname content was
            // ONLY ever pushed via framesToPushOnConnect's once-per-connection catalog-filter
            // exchange — never refreshed on an already-open link the way presence/position were in
            // decision 20. A nickname set after a P3 persistent link had already connected could
            // never reach that peer until the link happened to drop and reconnect, which — same as
            // decision 20's own finding — could now be indefinitely long. Small and bounded (this
            // app's groups are 3-8 people, PLAN-v2.md §5.5), so pushed unconditionally every
            // refresh, same as presence, rather than tracked for whether it actually changed.
            for (n in relay.nicknamesForGroup(g.id)) frames += MeshFrameCodec.encodeNickname(n)
        }
        // Positions/presence we're carrying for groups we aren't in. Outside the per-group loop
        // above on purpose: these belong to groups absent from getActiveGroups() precisely because
        // we're not a member, so that loop would never reach them.
        // Budgeted, like every other relay path here (MAX_RELAYED_POSITIONS_PER_GROUP for member
        // positions, MAX_CATALOG_ITEMS_PER_SESSION for content). This one previously wasn't: two
        // 200-entry stores could emit up to 400 frames, unbudgeted, at the FRONT of the push — and
        // since every frame is a serialised GATT write, a phone carrying for several strangers'
        // groups could spend an entire push on their traffic. Blind carriage must not outrank the
        // mesh's own delivery.
        val carried = opaquePositions.framesToRelay(excludePeer = toPeer, limit = MAX_OPAQUE_FRAMES_PER_SESSION) +
            opaquePresence.framesToRelay(excludePeer = toPeer, limit = MAX_OPAQUE_FRAMES_PER_SESSION)
        if (carried.isNotEmpty()) {
            frames += carried
            DiagnosticsLog.event("relay", "forwarding ${carried.size} opaque frame(s)")
        }
        return frames
    }

    /** Call periodically (~15-20s, see `MeshGattClient`'s refresh loop) on an already-open,
     *  persistent link — PLAN-v2.md P3 kept links open far past the moment
     *  [framesToPushOnConnect] used to be the only chance presence/position/nicknames ever had to
     *  cross one. Confirmed live (2026-08-05, docs/DECISIONS.md decision 20): without this, a
     *  peer's radar dot only ever refreshed when a connection happened to drop and reopen, which —
     *  now that links can stay up for 10-20 minutes — read as "the radar doesn't work" for the
     *  entire life of a healthy link. Nicknames got the same fix later (decision 30,
     *  2026-08-06) after the identical symptom showed up for them: a nickname set after a link was
     *  already open never reached that peer. */
    suspend fun refreshFramesToPush(toPeer: String?): List<ByteArray> = presenceAndPositionFrames(toPeer)

    /** My own fix, plus whatever I'm holding on behalf of other group members, one hop further out
     *  — capped to the nearest [MAX_RELAYED_POSITIONS_PER_GROUP] (see [selectPositionsToRelay]'s
     *  doc for why). Every frame is AES-GCM-sealed under the group key, so this is safe to push
     *  even to a peer that turns out not to be a member — they receive opaque bytes, not our
     *  members' GPS. */
    private fun positionFramesToPush(groupId: String, toPeer: String? = null): List<ByteArray> {
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
        val toRelay = selectPositionsToRelay(
            positionTracker.forGroup(groupId), repo.deviceId, maxPositionRelayHops, toPeer
        )
        for ((senderId, record) in toRelay) {
            // Forward the ORIGINAL ciphertext rather than re-sealing it. Re-encrypting produced a
            // fresh nonce every push (MeshFrameCodec.positionNonce), so the same position looked
            // like a brand-new frame to every downstream blind relay's ciphertext dedup — one
            // position could occupy 4-18 slots of a neighbour's 200-entry store and be re-pushed to
            // everyone, evicting genuinely distinct frames. Forwarding verbatim makes the ciphertext
            // stable end-to-end, so dedup works at every hop, AND preserves the sender's Ed25519
            // signature inside the seal, which re-encryption silently dropped (relayed positions
            // were previously unsigned and unverifiable).
            val sealed = record.sealed
            frames.add(
                if (sealed != null) {
                    MeshFrameCodec.reframePositionForRelay(groupId, record.hop + 1, sealed)
                } else {
                    // No original bytes (a record predating this, or our own fix) — seal it ourselves.
                    MeshFrameCodec.encodePosition(
                        groupId, key, senderId, record.lat, record.lon,
                        record.accuracyM, record.timestampSec, record.hop + 1
                    )
                }
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

    /** Decision 37 (docs/DECISIONS.md): SOS content is now AES-GCM sealed, not cleartext-plus-HMAC
     *  — a phone with no key for [frame]'s group can no longer read OR authenticate it, so it takes
     *  opaque custody instead of attempting the old vacuous-pass auth check. Same split
     *  [handlePositionSealed] already makes between the member path (this function) and
     *  [takeOpaqueSosCustody] (the blind-relay path). */
    private suspend fun handleSos(frame: MeshFrameCodec.Frame.SosSealed, peerAddress: String) {
        val key = repo.getGroupKey(frame.groupId)
        if (key == null) {
            takeOpaqueSosCustody(frame, peerAddress)
            return
        }
        // A failed decrypt (wrong key — can't happen, we just looked it up for this exact group —
        // tampered ciphertext, or a GCM tag mismatch) IS the auth failure now; there is no separate
        // mac to check. Replaces the old authOk(...) call entirely.
        val body = MeshFrameCodec.openSos(frame.sealed, key) ?: run {
            Log.w("RelayResponder", "SOS failed to open for a group we hold the key to — dropping")
            return
        }
        // Additive per-sender check: decrypting under the group key only proves SOME member
        // produced this; a pinned sender key catches a different member forging this one's SOS.
        if (!verifySignatureIfPinned(frame.groupId, body.senderId, body.signature, body.signedBytes)) {
            Log.w(
                "RelayResponder",
                "SOS signature failed verification for a pinned sender — dropping (possible impersonation)"
            )
            return
        }
        val sos = SosEntity(
            frame.id, frame.groupId, body.senderId, senderIsMe = false, body.message, body.timestamp,
            frame.ttl, frame.hop, body.isAlert, sealed = frame.sealed,
        )
        val isNew = relay.ingestSos(sos)
        // The receive half of relay. Without this the diagnostics log only showed what we PUSHED,
        // which made it impossible to tell "relay isn't happening" from "relay happened and we
        // simply had nothing new to offer" — the two look identical from the push side alone.
        // hop>1 here is direct evidence a frame arrived via a relay.
        // Hop-tracking runs on every receipt, not gated behind ingestSos's dedup return — a shorter
        // path found on a later sighting of the same sos.id must still improve our estimate;
        // considerDirectHop keeps it only if better.
        // frame.hop + 1, NOT ttl-derived (PLAN-v2.md P1 / docs/DECISIONS.md decision 16): a
        // degree-aware relay may drop ttl by more than 1 in a single hop for flood control, which
        // would silently corrupt a ttl-derived hop count. hop is a dedicated cleartext envelope
        // field, incremented by exactly +1 on every RelayEngine.ingestSos, immune to that.
        // frame.hop AS RECEIVED is the SENDER's own distance from origin (their stored, already-
        // incremented value) — mine is exactly one more, the same "+1" the old ttl formula baked in
        // (DEFAULT_TTL - ttl + 1) and what RelayEngine.ingestSos's `hop = sos.hop + 1` also stores,
        // so the value shown here and the value persisted for this SOS's own next relay agree.
        val hopsFromOrigin = frame.hop + 1
        if (isNew) {
            DiagnosticsLog.event(
                "recv",
                "NEW sos from ${body.senderId.take(SENDER_ID_LOG_CHARS)} hop=$hopsFromOrigin"
            )
        }
        // Sourced on senderId (stable, global per device — see PeerIdentityResolver's class doc),
        // not peerAddress (rotates ~every 15min and would strand HopTracker's route ownership on
        // an address that no longer exists — PLAN-v2.md §1.3 / P0b). body.senderId is already
        // authenticated by the successful decrypt-under-the-group-key above, so learning it here is
        // no less trustworthy than the routing decision this same value already drives.
        learnPeerIdentity(peerAddress, body.senderId)
        // Both gated on isAlert (decision 35, docs/DECISIONS.md) — an ordinary quiet message still
        // relays/syncs exactly like before (floodForwardSos below is unconditional), but has no
        // business feeding the SOS hop-gradient or firing the alarm-style notification, both of
        // which only make sense for a genuine flagged emergency.
        if (sos.isAlert) {
            hopTracker.considerDirectHop(frame.groupId, frame.id, hopsFromOrigin, body.senderId)
            // Reaching this branch already means we hold the key (we're a member) — the old
            // separate isMember check is no longer needed, a blind relay can't reach this far.
            if (isNew) {
                val groupName = repo.groupDao.getGroup(frame.groupId)?.name ?: frame.groupId
                onSosReceived(sos, groupName)
            }
        }
        // PLAN-v2.md P1 §5.3: immediate forward across every OTHER currently-open link, instead of
        // waiting for that link's own next catalogue-sync — see floodForwardSos's doc. Gated on
        // isNew alone (relay.ingestSos's DB-backed dedup, not a separate in-memory layer — see
        // docs/DECISIONS.md decision 18 for why DedupCache stays unwired here for now): a
        // duplicate has nothing new to flood.
        // excludeKey is best-effort split horizon: resolves to whatever learnPeerIdentity just
        // taught the resolver above, which may not exactly match the key this connection
        // originally registered under if identity resolution changed mid-connection (see
        // ConnectionRegistry's registeredKey/activeTrackerKey docs). Worst case is a redundant
        // echo back to the sender, which their own ingestSos dedup silently absorbs — not a
        // correctness bug.
        if (isNew) floodForwardSos(sos, hopsFromOrigin, excludeKey = peerIdentity.resolve(peerAddress))
    }

    /** Blind-relay custody for an SOS we hold no key for — see [OpaqueFrameRelay]'s class doc and
     *  [takeOpaqueCustody]/[takeOpaquePresenceCustody]'s identical shape for position/presence.
     *  Decision 37 (docs/DECISIONS.md) is what makes this possible at all: before SOS content was
     *  sealed, a non-member could already read it in cleartext via [handleSos]'s old vacuous-pass
     *  auth check, so there was never a reason for a separate opaque path. [maxHops] reuses
     *  [RelayEngine.DEFAULT_TTL] as a reasonable blind-relay depth ceiling — matching the scale a
     *  member's own degree-aware flood-forward would naturally reach, since a blind relay has no
     *  group-key-derived signal of its own to size this from. */
    private fun takeOpaqueSosCustody(frame: MeshFrameCodec.Frame.SosSealed, peerAddress: String) {
        val accepted = opaqueSos.offer(
            dedupKey = OpaqueFrameRelay.dedupKey(frame.sealed),
            hop = frame.hop,
            maxHops = RelayEngine.DEFAULT_TTL,
            viaPeer = peerAddress,
        ) { MeshFrameCodec.reframeSosForRelay(frame.groupId, frame.id, frame.ttl, frame.hop + 1, frame.sealed) }
        if (accepted) {
            DiagnosticsLog.event("relay", "carrying opaque sos hop=${frame.hop} (not a member)")
        }
    }

    /** Call right after [RelayEngine.createSos] succeeds for a message THIS device originated —
     *  see `MeshService.sendSos`. Without this, a freshly-authored message only ever left the
     *  device via `framesToPushOnConnect`'s one-shot push at the START of a connection; now that
     *  P3 keeps links open far past that moment (decision 19), an already-open link at the time of
     *  sending would otherwise never carry it at all until that link eventually drops and a new
     *  one forms. This is what closes that gap: the origin's own copy gets the exact same
     *  immediate flood-forward a received frame gets in [handleSos], just with no arrival peer to
     *  exclude and hop 0 (it's already the origin's own value — see `SosEntity.hop`'s doc). */
    suspend fun floodForwardLocalSos(sos: SosEntity) {
        floodForwardSos(sos, hopsFromOrigin = sos.hop, excludeKey = null)
    }

    /** Forwards [sos] (re-stamped with [hopsFromOrigin] and a degree-clamped TTL) to a fanout
     *  subset of every currently-open link EXCEPT [excludeKey] (null = no exclusion, the local-
     *  origin case), after a degree-scaled jitter — all via the real [ForwardingPolicy], exactly
     *  as gated in the P0a/P1 simulator (`ForwardingPlaneEngine`) before this was trusted with
     *  production wiring. */
    private suspend fun floodForwardSos(sos: SosEntity, hopsFromOrigin: Int, excludeKey: String?) {
        // sealed is null only transiently during construction (see SosEntity.sealed's own doc) —
        // never for anything that reached here, which is always either freshly created (createSos
        // seals before storing) or freshly ingested (handleSos constructs with sealed set).
        val sealed = sos.sealed ?: return
        val openLinkCount = connectionRegistry.openLinkCount()
        val forwardedTtl = ForwardingPolicy.forwardedTtl(sos.ttl, openLinkCount)
        if (forwardedTtl <= 0) return
        val candidates = connectionRegistry.others(excludeKey).keys.toList()
        if (candidates.isEmpty()) return
        val targets = ForwardingPolicy.linksToForwardOn(
            candidates, messageIdSeed = sos.id.hashCode().toLong(), openLinkCount = openLinkCount,
        )
        // Decision 37 (docs/DECISIONS.md): forwards the ORIGINAL sealed bytes verbatim, only the
        // envelope's hop/ttl change — same "never re-encrypt a relayed item" reasoning position's
        // own reframePositionForRelay already follows, and for the same dedup-stability reason.
        val outgoing = MeshFrameCodec.reframeSosForRelay(sos.groupId, sos.id, forwardedTtl, hopsFromOrigin, sealed)
        delay(ForwardingPolicy.pickJitterMs(openLinkCount))
        val liveTargets = connectionRegistry.others(excludeKey)
        for (peerKey in targets) liveTargets[peerKey]?.send(outgoing)
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

    /** Carries a position for a group we hold no key for — see [OpaquePositionRelay]'s class doc.
     *  Split out of [handlePositionSealed] purely to keep that function's return count within
     *  detekt's limit. */
    private fun takeOpaqueCustody(frame: MeshFrameCodec.Frame.PositionSealed, peerAddress: String) {
        // Dedup on the ciphertext itself: encodePosition gives every frame a unique nonce, so
        // identical sealed bytes mean the identical original frame coming back around a triangle.
        val accepted = opaquePositions.offer(
            dedupKey = OpaqueFrameRelay.dedupKey(frame.sealed),
            hop = frame.hop,
            maxHops = maxPositionRelayHops,
            viaPeer = peerAddress,
        ) { MeshFrameCodec.reframePositionForRelay(frame.groupId, frame.hop + 1, frame.sealed) }
        if (accepted) {
            DiagnosticsLog.event("relay", "carrying opaque position hop=${frame.hop} (not a member)")
        }
    }

    /** Presence for a group we hold no key for. Same custody pattern as [takeOpaqueCustody], and the
     *  piece that makes a GPS-less member visible past a non-member relay: they push no position for
     *  the position path to piggyback on, so this is the only thing that carries them outward. */
    private fun takeOpaquePresenceCustody(frame: MeshFrameCodec.Frame.Presence, peerAddress: String) {
        // (groupId, senderId, timestamp) identifies one presence heartbeat exactly — the sender
        // stamps a fresh timestamp per connection, and the mac is a pure function of these three.
        val accepted = opaquePresence.offer(
            dedupKey = OpaqueFrameRelay.dedupKey(
                frame.groupId.toByteArray(), frame.senderId.toByteArray(), frame.timestamp.toString().toByteArray()
            ),
            hop = frame.hop,
            maxHops = maxPositionRelayHops,
            viaPeer = peerAddress,
        ) { MeshFrameCodec.reframePresenceForRelay(frame, frame.hop + 1) }
        if (accepted) {
            DiagnosticsLog.event("relay", "carrying opaque presence hop=${frame.hop} (not a member)")
        }
    }

    private suspend fun handlePositionSealed(frame: MeshFrameCodec.Frame.PositionSealed, peerAddress: String) {
        // No key for this group: we cannot read this position and never will — but we CAN carry it,
        // and until this branch existed we simply dropped it, which is what made a member behind a
        // non-member relay invisible on the radar (see OpaquePositionRelay's class doc). The
        // ciphertext is moved verbatim; only the envelope's hop byte changes.
        val key = repo.getGroupKey(frame.groupId)
        if (key == null) {
            takeOpaqueCustody(frame, peerAddress)
            return
        }
        val body = MeshFrameCodec.openPosition(frame.sealed, key) ?: return
        ingestOpenedPosition(frame, body, peerAddress)
    }

    /** The member path for a position we could actually open. Split from [handlePositionSealed] to
     *  keep both functions' return counts within detekt's limit. */
    private suspend fun ingestOpenedPosition(
        frame: MeshFrameCodec.Frame.PositionSealed,
        body: MeshFrameCodec.PositionBody,
        peerAddress: String,
    ) {
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
            // frame.hop (envelope), not body.hop (sealed): the envelope's is the one every relay on
            // the path actually incremented, including relays that couldn't open the body at all.
            // Sourced on body.senderId (stable), not peerAddress — see PeerIdentityResolver's
            // class doc / PLAN-v2.md §1.3 / P0b. Already passed verifySignatureIfPinned above.
            learnPeerIdentity(peerAddress, body.senderId)
            hopTracker.considerNeighborReport(frame.groupId, "PRESENCE", frame.hop, body.senderId)
            if (frame.hop < maxPositionRelayHops) {
                positionTracker.offer(
                    frame.groupId, body.senderId, body.lat, body.lon,
                    body.accuracyM, body.timestampSec, frame.hop,
                    viaPeer = peerAddress, sealed = frame.sealed
                )
                // hop>0 is a RELAYED position — the single clearest signal that multi-hop actually
                // worked, and the thing the radar's far-phone dot depends on. Never logs the
                // coordinates themselves (see DiagnosticsLog's class doc).
                DiagnosticsLog.event(
                    "recv",
                    "position from ${body.senderId.take(SENDER_ID_LOG_CHARS)} " +
                        "hop=${frame.hop}" + if (frame.hop > 0) " (RELAYED)" else ""
                )
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
        if (!presenceWithinSkew(frame.timestamp, hop = frame.hop)) {
            Log.w("RelayResponder", "presence frame outside skew window — dropping (replay?)")
            return
        }
        // No key: we can't verify this and never will — but we can carry it, which is what makes a
        // GPS-less member reachable past a stranger's phone (see takeOpaquePresenceCustody).
        val key = repo.getGroupKey(frame.groupId)
        if (key == null) {
            takeOpaquePresenceCustody(frame, peerAddress)
            return
        }
        if (!presenceIsAuthentic(frame, key)) return
        if (frame.senderId != repo.deviceId) {
            // frame.hop, not a hardcoded 0: a presence that crossed relays (including relays that
            // couldn't verify it) must report the distance it actually travelled, or a member two
            // hops out reads as a direct neighbour. Sourced on frame.senderId (stable), not
            // peerAddress — see PeerIdentityResolver's class doc / PLAN-v2.md §1.3 / P0b. This
            // frame already passed presenceIsAuthentic (group MAC + sender-key pin) above.
            learnPeerIdentity(peerAddress, frame.senderId)
            hopTracker.considerNeighborReport(frame.groupId, "PRESENCE", frame.hop, frame.senderId)
        }
    }

    /** Group-key MAC plus the sender-identity pin/signature checks, in that order. Folded into one
     *  function so [handlePresence] keeps its return count within detekt's limit. */
    private suspend fun presenceIsAuthentic(frame: MeshFrameCodec.Frame.Presence, key: ByteArray): Boolean {
        val macInput = MeshFrameCodec.presenceMacInput(frame.groupId, frame.senderId, frame.timestamp)
        val macOk = CryptoUtils.constantTimeEquals(CryptoUtils.authTag(key, macInput), frame.mac)
        return macOk && presencePassesSenderIdentityChecks(frame, macInput)
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
        val pin = pinOrCheckSenderKey(frame.groupId, frame.senderId, frame.senderPublicKey)
        if (pin == SenderKeyPinResult.CHANGED) {
            // Re-pinned, not dropped — see pinOrCheckSenderKey's doc. Logged loudly because the
            // benign explanation (peer reinstalled / Keystore reset) and the hostile one (a member
            // swapped identity) look identical from here, and only the user can tell them apart.
            Log.w(
                "RelayResponder",
                "sender ${frame.senderId.take(SENDER_ID_LOG_CHARS)} presented a NEW public key for " +
                    "group ${frame.groupId.take(SENDER_ID_LOG_CHARS)} — re-pinned (benign after a " +
                    "reinstall; otherwise possible impersonation)"
            )
            DiagnosticsLog.event("identity", "re-pinned key for ${frame.senderId.take(SENDER_ID_LOG_CHARS)}")
            // Deliberately falls through: the frame still had to pass authOk's group-key HMAC, and
            // its own signature is checked below against the key we just accepted.
        }
        if (!verifySignatureIfPinned(frame.groupId, frame.senderId, frame.signature, macInput)) {
            Log.w(
                "RelayResponder",
                "presence signature failed verification under the pinned key — dropping (possible impersonation)"
            )
            DiagnosticsLog.event("reject", "presence signature failed for ${frame.senderId.take(SENDER_ID_LOG_CHARS)}")
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
        // Decision 37 (docs/DECISIONS.md): forwards each SOS's ORIGINAL sealed bytes verbatim, same
        // "never re-encrypt a relayed item" reasoning as floodForwardSos above. sealed is null only
        // transiently during construction, never for a stored row — see SosEntity.sealed's own doc.
        var pushed = pushUpTo(sosToPush, allowedToPush, ::reframeStoredSos, respond)
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
        DiagnosticsLog.event(
            "sync",
            "peer ${peerAddress.take(SENDER_ID_LOG_CHARS)}: pushed=$pushed skipped=$filterSkipped budget=$budgetSkipped"
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

    /** Re-frames a stored, already-sealed SOS for push — decision 37 (docs/DECISIONS.md), same
     *  "forward the original ciphertext verbatim" reasoning as [floodForwardSos]. `sealed` is null
     *  only transiently during construction, never for a stored row (see [SosEntity.sealed]'s own
     *  doc), so `!!` here documents an invariant rather than papering over a real null case. */
    private fun reframeStoredSos(sos: SosEntity): ByteArray =
        MeshFrameCodec.reframeSosForRelay(sos.groupId, sos.id, sos.ttl, sos.hop, sos.sealed!!)

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
                is MeshFrameCodec.Frame.SosSealed -> handleSos(frame, peerAddress)
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
        // How much of a sender/group id may appear in a log line — enough to tell peers apart
        // while never writing a full identifier to disk (see DiagnosticsLog's class doc).
        internal const val SENDER_ID_LOG_CHARS = 8

        private const val MAX_TRACKED_WFD_PEERS = 200
        private const val MAX_CATALOG_ITEMS_PER_SESSION = 200

        /** Per-connection cap on blind-carried frames, per store. Deliberately close to
         *  MAX_RELAYED_POSITIONS_PER_GROUP (12): carrying a stranger's group should cost about what
         *  serving one of our own does, not 16x more. OpaqueFrameRelay rotates its window, so a
         *  full 200-entry store still drains completely over successive connections. */
        private const val MAX_OPAQUE_FRAMES_PER_SESSION = 16
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

        // Per-hop slack on top of the skew window, mirroring HopTracker/PositionTracker's own
        // per-hop model (same 45s reconnect-cooldown unit). Without it, the flat 120s gate applied
        // to a RELAYED heartbeat too — but each relay hop costs at least one full reconnect cycle
        // (~45s) because presence only moves in framesToPushOnConnect, never mid-connection. So a
        // 2-hop presence needed ~90-135s to arrive and was then rejected as a replay, which
        // silently defeated the whole blind-presence-relay path. Replay protection at hop 0 is
        // unchanged at 120s; a relayed heartbeat gets exactly the budget its path actually costs.
        private const val PRESENCE_PER_HOP_SLACK_MS = 45_000L

        /** Pure — no [GroupRepository]/key access, deliberately, so this can be checked (and unit-
         *  tested) before ever touching the group key. See the `Frame.Presence` case above for why
         *  the ordering matters: the MAC already covers [timestamp], so an attacker can't forge a
         *  fresher one, but nothing previously verified the timestamp was recent at all — a replay
         *  of one captured frame verified as authentic forever. `internal` so it's directly
         *  unit-testable without a Robolectric `Context` (this class's other tests need one only
         *  because [RelayEngine]/[GroupRepository] do; this function needs neither). */
        internal fun presenceWithinSkew(
            timestamp: Long,
            now: Long = System.currentTimeMillis(),
            hop: Int = 0,
        ): Boolean = kotlin.math.abs(now - timestamp) <= PRESENCE_MAX_SKEW_MS + hop * PRESENCE_PER_HOP_SLACK_MS

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
            if (incomingPublicKey == null) return SenderKeyPinResult.UNCHANGED // nothing offered, nothing to do
            if (existingPublicKey == null) return SenderKeyPinResult.FIRST_SIGHT
            return if (existingPublicKey.contentEquals(incomingPublicKey)) {
                SenderKeyPinResult.UNCHANGED
            } else {
                SenderKeyPinResult.CHANGED
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
            toPeer: String? = null,
            limit: Int = MAX_RELAYED_POSITIONS_PER_GROUP,
        ): List<Pair<String, PositionTracker.Record>> =
            positions.entries
                .asSequence()
                .filter { (senderId, record) ->
                    // Split horizon: never advertise a route back toward whoever taught it to us.
                    // The textbook distance-vector loop guard, and needed here for exactly the
                    // textbook reason — see PositionTracker.Record.viaPeer's doc for the measured
                    // loop it eliminates. A null viaPeer is our own fix, always relayable.
                    val learnedFromThisPeer = toPeer != null && record.viaPeer == toPeer
                    senderId != selfId && record.hop + 1 < maxHops && !learnedFromThisPeer
                }
                .map { it.key to it.value }
                .sortedBy { it.second.hop }
                .take(limit)
                .toList()
    }
}
