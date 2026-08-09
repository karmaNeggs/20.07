package org.offlinemesh.app.ble

import java.util.BitSet

/**
 * A systematic random-linear fountain code with Gaussian-elimination (over GF(2)) decoding — P5
 * item 2 (`PLAN-v2.md` §4.3, `docs/DECISIONS.md` decision 46) toward replacing indexed evidence
 * chunks + `FRAME_MANIFEST`'s have-bitset + `RelayResponder`'s per-peer deficit computation, none of
 * which this slice touches yet (see the "slice 1 only" note below).
 *
 * **Why a hand-rolled LT code, not literal RFC 6330 RaptorQ (the spec §4.3 names) and not a
 * third-party library.** §4.3's actual load-bearing requirement is the PROPERTY — "a receiver
 * reconstructs from any k(1+ε) distinct symbols from any combination of sources" — not the specific
 * spec. RaptorQ earns that property's low-overhead tail (ε ~ 0.01%) through LDPC+HDPC precoding over
 * GF(256): a large, easy-to-get-subtly-wrong construction (real implementations run to thousands of
 * lines) whose whole value proposition is CDN-scale efficiency this app doesn't need — realistic
 * evidence items are ~200 symbols ([MeshFrameCodec.MAX_EVIDENCE_CHUNKS]'s own doc: "typically ~200
 * chunks" for a 640px/quality-45 JPEG), where a few percent of extra symbols costs nothing this app
 * can't already absorb. Two real Android-viable libraries were checked and rejected: OpenRQ (the
 * only mature RFC 6330 Java implementation) has had no commits since 2017 and isn't published to
 * Maven Central; the one Kotlin-native alternative found is a single-author, effectively unpublished
 * project barely a year old. Neither clears the maintenance bar every other dependency in this
 * project's `build.gradle.kts` meets (Tink, CameraX, zxing, LeakCanary all carry institutional
 * backing). This is the direct analogue of [CatalogFilter]'s own precedent — a plain Bloom filter
 * standing in for bitchat's heavier Golomb-Coded Set, for the identical reason ("far less
 * implementation risk than hand-rolling ... for a first, unverified-on-hardware pass").
 *
 * **Why Gaussian elimination, not belief propagation.** A suboptimal repair-symbol construction only
 * costs decoding EFFICIENCY under GE (more symbols needed before the matrix reaches full rank),
 * never correctness — GE succeeds whenever the received rows are linearly independent, full stop,
 * regardless of how those rows were built. RaptorQ/LT's usual belief-propagation decoder is faster
 * but its correctness genuinely depends on getting the precode/sparse-degree distribution exactly
 * right; a bug in [RepairPlan] here can only ever make decoding need MORE symbols than expected,
 * never produce a wrong answer — the one place this design is deliberately forgiving where
 * hand-rolled RaptorQ would have been least forgiving.
 *
 * **Why dense repair symbols, not a sparse (robust-soliton-style) degree distribution.** The first
 * version of [RepairPlan] used the classic robust soliton distribution (Luby 2002) — the standard
 * choice for LT codes, but standard specifically for BELIEF-PROPAGATION decoding, which this class
 * doesn't do. Measured against this class's own GE decoder (see [FountainCodeTest]'s overhead
 * scenarios), a sparse distribution needed 1.3-2.6x the information-theoretic minimum number of
 * repair symbols — most low-degree repair draws landed entirely inside the region a receiver already
 * had, contributing nothing. Under GE, a DENSE random coefficient vector (each source index included
 * independently with probability ~1/2 — standard random-linear-coding territory) is what minimizes
 * overhead: measured at 1-2 EXTRA symbols past the true deficit, independent of k, versus sparse's
 * multiplicative excess. The cost is more XOR work per symbol (O(k) source symbols touched instead
 * of a handful) — accepted deliberately, since this app's scarce resource is BLE bandwidth, not phone
 * CPU (see the "known cost" paragraph below for the honest number on that tradeoff).
 *
 * **Known cost, not yet hardware-measured.** [FountainDecoder.addSymbol] is O(k) BitSet checks per
 * call (the "clear this pivot from every other row" loop) plus data-dependent XOR work bounded by
 * how entangled the matrix currently is; end to end this is an O(k²)-ish incremental Gaussian
 * elimination, not RaptorQ's near-linear message passing. At `k` near [MeshFrameCodec.
 * MAX_EVIDENCE_CHUNKS] (4096) this is untested on real phone hardware — flagged exactly like every
 * other NOT-hardware-confirmed slice in `docs/DECISIONS.md`. At the realistic ~200-symbol case it is
 * not a concern (see [FountainCodeTest]'s own bounded-time smoke test for the order of magnitude
 * this was sanity-checked at).
 *
 * **This is slice 1 (construction-only) of P5 item 2** — same shape as decision 41's P4 slice 1: a
 * pure, exhaustively unit-tested primitive with ZERO production call sites. Nothing in
 * `RelayEngine`/`RelayResponder`/`MeshFrameCodec`/Room is touched by this slice. Wiring — replacing
 * `RelayEngine.chunkBytes`/`CHUNK_SIZE`, `Frame.Manifest`/`FRAME_MANIFEST`, `MeshProtocol.
 * encodeBitset`/`decodeBitset`, and `RelayResponder.handleManifest`'s deficit computation and session
 * chunk budget — is deferred to a later slice; see decision 46's own entry for that slice's scoping.
 */
object FountainCode {
    /** Splits [data] into [FountainEncoder.k] fixed-[symbolSize] systematic source symbols
     *  (esi `0 until k`), zero-padded on the last one exactly like `RelayEngine.chunkBytes` pads its
     *  final chunk today. Empty [data] still yields one all-zero symbol (k=1) rather than zero
     *  symbols — a fountain code with no symbols at all has nothing for a decoder to ever become
     *  complete from. */
    fun encoder(data: ByteArray, symbolSize: Int): FountainEncoder {
        require(symbolSize > 0) { "symbolSize must be positive, got $symbolSize" }
        val k = maxOf(1, (data.size + symbolSize - 1) / symbolSize)
        val padded = data.copyOf(k * symbolSize) // zero-pads when data.size < k*symbolSize
        val sourceSymbols = Array(k) { i -> padded.copyOfRange(i * symbolSize, (i + 1) * symbolSize) }
        return FountainEncoder(sourceSymbols, k, symbolSize)
    }
}

/** One fountain-coded unit on the wire: [esi] identifies which deterministic construction produced
 *  [data] (verbatim source data if `esi < k`, an XOR combination if `esi >= k` — see
 *  [FountainEncoder.symbol]/[RepairPlan]). Not a `data class`: [data] is a [ByteArray], whose
 *  default `equals`/`hashCode` would be referential — same discipline `EvidenceEntity`/`SosEntity`
 *  already follow for their own `ByteArray` fields. */
class Symbol(val esi: Int, val data: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Symbol) return false
        return esi == other.esi && data.contentEquals(other.data)
    }

    override fun hashCode(): Int = 31 * esi + data.contentHashCode()

    override fun toString(): String = "Symbol(esi=$esi, ${data.size} bytes)"
}

/** Produces symbols for one encoded blob — see [FountainCode.encoder]. Repair-symbol space
 *  (`esi >= k`) is unbounded; a sender can keep calling [symbol] with an ever-increasing esi for as
 *  long as a peer keeps asking, with no per-peer state to track (mirrors `RelayResponder.
 *  framesToPushOnConnect`'s own "no memory of any specific peer" design). */
class FountainEncoder internal constructor(
    private val sourceSymbols: Array<ByteArray>,
    val k: Int,
    val symbolSize: Int,
) {
    /** Symbol for [esi]: verbatim source data for `esi < k` (systematic), or the XOR of a
     *  deterministically-derived subset of source symbols for `esi >= k` (repair). */
    fun symbol(esi: Int): Symbol {
        require(esi >= 0) { "esi must be non-negative, got $esi" }
        val bytes = if (esi < k) {
            sourceSymbols[esi].copyOf()
        } else {
            val indices = RepairPlan.indicesFor(k, esi)
            val out = ByteArray(symbolSize)
            var i = indices.nextSetBit(0)
            while (i >= 0) {
                xorBytesInto(out, sourceSymbols[i])
                i = indices.nextSetBit(i + 1)
            }
            out
        }
        return Symbol(esi, bytes)
    }
}

/** Reassembles a [FountainEncoder]'s output from symbols arriving in any order, from any
 *  combination of sources, with duplicates and gaps — the actual §4.3 property this whole class
 *  exists for. [k]/[symbolSize] must match the encoder's exactly (both would travel on the wire as
 *  plain header fields once a later slice wires this in — see class doc); [originalLength] is the
 *  pre-padding byte length so [decode] can strip the last symbol's zero padding. */
class FountainDecoder(val k: Int, val symbolSize: Int, private val originalLength: Int) {
    private val rows = arrayOfNulls<Row>(k)
    private val seenEsi = HashSet<Int>()
    private var rank = 0

    private class Row(val coeffs: BitSet, val data: ByteArray)

    /** True once [rank] == [k] — every source index has been resolved, and [decode] is safe to
     *  call. A rank test, not a count of symbols received, which is what lets a duplicate or
     *  otherwise redundant symbol cost nothing but a wasted [addSymbol] call. */
    val isComplete: Boolean get() = rank == k

    /** Folds [symbol] into the decode state. Returns false if it carried no new information —
     *  either its esi was already seen, or (for a repair symbol) it turned out to be a linear
     *  combination of rows already known, which a fountain code's own repair-symbol randomness
     *  makes rare but not impossible. Throws only for a malformed symbol (wrong [symbolSize],
     *  negative esi), never for a merely-redundant one. */
    fun addSymbol(symbol: Symbol): Boolean {
        require(symbol.data.size == symbolSize) {
            "expected $symbolSize-byte symbols, got ${symbol.data.size}"
        }
        require(symbol.esi >= 0) { "esi must be non-negative, got ${symbol.esi}" }
        return if (isComplete || !seenEsi.add(symbol.esi)) false else tryInsert(symbol)
    }

    /** The actual row-insertion attempt, split out of [addSymbol] purely so its own single early
     *  return ("fully explained by rows already known") plus final return reads as one guard
     *  clause followed by the real work, rather than threading a third return through the middle
     *  of [addSymbol] itself. */
    private fun tryInsert(symbol: Symbol): Boolean {
        val coeffs = coeffsFor(symbol.esi)
        val data = symbol.data.copyOf()
        reduceAgainstExisting(coeffs, data)

        val pivot = coeffs.nextSetBit(0)
        if (pivot < 0) return false // fully explained by rows already known -- no new information

        val newRow = Row(coeffs, data)
        rows[pivot] = newRow
        rank++
        clearPivotFromOtherRows(pivot, newRow)
        return true
    }

    /** Keeps every other stored row clear of [pivot]'s column. Combined with
     *  [reduceAgainstExisting] always clearing every PRE-existing pivot from an incoming row before
     *  it's stored, this maintains the invariant that no stored row ever has a set bit at another
     *  stored row's pivot column -- which is exactly what makes every row a single, fully-isolated
     *  bit (its own column) by the time rank reaches k. See [decode]'s doc for why that's what
     *  makes reassembly a plain concatenation once complete. */
    private fun clearPivotFromOtherRows(pivot: Int, newRow: Row) {
        for (col in 0 until k) {
            val other = rows[col]
            if (other == null || col == pivot) continue
            if (other.coeffs.get(pivot)) {
                other.coeffs.xor(newRow.coeffs)
                xorBytesInto(other.data, newRow.data)
            }
        }
    }

    /** Reassembled original bytes, or null until [isComplete]. Safe to call repeatedly; does not
     *  mutate decode state. */
    fun decode(): ByteArray? {
        if (!isComplete) return null
        val out = ByteArray(k * symbolSize)
        for (i in 0 until k) {
            val row = rows[i] ?: return null // defensive; unreachable when isComplete
            System.arraycopy(row.data, 0, out, i * symbolSize, symbolSize)
        }
        return out.copyOf(originalLength)
    }

    private fun coeffsFor(esi: Int): BitSet =
        if (esi < k) {
            BitSet(k).apply { set(esi) }
        } else {
            RepairPlan.indicesFor(k, esi)
        }

    /** Repeatedly XORs [coeffs]/[data] against any existing pivot row whose column [coeffs] still
     *  has set. Safe in any scan order: a stored row is already zero at every OTHER row's pivot
     *  column (the invariant [addSymbol]'s final loop maintains), so eliminating one pivot can
     *  never reintroduce a bit at a different pivot column already handled in this same pass. */
    private fun reduceAgainstExisting(coeffs: BitSet, data: ByteArray) {
        while (true) {
            var eliminated = false
            var col = coeffs.nextSetBit(0)
            while (col >= 0) {
                val existing = rows[col]
                if (existing != null) {
                    coeffs.xor(existing.coeffs)
                    xorBytesInto(data, existing.data)
                    eliminated = true
                    break
                }
                col = coeffs.nextSetBit(col + 1)
            }
            if (!eliminated) return
        }
    }
}

private fun xorBytesInto(target: ByteArray, other: ByteArray) {
    for (i in target.indices) target[i] = (target[i].toInt() xor other[i].toInt()).toByte()
}

/** Derives, from [FountainEncoder.k]/[FountainDecoder.k] and a repair symbol's esi alone, exactly
 *  which source-symbol indices that repair symbol XORs together. Both [FountainEncoder.symbol] and
 *  [FountainDecoder.addSymbol] call this — nothing about a repair symbol's construction ever needs
 *  to travel on the wire beyond its bare esi, because both ends recompute the same answer
 *  independently. */
private object RepairPlan {
    /** Every one of the k source indices is included independently with probability ~1/2, drawn
     *  directly from [Prng]'s own random bits (64 candidate indices per [Prng.nextLong] call) rather
     *  than a degree-then-indices two-step — see the class doc's "why dense, not sparse" note for
     *  the measurement that motivated this over a sparse (robust-soliton-style) construction. */
    fun indicesFor(k: Int, esi: Int): BitSet {
        val prng = Prng(seedFor(k, esi))
        val bits = BitSet(k)
        var i = 0
        while (i < k) {
            val word = prng.nextLong()
            var bitPos = 0
            while (bitPos < Long.SIZE_BITS && i < k) {
                if ((word ushr bitPos) and 1L == 1L) bits.set(i)
                bitPos++
                i++
            }
        }
        return bits
    }
}

/** Deterministic SplitMix64 PRNG — not [java.util.Random]: although its LCG algorithm is
 *  documented, this codebase's own precedent ([CatalogFilter.hashIndexes]'s doc) is to avoid
 *  depending on a platform-provided algorithm for anything two independent devices must derive
 *  identically. Encoder and decoder each build their own instance from the same [seedFor] output
 *  and must draw an identical sequence — that determinism is [RepairPlan]'s entire mechanism. */
private class Prng(seed: Long) {
    private var state = seed

    fun nextLong(): Long {
        state += GOLDEN_GAMMA
        var z = state
        z = (z xor (z ushr MIX_SHIFT_1)) * MIX1
        z = (z xor (z ushr MIX_SHIFT_2)) * MIX2
        return z xor (z ushr FINAL_SHIFT)
    }

    private companion object {
        const val GOLDEN_GAMMA = -0x61c8864680b583ebL
        const val MIX1 = -0x40a7b892e31b1a47L
        const val MIX2 = -0x6b2fb644ecceee15L
        // SplitMix64's own fixed shift constants (Steele/Vigna/Bacon 2014) -- not tunable, part of
        // the algorithm's definition.
        const val MIX_SHIFT_1 = 30
        const val MIX_SHIFT_2 = 27
        const val FINAL_SHIFT = 31
    }
}

private const val SEED_SALT = 0x2545F4914F6CDD1DL

// Width of k/esi packed into the seed's upper/lower 32 bits, and the mask that keeps esi's sign
// extension from bleeding into k's half when esi is cast to Long.
private const val PACKED_HALF_BITS = 32
private const val LOWER_HALF_MASK = 0xFFFFFFFFL

private fun seedFor(k: Int, esi: Int): Long =
    (k.toLong() shl PACKED_HALF_BITS) xor (esi.toLong() and LOWER_HALF_MASK) xor SEED_SALT
