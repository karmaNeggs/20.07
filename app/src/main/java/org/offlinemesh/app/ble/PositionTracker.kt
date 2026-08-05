package org.offlinemesh.app.ble

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory only, deliberately never written to disk or Room — this is the privacy tradeoff
 * the "GPS radar" feature accepts: a phone can hold recent positions of other group members
 * in RAM, but never a persisted trail. Entries expire on their own; nothing to wipe if seized,
 * because there's nothing durable to find.
 *
 * [maxAgeSeconds] is a per-hop-0/1 baseline, not a flat window — see [effectiveMaxAgeSeconds]'s
 * doc for the live-tested reasoning (a relayed position's worst-case propagation delay scales
 * with hop count, since each hop needs its own independent GATT reconnect cycle to succeed
 * before a value can be passed on any further).
 */
class PositionTracker(private val now: () -> Long = System::currentTimeMillis) {
    data class Key(val groupId: String, val senderId: String)
    /** [viaPeer] is the peer address this record arrived from, or null for our own fix. It exists
     *  purely for split horizon (see [RelayResponder.selectPositionsToRelay]): a route must never be
     *  advertised back toward whoever supplied it. Without it, live 3-phone testing produced the
     *  classic distance-vector loop — one sender's position circulating the triangle and arriving
     *  back at hop 0, 1, 2 AND 3, with 121 of 267 receipts being hop-3 copies that existed only to
     *  be discarded. */
    data class Record(
        val lat: Double,
        val lon: Double,
        val accuracyM: Int,
        val timestampSec: Long,
        val hop: Int,
        val viaPeer: String? = null,
        /** The ORIGINAL sealed bytes this position arrived in, kept so relaying can forward them
         *  verbatim instead of re-encrypting (see [RelayResponder.positionFramesToPush]). Null only
         *  for our own fix, which we seal ourselves. Deliberately excluded from [equals]/[hashCode]
         *  below — a data class compares ByteArray by reference, which would make two records with
         *  identical content compare unequal. */
        val sealed: ByteArray? = null,
    ) {
        // Content equality over the scalar fields only; `sealed` is derived from them, so including
        // it (by reference, as a data class would) would only ever produce false inequality.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Record) return false
            return lat == other.lat && lon == other.lon && accuracyM == other.accuracyM &&
                timestampSec == other.timestampSec && hop == other.hop && viaPeer == other.viaPeer
        }

        override fun hashCode(): Int =
            listOf(lat, lon, accuracyM, timestampSec, hop, viaPeer).hashCode()
    }

    // 180s, not 90s. Live measurement across three ~110-minute sessions: position refresh median
    // was 13-16s and p90 36-52s, but 5-8% of gaps exceeded 90s — and every one of those blanked the
    // dot, which is the "radar goes blank / jittery" report. The window only had ~1.8x headroom over
    // p90, so ordinary tail latency (a reconnect that took 150-250s, an address rotation forcing
    // rediscovery) crossed it routinely. Doubling it covers the tail; the cost is that a member who
    // has genuinely left can linger for up to 3 minutes, which is why RadarView's age-fade now spans
    // the whole window instead of stopping at 90s — an old dot must LOOK old, not authoritative.
    private val maxAgeSeconds = BASE_MAX_AGE_SECONDS
    // No StateFlow mirror here, deliberately: one existed and was copied on every accepted
    // position (an O(n) allocation on the hot path) while having no consumers at all — the UI reads
    // forGroup(). Removed rather than left as a cost nothing collected.
    private val table = ConcurrentHashMap<Key, Record>()

    // Live-tested gap: ConnectionAttemptTracker already skips a peer's reconnect cooldown early
    // when there's genuinely new CONTENT to offer them (RelayEngine.catalogEpoch — see that
    // class's doc), but had no equivalent for position: a phone that just picked up a fresher
    // position for someone had no way to say "I have something new for this peer" the way content
    // does, so a relayed position sat waiting out the full, un-skippable cooldown regardless of
    // how quickly it was actually received. Bumped only when offer() below accepts a genuinely
    // newer record (not a stale/duplicate/out-of-order one) — same "only when there's actually
    // something new" discipline catalogEpoch already follows.
    private val epoch = AtomicInteger(0)
    val positionEpoch: Int get() = epoch.get()

    @Suppress("LongParameterList") // wire-shaped scalars, matching MeshFrameCodec.encodePosition
    fun offer(
        groupId: String,
        senderId: String,
        lat: Double,
        lon: Double,
        accuracyM: Int,
        timestampSec: Long,
        hop: Int,
        viaPeer: String? = null,
        sealed: ByteArray? = null,
    ) {
        val key = Key(groupId, senderId)
        val existing = table[key]
        // Latest-wins, and at equal timestamps prefer the SHORTER path: the same fix can race over
        // two routes, and taking whichever landed first would pin a worse hop count — inflating the
        // displayed distance, stretching this record's staleness window, and (via viaPeer) pointing
        // split horizon at the wrong peer.
        val staleOrWorse = existing != null && (
            existing.timestampSec > timestampSec ||
                (existing.timestampSec == timestampSec && existing.hop <= hop)
            )
        if (staleOrWorse) return
        table[key] = Record(lat, lon, accuracyM, timestampSec, hop, viaPeer, sealed)
        epoch.incrementAndGet()
        prune()
    }

    fun forGroup(groupId: String): Map<String, Record> {
        // Staleness must be enforced here, at read time, not only via prune()'s side effect of
        // a new offer() arriving — if a peer goes quiet and nothing else comes in for anyone,
        // prune() never re-runs and their last-known dot would otherwise sit on the radar forever.
        val nowSec = now() / 1000
        return table.filterKeys { it.groupId == groupId }
            .filterValues { nowSec - it.timestampSec <= effectiveMaxAgeSeconds(maxAgeSeconds, it.hop) }
            .mapKeys { it.key.senderId }
    }

    private fun prune() {
        val nowSec = now() / 1000
        val stale = table.filterValues { nowSec - it.timestampSec > effectiveMaxAgeSeconds(maxAgeSeconds, it.hop) }.keys
        stale.forEach { table.remove(it) }
    }

    companion object {
        // See PositionTracker's class doc / HopTracker.PER_HOP_SLACK_MS (same value, same
        // reasoning — kept in sync by doc only, this file has no ble-internal dependency either).
        private const val PER_HOP_SLACK_SECONDS = 45L

        /** See the class-level note on why this is 180s and not 90s. */
        private const val BASE_MAX_AGE_SECONDS = 180L

        /** `internal`, no instance state — directly unit-testable. [hop] is a position record's
         *  OWN stored hop value (0 = the sender's direct fix, relayed once more for each hop
         *  beyond that — see [MeshFrameCodec.encodePosition]'s doc) — hop 0 gets exactly
         *  [baseMaxAgeSeconds], since a direct fix needs only one connection to refresh; each
         *  relay hop beyond that adds [PER_HOP_SLACK_SECONDS] of margin for the one additional,
         *  independent reconnect cycle that hop's propagation depends on. */
        internal fun effectiveMaxAgeSeconds(baseMaxAgeSeconds: Long, hop: Int): Long =
            baseMaxAgeSeconds + hop.coerceAtLeast(0) * PER_HOP_SLACK_SECONDS
    }
}
