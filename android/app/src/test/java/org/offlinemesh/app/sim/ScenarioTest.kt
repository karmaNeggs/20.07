package org.offlinemesh.app.sim

import org.junit.Assert.assertTrue
import org.offlinemesh.app.ble.OpaqueFrameRelay
import org.junit.Test

/**
 * PLAN-v2.md §6.3's named scenario catalogue, run against the v1-baseline [CatalogSyncEngine].
 * Covers S1, S2, S6, S7, S8, S9, S11 — the ones expressible against the CURRENT connect-sync-
 * disconnect mechanism this phase models. **Deliberately not built yet, and why:**
 * - **S3 "Walking out" / S4 "Walking in"** need a broadcast tier with Trickle suppression to have
 *   anything to test — I5 (fail-open) is trivially satisfied by an engine with no suppression
 *   mechanism at all. [SimNetwork.degreeRamp] (the mobility model they need) is already built;
 *   wiring S3/S4 up is P2 work, once Trickle governs a broadcast-tier engine sharing this same rig.
 * - **S5 "Split and rejoin"** needs courier/store-and-forward logic (P4).
 * - **S10 "Relay dies mid-transfer"** is a media-transfer scenario (P5); this phase has no bulk
 *   transfer model yet, only catalogue-item presence/absence.
 */
class ScenarioTest {

    @Test
    fun `S1 three in a room - baseline connectivity and yield at D=2`() {
        val result = ScenarioSupport.runCatalogSyncScenario(
            CatalogSyncScenarioConfig(nodeCount = 3, durationMs = 10 * 60_000L, injectedItems = 3),
        )
        val allItems = result.metrics.injections.keys
        assertTrue("every item should reach every node at D=2 well within a 10min run",
            allItems.all { item -> result.nodes.all { item in it.catalogItems } })
        Invariants.checkYieldFloor(result.metrics, minYieldFraction = 0.02)
        for (node in result.nodes) Invariants.checkBoundedPeerState(node, maxExpected = 10)
    }

    @Test
    fun `S2 five in a march - a small group is findable and served inside 400 strangers`() {
        val totalNodes = 400
        val groupSize = 5
        val clock = SimClock()
        val events = SimEventQueue(clock)
        val metrics = SimMetrics()
        val nodes = ScenarioSupport.buildNodes(totalNodes, clock)
        val group = nodes.take(groupSize)
        val network = SimNetwork.randomRegular(nodes, degree = 20)
        val engine = CatalogSyncEngine(clock, events, network, PlatformQuirks(), metrics)
        engine.start()

        val item = "sos:group-flare"
        events.schedule(1_000L) {
            group[0].catalogItems += item
            metrics.recordInjection(item, clock.now())
            metrics.recordDelivery(item, group[0].id, clock.now())
        }
        val deadlineMs = 20 * 60_000L
        events.runUntil(deadlineMs)

        Invariants.checkGroupDelivery(group, item, deadlineMs, clock.now())
    }

    @Test
    fun `S6 the one old phone - an advertise-incapable member is invisible but fully functional`() {
        // Pass 12's real bug, permanently regression-tested: a node that cannot advertise never
        // gets connected TO, but can still initiate and receive as the scanning side.
        val result = ScenarioSupport.runCatalogSyncScenario(
            CatalogSyncScenarioConfig(nodeCount = 5, durationMs = 15 * 60_000L, injectedItems = 5),
            quirks = PlatformQuirks(advertiseIncapableFraction = 0.2), // ~1 of 5
        )
        val allItems = result.metrics.injections.keys
        assertTrue("content should still fully converge even with one advertise-incapable node",
            allItems.all { item -> result.nodes.all { item in it.catalogItems } })
    }

    @Test
    fun `S7 kettle - D=400 static crowd, bounded peer state and no radio-churn breaker trip`() {
        val quirks = PlatformQuirks(radioChurnInstabilityThreshold = 50, radioChurnWindowMs = 60_000L)
        val result = ScenarioSupport.runCatalogSyncScenario(
            CatalogSyncScenarioConfig(nodeCount = 400, durationMs = 30 * 60_000L, injectedItems = 20),
            quirks = quirks,
        )
        // I2: sampled nodes' tracked-peer-state count should reflect real neighbours seen, not an
        // unbounded address-churn artefact (quirks.addressRotationIntervalMs is off by default here).
        for (node in result.nodes.take(10)) Invariants.checkBoundedPeerState(node, maxExpected = 400)
        // I1, mechanised: this engine only touches the radio to attempt a real connection (never on
        // a bare timer), so under a generous churn threshold the breaker should never trip.
        for (node in result.nodes.take(10)) Invariants.checkNoUngovernedRadioChurn(node, quirks, result.metrics)
    }

    @Test
    fun `S8 stampede - SOS-equivalent time-to-all-group-members, the headline product claim`() {
        val groupSize = 3
        val clock = SimClock()
        val events = SimEventQueue(clock)
        val metrics = SimMetrics()
        val nodes = ScenarioSupport.buildNodes(groupSize, clock)
        val network = SimNetwork.fullMesh(nodes)
        val engine = CatalogSyncEngine(clock, events, network, PlatformQuirks(), metrics)
        engine.start()

        val sos = "sos:stampede"
        events.schedule(0L) {
            nodes[0].catalogItems += sos
            metrics.recordInjection(sos, clock.now())
            metrics.recordDelivery(sos, nodes[0].id, clock.now())
        }
        // v1's OWN measured worst case (PLAN-v2 §1.1) is ~45s/hop; a v1-baseline 3-phone stampede
        // (>=1 hop for the far member) delivering inside 5x that is still evidence of the pre-P1
        // ceiling this scenario exists to make visible, not evidence of speed.
        val deadlineMs = 225_000L
        events.runUntil(deadlineMs)

        Invariants.checkGroupDelivery(nodes, sos, deadlineMs, clock.now())
        val convergence = ScenarioSupport.fullConvergenceTimeMs(nodes, metrics)
        assertTrue("expected a measurable stampede delivery time", convergence != null && convergence > 0)
    }

    @Test
    fun `S9 hostile node - malformed frames cost a slot but never corrupt honest catalogues`() {
        // Injected only at honest nodes, deliberately: an item that originates AT a malicious node
        // is a different, uninteresting question (whether a hostile node bothers to relay its own
        // claimed content at all) — S9's actual point (Pass 23's fixes) is that HONEST content
        // still reaches every honest member despite hostile peers occupying connection slots.
        val clock = SimClock()
        val events = SimEventQueue(clock)
        val metrics = SimMetrics()
        val nodes = ScenarioSupport.buildNodes(6, clock, NodePopulationConfig(maliciousFraction = 0.33)) // ~2 of 6
        val honestNodes = nodes.filterNot { it.malicious }
        val network = SimNetwork.fullMesh(nodes)
        val engine = CatalogSyncEngine(clock, events, network, PlatformQuirks(), metrics)
        engine.start()
        val items = (0 until 6).map { "sos:honest-$it" }
        items.forEachIndexed { i, item ->
            events.schedule(i * 1_000L) {
                val origin = honestNodes[i % honestNodes.size]
                origin.catalogItems += item
                metrics.recordInjection(item, clock.now())
                metrics.recordDelivery(item, origin.id, clock.now())
            }
        }
        events.runUntil(15 * 60_000L)

        assertTrue("content should still converge among every honest node despite hostile peers",
            items.all { item -> honestNodes.all { item in it.catalogItems } })
    }

    @Test
    fun `S11 blind-relay load - a 3-person group is still served while carrying for 50 other groups`() {
        val clock = SimClock()
        val events = SimEventQueue(clock)
        val metrics = SimMetrics()
        val nodes = ScenarioSupport.buildNodes(count = 8, clock = clock)
        val group = nodes.take(3)
        val carrier = nodes[3]
        // Seed the carrier with ~50 other groups' worth of opaque (undecryptable) blind-relay
        // traffic — the "completely unmeasured today" load PLAN-v2 §6.3 flags for S11.
        repeat(50) { i ->
            val bytes = "stranger-group-item-$i".toByteArray()
            carrier.opaqueRelay.offer(
                dedupKey = OpaqueFrameRelay.dedupKey(bytes), hop = 0, maxHops = 8,
            ) { bytes }
        }
        val network = SimNetwork.fullMesh(nodes)
        val engine = CatalogSyncEngine(clock, events, network, PlatformQuirks(), metrics)
        engine.start()

        val ownItem = "sos:group-own"
        events.schedule(0L) {
            group[0].catalogItems += ownItem
            metrics.recordInjection(ownItem, clock.now())
            metrics.recordDelivery(ownItem, group[0].id, clock.now())
        }
        val deadlineMs = 10 * 60_000L
        events.runUntil(deadlineMs)

        // decision 13's fix (own catalogue always exchanged, blind relay a separate, capped-by-
        // budget path) should hold under this load: the group's own item still gets everywhere.
        Invariants.checkGroupDelivery(group, ownItem, deadlineMs, clock.now())
    }
}
