package org.offlinemesh.app.sim

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * PLAN-v2.md Part 7's P3 gate, plus a re-run of P1's own hardware-gate scenarios under
 * [PersistentForwardingEngine] — per the user's explicit sequencing decision (2026-08-05,
 * following `docs/DECISIONS.md` decision 16's finding that P1 alone doesn't hit its own latency
 * claim): build P3, then measure P1+P3 TOGETHER before touching production.
 *
 * *Sim gate: at D = 400, diversity selection beats first-heard on reachability by a measurable
 * margin.*
 */
class P3GateTest {

    @Test
    fun `D=400 diversity selection beats first-heard on held-link spread`() {
        val nodeCount = 400
        val radius = 0.05 // ~40 candidates within range per node on a ring of 400
        // Must be small relative to the neighbourhood diameter (2*radius = 0.1) for eviction to
        // ever fire at all — this caught a real bug in the test itself: the default
        // minDiversitySeparation (0.1) is larger than an entire neighbourhood at this radius, so
        // candidateAddsCoverage was almost never true and diversity mode was silently behaving
        // identically to first-heard. A fifth of the neighbourhood diameter leaves real room for
        // "close together" (redundant) vs "spread within range" (worth swapping for) to differ.
        val minSeparation = radius / 2.5
        val random = Random(SimNetwork.SEED + 5)
        val positions = (0 until nodeCount).associate { "node-$it" to random.nextDouble() }
        val runMs = 30 * 60_000L

        fun heldSpread(diversityEnabled: Boolean): Double {
            val clock = SimClock()
            val events = SimEventQueue(clock)
            val metrics = SimMetrics()
            val nodes = ScenarioSupport.buildNodes(nodeCount, clock)
            val network = SimNetwork.spatialRing(nodes, positions, radius)
            val tuning = PersistentLinkTuning(
                minDiversitySeparation = minSeparation, diversityEnabled = diversityEnabled,
            )
            val engine = PersistentForwardingEngine(clock, events, network, positions, metrics, tuning)
            engine.start()
            events.runUntil(runMs)

            // Reachability proxy: how much of the [0,1) ring a node's held links span. A node
            // clustered on nearby links has a small span; a diverse node's span approaches the
            // radius ceiling (it can't exceed 2*radius on a ring by construction).
            fun ringSpan(nodeId: String, heldIds: Collection<String>): Double {
                if (heldIds.isEmpty()) return 0.0
                val center = positions.getValue(nodeId)
                val offsets = heldIds.map { id ->
                    val raw = positions.getValue(id) - center
                    when {
                        raw > 0.5 -> raw - 1.0
                        raw < -0.5 -> raw + 1.0
                        else -> raw
                    }
                }
                return (offsets.max() - offsets.min())
            }

            // Peek at the engine's held links via a second, identical accessor isn't exposed
            // publicly — read spread from the metrics-observable proxy instead: which peers each
            // node ended up actually syncing with (recorded via recordSync), deduplicated. Every
            // held link produces at least one backfill sync in a 30-minute run at this cadence.
            val forward = metrics.syncs.map { it.initiator to it.peer }
            val backward = metrics.syncs.map { it.peer to it.initiator }
            val heldByNode = (forward + backward).groupBy({ it.first }, { it.second })
            val spreads = nodes.map { node ->
                val held = heldByNode[node.id]?.toSet().orEmpty()
                ringSpan(node.id, held)
            }
            return spreads.average()
        }

        val firstHeardSpread = heldSpread(diversityEnabled = false)
        val diversitySpread = heldSpread(diversityEnabled = true)

        assertTrue(
            "expected diversity selection to beat first-heard on held-link ring-spread: " +
                "diversity=$diversitySpread firstHeard=$firstHeardSpread",
            diversitySpread > firstHeardSpread,
        )
    }

    @Test
    fun `P1+P3 combined - line topology SOS latency, re-measured against P1-alone's 52s finding`() {
        val clock = SimClock()
        val events = SimEventQueue(clock)
        val metrics = SimMetrics()
        val nodes = ScenarioSupport.buildNodes(3, clock)
        val (a, b, c) = nodes
        val adjacency = mapOf(a.id to listOf(b), b.id to listOf(a, c), c.id to listOf(b))
        val network = SimNetwork(nodes, Random(SimNetwork.SEED)) { adjacency }
        val positions = nodes.associate { it.id to 0.5 } // irrelevant at D<=2, never reaches the diversity floor
        val engine = PersistentForwardingEngine(clock, events, network, positions, metrics)
        engine.start()

        val sos = "sos:p1p3-line"
        events.schedule(30_000L) { engine.injectPacket(a, sos) }
        events.runUntil(10 * 60_000L)

        val deliveredAtC = metrics.firstDeliveryMs(sos, c.id)
        assertTrue("expected the SOS to reach the far end of the line", deliveredAtC != null)
        val latencyMs = deliveredAtC!! - 30_000L
        // The actual headline claim: seconds, not the ~45s/hop v1 baseline (or P1-alone's own
        // measured 52s for this identical topology, docs/DECISIONS.md decision 16).
        assertTrue(
            "expected persistent links to close the gap P1 alone couldn't - got ${latencyMs}ms " +
                "(P1-alone measured 52000ms for this identical topology)",
            latencyMs < 10_000L,
        )
    }
}
