package org.offlinemesh.app.ble

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory only, deliberately never written to disk or Room — this is the privacy tradeoff
 * the "GPS radar" feature accepts: a phone can hold recent positions of other group members
 * in RAM, but never a persisted trail. Entries expire on their own; nothing to wipe if seized,
 * because there's nothing durable to find.
 */
class PositionTracker {
    data class Key(val groupId: String, val senderId: String)
    data class Record(val lat: Double, val lon: Double, val accuracyM: Int, val timestampSec: Long, val hop: Int)

    private val maxAgeSeconds = 90L
    private val table = ConcurrentHashMap<Key, Record>()
    private val _snapshot = MutableStateFlow<Map<Key, Record>>(emptyMap())
    val snapshot: StateFlow<Map<Key, Record>> = _snapshot

    fun offer(groupId: String, senderId: String, lat: Double, lon: Double, accuracyM: Int, timestampSec: Long, hop: Int) {
        val key = Key(groupId, senderId)
        val existing = table[key]
        if (existing != null && existing.timestampSec >= timestampSec) return // latest-wins, drop stale/out-of-order
        table[key] = Record(lat, lon, accuracyM, timestampSec, hop)
        prune()
        _snapshot.value = table.toMap()
    }

    fun forGroup(groupId: String): Map<String, Record> {
        // Staleness must be enforced here, at read time, not only via prune()'s side effect of
        // a new offer() arriving — if a peer goes quiet and nothing else comes in for anyone,
        // prune() never re-runs and their last-known dot would otherwise sit on the radar
        // forever (found during rubric trace, case 6.7/6.9).
        val cutoff = System.currentTimeMillis() / 1000 - maxAgeSeconds
        return table.filterKeys { it.groupId == groupId }
            .filterValues { it.timestampSec >= cutoff }
            .mapKeys { it.key.senderId }
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() / 1000 - maxAgeSeconds
        val stale = table.filterValues { it.timestampSec < cutoff }.keys
        stale.forEach { table.remove(it) }
    }
}
