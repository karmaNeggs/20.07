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
class RelayResponder(
    private val repo: GroupRepository,
    private val relay: RelayEngine,
    private val hopTracker: HopTracker,
    private val positionTracker: PositionTracker,
    private val locationTracker: LocationTracker,
    // Fires once per newly-received (not duplicate/relayed-again), authenticated SOS in a group
    // we're actually a member of — MeshService uses this to post the system notification. No-op
    // default so this stays optional for anything else that constructs a RelayResponder.
    private val onSosReceived: suspend (SosEntity, groupName: String) -> Unit = { _, _ -> },
) {
    private val maxPositionRelayHops = 4

    // Per-connection cap on *responses* to a manifest (i.e. novel chunks actually pushed).
    // Keeps one busy item from starving the rotation through other peers.
    private val maxChunksPerSession = 150
    private val sessionBudget = ConcurrentHashMap<String, Int>()

    fun resetSessionBudget(address: String) {
        sessionBudget[address] = 0
    }

    // Per-peer "have I already gotten this STATIC item to them" tracking — SOS/evidence-header/
    // nickname content is immutable once created (nicknames are keyed including their updatedAt, so
    // a genuine change is a new item, not a repeat), so once a peer has one there's never a reason
    // to send it again. Position, presence, and the evidence manifest are deliberately NOT tracked
    // here: position/presence are explicitly time-sensitive (a stale one is wrong, not redundant),
    // and the manifest represents *evolving* chunk progress that has to keep flowing until a
    // transfer completes. See PeerDeliveryTracker's own doc for why this is a separate class.
    private val deliveryTracker = PeerDeliveryTracker()

    /** Called by the caller (MeshGattClient/MeshGattServer) only after a push actually succeeded —
     *  never optimistically. Marking an item delivered before confirming the write went through
     *  would mean a failed write silently never gets retried to that peer; see [PushFrame.dedupKey]. */
    fun markDelivered(peerAddress: String, itemKey: String) = deliveryTracker.markDelivered(peerAddress, itemKey)

    /** One frame to push, plus the key to mark delivered under — or null for a frame that should
     *  never be deduped (time-sensitive or evolving content; see the class doc above). */
    data class PushFrame(val bytes: ByteArray, val dedupKey: String?)

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

    /**
     * On connect we only ever announce what we have (position + SOS + evidence headers + per-item
     * have-bitsets). Actual chunk bytes only move in response to a peer's manifest telling us
     * what they're missing — see the FRAME_MANIFEST case in [handleIncoming]. This is what keeps
     * large files viable: once a chunk has spread, most contacts exchange near-empty deficits
     * instead of re-sending it.
     *
     * SOS/evidence-header/nickname frames are additionally filtered against [deliveryTracker] for
     * this specific peer — those three are static, one-time content, so a peer we've already
     * successfully told about a given SOS/header/nickname never needs to hear it again. Without
     * this, every connection re-sent the *entire* history to every peer, forever — a real, growing
     * cost as message count climbed, though not itself a correctness bug. Position, presence, and
     * the manifest are deliberately excluded from this filtering (see [PeerDeliveryTracker]'s doc).
     * The caller is responsible for calling [markDelivered] — only once a push actually *succeeds*.
     */
    suspend fun framesToPushOnConnect(peerAddress: String): List<PushFrame> {
        val frames = mutableListOf<PushFrame>()
        for (g in repo.groupDao.getActiveGroups()) {
            // A tiny authenticated "I'm a member of this group, on this connection" heartbeat, sent
            // first for every group we're in. This is what makes presence work when beacon discovery
            // is one-directional: the single GATT link carries it both ways, so a peer that can't
            // hear our beacon still learns we're here. Costs ~70 bytes and one HMAC per group.
            repo.getGroupKey(g.id)?.let { key ->
                frames += PushFrame(MeshFrameCodec.encodePresence(g.id, repo.deviceId, System.currentTimeMillis(), key), null)
            }
            frames += positionFramesToPush(g.id).map { PushFrame(it, null) }
            for (n in relay.nicknamesForGroup(g.id)) {
                val itemKey = "nick:${n.groupId}:${n.senderId}:${n.updatedAt}"
                if (!deliveryTracker.alreadyDelivered(peerAddress, itemKey)) frames += PushFrame(MeshFrameCodec.encodeNickname(n), itemKey)
            }
        }
        for (sos in relay.relayableSos()) {
            val itemKey = "sos:${sos.id}"
            if (!deliveryTracker.alreadyDelivered(peerAddress, itemKey)) frames += PushFrame(MeshFrameCodec.encodeSos(sos), itemKey)
        }
        for (meta in relay.relayableEvidenceMeta()) {
            val itemKey = "evid:${meta.id}"
            if (!deliveryTracker.alreadyDelivered(peerAddress, itemKey)) frames += PushFrame(MeshFrameCodec.encodeEvidMeta(meta), itemKey)
            // Never deduped — this bitset changes as chunks arrive, so it has to keep flowing every
            // connection until the transfer completes on both ends.
            frames += PushFrame(MeshFrameCodec.encodeManifest(meta.id, meta.totalChunks, relay.haveIndexSet(meta.id)), null)
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
                is MeshFrameCodec.Frame.Manifest -> {
                    val myHave = relay.haveIndexSet(frame.evidenceId)
                    val deficit = (myHave - frame.peerHave).sorted()
                    if (deficit.isNotEmpty()) {
                        val take = consumeBudget(peerAddress, deficit.size)
                        if (take > 0) {
                            for (chunk in relay.chunksByIndexes(frame.evidenceId, deficit.take(take))) {
                                respond(MeshFrameCodec.encodeChunk(chunk))
                                delay(15)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("RelayResponder", "frame handling failed: ${e.message}")
        }
    }
}
