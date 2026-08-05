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
