package org.offlinemesh.app.ble

import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

object MeshProtocol {
    // Public, fixed — identifies "this app's phone" at the radio level. Not secret; matching
    // group content still requires the per-group derived key, this UUID just says "same app".
    val SERVICE_UUID: UUID = UUID.fromString("6c1e2e10-6a1a-4b1e-9a4b-0b6f7e2f1a01")
    val RELAY_CHAR_UUID: UUID = UUID.fromString("6c1e2e11-6a1a-4b1e-9a4b-0b6f7e2f1a01")

    const val ADV_TYPE_GENERIC: Byte = 0x00
    const val ADV_TYPE_GROUP: Byte = 0x01

    const val UNKNOWN_HOP: Int = 255
    const val ROTATING_ID_LEN: Int = 6

    /** BLE's ATT MTU before any negotiation ever succeeds — the floor every connection starts at
     *  and the value to assume if `requestMtu`/`onMtuChanged` never resolved for some reason. */
    const val DEFAULT_ATT_MTU = 23

    /** Every `writeCharacteristic`/notify consumes 3 bytes of the negotiated MTU for the ATT
     *  opcode + attribute handle — the actual usable payload per write is `mtu - ATT_WRITE_OVERHEAD_BYTES`. */
    const val ATT_WRITE_OVERHEAD_BYTES = 3

    /**
     * 8-byte advertisement service-data payload: type(1) + rotatingId(6) + sosHop(1).
     * Kept deliberately tiny — legacy BLE advertising has a hard 31-byte total limit and
     * Android auto-adds 3 bytes of its own (a Flags structure) on top of whatever's built here.
     * There's no "groupHop" field: only an actual group member can compute a valid rotatingId
     * at all (needs the group key), so anyone advertising one is a presence source by
     * construction — always 0, not worth spending a wire byte to say so.
     */
    fun encodeBeacon(type: Byte, rotatingGroupId: ByteArray, sosHop: Int): ByteArray {
        val buf = ByteBuffer.allocate(1 + ROTATING_ID_LEN + 1)
        buf.put(type)
        buf.put(rotatingGroupId.copyOf(ROTATING_ID_LEN)) // zero-padded if shorter
        buf.put(sosHop.coerceIn(0, 255).toByte())
        return buf.array()
    }

    data class Beacon(val type: Byte, val rotatingGroupId: ByteArray, val sosHop: Int)

    fun decodeBeacon(bytes: ByteArray): Beacon? {
        val expected = 1 + ROTATING_ID_LEN + 1
        if (bytes.size < expected) return null
        val type = bytes[0]
        val rid = bytes.copyOfRange(1, 1 + ROTATING_ID_LEN)
        val sHop = bytes[1 + ROTATING_ID_LEN].toInt() and 0xFF
        return Beacon(type, rid, sHop)
    }

    // Relay frame-type constants live in MeshFrameCodec (the one place that encodes/decodes them) —
    // deliberately not duplicated here, where they used to drift out of sync.

    /** 1 bit per chunk index, 1 = have it. Compact even for thousands of chunks (~650B for 5000).
     *  Coerced against [MeshFrameCodec.MAX_EVIDENCE_CHUNKS] as defence in depth — the primary guard
     *  is at [MeshFrameCodec.decode], which every wire-sourced totalChunks value must pass through,
     *  but this is also reachable directly from RelayEngine using a locally-persisted value, so the
     *  allocation itself stays bounded regardless of caller. */
    fun encodeBitset(haveIndexes: Set<Int>, totalChunks: Int): ByteArray {
        val bounded = totalChunks.coerceIn(0, MeshFrameCodec.MAX_EVIDENCE_CHUNKS)
        val bytes = ByteArray((bounded + 7) / 8)
        for (i in haveIndexes) {
            if (i < 0 || i >= bounded) continue
            bytes[i / 8] = (bytes[i / 8].toInt() or (1 shl (i % 8))).toByte()
        }
        return bytes
    }

    /** Same bound as [encodeBitset] — a negative [totalChunks] would otherwise silently "succeed"
     *  with an empty result (`0 until totalChunks` is an empty range for negative values) instead
     *  of being treated as malformed. */
    fun decodeBitset(bytes: ByteArray, totalChunks: Int): Set<Int> {
        val bounded = totalChunks.coerceIn(0, MeshFrameCodec.MAX_EVIDENCE_CHUNKS)
        val result = mutableSetOf<Int>()
        for (i in 0 until bounded) {
            if (i / 8 >= bytes.size) break
            if ((bytes[i / 8].toInt() shr (i % 8)) and 1 == 1) result.add(i)
        }
        return result
    }
}

/** Live, in-memory distance-vector table: how many relay-hops away is my nearest group member / an
 *  active SOS. [now] is injectable (defaults to the real clock) so staleness behavior — a real,
 *  previously-buggy part of this class (see [bestActiveSosHop]'s doc) — is testable without waiting
 *  out a real 90-second window. */
class HopTracker(private val now: () -> Long = System::currentTimeMillis) {
    data class Key(val groupId: String, val target: String) // target = "PRESENCE" or an sosId
    private val table = ConcurrentHashMap<Key, Int>()
    private val lastUpdated = ConcurrentHashMap<Key, Long>()
    // Which reporter's report currently "owns" table[key] — see updateHop's doc for why this
    // exists: without it, a value only ever got BETTER, forever, even after the route that
    // produced it was long gone, as long as something (anything) kept refreshing recency. A
    // peer/connection address is a fine source identity here even though BLE addresses rotate
    // every ~15min — see updateHop's doc for why that rotation doesn't reopen the bug this fixes.
    private val lastSource = ConcurrentHashMap<Key, String>()
    private val _snapshot = MutableStateFlow<Map<Key, Int>>(emptyMap())
    val snapshot: StateFlow<Map<Key, Int>> = _snapshot

    // 90s, not 45s: presence can now be fed by the GATT channel (see RelayResponder — presence /
    // position frames), which only refreshes on each reconnect (~every 48s given the client's
    // reconnect cooldown), not continuously like a beacon does. A 45s window would expire between
    // GATT refreshes and make "nearby" flicker for a peer we can only reach one-directionally. The
    // cost is a peer that has actually left lingers as "nearby" for up to 90s — acceptable for a
    // presence hint, and far better than flicker.
    private val staleMs = 90_000L

    fun myHop(groupId: String, target: String): Int {
        val key = Key(groupId, target)
        val ts = lastUpdated[key] ?: return MeshProtocol.UNKNOWN_HOP
        if (now() - ts > staleMs) return MeshProtocol.UNKNOWN_HOP
        return table[key] ?: MeshProtocol.UNKNOWN_HOP
    }

    /** A direct BLE neighbor is by definition 1 hop away for whatever they're broadcasting as 0/near.
     *  [sourceId] is whoever's actually reporting this (a peer/connection address) — see
     *  [updateHop]'s doc for what it's used for. */
    fun considerNeighborReport(groupId: String, target: String, neighborHop: Int, sourceId: String) {
        if (neighborHop >= MeshProtocol.UNKNOWN_HOP) return
        val candidate = (neighborHop + 1).coerceAtMost(MeshProtocol.UNKNOWN_HOP - 1)
        updateHop(groupId, target, candidate, sourceId)
    }

    /** Set my own hop value directly (not "neighbor + 1") — used when I can derive my true
     *  distance from something other than a live neighbor report, e.g. TTL consumed by a
     *  relayed SOS packet. See [updateHop] for the acceptance rule. */
    fun considerDirectHop(groupId: String, target: String, hopValue: Int, sourceId: String) {
        if (hopValue < 0 || hopValue >= MeshProtocol.UNKNOWN_HOP) return
        updateHop(groupId, target, hopValue, sourceId)
    }

    /** Shared acceptance rule for both public update methods above.
     *
     *  A report that IMPROVES the currently tracked hop is always accepted, from any source —
     *  ordinary distance-vector relaxation. A report that does NOT improve it is only accepted
     *  (replacing the value, possibly upward — i.e. genuinely worse) when it comes from the SAME
     *  source that established the current value: that source is re-asserting its own route got
     *  worse or vanished, which a strictly-better-only rule would otherwise ignore forever — once
     *  a key was recorded at hop 1, it stayed "1 hop away" no matter how stale or wrong, as long as
     *  *anything* kept refreshing recency (which every call here already did, on every report). A
     *  worse report from a DIFFERENT, non-owning source is never allowed to downgrade an existing
     *  better-known route — it has no basis to override what the owning source itself last said.
     *  Recency always refreshes regardless of acceptance, so a stable, unchanged route doesn't go
     *  stale purely from a lack of new reports. */
    private fun updateHop(groupId: String, target: String, candidate: Int, sourceId: String) {
        val key = Key(groupId, target)
        val current = myHop(groupId, target)
        val ownedBySameSource = lastSource[key] == sourceId
        if (candidate < current || (ownedBySameSource && candidate != current)) {
            table[key] = candidate
            lastSource[key] = sourceId
            lastUpdated[key] = now()
            _snapshot.update { it + (key to candidate) }
        } else {
            lastUpdated[key] = now()
        }
    }

    fun markSosOrigin(groupId: String, sosId: String) {
        val key = Key(groupId, sosId)
        table[key] = 0
        lastSource[key] = "self"
        lastUpdated[key] = now()
        _snapshot.update { it + (key to 0) }
    }

    /** Minimum hop distance among currently-*fresh* SOS trackers for a group (excludes PRESENCE) —
     *  i.e. "how far to the nearest known active SOS," expiring old entries the same way [myHop]
     *  does for presence. Previously the SOS display read the raw table directly, bypassing this
     *  staleness check entirely — a stale reading could sit there and be shown as current. */
    fun bestActiveSosHop(groupId: String): Int {
        val nowMs = now()
        return table.keys
            .asSequence()
            .filter { it.groupId == groupId && it.target != "PRESENCE" }
            .filter { nowMs - (lastUpdated[it] ?: 0L) <= staleMs }
            .mapNotNull { table[it] }
            .minOrNull() ?: MeshProtocol.UNKNOWN_HOP
    }
}
