package org.offlinemesh.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkSelectorTest {

    @Test
    fun `empty held set - nothing to evict`() {
        assertNull(LinkSelector.evictionCandidate(emptyList(), candidateDiversity = 0.5, minSeparation = 0.1))
    }

    @Test
    fun `candidate too close to every held value adds no coverage - no eviction`() {
        val held = listOf(0.1, 0.5, 0.9)
        assertNull(LinkSelector.evictionCandidate(held, candidateDiversity = 0.52, minSeparation = 0.1))
    }

    @Test
    fun `candidate far from everything evicts the most redundant held link`() {
        // 0.1 and 0.15 are close together (redundant); 0.9 stands alone.
        val held = listOf(0.1, 0.15, 0.9)
        val evictIndex = LinkSelector.evictionCandidate(held, candidateDiversity = 0.5, minSeparation = 0.1)
        // Either 0.1 (index 0) or 0.15 (index 1) is the most redundant - both are equally close to
        // the other, so either is an acceptable answer; assert it's one of that redundant pair,
        // never the isolated 0.9 (index 2).
        assertEquals(true, evictIndex == 0 || evictIndex == 1)
    }

    @Test
    fun `two identical held values correctly see each other as maximally redundant`() {
        val held = listOf(0.5, 0.5, 0.9)
        val evictIndex = LinkSelector.evictionCandidate(held, candidateDiversity = 0.1, minSeparation = 0.1)
        assertEquals(true, evictIndex == 0 || evictIndex == 1)
    }

    @Test
    fun `a single held value is always the eviction candidate when the candidate qualifies`() {
        val held = listOf(0.5)
        assertEquals(0, LinkSelector.evictionCandidate(held, candidateDiversity = 0.9, minSeparation = 0.1))
    }
}
