package org.offlinemesh.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier 1: [PositionTracker]'s staleness/pruning, with a fake clock. Live-tested finding: a flat
 * 90s window had zero margin for a relayed position's worst-case propagation delay (two
 * independent ~45s GATT reconnect cycles for a 2-hop relay) — see
 * [PositionTracker.effectiveMaxAgeSeconds]'s doc, same reasoning as [HopTracker]'s identical fix.
 */
class PositionTrackerTest {
    private var clock = 0L
    private fun tracker() = PositionTracker(now = { clock * 1000 })

    private fun offer(t: PositionTracker, senderId: String, hop: Int, atSec: Long = clock) {
        t.offer("group-1", senderId, lat = 1.0, lon = 2.0, accuracyM = 5, timestampSec = atSec, hop = hop)
    }

    @Test
    fun `effectiveMaxAgeSeconds gives hop 0 exactly the base window, no extra slack`() {
        assertEquals(90L, PositionTracker.effectiveMaxAgeSeconds(90L, hop = 0))
    }

    @Test
    fun `effectiveMaxAgeSeconds adds one reconnect-cooldown's worth of slack per relay hop`() {
        assertEquals(135L, PositionTracker.effectiveMaxAgeSeconds(90L, hop = 1))
        assertEquals(180L, PositionTracker.effectiveMaxAgeSeconds(90L, hop = 2))
    }

    @Test
    fun `a direct fix (hop 0) goes stale at exactly the base window`() {
        // 180s, widened from 90s after live measurement — see PositionTracker.maxAgeSeconds' note.
        val t = tracker()
        offer(t, "sender-1", hop = 0)
        clock += 181
        assertTrue(t.forGroup("group-1").isEmpty())
    }

    @Test
    fun `a once-relayed position (hop 1) survives past the old flat 90s window`() {
        // The exact scenario this fix targets: a position relayed through one middle phone can
        // legitimately take close to 90s+45s to arrive, and must not read as stale the moment it
        // does — this is what "the farthest phone's dot never appears" looked like in the field.
        val t = tracker()
        offer(t, "sender-1", hop = 1)
        clock += 181 // would have pruned a hop-0 fix, must not prune a hop-1 one
        assertTrue(t.forGroup("group-1").containsKey("sender-1"))
    }

    @Test
    fun `a once-relayed position still eventually goes stale`() {
        val t = tracker()
        offer(t, "sender-1", hop = 1)
        clock += 226 // 180s base + 45s (one hop's worth of slack) + 1s
        assertFalse(t.forGroup("group-1").containsKey("sender-1"))
    }

    @Test
    fun `a fresher hop-0 update for the same sender replaces an older stale-tolerant hop-1 record`() {
        val t = tracker()
        offer(t, "sender-1", hop = 1, atSec = 0)
        clock = 10
        offer(t, "sender-1", hop = 0, atSec = 10) // e.g. sender came into direct range
        val record = t.forGroup("group-1")["sender-1"]
        checkNotNull(record)
        assertEquals(0, record.hop)
    }

    // ---------- positionEpoch (the missing "something new to relay" fast path) ----------

    @Test
    fun `positionEpoch advances when a genuinely new position is accepted`() {
        val t = tracker()
        val before = t.positionEpoch
        offer(t, "sender-1", hop = 0)
        assertTrue(t.positionEpoch > before)
    }

    @Test
    fun `positionEpoch does not advance for a stale or duplicate offer`() {
        val t = tracker()
        offer(t, "sender-1", hop = 0, atSec = 10)
        val after = t.positionEpoch
        offer(t, "sender-1", hop = 0, atSec = 5) // older timestamp, dropped
        offer(t, "sender-1", hop = 0, atSec = 10) // same timestamp, not strictly newer, dropped
        assertEquals(after, t.positionEpoch)
    }

    @Test
    fun `at an equal timestamp the shorter path wins, whichever arrived first`() {
        // The same fix can race over two routes. Taking whichever landed first pinned a worse hop —
        // inflating displayed distance, stretching the staleness window, and pointing split horizon
        // at the wrong peer. Worse, relaying it onward at hop+1 could push it past the ceiling so
        // devices further out never learned the position at all.
        val t = tracker()
        t.offer("group-1", "sender-1", 1.0, 2.0, 5, timestampSec = 100, hop = 2, viaPeer = "far")
        t.offer("group-1", "sender-1", 1.0, 2.0, 5, timestampSec = 100, hop = 1, viaPeer = "near")
        val record = t.forGroup("group-1")["sender-1"]
        checkNotNull(record)
        assertEquals(1, record.hop)
        assertEquals("near", record.viaPeer)
    }

    @Test
    fun `an equal-timestamp copy over a LONGER path is ignored`() {
        val t = tracker()
        t.offer("group-1", "sender-1", 1.0, 2.0, 5, timestampSec = 100, hop = 1, viaPeer = "near")
        t.offer("group-1", "sender-1", 1.0, 2.0, 5, timestampSec = 100, hop = 3, viaPeer = "far")
        assertEquals(1, t.forGroup("group-1")["sender-1"]!!.hop)
    }
}
