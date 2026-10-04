package org.offlinemesh.app.ble

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tier 1: exhaustive truth table over [CourierPool.decide] — the pure admission policy behind P4
 * slice 3's "bounded pool with tiers" (`docs/DECISIONS.md` decision 43, `PLAN-v2.md` §4.2). Plain
 * JVM test, no Room/Robolectric — the actual DAO wiring lives in [RelayEngine.admitCourierEnvelope]
 * and is covered separately in `RelayEngineTest`.
 */
class CourierPoolTest {

    @Test
    fun `own-group accepts under capacity`() {
        assertEquals(CourierPool.Admission.ACCEPT, CourierPool.decide(ownCount = 5, blindCount = 5, isOwnGroup = true))
    }

    @Test
    fun `blind-carry accepts under its reserved sub-capacity`() {
        assertEquals(CourierPool.Admission.ACCEPT, CourierPool.decide(ownCount = 0, blindCount = 5, isOwnGroup = false))
    }

    @Test
    fun `own-group at full pool evicts oldest blind carry first, never rejects`() {
        // 40 total, some own + some blind — an own-group insert must never be hard-rejected while
        // ANY blind-carry row exists to evict instead.
        val decision = CourierPool.decide(ownCount = 25, blindCount = 15, isOwnGroup = true)
        assertEquals(CourierPool.Admission.EVICT_OLDEST_BLIND, decision)
    }

    @Test
    fun `own-group at a pool that is entirely own-group evicts its own oldest sibling, never rejects`() {
        // The one degenerate case where an own-group insert evicts another own-group row — no
        // blind-carry row left to evict instead, and own-group is never hard-rejected either way.
        val decision = CourierPool.decide(ownCount = 40, blindCount = 0, isOwnGroup = true)
        assertEquals(CourierPool.Admission.EVICT_OLDEST_OWN, decision)
    }

    @Test
    fun `blind-carry at its sub-capacity LRU-evicts its own oldest, never rejects, even with zero own-group rows`() {
        // The key reservation-is-a-hard-cap property: blind-carry capacity is CAPACITY -
        // OWN_GROUP_RESERVED (20), not CAPACITY - ownCount — it stays capped at 20 even when
        // ownCount == 0, proving the 20 own-group slots are genuinely guaranteed, not just softly
        // prioritized against whatever blind traffic happens to arrive first.
        val decision = CourierPool.decide(
            ownCount = 0, blindCount = CourierPool.OWN_GROUP_RESERVED, isOwnGroup = false,
        )
        assertEquals(CourierPool.Admission.EVICT_OLDEST_BLIND, decision)
    }

    @Test
    fun `blind-carry never grows past its reserved sub-capacity even when own-group slots sit unused`() {
        val atCap = CourierPool.CAPACITY - CourierPool.OWN_GROUP_RESERVED
        assertEquals(
            CourierPool.Admission.ACCEPT,
            CourierPool.decide(ownCount = 0, blindCount = atCap - 1, isOwnGroup = false),
        )
        assertEquals(
            CourierPool.Admission.EVICT_OLDEST_BLIND,
            CourierPool.decide(ownCount = 0, blindCount = atCap, isOwnGroup = false),
        )
    }

    @Test
    fun `own-group can grow past its reserved 20 slots by borrowing unused blind-carry capacity`() {
        // 25 own-group rows, 0 blind — still under the 40 total cap, so a further own-group insert
        // just accepts normally, past the 20-slot reservation that only guarantees a FLOOR.
        assertEquals(CourierPool.Admission.ACCEPT, CourierPool.decide(ownCount = 25, blindCount = 0, isOwnGroup = true))
    }

    @Test
    fun `custom capacity and reservation are honored, not just the defaults`() {
        assertEquals(
            CourierPool.Admission.EVICT_OLDEST_BLIND,
            CourierPool.decide(ownCount = 0, blindCount = 3, isOwnGroup = false, capacity = 10, ownReserved = 7),
        )
        assertEquals(
            CourierPool.Admission.ACCEPT,
            CourierPool.decide(ownCount = 0, blindCount = 2, isOwnGroup = false, capacity = 10, ownReserved = 7),
        )
    }
}
