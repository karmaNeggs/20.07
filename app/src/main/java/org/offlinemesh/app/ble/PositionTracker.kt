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
        /** Decision 38 (docs/DECISIONS.md): the opaque GATT wire handle this position arrived under
         *  — see [MeshFrameCodec.groupHandle]'s doc. Same "kept so relaying can forward it verbatim"
         *  reasoning and the same equals/hashCode exclusion as [sealed]. Null only for our own fix
         *  (never stored here at all — see `RelayResponder.positionFramesToPush`'s own-fix branch,
         *  which computes a fresh handle on every ~20s push cycle instead of storing one). */
        val handle: ByteArray? = null,
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
        handle: ByteArray? = null,
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
        table[key] = Record(lat, lon, accuracyM, timestampSec, hop, viaPeer, sealed, handle)
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

    /** Call when a group is dismantled (decision 30, `docs/DECISIONS.md`) — this table is the one
     *  piece of per-group state `GroupRepository.dismantleGroup` can't reach itself (in-memory,
     *  owned by `MeshService`, not Room; different package layer), so it's the caller's job, not
     *  something `offer`/`forGroup`'s own staleness pruning would ever clean up promptly on its
     *  own. Without this, a dismantled group's last-known positions linger in memory until the
     *  ordinary staleness window (180s, plus up to 45s/hop) expires on its own — harmless once the
     *  group is gone from the UI's own group list, but no reason to hold onto it. */
    fun clearForGroup(groupId: String) {
        table.keys.filter { it.groupId == groupId }.forEach { table.remove(it) }
    }

    /** Periodic safety net alongside [clearForGroup]'s immediate per-group clear (decision 30) —
     *  removes any tracked position whose groupId isn't in [activeGroupIds]. Catches automatic
     *  expiry (`GroupRepository.expireGroups`, which reuses `dismantleGroup` the same way a manual
     *  delete does but has no single call site to hook a `clearForGroup` into) and any other future
     *  dismantle path, without needing every caller to remember this table exists — same "orphan
     *  sweep" shape as `GroupRepository.sweepOrphanKeys`. */
    fun pruneOrphaned(activeGroupIds: Set<String>) {
        table.keys.filter { it.groupId !in activeGroupIds }.forEach { table.remove(it) }
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

        /** [effectiveMaxAgeSeconds] pinned to this class's own [BASE_MAX_AGE_SECONDS] — the single
         *  source of truth callers outside this file should use (decision 33, `docs/DECISIONS.md`)
         *  rather than re-declaring the 180s literal themselves the way `RadarView`'s fade curve
         *  used to. Public specifically so `RadarCanvas` can size a dot's fade-out window to the
         *  SAME staleness budget [forGroup] actually enforces for it, instead of an unrelated flat
         *  constant that stopped matching once `RelayResponder.maxPositionRelayHops` grew from 4 to
         *  a much larger ceiling — a hop-120 position's real staleness budget is over 90 minutes,
         *  not the ~3-6 minutes the old flat constant assumed. */
        fun effectiveMaxAgeSecondsFor(hop: Int): Long = effectiveMaxAgeSeconds(BASE_MAX_AGE_SECONDS, hop)
    }
}
