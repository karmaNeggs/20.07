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

    /** 1 bit per chunk index, 1 = have it. Compact even for thousands of chunks (~650B for 5000). */
    fun encodeBitset(haveIndexes: Set<Int>, totalChunks: Int): ByteArray {
        val bytes = ByteArray((totalChunks + 7) / 8)
        for (i in haveIndexes) {
            if (i < 0 || i >= totalChunks) continue
            bytes[i / 8] = (bytes[i / 8].toInt() or (1 shl (i % 8))).toByte()
        }
        return bytes
    }

    fun decodeBitset(bytes: ByteArray, totalChunks: Int): Set<Int> {
        val result = mutableSetOf<Int>()
        for (i in 0 until totalChunks) {
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

    /** A direct BLE neighbor is by definition 1 hop away for whatever they're broadcasting as 0/near. */
    fun considerNeighborReport(groupId: String, target: String, neighborHop: Int) {
        if (neighborHop >= MeshProtocol.UNKNOWN_HOP) return
        val candidate = (neighborHop + 1).coerceAtMost(MeshProtocol.UNKNOWN_HOP - 1)
        val key = Key(groupId, target)
        val current = myHop(groupId, target)
        if (candidate < current) {
            table[key] = candidate
            lastUpdated[key] = now()
            _snapshot.update { it + (key to candidate) }
        } else {
            // refresh recency even if not an improvement, so a stable route doesn't go stale
            lastUpdated[key] = now()
        }
    }

    /** Set my own hop value directly (not "neighbor + 1") — used when I can derive my true
     *  distance from something other than a live neighbor report, e.g. TTL consumed by a
     *  relayed SOS packet. Only updates if it's an improvement, same as considerNeighborReport. */
    fun considerDirectHop(groupId: String, target: String, hopValue: Int) {
        if (hopValue < 0 || hopValue >= MeshProtocol.UNKNOWN_HOP) return
        val key = Key(groupId, target)
        val current = myHop(groupId, target)
        if (hopValue < current) {
            table[key] = hopValue
            lastUpdated[key] = now()
            _snapshot.update { it + (key to hopValue) }
        } else {
            lastUpdated[key] = now()
        }
    }

    fun markSosOrigin(groupId: String, sosId: String) {
        val key = Key(groupId, sosId)
        table[key] = 0
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
