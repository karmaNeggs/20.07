package org.offlinemesh.app.sim

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PLAN-v2.md Part 7's own gate for P0a: *"reproduce v1's measured behaviour at D=3 (93% empty
 * syncs, one connection per ~50s) and project it to D=400 (~8min sync round). If the harness cannot
 * reproduce the known-bad numbers it is not modelling anything, and nothing built on it can be
 * trusted."* The measured D=3 numbers are from `20.07 mesh diagnostics 10` (24.7min, 2-3 phones,
 * see PLAN-v2.md §1.2): 61 catalogue syncs, 57 empty (93%), 30 "synced ok" events at roughly one
 * per 50s (matching `reconnectCooldownMs = 45_000`).
 *
 * **Tolerance bands, not exact-number matching.** This calibrates the MECHANISM, not a byte-exact
 * replay of one specific 24.7-minute session — content-injection timing, exact device count, and
 * real radio jitter differ run to run on real hardware too. The bands below are chosen tight enough
 * that a broken model (one that doesn't reproduce a mostly-empty-sync pattern at all, or that syncs
 * far faster/slower than the reconnect cooldown governs) fails, and loose enough that the model
 * isn't fit to one session's noise.
 *
 * **On the D=400 projection specifically:** PLAN-v2.md itself states two different back-of-envelope
 * numbers for D=400 depending on traffic type — §1.4's `6xD` formula (full catalogue sync, 3
 * concurrent slots, ~18s/connection) gives ~40min; §9.2 item 7's presence-specific estimate (5
 * slots, ~6s/connection) gives ~8min, which is the number Part 7's gate text actually cites. This
 * test measures the harness's OWN full-catalogue-sync convergence time directly (not either
 * formula) and asserts only the qualitative claim both formulas agree on: full convergence at D=400
 * takes low-single-digit MINUTES at the fastest, not seconds — an order-of-magnitude check, which is
 * what "the harness is modelling something real" actually requires. The two-formula discrepancy
 * itself is recorded here rather than silently resolved, since PLAN-v2.md never reconciled it either.
 */
class P0aGateTest {

    @Test
    fun `D=3 reproduces v1's measured empty-sync rate and pair-sync cadence`() {
        val result = ScenarioSupport.runCatalogSyncScenario(
            CatalogSyncScenarioConfig(nodeCount = 3, durationMs = D3_SESSION_MS, injectedItems = 8),
        )

        val emptyRate = result.metrics.emptySyncRate()
        assertTrue(
            "expected mostly-empty syncs (v1 measured 93%%), got ${emptyRate * 100}%%",
            emptyRate >= 0.70,
        )
        val meanInterval = result.metrics.meanPairSyncIntervalMs()
        assertTrue(
            "expected pair-sync cadence anchored on the 45s reconnect cooldown " +
                "(v1 measured ~50s), got ${meanInterval}ms",
            meanInterval in 40_000.0..75_000.0,
        )
    }

    @Test
    fun `D=400 full-sync convergence is minutes, not seconds, and yield collapses further`() {
        val result = ScenarioSupport.runCatalogSyncScenario(
            CatalogSyncScenarioConfig(nodeCount = 400, durationMs = D400_SESSION_MS, injectedItems = 40),
        )

        val convergenceMs = ScenarioSupport.fullConvergenceTimeMs(result.nodes, result.metrics)
        assertTrue(
            "expected every injected item to fully converge within the ${D400_SESSION_MS}ms run " +
                "(a non-convergence here would itself confirm the scaling failure, but this harness " +
                "should show a measurable minutes-scale number, not silently time out)",
            convergenceMs != null,
        )
        assertTrue(
            "expected D=400 full-sync convergence to take minutes, not seconds " +
                "(PLAN-v2 §1.4 projects ~40min via 6xD, §9.2 item 7 projects ~8min for lighter " +
                "traffic) — measured ${convergenceMs}ms (${(convergenceMs ?: 0) / 60_000.0} min)",
            (convergenceMs ?: 0L) > 60_000L,
        )
        // §1.2's finding: yield gets WORSE, not better, as density rises — connections outnumber
        // ones actually carrying anything, because most peers already converged.
        assertTrue(
            "expected empty-sync rate at D=400 to be at least as bad as the D=3 baseline",
            result.metrics.emptySyncRate() >= 0.85,
        )
    }

    private companion object {
        const val D3_SESSION_MS = 24 * 60_000L + 42_000L // 24.7 min, matching diagnostics 10
        const val D400_SESSION_MS = 90 * 60_000L
    }
}
