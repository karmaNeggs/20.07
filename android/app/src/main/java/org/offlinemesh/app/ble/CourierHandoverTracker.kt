package org.offlinemesh.app.ble

/**
 * P4 slice 4 (`docs/DECISIONS.md` decision 44, `PLAN-v2.md` §4.2's "rate-limit handover to 1
 * attempt per envelope per 10 min") — per-(envelope, peer) last-handover-attempt bookkeeping, kept
 * separate from [CourierHandover]'s pure split arithmetic the same way [ConnectionAttemptTracker]
 * keeps its own bounded/timed state separate from whatever decision consumes it.
 *
 * Prevents a courier from re-splitting and re-pushing the same envelope to the same peer on every
 * rapid reconnect — without this, a peer whose `CatalogFilter` hasn't yet reflected a just-received
 * envelope (the same false-negative window [CatalogFilter]'s own class doc already accepts as safe
 * for ordinary content) would otherwise trigger a fresh split-and-halve on every single reconnect,
 * needlessly shrinking the local copy count for no delivery benefit.
 *
 * [peerKey] should be the peer's resolved stable identity ([PeerIdentityResolver.resolve]), not the
 * raw BLE address — the address rotates roughly every ~15 minutes, which would make a 10-minute
 * rate limit nearly useless if keyed on it directly. Falls back gracefully to the raw address when
 * identity isn't resolved yet (same "worst case is one redundant attempt, not a correctness bug"
 * degradation [RelayResponder.ingestOpenedSos]'s own `excludeKey` computation already accepts).
 *
 * Bounded via LRU eviction at [maxTracked] entries, mirroring [ConnectionAttemptTracker.
 * maxTrackedAddresses]'s own reasoning — an (envelope, peer) pair is not a stable long-lived key
 * either (envelopes expire at 24h, peers rotate), so an unbounded map would grow forever in a long
 * crowd session.
 */
class CourierHandoverTracker(
    private val rateLimitMs: Long = RATE_LIMIT_MS,
    private val maxTracked: Int = MAX_TRACKED,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Key(val envelopeId: String, val peerKey: String)

    // Access-ordered so eviction drops the least-recently-touched pair, matching
    // ConnectionAttemptTracker's own bounding pattern for the same "unbounded peer-keyed state"
    // hazard.
    private val lastAttemptAt = object : LinkedHashMap<Key, Long>(MAP_CAPACITY, MAP_LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Long>) = size > maxTracked
    }

    /** True if a handover to [peerKey] for [envelopeId] has never been attempted, or the last
     *  attempt was at least [rateLimitMs] ago. */
    @Synchronized
    fun canAttempt(envelopeId: String, peerKey: String): Boolean {
        val last = lastAttemptAt[Key(envelopeId, peerKey)] ?: return true
        return now() - last >= rateLimitMs
    }

    /** Records that a handover to [peerKey] for [envelopeId] was just attempted — call only when
     *  [canAttempt] was just checked and a real split-and-push actually happened, not speculatively. */
    @Synchronized
    fun recordAttempt(envelopeId: String, peerKey: String) {
        lastAttemptAt[Key(envelopeId, peerKey)] = now()
    }

    companion object {
        const val RATE_LIMIT_MS = 10 * 60 * 1000L
        private const val MAX_TRACKED = 500
        private const val MAP_CAPACITY = 16
        private const val MAP_LOAD_FACTOR = 0.75f
    }
}
