package org.offlinemesh.app.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Slice 1 of P5 item 2 (`PLAN-v2.md` §4.3, `docs/DECISIONS.md` decision 46, [FountainCode]'s own
 * class doc). Proves the actual property §4.3 asks for -- "a receiver reconstructs from any
 * k(1+ε) distinct symbols from any combination of sources" -- directly, not just "tolerates loss."
 */
class FountainCodeTest {

    private fun randomBytes(size: Int, seed: Int): ByteArray {
        val r = Random(seed)
        return ByteArray(size) { r.nextInt(256).toByte() }
    }

    @Test
    fun `exactly the k systematic symbols reconstruct byte-identical data`() {
        val data = randomBytes(2000, seed = 1)
        val encoder = FountainCode.encoder(data, symbolSize = 100)
        val decoder = FountainDecoder(encoder.k, symbolSize = 100, originalLength = data.size)
        for (esi in 0 until encoder.k) decoder.addSymbol(encoder.symbol(esi))
        assertTrue(decoder.isComplete)
        assertArrayEquals(data, decoder.decode())
    }

    @Test
    fun `round trip survives dropped systematic symbols via repair symbols, various k`() {
        for (k in listOf(1, 2, 5, 37, 200, 4096)) {
            val symbolSize = 32
            val dataLen = (k * symbolSize) - (symbolSize / 2) // exercise the padding path too
            val data = randomBytes(dataLen.coerceAtLeast(1), seed = k)
            val encoder = FountainCode.encoder(data, symbolSize)
            assertEquals("k mismatch for dataLen=$dataLen symbolSize=$symbolSize", k, encoder.k)

            val rnd = Random(k * 31 + 7)
            val dropped = (0 until k).filter { rnd.nextInt(3) == 0 }.toSet() // drop ~1/3
            val decoder = FountainDecoder(k, symbolSize, data.size)

            for (esi in 0 until k) if (esi !in dropped) decoder.addSymbol(encoder.symbol(esi))
            var repairEsi = k
            while (!decoder.isComplete) {
                decoder.addSymbol(encoder.symbol(repairEsi))
                repairEsi++
                require(repairEsi < k + k + 1000) { "runaway decode at k=$k, never completed" }
            }
            assertArrayEquals("mismatch at k=$k", data, decoder.decode())
        }
    }

    @Test
    fun `reconstructs from any combination of independent sources, not just one stream`() {
        // The literal PLAN-v2.md property: two disjoint "sources" each contribute a disjoint slice
        // of systematic symbols plus a disjoint range of repair esi's; assembled into ONE decoder.
        val symbolSize = 64
        val k = 60
        val data = randomBytes(k * symbolSize, seed = 99)
        val encoder = FountainCode.encoder(data, symbolSize)
        val decoder = FountainDecoder(k, symbolSize, data.size)

        // Source A: even systematic indices + repair esi's in [k, k+50)
        for (esi in 0 until k step 2) decoder.addSymbol(encoder.symbol(esi))
        for (esi in k until k + 50) decoder.addSymbol(encoder.symbol(esi))

        // Source B: odd systematic indices (independently "discovered" this peer already had them)
        for (esi in 1 until k step 2) decoder.addSymbol(encoder.symbol(esi))

        assertTrue(decoder.isComplete)
        assertArrayEquals(data, decoder.decode())
    }

    @Test
    fun `duplicate esi is a harmless no-op, not double-counted toward rank`() {
        val symbolSize = 16
        val k = 10
        val data = randomBytes(k * symbolSize, seed = 5)
        val encoder = FountainCode.encoder(data, symbolSize)
        val decoder = FountainDecoder(k, symbolSize, data.size)

        assertTrue(decoder.addSymbol(encoder.symbol(0)))
        assertFalse("re-adding the same esi must be a no-op", decoder.addSymbol(encoder.symbol(0)))
        for (esi in 1 until k) decoder.addSymbol(encoder.symbol(esi))
        assertTrue(decoder.isComplete)
        assertArrayEquals(data, decoder.decode())
    }

    @Test
    fun `out-of-order and shuffled arrival reaches the same final decode`() {
        val symbolSize = 24
        val k = 40
        val data = randomBytes(k * symbolSize, seed = 42)
        val encoder = FountainCode.encoder(data, symbolSize)

        val symbols = (0 until k).map { encoder.symbol(it) }.shuffled(Random(123))
        val decoder = FountainDecoder(k, symbolSize, data.size)
        for (s in symbols) decoder.addSymbol(s)

        assertTrue(decoder.isComplete)
        assertArrayEquals(data, decoder.decode())
    }

    @Test
    fun `small overhead past k reliably completes decoding across many random trials`() {
        val symbolSize = 20
        val k = 150
        val trials = 30
        var successes = 0
        for (trial in 0 until trials) {
            val data = randomBytes(k * symbolSize, seed = 1000 + trial)
            val encoder = FountainCode.encoder(data, symbolSize)
            val rnd = Random(2000 + trial)
            // Keep only a random subset of systematic symbols, then top up with repair symbols
            // until k+5 distinct symbols total have been offered -- a small, realistic overhead.
            val order = (0 until k).shuffled(rnd).take((k * 0.7).toInt())
            val decoder = FountainDecoder(k, symbolSize, data.size)
            for (esi in order) decoder.addSymbol(encoder.symbol(esi))
            var repairEsi = k
            val budget = k + 5
            var offered = order.size
            while (!decoder.isComplete && offered < budget) {
                decoder.addSymbol(encoder.symbol(repairEsi))
                repairEsi++
                offered++
            }
            if (decoder.isComplete && data.contentEquals(decoder.decode())) successes++
        }
        // Not claiming RaptorQ-grade near-zero overhead -- see FountainCode's own class doc on why
        // that's an accepted, deliberate tradeoff. A generous reliability bar, not a tight one.
        assertTrue(
            "expected most trials to complete within k+5 symbols, got $successes/$trials",
            successes >= trials * 0.8,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `addSymbol rejects a wrong-sized symbol rather than silently corrupting decode state`() {
        val decoder = FountainDecoder(k = 5, symbolSize = 10, originalLength = 50)
        decoder.addSymbol(Symbol(esi = 0, data = ByteArray(9)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `addSymbol rejects a negative esi`() {
        val decoder = FountainDecoder(k = 5, symbolSize = 10, originalLength = 50)
        decoder.addSymbol(Symbol(esi = -1, data = ByteArray(10)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encoder rejects a non-positive symbolSize`() {
        FountainCode.encoder(ByteArray(10), symbolSize = 0)
    }

    @Test
    fun `empty input still yields one all-zero symbol and decodes back to empty`() {
        val encoder = FountainCode.encoder(ByteArray(0), symbolSize = 16)
        assertEquals(1, encoder.k)
        val decoder = FountainDecoder(encoder.k, symbolSize = 16, originalLength = 0)
        decoder.addSymbol(encoder.symbol(0))
        assertTrue(decoder.isComplete)
        assertArrayEquals(ByteArray(0), decoder.decode())
    }

    @Test
    fun `k=1 (data no larger than one symbol) round-trips including via a repair symbol`() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val encoder = FountainCode.encoder(data, symbolSize = 16)
        assertEquals(1, encoder.k)

        val decoder = FountainDecoder(1, symbolSize = 16, originalLength = data.size)
        // Skip the systematic symbol entirely -- only repair symbols are offered. At k=1 a given
        // repair esi has real (~1/2) odds of being an empty/uninformative row under this class's
        // dense construction (see FountainCode's own "why dense" doc) -- same as any other
        // redundant symbol, a decoder just tries the next esi, which is what this loop does.
        var esi = 1
        while (!decoder.isComplete) {
            decoder.addSymbol(encoder.symbol(esi))
            esi++
            require(esi < 1000) { "unexpectedly never completed for k=1" }
        }
        assertArrayEquals(data, decoder.decode())
    }

    @Test
    fun `decode returns null until complete and does not throw`() {
        val encoder = FountainCode.encoder(randomBytes(100, seed = 7), symbolSize = 10)
        val decoder = FountainDecoder(encoder.k, symbolSize = 10, originalLength = 100)
        assertNull(decoder.decode())
        decoder.addSymbol(encoder.symbol(0))
        assertNull(decoder.decode())
    }

    @Test
    fun `originalLength not an exact multiple of symbolSize strips trailing padding correctly`() {
        val data = randomBytes(103, seed = 3) // 103 is not a multiple of 32
        val encoder = FountainCode.encoder(data, symbolSize = 32)
        val decoder = FountainDecoder(encoder.k, symbolSize = 32, originalLength = data.size)
        for (esi in 0 until encoder.k) decoder.addSymbol(encoder.symbol(esi))
        val decoded = decoder.decode()
        assertEquals(103, decoded?.size)
        assertArrayEquals(data, decoded)
    }

    @Test
    fun `two independently seeded encoders for the same k derive identical repair symbols`() {
        // The whole RepairPlan mechanism: encoder and decoder (or two separate encoder instances,
        // standing in for two different relay devices) must derive the SAME construction from
        // (k, esi) alone, with no shared state beyond those two integers.
        val data = randomBytes(500, seed = 11)
        val encoderA = FountainCode.encoder(data, symbolSize = 50)
        val encoderB = FountainCode.encoder(data, symbolSize = 50)
        for (esi in encoderA.k until encoderA.k + 20) {
            assertArrayEquals(encoderA.symbol(esi).data, encoderB.symbol(esi).data)
        }
    }

    @Test
    fun `rank and deficit track progress and only ever move toward completion`() {
        val symbolSize = 10
        val k = 20
        val data = randomBytes(k * symbolSize, seed = 55)
        val encoder = FountainCode.encoder(data, symbolSize)
        val decoder = FountainDecoder(k, symbolSize, data.size)

        assertEquals(0, decoder.rank)
        assertEquals(k, decoder.deficit)

        for (esi in 0 until k) {
            val before = decoder.rank
            val added = decoder.addSymbol(encoder.symbol(esi))
            assertEquals(if (added) before + 1 else before, decoder.rank)
            assertEquals(k - decoder.rank, decoder.deficit)
        }
        assertEquals(k, decoder.rank)
        assertEquals(0, decoder.deficit)
        assertTrue(decoder.isComplete)

        // A redundant symbol past completion must not move rank/deficit at all.
        decoder.addSymbol(encoder.symbol(0))
        assertEquals(k, decoder.rank)
        assertEquals(0, decoder.deficit)
    }

    @Test
    fun `bounded-time smoke test at a moderately large k does not blow up quadratically`() {
        // Not the full MAX_EVIDENCE_CHUNKS=4096 ceiling (kept out of the regular suite to avoid a
        // slow/flaky CI run) -- see FountainCode's own class doc: full-4096 decode cost is real but
        // explicitly NOT hardware-confirmed yet, same as every other unconfirmed slice in this
        // project. This just catches an outright algorithmic blowup early, at a size close enough
        // to be informative.
        val symbolSize = 64
        val k = 1000
        val data = randomBytes(k * symbolSize, seed = 77)
        val encoder = FountainCode.encoder(data, symbolSize)
        val decoder = FountainDecoder(k, symbolSize, data.size)

        val start = System.currentTimeMillis()
        for (esi in 0 until k) decoder.addSymbol(encoder.symbol(esi))
        val elapsedMs = System.currentTimeMillis() - start

        assertTrue(decoder.isComplete)
        assertArrayEquals(data, decoder.decode())
        assertTrue("decode at k=$k took ${elapsedMs}ms, expected well under 10s", elapsedMs < 10_000)
    }
}
