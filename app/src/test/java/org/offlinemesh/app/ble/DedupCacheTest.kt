package org.offlinemesh.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DedupCacheTest {

    @Test
    fun `first sighting of a key is new`() {
        val cache = DedupCache()
        assertTrue(cache.offerNew("a"))
    }

    @Test
    fun `repeat sighting within the age window is a duplicate`() {
        var now = 0L
        val cache = DedupCache(maxAgeMillis = 1000L, now = { now })
        assertTrue(cache.offerNew("a"))
        now = 500L
        assertFalse(cache.offerNew("a"))
    }

    @Test
    fun `sighting after the age window expires is treated as new again`() {
        var now = 0L
        val cache = DedupCache(maxAgeMillis = 1000L, now = { now })
        assertTrue(cache.offerNew("a"))
        now = 1500L
        assertTrue(cache.offerNew("a"))
    }

    @Test
    fun `bounded via LRU eviction`() {
        val cache = DedupCache(maxEntries = 2)
        cache.offerNew("a")
        cache.offerNew("b")
        cache.offerNew("c") // evicts "a"
        assertEquals(2, cache.size())
        assertTrue(cache.offerNew("a")) // forgotten -> new again
    }
}
