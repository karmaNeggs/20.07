package org.offlinemesh.app.sim

import kotlin.random.Random

/** Result of a completed scenario run: the recorded metrics alongside the node list, so a caller's
 *  own assertions and [Invariants] checks can inspect both. */
data class ScenarioResult(
    val metrics: SimMetrics,
    val nodes: List<SimNode>,
    val network: SimNetwork,
    val clock: SimClock,
)

/** Node-population knobs for [ScenarioSupport.buildNodes], bundled so that function's parameter
 *  list stays short. */
data class NodePopulationConfig(
    val peerKeyMode: PeerKeyMode = PeerKeyMode.ROTATING_ADDRESS,
    val maxConcurrentConnections: Int = 3,
    val reconnectCooldownMs: Long = 45_000L,
    val advertiseIncapableFraction: Double = 0.0,
    val maliciousFraction: Double = 0.0,
    val idPrefix: String = "node",
)

/** What/how much content to inject for [ScenarioSupport.scheduleInjections], bundled for the same
 *  reason as [NodePopulationConfig]. */
data class InjectionPlan(
    val count: Int,
    val withinMs: Long,
    val atNode: SimNode? = null,
    val itemPrefix: String = "sos:sim",
)

/** What one [ScenarioSupport.runCatalogSyncScenario] call should build and run, bundled for the
 *  same reason as [NodePopulationConfig]. */
data class CatalogSyncScenarioConfig(
    val nodeCount: Int,
    val durationMs: Long,
    val injectedItems: Int,
    val peerKeyMode: PeerKeyMode = PeerKeyMode.ROTATING_ADDRESS,
    val blindRelayBudgetPerConnection: Int? = null,
)

/** Shared scenario-construction helpers so P0a's gate test and the named §6.3 scenario tests build
 *  networks/engines the same way rather than duplicating setup. */
object ScenarioSupport {

    fun buildNodes(
        count: Int,
        clock: SimClock,
        config: NodePopulationConfig = NodePopulationConfig(),
        random: Random = Random(SimNetwork.SEED),
    ): List<SimNode> = (0 until count).map { i ->
        SimNode(
            id = "${config.idPrefix}-$i",
            clock = clock,
            peerKeyMode = config.peerKeyMode,
            config = SimNodeConfig(
                maxConcurrentConnections = config.maxConcurrentConnections,
                reconnectCooldownMs = config.reconnectCooldownMs,
                canAdvertise = random.nextDouble() >= config.advertiseIncapableFraction,
                malicious = random.nextDouble() < config.maliciousFraction,
            ),
        )
    }

    /** Injects [InjectionPlan.count] content items at uniformly random nodes (or a fixed
     *  [InjectionPlan.atNode]) and times spread across the first half of [InjectionPlan.withinMs] —
     *  realistic "new SOS/evidence/nickname shows up occasionally" volume, not a steady flood (a
     *  20.07 group's real catalogue is tens of items, not thousands — see
     *  [org.offlinemesh.app.ble.CatalogFilter]'s own class doc). */
    fun scheduleInjections(
        nodes: List<SimNode>,
        events: SimEventQueue,
        metrics: SimMetrics,
        clock: SimClock,
        plan: InjectionPlan,
    ) {
        val random = Random(SimNetwork.SEED + 1)
        repeat(plan.count) { i ->
            val atMs = if (plan.count == 1) 0L else random.nextLong(0, (plan.withinMs / 2).coerceAtLeast(1))
            events.schedule(atMs) {
                val node = plan.atNode ?: nodes[random.nextInt(nodes.size)]
                val item = "${plan.itemPrefix}-$i"
                node.catalogItems += item
                metrics.recordInjection(item, clock.now())
                metrics.recordDelivery(item, node.id, clock.now())
            }
        }
    }

    /** Runs the v1-baseline [CatalogSyncEngine] over [network] (default: full mesh) for
     *  [CatalogSyncScenarioConfig.durationMs] of simulated time, injecting
     *  [CatalogSyncScenarioConfig.injectedItems] pieces of content, and returns the recorded run
     *  for the caller's own assertions/invariant checks. */
    fun runCatalogSyncScenario(
        config: CatalogSyncScenarioConfig,
        quirks: PlatformQuirks = PlatformQuirks(),
        network: ((List<SimNode>, Random) -> SimNetwork)? = null,
    ): ScenarioResult {
        val clock = SimClock()
        val events = SimEventQueue(clock)
        val metrics = SimMetrics()
        val nodes = buildNodes(
            config.nodeCount, clock,
            NodePopulationConfig(
                peerKeyMode = config.peerKeyMode,
                advertiseIncapableFraction = quirks.advertiseIncapableFraction,
                maliciousFraction = quirks.maliciousFraction,
            ),
        )
        val net = network?.invoke(nodes, Random(SimNetwork.SEED)) ?: SimNetwork.fullMesh(nodes)
        scheduleInjections(nodes, events, metrics, clock, InjectionPlan(config.injectedItems, config.durationMs))
        val timing = CatalogSyncTiming(blindRelayBudgetPerConnection = config.blindRelayBudgetPerConnection)
        val engine = CatalogSyncEngine(clock, events, net, quirks, metrics, timing)
        engine.start()
        events.runUntil(config.durationMs)
        return ScenarioResult(metrics, nodes, net, clock)
    }

    /** Max, over every injected item, of (time every node in the run held it) - (injection time) —
     *  null if at least one item never fully converged within the run. This is PLAN-v2.md's
     *  "full-sync round time" measured directly from the harness rather than the back-of-envelope
     *  formulas in §1.4/§9.2. */
    fun fullConvergenceTimeMs(nodes: List<SimNode>, metrics: SimMetrics): Long? {
        if (metrics.injections.isEmpty()) return null
        var worst = 0L
        for ((item, injectedAt) in metrics.injections) {
            val deliveredAt = metrics.fullDeliveryMs(item, nodes.map { it.id }) ?: return null
            worst = maxOf(worst, deliveredAt - injectedAt)
        }
        return worst
    }
}
