package org.offlinemesh.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tier 1: exhaustive coverage of [CourierHandover.split]'s arithmetic — P4 slice 4
 * (`docs/DECISIONS.md` decision 44) calls this out explicitly as "worth a dedicated unit test on
 * the split arithmetic alone... given how easy an off-by-one here is to get wrong silently."
 */
class CourierHandoverTest {

    @Test
    fun `4 splits into keep 2, give 2`() {
        assertEquals(2 to 2, CourierHandover.split(4))
    }

    @Test
    fun `an odd count gives the smaller half away, keeps the larger`() {
        // floor(5/2) = 2 given away, ceil(5/2) = 3 kept — the courier handing out keeps the edge.
        assertEquals(3 to 2, CourierHandover.split(5))
    }

    @Test
    fun `3 splits into keep 2, give 1`() {
        assertEquals(2 to 1, CourierHandover.split(3))
    }

    @Test
    fun `2 splits into keep 1, give 1, the smallest real split`() {
        assertEquals(1 to 1, CourierHandover.split(2))
    }

    @Test
    fun `1 has nothing to hand over`() {
        assertNull(CourierHandover.split(1))
    }

    @Test
    fun `0 has nothing to hand over`() {
        assertNull(CourierHandover.split(0))
    }

    @Test
    fun `a negative count is treated the same as having nothing to hand over`() {
        // Defensive — copiesRemaining should never actually go negative, but split() must not
        // silently misbehave (e.g. produce a negative give) if it somehow did.
        assertNull(CourierHandover.split(-1))
    }

    @Test
    fun `keep plus give always equals the original count — no copies are minted or lost`() {
        for (n in CourierHandover.MIN_COPIES_TO_SPLIT..20) {
            val (keep, give) = requireNotNull(CourierHandover.split(n)) { "split($n) unexpectedly returned null" }
            assertEquals("split($n) must conserve the total", n, keep + give)
        }
    }

    @Test
    fun `every split below MIN_COPIES_TO_SPLIT returns null`() {
        for (n in 0 until CourierHandover.MIN_COPIES_TO_SPLIT) {
            assertNull("split($n) should have nothing to hand over", CourierHandover.split(n))
        }
    }
}
