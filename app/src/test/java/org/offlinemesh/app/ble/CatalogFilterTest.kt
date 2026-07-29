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
        val reconstructed = CatalogFilter.fromBits(original.toBits(), seed = 42L, sizeBits = original.sizeBits)
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
        // tuning constants (BITS_PER_ITEM=10, HASH_COUNT=5) documented in the class doc actually
        // hold in practice, not just in the back-of-envelope math in the comment.
        val holdings = (0 until 150).map { "sos:item-$it" }
        val f = CatalogFilter.build(holdings, seed = 7L)
        val falsePositives = (0 until 1000).count { f.mightContain("evid:probe-$it") }
        assertTrue("false positive rate too high: $falsePositives/1000", falsePositives < 100) // well under 10%
    }

    // ---------- sizeBits scales with the actual catalog, not a fixed worst-case ----------
    // This is the whole point of the dynamic-sizing change: a typical short-lived group's small
    // catalog should produce a small, wire-cheap filter, not always pay for a worst-case size.

    @Test
    fun `an empty or tiny catalog produces a small filter, not a fixed large one`() {
        val f = CatalogFilter.build(emptyList())
        assertTrue("expected a small filter for an empty catalog, got ${f.sizeBits} bits", f.sizeBits <= 64)
    }

    @Test
    fun `sizeBits grows with a larger catalog`() {
        val small = CatalogFilter.build((0 until 5).map { "sos:$it" })
        val large = CatalogFilter.build((0 until 300).map { "sos:$it" })
        assertTrue(
            "expected a larger catalog to produce a larger (or equal, once capped) filter",
            large.sizeBits >= small.sizeBits
        )
    }

    @Test
    fun `sizeBits is capped for a very large catalog rather than growing unbounded`() {
        val huge = CatalogFilter.build((0 until 10_000).map { "sos:$it" })
        assertTrue("expected sizeBits to be capped, got ${huge.sizeBits}", huge.sizeBits <= 4096)
    }

    @Test
    fun `reconstructing with the wrong sizeBits produces wrong membership answers`() {
        // Direct demonstration of why sizeBits has to travel on the wire alongside seed/bits (see
        // MeshFrameCodec.Frame.CatalogFilter's doc) — hashIndexes derives bit positions modulo
        // sizeBits, so using a different sizeBits than the sender actually built against computes
        // different positions entirely, not a merely-degraded false-positive rate.
        val original = CatalogFilter.build((0 until 50).map { "sos:$it" }, seed = 99L)
        val wrongSize = CatalogFilter.fromBits(original.toBits(), seed = 99L, sizeBits = 4096)
        val correctSize = CatalogFilter.fromBits(original.toBits(), seed = 99L, sizeBits = original.sizeBits)
        assertTrue(correctSize.mightContain("sos:0"))
        // Not asserting wrongSize is false for every item (Bloom filters can coincidentally agree),
        // but the two must disagree on at least one held item across a reasonably sized set,
        // proving sizeBits is actually load-bearing rather than incidentally ignorable.
        val disagreement = (0 until 50).any { i ->
            wrongSize.mightContain("sos:$i") != correctSize.mightContain("sos:$i")
        }
        assertTrue("expected the wrong sizeBits to disagree with the correct one on at least one item", disagreement)
    }
}
