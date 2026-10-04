package org.offlinemesh.app.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier 1: covers [CourierHandoverTracker]'s rate-limit and LRU-eviction bookkeeping — a fake clock
 * makes the 10-minute window deterministic without a real wait, mirroring
 * [ConnectionAttemptTrackerTest]'s own style for the same reason.
 */
class CourierHandoverTrackerTest {
    private var clock = 0L
    private fun tracker(rateLimitMs: Long = 600_000L, maxTracked: Int = 500) =
        CourierHandoverTracker(rateLimitMs, maxTracked, now = { clock })

    @Test
    fun `a pair never attempted before can attempt`() {
        assertTrue(tracker().canAttempt("env-1", "peer-a"))
    }

    @Test
    fun `an attempt just recorded cannot be repeated immediately`() {
        val t = tracker()
        t.recordAttempt("env-1", "peer-a")
        assertFalse(t.canAttempt("env-1", "peer-a"))
    }

    @Test
    fun `an attempt becomes allowed again once the rate-limit window has fully elapsed`() {
        val t = tracker(rateLimitMs = 1000L)
        t.recordAttempt("env-1", "peer-a")
        clock = 999L
        assertFalse("just under the window must still be blocked", t.canAttempt("env-1", "peer-a"))
        clock = 1000L
        assertTrue("exactly at the window must be allowed", t.canAttempt("env-1", "peer-a"))
    }

    @Test
    fun `the rate limit is scoped to one specific (envelope, peer) pair, not global`() {
        val t = tracker()
        t.recordAttempt("env-1", "peer-a")
        assertTrue("a different peer, same envelope, is unaffected", t.canAttempt("env-1", "peer-b"))
        assertTrue("a different envelope, same peer, is unaffected", t.canAttempt("env-2", "peer-a"))
    }

    @Test
    fun `oldest tracked pair is evicted once maxTracked is exceeded`() {
        val t = tracker(maxTracked = 2)
        t.recordAttempt("env-1", "peer-a")
        t.recordAttempt("env-2", "peer-a")
        t.recordAttempt("env-3", "peer-a") // evicts env-1's entry (least recently touched)

        assertTrue("evicted entries must not still be tracked as recently attempted", t.canAttempt("env-1", "peer-a"))
        assertFalse(t.canAttempt("env-3", "peer-a"))
    }

    @Test
    fun `checking canAttempt on a pair protects it from eviction, mirroring ConnectionAttemptTracker`() {
        val t = tracker(maxTracked = 2)
        t.recordAttempt("env-1", "peer-a")
        t.recordAttempt("env-2", "peer-a")
        t.canAttempt("env-1", "peer-a") // touches env-1, making env-2 the least-recently-touched
        t.recordAttempt("env-3", "peer-a") // should evict env-2, not env-1

        assertFalse("env-1 was touched by canAttempt and must survive eviction", t.canAttempt("env-1", "peer-a"))
        assertTrue("env-2 should have been evicted instead", t.canAttempt("env-2", "peer-a"))
    }
}
