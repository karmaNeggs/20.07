package org.offlinemesh.app.ble

/**
 * In-memory hot-layer dedup for the P1 forwarding plane (PLAN-v2.md §5.3) — "hear a packet once
 * this session, don't forward it again." The existing Room `SeenMessageEntity` (48h) stays the
 * COLD layer for catalogue-sync backfill dedup, unchanged; this is the fast, size-bounded,
 * in-memory layer flood-forwarding needs on the hot receive path — mirroring bitchat's own dedup
 * shape (~1000 entries / 5 min) but sized for what actually enters THIS app's flood (see
 * [DEFAULT_MAX_ENTRIES]'s doc: presence/position stay on the existing GATT-connect-time path,
 * untouched by P1, so the flood only ever carries SOS/evidence-headers/nicknames/courier envelopes).
 *
 * LRU + time bounded, same shape as every other extracted decision class in this package
 * (`ConnectionAttemptTracker.cooldownUntil`, `OpaqueFrameRelay.held`) — a set that only ever grows
 * without eviction is exactly the class of bug this project keeps finding under load.
 */
class DedupCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val seenAt = object : LinkedHashMap<String, Long>(MAP_CAPACITY, MAP_LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>) = size > maxEntries
    }

    /** True if [key] is newly seen (and is now remembered) — false if it's a duplicate already
     *  offered within [maxAgeMillis]. An entry older than that is treated as unseen again: the hot
     *  flood layer only needs to suppress duplicates arriving close together in time, not forever
     *  (Room's `SeenMessageEntity` already covers the long window). */
    @Synchronized
    fun offerNew(key: String): Boolean {
        val last = seenAt[key]
        val isDuplicate = last != null && now() - last < maxAgeMillis
        seenAt[key] = now()
        return !isDuplicate
    }

    @Synchronized
    fun size(): Int = seenAt.size

    companion object {
        // §9.2 item 6's derivation: only SOS/evidence-headers/nicknames/courier envelopes enter
        // the flood — order 1-5 unique packets/sec at busy moments, ~1500 entries over a 5-minute
        // window. 3000 (mid the plan's own derived 2000-4000 "safe" range) leaves real headroom
        // without the ~15000 a leakier scope (presence/position also flooding) would need.
        const val DEFAULT_MAX_ENTRIES = 3000
        const val DEFAULT_MAX_AGE_MILLIS = 5 * 60_000L
        private const val MAP_CAPACITY = 16
        private const val MAP_LOAD_FACTOR = 0.75f
    }
}
