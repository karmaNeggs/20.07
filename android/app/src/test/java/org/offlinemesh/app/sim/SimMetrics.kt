package org.offlinemesh.app.sim

/**
 * Everything a scenario run measures, in one place — both the yield/latency numbers PLAN-v2.md
 * Part 1 cites and the raw traces §6.2's invariants check against. Deliberately dumb (append-only
 * lists + simple derived stats): correctness lives in [Invariants] and each scenario test's own
 * assertions, not here.
 */
class SimMetrics {
    data class SyncEvent(val initiator: String, val peer: String, val pushed: Int, val atMs: Long)
    data class HalfOpenEvent(val nodeId: String, val atMs: Long, val recoveredAtMs: Long? = null)

    val syncs = mutableListOf<SyncEvent>()
    val radioTouches = mutableListOf<Pair<String, Long>>() // nodeId to atMs
    val stuckRecoveries = mutableListOf<Pair<String, Long>>()
    private val halfOpenByNode = mutableMapOf<String, HalfOpenEvent>()
    private val deliveries = mutableListOf<Triple<String, String, Long>>() // item, nodeId, atMs
    val injections = mutableMapOf<String, Long>() // item -> injectedAtMs

    fun recordSync(initiator: String, peer: String, pushed: Int, atMs: Long) {
        syncs += SyncEvent(initiator, peer, pushed, atMs)
    }

    fun recordRadioTouch(nodeId: String, atMs: Long) {
        radioTouches += nodeId to atMs
    }

    fun recordStuckRecovered(nodeId: String, atMs: Long) {
        stuckRecoveries += nodeId to atMs
    }

    fun recordHalfOpen(nodeId: String, atMs: Long) {
        halfOpenByNode[nodeId] = HalfOpenEvent(nodeId, atMs)
    }

    fun recordHalfOpenRecovered(nodeId: String, atMs: Long) {
        halfOpenByNode[nodeId]?.let { halfOpenByNode[nodeId] = it.copy(recoveredAtMs = atMs) }
    }

    val halfOpenEvents: List<HalfOpenEvent> get() = halfOpenByNode.values.toList()

    fun recordInjection(item: String, atMs: Long) {
        injections[item] = atMs
    }

    fun recordDelivery(item: String, nodeId: String, atMs: Long) {
        deliveries += Triple(item, nodeId, atMs)
    }

    /** First time [item] was present at [nodeId], or null if it never arrived during the run. */
    fun firstDeliveryMs(item: String, nodeId: String): Long? =
        deliveries.filter { it.first == item && it.second == nodeId }.minOfOrNull { it.third }

    /** Time by which EVERY node in [nodeIds] held [item], or null if at least one never did within
     *  the recorded run. */
    fun fullDeliveryMs(item: String, nodeIds: Collection<String>): Long? {
        val times = nodeIds.map { firstDeliveryMs(item, it) ?: return null }
        return times.max()
    }

    fun emptySyncRate(): Double =
        if (syncs.isEmpty()) 0.0 else syncs.count { it.pushed == 0 }.toDouble() / syncs.size

    /** Mean gap between consecutive completed syncs FOR THE SAME (initiator, peer) pair — what
     *  the diagnostics-10 "one connection every ~50s" line actually measured. */
    fun meanPairSyncIntervalMs(): Double {
        val byPair = syncs.groupBy { setOf(it.initiator, it.peer) }
        val gaps = byPair.values.flatMap { pairSyncs ->
            pairSyncs.sortedBy { it.atMs }.zipWithNext { a, b -> (b.atMs - a.atMs).toDouble() }
        }
        return if (gaps.isEmpty()) Double.NaN else gaps.average()
    }

    /** Radio touches for [nodeId] per unit time — the I8 "bounded per-node work" measurement. */
    fun radioTouchRatePerSecond(nodeId: String, overMs: Long): Double {
        if (overMs <= 0L) return 0.0
        return radioTouches.count { it.first == nodeId } / (overMs / 1000.0)
    }
}
