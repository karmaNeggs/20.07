package org.offlinemesh.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupExpiryTest {

    @Test
    fun `already-expired or zero remaining time reports expired`() {
        assertEquals("expired", formatTimeRemaining(0))
        assertEquals("expired", formatTimeRemaining(-1))
    }

    @Test
    fun `under an hour remaining is reported distinctly from zero hours`() {
        assertEquals("<1h", formatTimeRemaining(30 * 60 * 1000L)) // 30 minutes
    }

    @Test
    fun `whole hours under 48 are reported in hours`() {
        assertEquals("41h", formatTimeRemaining(41L * 60 * 60 * 1000))
    }

    @Test
    fun `48 hours or more is reported in whole days`() {
        assertEquals("2d", formatTimeRemaining(48L * 60 * 60 * 1000))
        assertEquals("6d", formatTimeRemaining(150L * 60 * 60 * 1000)) // 150h = 6.25d, truncates to 6d
    }

    @Test
    fun `lifetime options are ordered ascending and the default index points at 48 hours`() {
        val millis = GROUP_LIFETIME_OPTIONS.map { it.millis }
        assertEquals(millis.sorted(), millis)
        assertEquals(48L * 60 * 60 * 1000, GROUP_LIFETIME_OPTIONS[DEFAULT_GROUP_LIFETIME_INDEX].millis)
    }
}
