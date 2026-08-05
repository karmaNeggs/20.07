package org.offlinemesh.app.sim

import org.junit.Assert.assertThrows
import org.junit.Test

/** Direct unit coverage of [Invariants]' own logic — both the accept and reject path for each of
 *  PLAN-v2.md §6.2's eight invariants, independent of any scenario run, matching this codebase's
 *  usual convention of testing extracted decision classes directly (see e.g. ConnectionAttemptTrackerTest). */
class InvariantsTest {

    @Test
    fun `I1 passes under threshold and fails when radio touched too often in the window`() {
        val clock = SimClock()
        val node = SimNode("n", clock, PeerKeyMode.STABLE_PUBKEY)
        val quirks = PlatformQuirks(radioChurnInstabilityThreshold = 2, radioChurnWindowMs = 10_000L)
        val metrics = SimMetrics()
        metrics.recordRadioTouch("n", 0L)
        metrics.recordRadioTouch("n", 1_000L)
        Invariants.checkNoUngovernedRadioChurn(node, quirks, metrics) // 2 touches, threshold 2: OK

        metrics.recordRadioTouch("n", 2_000L) // now 3 within the 10s window
        assertThrows(IllegalStateException::class.java) {
            Invariants.checkNoUngovernedRadioChurn(node, quirks, metrics)
        }
    }

    @Test
    fun `I2 passes within bound and fails once tracked peer state exceeds it`() {
        val clock = SimClock()
        val node = SimNode("n", clock, PeerKeyMode.ROTATING_ADDRESS)
        repeat(3) { i -> node.connectionAttemptTracker.connectionEnded("addr-$i") }
        Invariants.checkBoundedPeerState(node, maxExpected = 3)
        assertThrows(IllegalStateException::class.java) {
            Invariants.checkBoundedPeerState(node, maxExpected = 2)
        }
    }

    @Test
    fun `I3 passes when a half-open connection recovers in time and fails if it never recovers`() {
        val metrics = SimMetrics()
        metrics.recordHalfOpen("n", atMs = 0L)
        metrics.recordHalfOpenRecovered("n", atMs = 20_000L)
        Invariants.checkNoSlotLeak(metrics, connectionMaxMs = 20_000L)

        val leaked = SimMetrics()
        leaked.recordHalfOpen("n", atMs = 0L)
        assertThrows(IllegalStateException::class.java) {
            Invariants.checkNoSlotLeak(leaked, connectionMaxMs = 20_000L)
        }
    }

    @Test
    fun `I4 low-degree case must equal the identity result, high-degree is unconstrained`() {
        Invariants.checkLowDegreeIsIdentity(degree = 3, floor = 4, actual = "all-links", identityResult = "all-links")
        Invariants.checkLowDegreeIsIdentity(degree = 10, floor = 4, actual = "subset", identityResult = "all-links")
        assertThrows(IllegalStateException::class.java) {
            Invariants.checkLowDegreeIsIdentity(degree = 2, floor = 4, actual = "subset", identityResult = "all-links")
        }
    }

    @Test
    fun `I5 passes when a recent radio touch exists and fails after prolonged silence`() {
        val metrics = SimMetrics()
        val clock = SimClock()
        val node = SimNode("n", clock, PeerKeyMode.STABLE_PUBKEY)
        metrics.recordRadioTouch("n", 5_000L)
        Invariants.checkFailOpen(node, metrics, sinceMs = 0L, nowMs = 10_000L, maxSilenceMs = 10_000L)
        assertThrows(IllegalStateException::class.java) {
            Invariants.checkFailOpen(node, metrics, sinceMs = 0L, nowMs = 100_000L, maxSilenceMs = 10_000L)
        }
    }

    @Test
    fun `I6 passes above the yield floor and fails below it`() {
        val metrics = SimMetrics()
        repeat(3) { metrics.recordSync("a", "b", pushed = 1, atMs = it * 1000L) }
        metrics.recordSync("a", "b", pushed = 0, atMs = 4000L)
        Invariants.checkYieldFloor(metrics, minYieldFraction = 0.5) // 3/4 = 75%
        assertThrows(IllegalStateException::class.java) {
            Invariants.checkYieldFloor(metrics, minYieldFraction = 0.9)
        }
    }

    @Test
    fun `I7 passes once every member holds the item by the deadline and fails if one is missing`() {
        val clock = SimClock()
        val group = listOf(
            SimNode("a", clock, PeerKeyMode.STABLE_PUBKEY),
            SimNode("b", clock, PeerKeyMode.STABLE_PUBKEY),
        )
        group.forEach { it.catalogItems += "sos:x" }
        Invariants.checkGroupDelivery(group, "sos:x", deadlineMs = 1000L, nowMs = 1000L)

        group[1].catalogItems.clear()
        assertThrows(IllegalStateException::class.java) {
            Invariants.checkGroupDelivery(group, "sos:x", deadlineMs = 1000L, nowMs = 1000L)
        }
    }

    @Test
    fun `I8 passes within the growth factor and fails when per-node work grows too much with swarm size`() {
        Invariants.checkBoundedGroupWork(
            smallSwarmTouchRate = 1.0, largeSwarmTouchRate = 1.5, maxGrowthFactor = 2.0,
        )
        assertThrows(IllegalStateException::class.java) {
            Invariants.checkBoundedGroupWork(
                smallSwarmTouchRate = 1.0, largeSwarmTouchRate = 5.0, maxGrowthFactor = 2.0,
            )
        }
    }
}
