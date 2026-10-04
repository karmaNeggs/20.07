package org.offlinemesh.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tier 1 (Robolectric-backed — the one thing here that needs a real android.location.Location
 * implementation rather than a stub): known-coordinate-pair tests for the radar's bearing/distance/
 * forward-up-rotation math, and the rough-GPS-fix rejection boundary. This is "checking relative
 * math of coordinate drops," pinned to exact expected numbers instead of eyeballing a live radar.
 */
@RunWith(RobolectricTestRunner::class)
class RadarMathTest {

    @Test
    fun `a peer due north with heading 0 (facing north) appears straight ahead`() {
        val p = placePeerOnRadar(meLat = 0.0, meLon = 0.0, meAccuracyM = 5f, peerLat = 0.001, peerLon = 0.0, peerAccuracyM = 5, headingDegrees = 0f)
        checkNotNull(p)
        assertEquals(0f, p.screenAngleDegrees, 2f)
        assertTrue("expected ~111m, got ${p.distanceMeters}", p.distanceMeters in 100f..120f)
    }

    @Test
    fun `a peer due east with heading 0 appears to the right at 90 degrees`() {
        val p = placePeerOnRadar(0.0, 0.0, 5f, 0.0, 0.001, 5, headingDegrees = 0f)
        checkNotNull(p)
        assertEquals(90f, p.screenAngleDegrees, 2f)
    }

    @Test
    fun `facing east rotates a peer due north to appear to the left at 270 degrees`() {
        // "Forward-up": rotating by our own heading means a peer at true-north bearing, while we
        // face east (heading 90), should appear at screen angle 270 (i.e. behind-left), not 0.
        val p = placePeerOnRadar(0.0, 0.0, 5f, 0.001, 0.0, 5, headingDegrees = 90f)
        checkNotNull(p)
        assertEquals(270f, p.screenAngleDegrees, 2f)
    }

    @Test
    fun `screen angle is always normalized into a clean 0 to 360 range`() {
        val p = placePeerOnRadar(0.0, 0.0, 5f, 0.001, 0.0, 5, headingDegrees = 350f)
        checkNotNull(p)
        assertTrue(p.screenAngleDegrees >= 0f && p.screenAngleDegrees < 360f)
    }

    @Test
    fun `a peer at the same coordinates has near-zero distance`() {
        val p = placePeerOnRadar(12.34, 56.78, 5f, 12.34, 56.78, 5, headingDegrees = 0f)
        checkNotNull(p)
        assertEquals(0f, p.distanceMeters, 1f)
    }

    @Test
    fun `combined GPS uncertainty at the rough-fix threshold is rejected`() {
        val p = placePeerOnRadar(
            0.0, 0.0, meAccuracyM = 150f, peerLat = 0.001, peerLon = 0.0, peerAccuracyM = 100, headingDegrees = 0f
        )
        assertNull(p) // 150 + 100 = ROUGH_FIX_METERS exactly — >= is a reject, not a boundary pass
    }

    @Test
    fun `combined GPS uncertainty just under the threshold is accepted`() {
        val p = placePeerOnRadar(
            0.0, 0.0, meAccuracyM = 150f, peerLat = 0.001, peerLon = 0.0, peerAccuracyM = 99, headingDegrees = 0f
        )
        assertNotNull(p)
    }

    @Test
    fun `a very rough network-location-grade fix is rejected even at zero distance`() {
        // The exact "distances that don't make sense" case: a rough indoor/network fix shouldn't be
        // plotted as a confident dot just because the two points happen to be close together.
        val p = placePeerOnRadar(
            0.0, 0.0, meAccuracyM = 300f, peerLat = 0.0, peerLon = 0.0, peerAccuracyM = 300, headingDegrees = 0f
        )
        assertNull(p)
    }
}
