package org.offlinemesh.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier 1: [BeaconRadio.roundRobinDwellMs] only — the pure decision behind how long one group holds
 * the shared advertiser. Worth its own test despite being three tokens of logic: both neighboring
 * values for this cadence are known to break real hardware (see `docs/DECISIONS.md`) — rotating
 * every ~700-900ms caused total symmetric discovery failure, every ~3s destabilizes the stack under
 * multi-group churn, and a fixed 60s dwell starved the not-currently-advertised group of all
 * airtime and broke same-room discovery outright. The adaptive rule is what keeps it off both horns.
 */
class BeaconRadioDwellTest {

    @Test
    fun `while blind, rotate on every check so discovery is never starved`() {
        // 0 means "the dwell has always already elapsed" — i.e. advance every tick, which is the
        // original behavior that is known to discover reliably.
        assertEquals(0L, BeaconRadio.roundRobinDwellMs(blind = true))
    }

    @Test
    fun `once presence is established, dwell instead of restarting the radio every check`() {
        val dwell = BeaconRadio.roundRobinDwellMs(blind = false)
        // Must be meaningfully longer than the advertise check interval (2-4s, see BleTuning) or it
        // would not actually reduce restarts at all — that's the churn this exists to cut.
        assertTrue("dwell must exceed the check interval to reduce restarts", dwell > 4_000L)
        // And must stay well under the 60s that was tried and reverted for starving the other group.
        assertTrue("dwell must stay far below the reverted 60s", dwell < 30_000L)
    }
}

/**
 * Tier 1: [BeaconRadio.broadcastTierReportDelayMs] — the pure decision behind Tier B's degree-gated
 * scan-report batching (PLAN-v2.md §9.2 item 1, decision 26). Own class rather than folded into
 * [BeaconRadioDwellTest] above since it's a distinct lever with its own floor constant, matching
 * this file's existing one-pure-function-per-class-doc pattern.
 */
class BeaconRadioBroadcastTierBatchingTest {

    @Test
    fun `at or below the degree floor, no batching - 3-phone discovery stays immediate`() {
        assertEquals(0L, BeaconRadio.broadcastTierReportDelayMs(degree = 0))
        val justBelowFloor = BeaconRadio.BROADCAST_TIER_DEGREE_BATCHING_FLOOR - 1
        assertEquals(0L, BeaconRadio.broadcastTierReportDelayMs(degree = justBelowFloor))
    }

    @Test
    fun `at or above the degree floor, batching engages`() {
        val delay = BeaconRadio.broadcastTierReportDelayMs(degree = BeaconRadio.BROADCAST_TIER_DEGREE_BATCHING_FLOOR)
        assertTrue("expected a positive report delay once the floor is reached, got $delay", delay > 0L)
        // Plan states this lever specifically as "1-2s".
        assertTrue("report delay should stay within the plan's stated 1-2s range", delay in 1_000L..2_000L)
    }

    @Test
    fun `well above the floor, still the same delay - not a second, unbounded scaling knob`() {
        assertEquals(
            BeaconRadio.broadcastTierReportDelayMs(degree = BeaconRadio.BROADCAST_TIER_DEGREE_BATCHING_FLOOR),
            BeaconRadio.broadcastTierReportDelayMs(degree = 400),
        )
    }
}
