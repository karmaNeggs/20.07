package org.offlinemesh.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [BroadcastSosPreview] deliberately has no staleness clock of its own — freshness is entirely
 * delegated to the caller passing in `HopTracker.bestActiveSos`'s current id (see the class doc for
 * why). These tests cover that contract plus the decision-30 group-teardown wiring
 * ([BroadcastSosPreview.clearForGroup]/[BroadcastSosPreview.pruneOrphaned], same shape as
 * [PositionTrackerTest]'s equivalent cases).
 */
class BroadcastSosPreviewTest {
    @Test
    fun `returns the cached preview when its id matches the caller's current best id`() {
        val t = BroadcastSosPreview()
        t.offer("group-1", "sos-1", "help", 100L)
        val content = t.forGroupIfBest("group-1", currentBestSosId = "sos-1")
        assertEquals("sos-1", content?.sosId)
        assertEquals("help", content?.message)
    }

    @Test
    fun `returns null when the caller's current best id no longer matches the cached one`() {
        val t = BroadcastSosPreview()
        t.offer("group-1", "sos-1", "help", 100L)
        // e.g. sos-1 went stale in HopTracker and a different SOS (or none) is now nearest.
        assertNull(t.forGroupIfBest("group-1", currentBestSosId = "sos-2"))
        assertNull(t.forGroupIfBest("group-1", currentBestSosId = null))
    }

    @Test
    fun `returns null for a group with nothing cached`() {
        val t = BroadcastSosPreview()
        assertNull(t.forGroupIfBest("group-1", currentBestSosId = "sos-1"))
    }

    @Test
    fun `a repeat offer for the same id overwrites, and a newer id replaces the cached one`() {
        val t = BroadcastSosPreview()
        t.offer("group-1", "sos-1", "help", 100L)
        t.offer("group-1", "sos-2", "help 2", 200L)
        assertNull(t.forGroupIfBest("group-1", currentBestSosId = "sos-1"))
        assertEquals("help 2", t.forGroupIfBest("group-1", currentBestSosId = "sos-2")?.message)
    }

    @Test
    fun `clearForGroup removes only that group's preview, leaving others untouched`() {
        val t = BroadcastSosPreview()
        t.offer("group-1", "sos-1", "help", 100L)
        t.offer("group-2", "sos-2", "help 2", 100L)
        t.clearForGroup("group-1")
        assertNull(t.forGroupIfBest("group-1", "sos-1"))
        assertEquals("help 2", t.forGroupIfBest("group-2", "sos-2")?.message)
    }

    @Test
    fun `pruneOrphaned removes previews for groups not in the active set`() {
        val t = BroadcastSosPreview()
        t.offer("group-1", "sos-1", "help", 100L)
        t.offer("group-2", "sos-2", "help 2", 100L)
        t.pruneOrphaned(activeGroupIds = setOf("group-2"))
        assertNull(t.forGroupIfBest("group-1", "sos-1"))
        assertEquals("help 2", t.forGroupIfBest("group-2", "sos-2")?.message)
    }
}
