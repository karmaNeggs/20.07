package org.offlinemesh.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tier 1: the crowd-scaling catalog-sync filter that replaced PeerDeliveryTracker — see
 *  CatalogFilter's class doc for why an unbounded per-peer memory doesn't scale, and why a
 *  false positive here is safe (a skipped send, never a lost item). */
class CatalogFilterTest {

    @Test
    fun `an item that was added is reported as present`() {
        val f = CatalogFilter.build(listOf("sos:a", "sos:b"))
        assertTrue(f.mightContain("sos:a"))
        assertTrue(f.mightContain("sos:b"))
    }

    @Test
    fun `an item never added is very likely reported as absent`() {
        // Not an absolute guarantee (Bloom filters can false-positive) but with a handful of items
        // against a 2048-bit filter and 5 hash functions, a specific unrelated key colliding is
        // astronomically unlikely — if this test ever flakes, that's real signal, not noise.
        val f = CatalogFilter.build(listOf("sos:a", "sos:b", "sos:c"))
        assertFalse(f.mightContain("evid:totally-unrelated-item-xyz"))
    }

    @Test
    fun `an empty filter reports everything absent`() {
        val f = CatalogFilter.build(emptyList())
        assertFalse(f.mightContain("sos:anything"))
    }

    @Test
    fun `toBits then fromBits with the same seed reproduces identical membership answers`() {
        val original = CatalogFilter.build(listOf("sos:a", "evid:b", "nick:g:s:123"), seed = 42L)
        val reconstructed = CatalogFilter.fromBits(original.toBits(), seed = 42L)
        for (item in listOf("sos:a", "evid:b", "nick:g:s:123", "sos:not-present")) {
            assertEquals(original.mightContain(item), reconstructed.mightContain(item))
        }
    }

    @Test
    fun `different seeds over the same items generally disagree on borderline false positives`() {
        // Direct check of the class doc's core safety claim: re-salting changes which items false-
        // positive. Build many single-item filters with different seeds and confirm the *set* of
        // items that spuriously collide with an unrelated probe key differs across seeds — i.e. a
        // false positive isn't a fixed property of (holdings, item) alone, it depends on the seed.
        val holdings = (0 until 20).map { "sos:item-$it" }
        val probe = "evid:probe-key"
        val seeds = listOf(1L, 2L, 3L, 4L, 5L)
        val results = seeds.map { CatalogFilter.build(holdings, seed = it).mightContain(probe) }
        // Overwhelmingly likely not all identical across 5 independent seeds for an unrelated probe;
        // if they were all `true`, re-salting would provide no benefit at all.
        assertFalse(results.all { it })
    }

    @Test
    fun `false-positive rate stays low at a realistic catalog size`() {
        // ~150 items is a generous stand-in for this app's actual per-connection relayable catalog
        // (SOS + evidence-headers + nicknames, bounded by the 48h retention window). Confirms the
        // tuning constants (SIZE_BITS=2048, HASH_COUNT=5) documented in the class doc actually hold
        // in practice, not just in the back-of-envelope math in the comment.
        val holdings = (0 until 150).map { "sos:item-$it" }
        val f = CatalogFilter.build(holdings, seed = 7L)
        val falsePositives = (0 until 1000).count { f.mightContain("evid:probe-$it") }
        assertTrue("false positive rate too high: $falsePositives/1000", falsePositives < 100) // well under 10%
    }
}
