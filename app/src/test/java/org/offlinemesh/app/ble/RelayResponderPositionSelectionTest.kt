package org.offlinemesh.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [RelayResponder.selectPositionsToRelay] in isolation — deliberately NOT part of
 * [RelayResponderTest] (Robolectric-backed): [RelayResponder.positionFramesToPush] itself needs a
 * real group key (`GroupRepository.getGroupKey`, Android Keystore-backed, unavailable under
 * Robolectric — see [RelayResponderTest]'s own class doc for the same constraint), but the actual
 * selection/capping logic this test targets has no such dependency and is fully testable directly.
 */
class RelayResponderPositionSelectionTest {

    private fun record(hop: Int, timestampSec: Long = 1000L) =
        PositionTracker.Record(lat = 1.0, lon = 2.0, accuracyM = 5, timestampSec = timestampSec, hop = hop)

    @Test
    fun `excludes self`() {
        val positions = mapOf("me" to record(hop = 0), "peer" to record(hop = 0))
        val result = RelayResponder.selectPositionsToRelay(positions, selfId = "me", maxHops = 4)
        assertEquals(listOf("peer"), result.map { it.first })
    }

    @Test
    fun `excludes a peer whose relayed hop would reach or exceed maxHops`() {
        // hop=3 relayed becomes hop+1=4, which must be excluded when maxHops=4 (">=", not ">").
        val positions = mapOf("far" to record(hop = 3), "near" to record(hop = 0))
        val result = RelayResponder.selectPositionsToRelay(positions, selfId = "me", maxHops = 4)
        assertEquals(listOf("near"), result.map { it.first })
    }

    @Test
    fun `caps to the limit, keeping the nearest by hop`() {
        val positions = (0 until 20).associate { i -> "peer$i" to record(hop = i) }
        val result = RelayResponder.selectPositionsToRelay(positions, selfId = "me", maxHops = 100, limit = 12)
        assertEquals(12, result.size)
        // The 12 nearest (lowest hop) survive, not an arbitrary 12.
        assertEquals((0 until 12).map { "peer$it" }.toSet(), result.map { it.first }.toSet())
    }

    @Test
    fun `result is sorted nearest-hop-first`() {
        val positions = mapOf("c" to record(hop = 2), "a" to record(hop = 0), "b" to record(hop = 1))
        val result = RelayResponder.selectPositionsToRelay(positions, selfId = "me", maxHops = 100)
        assertEquals(listOf("a", "b", "c"), result.map { it.first })
    }

    @Test
    fun `an empty position map produces no selections`() {
        val result = RelayResponder.selectPositionsToRelay(emptyMap(), selfId = "me", maxHops = 4)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `default limit is the documented MAX_RELAYED_POSITIONS_PER_GROUP of 12`() {
        val positions = (0 until 20).associate { i -> "peer$i" to record(hop = i) }
        val result = RelayResponder.selectPositionsToRelay(positions, selfId = "me", maxHops = 100)
        assertEquals(12, result.size)
        assertFalse(result.map { it.first }.contains("peer12")) // the 13th-nearest must not survive
    }
}
