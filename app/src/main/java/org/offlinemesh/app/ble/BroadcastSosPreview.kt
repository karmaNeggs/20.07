package org.offlinemesh.app.ble

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory-only cache of broadcast-tier SOS content previews (decision 29's follow-up, decision
 * 30, `docs/DECISIONS.md`) — never written to disk, and deliberately NOT a real [org.offlinemesh
 * .app.data.SosEntity]: a preview is missing fields (`senderId`, `ttl`, the GATT-authoritative
 * mac/signature) a stored record requires, so it stays here rather than in Room, same reasoning as
 * [PositionTracker]'s own class doc.
 *
 * Deliberately has NO staleness clock of its own — [forGroupIfBest] instead requires the caller to
 * pass in [HopTracker.bestActiveSos]'s current answer for the same group and only returns a match
 * when the cached id agrees with it. A second, independently-aging notion of "is this still
 * current" is exactly the bug shape this app has already been bitten by once (two channels feeding
 * one SOS-hop display, one of them going stale on its own — see the historical Pass 13 fix in
 * `docs/DECISIONS.md`); reusing `HopTracker`'s already-tested freshness check here avoids
 * reintroducing it.
 */
class BroadcastSosPreview {
    data class Content(val sosId: String, val message: String, val timestampSec: Long)

    private val table = ConcurrentHashMap<String, Content>() // keyed by groupId

    /** SOS content is immutable once created (decision 29's own note) — a repeat offer for the
     *  same id is always identical, so a plain overwrite is safe; no dedup/compare needed. */
    fun offer(groupId: String, sosId: String, message: String, timestampSec: Long) {
        table[groupId] = Content(sosId, message, timestampSec)
    }

    /** Returns the cached preview for [groupId] only if its id still matches [currentBestSosId] —
     *  normally the caller's own `HopTracker.bestActiveSos(groupId)?.first`. See the class doc for
     *  why this is the only freshness check this class performs. */
    fun forGroupIfBest(groupId: String, currentBestSosId: String?): Content? =
        table[groupId]?.takeIf { it.sosId == currentBestSosId }

    /** Same shape as [PositionTracker.clearForGroup] (decision 30) — this table is also ble-layer,
     *  in-memory, per-group state that `GroupRepository.dismantleGroup`'s data-layer call can't
     *  reach on its own. */
    fun clearForGroup(groupId: String) {
        table.remove(groupId)
    }

    /** Same shape as [PositionTracker.pruneOrphaned] (decision 30) — periodic safety net alongside
     *  [clearForGroup]'s immediate clear, also catching automatic `GroupRepository.expireGroups`. */
    fun pruneOrphaned(activeGroupIds: Set<String>) {
        table.keys.filter { it !in activeGroupIds }.forEach { table.remove(it) }
    }
}
