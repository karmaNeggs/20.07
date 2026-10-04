package org.offlinemesh.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForwardingPolicyTest {

    // ---------- forwardedTtl ----------

    @Test
    fun `below the clamp floor TTL just decrements by 1`() {
        assertEquals(7, ForwardingPolicy.forwardedTtl(incomingTtl = 8, openLinkCount = 2))
        assertEquals(3, ForwardingPolicy.forwardedTtl(incomingTtl = 4, openLinkCount = 5))
    }

    @Test
    fun `at or above the clamp floor TTL is capped even if decrementing alone would be higher`() {
        assertEquals(5, ForwardingPolicy.forwardedTtl(incomingTtl = 8, openLinkCount = 6))
        assertEquals(5, ForwardingPolicy.forwardedTtl(incomingTtl = 8, openLinkCount = 400))
    }

    @Test
    fun `the clamp never RAISES ttl - a low incoming ttl still just decrements`() {
        assertEquals(2, ForwardingPolicy.forwardedTtl(incomingTtl = 3, openLinkCount = 400))
    }

    // ---------- jitterRangeMs / pickJitterMs ----------

    @Test
    fun `low degree jitter is the tight range`() {
        assertEquals(10L..30L, ForwardingPolicy.jitterRangeMs(openLinkCount = 4))
    }

    @Test
    fun `high degree jitter is the wide range`() {
        assertEquals(10L..220L, ForwardingPolicy.jitterRangeMs(openLinkCount = 5))
    }

    @Test
    fun `picked jitter always falls inside the declared range`() {
        val random = kotlin.random.Random(42)
        repeat(50) {
            val picked = ForwardingPolicy.pickJitterMs(openLinkCount = 400, random = random)
            assertTrue(picked in ForwardingPolicy.jitterRangeMs(400))
        }
    }

    // ---------- linksToForwardOn ----------

    @Test
    fun `at or below the low-degree floor every open link is used`() {
        val links = listOf("a", "b")
        assertEquals(links, ForwardingPolicy.linksToForwardOn(links, messageIdSeed = 1L, openLinkCount = 2))
    }

    @Test
    fun `above the floor a subset of roughly log2(degree) links is chosen`() {
        val links = (0 until 400).map { "link-$it" }
        val subset = ForwardingPolicy.linksToForwardOn(links, messageIdSeed = 7L, openLinkCount = 400)
        // ceil(log2(400)) = 9
        assertEquals(9, subset.size)
        assertTrue(links.containsAll(subset))
    }

    @Test
    fun `the same seed and inputs always produce the same subset`() {
        val links = (0 until 50).map { "link-$it" }
        val first = ForwardingPolicy.linksToForwardOn(links, messageIdSeed = 99L, openLinkCount = 50)
        val second = ForwardingPolicy.linksToForwardOn(links, messageIdSeed = 99L, openLinkCount = 50)
        assertEquals(first, second)
    }

    @Test
    fun `subset size never exceeds the actual number of open links available`() {
        val links = listOf("a", "b", "c")
        val subset = ForwardingPolicy.linksToForwardOn(links, messageIdSeed = 1L, openLinkCount = 400)
        assertTrue(subset.size <= links.size)
    }
}
