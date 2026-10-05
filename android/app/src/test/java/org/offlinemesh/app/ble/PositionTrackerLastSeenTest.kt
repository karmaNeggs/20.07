package org.offlinemesh.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionTrackerLastSeenTest {
    private var nowMs = 1_800_000_000_000L
    private val tracker = PositionTracker { nowMs }
    private val nowSec get() = nowMs / 1000

    private fun live(sender: String = "m1", ts: Long = nowSec, lat: Double = 10.0) =
        tracker.offer("g", sender, lat, 20.0, 5, ts, 0)

    @Test fun `a live position is not listed as last seen while it is still live`() {
        live()
        assertTrue(tracker.lastSeenForGroup("g").isEmpty())
        assertEquals(setOf("m1"), tracker.forGroup("g").keys)
    }

    @Test fun `once a live position goes stale it is still available as last seen`() {
        live()
        nowMs += 10 * 60_000L
        assertTrue(tracker.forGroup("g").isEmpty())
        val seen = tracker.lastSeenForGroup("g")
        assertEquals(setOf("m1"), seen.keys)
        assertEquals(10.0, seen.getValue("m1").lat, 0.0)
    }

    @Test fun `a position delivered late as last known never becomes a live dot`() {
        tracker.offerLastSeen("g", "m2", 1.0, 2.0, 5, nowSec - 1800, 1, viaPeer = RelayResponder.INTERNET_PEER)
        assertTrue(tracker.forGroup("g").isEmpty())
        val rec = tracker.lastSeenForGroup("g").getValue("m2")
        assertEquals(RelayResponder.INTERNET_PEER, rec.viaPeer)
        assertEquals(nowSec - 1800, rec.timestampSec)
    }

    @Test fun `the newest position wins and an older late arrival does not overwrite it`() {
        tracker.offerLastSeen("g", "m2", 1.0, 2.0, 5, nowSec - 600, 0)
        tracker.offerLastSeen("g", "m2", 9.0, 9.0, 5, nowSec - 3000, 0)
        assertEquals(1.0, tracker.lastSeenForGroup("g").getValue("m2").lat, 0.0)
        tracker.offerLastSeen("g", "m2", 7.0, 7.0, 5, nowSec - 100, 0)
        assertEquals(7.0, tracker.lastSeenForGroup("g").getValue("m2").lat, 0.0)
    }

    @Test fun `last seen expires after six hours`() {
        tracker.offerLastSeen("g", "m2", 1.0, 2.0, 5, nowSec, 0)
        nowMs += 5 * 3600 * 1000L
        assertTrue(tracker.lastSeenForGroup("g").isNotEmpty())
        nowMs += 2 * 3600 * 1000L
        assertTrue(tracker.lastSeenForGroup("g").isEmpty())
    }

    @Test fun `groups are separate and dismantling a group clears its last seen`() {
        tracker.offerLastSeen("g", "m1", 1.0, 2.0, 5, nowSec, 0)
        tracker.offerLastSeen("h", "m1", 3.0, 4.0, 5, nowSec, 0)
        assertEquals(1.0, tracker.lastSeenForGroup("g").getValue("m1").lat, 0.0)
        assertEquals(3.0, tracker.lastSeenForGroup("h").getValue("m1").lat, 0.0)
        tracker.clearForGroup("g")
        assertTrue(tracker.lastSeenForGroup("g").isEmpty())
        assertFalse(tracker.lastSeenForGroup("h").isEmpty())
    }

    @Test fun `orphaned groups are swept from last seen too`() {
        tracker.offerLastSeen("gone", "m1", 1.0, 2.0, 5, nowSec, 0)
        tracker.pruneOrphaned(setOf("g"))
        assertTrue(tracker.lastSeenForGroup("gone").isEmpty())
    }
}
