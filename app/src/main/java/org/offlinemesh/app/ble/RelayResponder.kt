package org.offlinemesh.app.ble

import android.util.Log
import kotlinx.coroutines.delay
import org.offlinemesh.app.crypto.CryptoUtils
import org.offlinemesh.app.data.EvidenceChunkEntity
import org.offlinemesh.app.data.GroupRepository
import org.offlinemesh.app.data.SosEntity
import org.offlinemesh.app.sensors.LocationTracker
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything both the GATT server and GATT client paths need to do with a connection once it's
 * open: what to announce on connect, and what to do with each frame that arrives. Kept
 * independent of BluetoothGatt/BluetoothGattServer entirely so both roles share one
 * implementation instead of two copies that could quietly drift apart.
 */
@Suppress("LongParameterList") // one collaborator per constructor param, same shape MeshService
// already wires every other class in this file with (BeaconRadio/MeshGattClient/MeshGattServer
// all take a comparable number) — not a candidate for a params-object without adding an
// abstraction this codebase doesn't otherwise use.
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

    /** Forwarded from [RelayEngine.catalogEpoch] — see [ConnectionAttemptTracker]'s `currentEpoch`
     *  param for what this is used for. Kept as a passthrough rather than handing `relay` itself
     *  to [MeshGattClient]/[MeshGattServer], which only ever depend on [RelayResponder]. */
    val catalogEpoch: Int get() = relay.catalogEpoch

    // Per-connection cap on *responses* to a manifest (i.e. novel chunks actually pushed).
    // Keeps one busy item from starving the rotation through other peers.
    private val maxChunksPerSession = 150
    private val sessionBudget = ConcurrentHashMap<String, Int>()

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

    @Synchronized
    private fun consumeBudget(address: String, want: Int): Int {
        val used = sessionBudget.getOrDefault(address, 0)
        val remaining = (maxChunksPerSession - used).coerceAtLeast(0)
        val take = minOf(remaining, want)
        sessionBudget[address] = used + take
        return take
    }

    /** All of our currently-relayable SOS/evidence-header/nickname item keys, across every active
     *  group — the exact set [CatalogFilter] gets built over, and the exact key format
     *  [handleIncoming]'s `Frame.CatalogFilter` case tests a peer's incoming filter against. Kept
     *  as one shared helper so the two sides can never quietly drift out of sync on key format. */
    private suspend fun currentCatalogKeys(): List<String> {
        val keys = mutableListOf<String>()
        for (sos in relay.relayableSos()) keys += "sos:${sos.id}"
        for (meta in relay.relayableEvidenceMeta()) keys += "evid:${meta.id}"
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
     */
    suspend fun framesToPushOnConnect(): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        val keys = currentCatalogKeys()
        val filter = CatalogFilter.build(keys)
        frames += MeshFrameCodec.encodeCatalogFilter(filter.seed, filter.toBits())
        // Cheap, low-volume (once per connection, not per frame) — lets a live logcat pull during
        // a "message isn't arriving" report confirm whether the item was even in this device's own
        // outgoing catalog at connect time, without needing to reproduce anything.
        Log.d("RelayResponder", "advertising catalog filter: ${keys.size} item(s) held")
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
                frames += MeshFrameCodec.encodePresence(g.id, repo.deviceId, System.currentTimeMillis(), key)
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

    /** My own fix, plus whatever I'm holding on behalf of other group members, one hop further out.
     *  Every frame is AES-GCM-sealed under the group key, so this is safe to push even to a peer
     *  that turns out not to be a member — they receive opaque bytes, not our members' GPS. */
    private fun positionFramesToPush(groupId: String): List<ByteArray> {
        val key = repo.getGroupKey(groupId) ?: return emptyList()
        val frames = mutableListOf<ByteArray>()
        val myLoc = locationTracker.location.value
        if (myLoc != null) {
            val nowSec = System.currentTimeMillis() / 1000
            frames.add(
                MeshFrameCodec.encodePosition(
                    groupId, key, repo.deviceId, myLoc.latitude, myLoc.longitude, myLoc.accuracy.toInt(), nowSec, 0
                )
            )
        }
        for ((senderId, record) in positionTracker.forGroup(groupId)) {
            if (senderId == repo.deviceId) continue
            if (record.hop + 1 >= maxPositionRelayHops) continue
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
    @Suppress("ReturnCount") // guard-clause early returns — one per unmet precondition, matches
    // this codebase's established style for exactly this kind of gate (see e.g. authOk's callers)
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

    /** May call [respond] zero, one, or many times (a manifest deficit can trigger a whole run of
     *  chunk frames) — the caller supplies how a response frame actually reaches the peer. */
    suspend fun handleIncoming(bytes: ByteArray, peerAddress: String, respond: suspend (ByteArray) -> Unit) {
        val frame = MeshFrameCodec.decode(bytes) ?: return
        try {
            when (frame) {
                is MeshFrameCodec.Frame.Sos -> {
                    // If this is a group we hold the key to, the SOS must authenticate or we neither
                    // show it nor pass it on — that's what stops a phone without the key from
                    // injecting a fake emergency people would run toward. If we're not a member
                    // (no key), we can't verify, so we relay it blind; a member downstream will
                    // reject it if it's forged.
                    if (!authOk(frame.sos.groupId, frame.sos.mac) {
                            MeshFrameCodec.sosMacInput(frame.sos.id, frame.sos.groupId, frame.sos.senderId, frame.sos.message, frame.sos.timestamp)
                        }) {
                        Log.w("RelayResponder", "SOS failed auth for a group we hold — dropping")
                        return
                    }
                    val isMember = repo.getGroupKey(frame.sos.groupId) != null
                    val isNew = relay.ingestSos(frame.sos)
                    // Hop-tracking runs on every receipt, not gated behind ingestSos's dedup return —
                    // a shorter path found on a later sighting of the same sos.id must still improve
                    // our estimate; considerDirectHop keeps it only if better. Hop is derived from
                    // TTL consumed, not hardcoded (that would mark every relayer as the origin).
                    val hopsFromOrigin = RelayEngine.DEFAULT_TTL - frame.sos.ttl + 1
                    hopTracker.considerDirectHop(frame.sos.groupId, frame.sos.id, hopsFromOrigin)
                    // Only notify for groups we're actually in — a blind carrier ingests and relays
                    // SOS for groups it isn't a member of too, but has no business alerting on them.
                    if (isNew && isMember) {
                        val groupName = repo.groupDao.getGroup(frame.sos.groupId)?.name ?: frame.sos.groupId
                        onSosReceived(frame.sos, groupName)
                    }
                }
                is MeshFrameCodec.Frame.EvidMeta -> {
                    if (!authOk(frame.meta.groupId, frame.meta.mac) {
                            MeshFrameCodec.evidMacInput(frame.meta.id, frame.meta.groupId, frame.meta.senderId, frame.meta.timestamp, frame.meta.sha256, frame.meta.totalChunks, frame.meta.mimeType)
                        }) {
                        Log.w("RelayResponder", "evidence header failed auth for a group we hold — dropping")
                        return
                    }
                    if (relay.ingestEvidenceMeta(frame.meta)) {
                        // Without this, the connection that first tells a peer an item exists could
                        // never also transfer its chunks — our own manifest push already happened at
                        // connection start, before we knew this item existed. Responding immediately
                        // (with our honest 0%-complete manifest) lets the sender fill us in now.
                        respond(MeshFrameCodec.encodeManifest(frame.meta.id, frame.meta.totalChunks, relay.haveIndexSet(frame.meta.id)))
                    }
                }
                is MeshFrameCodec.Frame.EvidChunk -> {
                    relay.ingestChunk(EvidenceChunkEntity(frame.chunk.evidenceId, frame.chunk.chunkIndex, frame.chunk.data))
                }
                is MeshFrameCodec.Frame.PositionSealed -> {
                    // Positions only ever propagate member-to-member; a non-member has no key to open
                    // this and doesn't relay it, so live GPS never travels in the clear.
                    val key = repo.getGroupKey(frame.groupId) ?: return
                    val body = MeshFrameCodec.openPosition(frame.sealed, key) ?: return
                    if (body.senderId != repo.deviceId) {
                        // Receiving an authenticated position over GATT is itself proof this member
                        // is reachable — feed presence from it too (its hop, so a relayed position
                        // also extends presence outward), not just from the beacon path.
                        hopTracker.considerNeighborReport(frame.groupId, "PRESENCE", body.hop)
                        if (body.hop < maxPositionRelayHops) {
                            positionTracker.offer(frame.groupId, body.senderId, body.lat, body.lon, body.accuracyM, body.timestampSec, body.hop)
                        }
                    }
                }
                is MeshFrameCodec.Frame.Presence -> {
                    // Direct-neighbor heartbeat: not stored, not relayed. Only meaningful for a group
                    // we're a member of, and must authenticate (a non-member can't fake co-membership).
                    val key = repo.getGroupKey(frame.groupId) ?: return
                    val ok = CryptoUtils.constantTimeEquals(
                        CryptoUtils.authTag(key, MeshFrameCodec.presenceMacInput(frame.groupId, frame.senderId, frame.timestamp)),
                        frame.mac
                    )
                    if (ok && frame.senderId != repo.deviceId) {
                        hopTracker.considerNeighborReport(frame.groupId, "PRESENCE", 0)
                    }
                }
                is MeshFrameCodec.Frame.Nickname -> {
                    if (!authOk(frame.nickname.groupId, frame.nickname.mac) {
                            MeshFrameCodec.nicknameMacInput(frame.nickname.groupId, frame.nickname.senderId, frame.nickname.username, frame.nickname.updatedAt)
                        }) {
                        Log.w("RelayResponder", "nickname failed auth for a group we hold — dropping")
                        return
                    }
                    relay.ingestNickname(frame.nickname)
                }
                is MeshFrameCodec.Frame.CatalogFilter -> {
                    // The peer just told us (probabilistically) what they already hold — push only
                    // what their filter says they're missing. See CatalogFilter's class doc for why
                    // a false positive here (skipping something they actually don't have) is safe:
                    // it costs a skipped send this connection, not a lost item — the item stays in
                    // our own relayable set and gets offered again, against a freshly-salted filter,
                    // on the very next reconnect.
                    val peerFilter = CatalogFilter.fromBits(frame.bits, frame.seed)
                    var pushed = 0
                    var skipped = 0
                    for (sos in relay.relayableSos()) {
                        if (peerFilter.mightContain("sos:${sos.id}")) {
                            skipped++
                        } else {
                            respond(MeshFrameCodec.encodeSos(sos))
                            pushed++
                        }
                    }
                    for (meta in relay.relayableEvidenceMeta()) {
                        if (peerFilter.mightContain("evid:${meta.id}")) {
                            skipped++
                        } else {
                            respond(MeshFrameCodec.encodeEvidMeta(meta))
                            pushed++
                        }
                    }
                    for (g in repo.groupDao.getActiveGroups()) {
                        for (n in relay.nicknamesForGroup(g.id)) {
                            val key = "nick:${n.groupId}:${n.senderId}:${n.updatedAt}"
                            if (peerFilter.mightContain(key)) {
                                skipped++
                            } else {
                                respond(MeshFrameCodec.encodeNickname(n))
                                pushed++
                            }
                        }
                    }
                    // The single most useful line for diagnosing "messaging isn't arriving" from a
                    // live logcat pull: confirms the round trip actually ran on this connection and
                    // exactly how it resolved, without needing to reproduce anything or add a
                    // debugger. peerAddress included since a device can hold several connections in
                    // quick succession and this is the only place that ties a decision to which one.
                    Log.d(
                        "RelayResponder",
                        "catalog filter from $peerAddress: pushed $pushed, skipped $skipped (peer already had them)"
                    )
                }
                is MeshFrameCodec.Frame.Manifest -> {
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
                        // existing seenDao dedup already makes a chunk arriving twice (once via BLE,
                        // once via WFD) a harmless no-op, so this needs no coordination with the push
                        // just above it — see WifiDirectHandoffCoordinator's class doc.
                        maybeAccelerateOverWifiDirect(frame.evidenceId, deficit, peerAddress, respond)
                    }
                }
                is MeshFrameCodec.Frame.WifiDirectCap -> {
                    markWfdCapable(peerAddress)
                }
                is MeshFrameCodec.Frame.WifiDirectHandoff -> {
                    // not a member of this group — can't verify, drop (see class doc)
                    val key = repo.getGroupKey(frame.groupId) ?: return
                    wifiDirectCoordinator?.onHandoffProposalReceived(frame, peerAddress, key, respond)
                }
                is MeshFrameCodec.Frame.WifiDirectAccept -> {
                    val key = repo.getGroupKey(frame.groupId) ?: return
                    wifiDirectCoordinator?.onHandoffAccepted(frame, peerAddress, key)
                }
            }
        } catch (e: Exception) {
            Log.w("RelayResponder", "frame handling failed: ${e.message}")
        }
    }

    companion object {
        private const val MAX_TRACKED_WFD_PEERS = 200
        private const val WFD_PEER_MAP_INITIAL_CAPACITY = 16
        private const val WFD_PEER_MAP_LOAD_FACTOR = 0.75f
    }
}
