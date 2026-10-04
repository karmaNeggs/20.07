package org.offlinemesh.app.ble

import org.offlinemesh.app.crypto.CryptoUtils

/**
 * Carries frames belonging to groups we hold no key for onward, **without being able to read them** —
 * the blind-relay mechanism behind both the radar and the hop count crossing a stranger's phone.
 *
 * Two frame types need this, for the same reason and with the same shape:
 *  - **Positions.** `handlePositionSealed` used to open with `getGroupKey(groupId) ?: return`, so a
 *    non-member dropped them. Live measurement was unambiguous: across three 3-phone sessions, all
 *    627 positions received arrived at hop 0, while SOS from the same sessions reached hop 2 through
 *    the same relay — content was blind-relayed, positions never were.
 *  - **Presence.** Same gate, same outcome, and it's what the group's hop count is built on. Fixing
 *    positions alone leaves one hole: a member with no GPS fix (indoors, GPS off, cold start) pushes
 *    no position at all, so nothing carries their existence outward and they read as absent rather
 *    than distant — precisely the GPS-denied situation this app is meant for.
 *
 * The privacy property that motivated the original gate is fully preserved: nothing here is opened,
 * decrypted, verified, or inspected. Frames are re-encoded only to advance their cleartext hop, and a
 * relay learns that *someone* in *some* group is roughly N hops away — which the beacon already
 * reveals — not who, and not where.
 *
 * In-memory only, like [PositionTracker], and for the same reason: this is live coordination state
 * with a sub-minute useful life, not a record. Nothing survives a restart; a seized phone yields
 * nothing from it.
 *
 * Bounded on both axes — [maxEntries] (LRU) and [maxAgeMillis] (a frame too old for its eventual
 * recipient to accept is also too old to spend airtime forwarding). Callers supply their own dedup
 * key; without one, a three-phone triangle would circulate the same frame between itself forever.
 */
class OpaqueFrameRelay(
    private val maxEntries: Int = MAX_ENTRIES,
    private val maxAgeMillis: Long = MAX_AGE_MILLIS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Held(val forwardable: ByteArray, val receivedAtMs: Long, val viaPeer: String?)

    // Access-ordered so eviction drops the least-recently-touched entry, matching
    // ConnectionAttemptTracker's bounding pattern for the same "unbounded peer state" hazard.
    private val held = object : LinkedHashMap<String, Held>(MAP_CAPACITY, MAP_LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Held>) = size > maxEntries
    }

    /**
     * Takes custody of a frame for a group we can't decrypt. Returns true if it was newly accepted
     * (i.e. worth forwarding), false for a duplicate already carried or a frame already too far out
     * to be useful to anyone.
     *
     * [hop] is the hop the frame ARRIVED with. [encodeNextHop] is invoked once, now, to produce the
     * bytes that will actually be forwarded — so nothing needs to be re-derived (or re-decoded)
     * later at push time.
     */
    @Synchronized
    fun offer(
        dedupKey: String,
        hop: Int,
        maxHops: Int,
        viaPeer: String? = null,
        encodeNextHop: () -> ByteArray,
    ): Boolean {
        // Forwarding produces hop+1; if that's already at the ceiling, carrying it is pure cost.
        if (hop + 1 >= maxHops) return false
        if (held.containsKey(dedupKey)) return false
        held[dedupKey] = Held(encodeNextHop(), now(), viaPeer)
        prune()
        return true
    }

    /** Everything currently worth passing on, already encoded at hop+1 and ready to write.
     *  [excludePeer] applies split horizon — a frame is never handed back to the peer that supplied
     *  it, which is what stops a three-phone triangle circulating one frame at escalating hops. */
    @Synchronized
    fun framesToRelay(excludePeer: String? = null, limit: Int = Int.MAX_VALUE): List<ByteArray> {
        prune()
        val eligible = held.values.filter { excludePeer == null || it.viaPeer != excludePeer }
        if (eligible.size <= limit) return eligible.map { it.forwardable }
        // Rotate the starting point per call. Without this, a store larger than the per-connection
        // budget would emit the same head-of-list frames every single session and the tail would
        // NEVER go out — a silent, permanent delivery hole for whichever frames happened to land
        // late. Rotating means every entry reaches the front within ceil(size/limit) connections.
        val start = rotationOffset % eligible.size
        rotationOffset = (rotationOffset + limit) % eligible.size.coerceAtLeast(1)
        return (0 until limit).map { eligible[(start + it) % eligible.size].forwardable }
    }

    private var rotationOffset = 0

    /** Count currently being carried — for diagnostics and tests, not logic. */
    @Synchronized
    fun size(): Int {
        prune()
        return held.size
    }

    private fun prune() {
        val cutoff = now() - maxAgeMillis
        held.entries.filter { it.value.receivedAtMs < cutoff }.map { it.key }.forEach { held.remove(it) }
    }

    companion object {
        // Matches PositionTracker's own base staleness window: a frame past it can't be used by the
        // eventual recipient either, so forwarding it is wasted airtime.
        private const val MAX_AGE_MILLIS = 180_000L

        // Generous for this app's real target (~10 people, a few groups) while still bounding a dense
        // crowd, where one phone could be handed frames for hundreds of groups.
        private const val MAX_ENTRIES = 200

        private const val MAP_CAPACITY = 16
        private const val MAP_LOAD_FACTOR = 0.75f

        /** Stable short digest of arbitrary frame-identifying bytes, for use as a dedup key.
         *  8 bytes of SHA-256: collisions are negligible at this set size, and the only cost of one
         *  would be a single frame not forwarded. */
        fun dedupKey(vararg parts: ByteArray): String {
            val joined = parts.fold(ByteArray(0)) { acc, p -> acc + p }
            return CryptoUtils.sha256(joined).copyOf(DEDUP_KEY_BYTES).joinToString("") { "%02x".format(it) }
        }

        private const val DEDUP_KEY_BYTES = 8
    }
}
