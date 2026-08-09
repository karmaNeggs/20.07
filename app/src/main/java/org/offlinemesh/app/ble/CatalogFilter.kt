package org.offlinemesh.app.ble

import java.security.SecureRandom
import java.util.BitSet

/**
 * A compact, probabilistic "here's roughly what I already have" set-membership filter — advertised
 * once per connection so a peer can skip re-sending SOS/evidence-header/nickname items we already
 * hold, without either side needing to remember anything about the OTHER specific peer across
 * connections. See [RelayResponder.framesToPushOnConnect]/[RelayResponder.handleIncoming]'s
 * `Frame.CatalogFilter` case for how this replaces the older per-peer delivery-tracking approach
 * (`PeerDeliveryTracker`, removed) — that mechanism needed a bounded, evictable memory of every
 * peer ever met, which is exactly the kind of state that doesn't scale to a crowd of thousands
 * (an evicted peer silently reverted to "resend them everything"). This one needs no memory of
 * peers at all: each connection, each side freshly advertises its own current holdings and the
 * other side computes the deficit live, right then — so there's nothing to evict and nothing that
 * gets stale.
 *
 * The old have-bitset this app used for evidence chunks through v0.7.13-dev (retired by decision 47,
 * `docs/DECISIONS.md`, when fountain coding replaced it) never generalized to this problem anyway,
 * because a chunk count is a small, fixed, mutually-known number — the SOS+evidence-header+nickname
 * catalog has no such bound. A Bloom filter is the
 * standard tool for compactly advertising an unbounded, evolving set; bitchat's own protocol
 * (github.com/permissionlesstech/bitchat, WHITEPAPER.md §6.3) assigns the same role to a Golomb-Coded
 * Set for its analogous public-history gossip sync. A plain Bloom filter is used here instead of a
 * literal GCS — same practical effect (compact membership test, tunable false-positive rate), far
 * less implementation risk than hand-rolling Golomb-Rice bit coding for a first, unverified-on-
 * hardware pass.
 *
 * **Correctness under false positives.** A Bloom filter can say "probably present" for an item
 * that isn't (false positive) but never the reverse (false negative) — [mightContain] is used ONLY
 * to decide whether to SKIP sending an item that would otherwise be sent (see
 * [RelayResponder.handleIncoming]), never to fabricate a send. So the worst case of a false
 * positive is "this item is skipped on this particular connection" — not "lost": the item stays in
 * the sender's own relayable set (SOS/evidence-header ttl/48h-prune rules are completely unchanged
 * by this class) and gets a fresh, independent chance to be offered on the very next reconnect,
 * against a freshly-salted filter. [seed] is re-randomized on every [build] call specifically so a
 * false positive doesn't deterministically recur connection after connection against the same
 * (holdings, item) pair — see [build]'s doc.
 *
 * [sizeBits] is per-instance, not a shared constant — see [sizeBitsFor]'s doc for why a group's
 * actual catalog size, not a fixed worst-case, drives it. It travels on the wire alongside [seed]
 * ([MeshFrameCodec.Frame.CatalogFilter]/`encodeCatalogFilter`) since a receiver's [hashIndexes]
 * must use the exact same bit-space the sender built against, or membership answers silently
 * disagree.
 */
class CatalogFilter private constructor(
    private val bits: BitSet,
    val seed: Long,
    val sizeBits: Int,
) {
    companion object {
        const val HASH_COUNT = 5

        // A 20.07 group is short-lived by design (days, not months — groups are deleted when the
        // task they were created for is done) and small (this app's real target is roughly a
        // dozen people), so a typical catalog (SOS + evidence-headers + nicknames combined, all
        // relayable items across every active group) is tens of items, not hundreds — the old
        // fixed 2048-bit/256-byte filter was generous headroom over that, most of it wasted on
        // every connection. Sizing to the actual catalog instead means a typical filter is small
        // enough to fit comfortably under even a conservative negotiated MTU, which is the real
        // point: RelayResponder.framesToPushOnConnect's MTU-fallback path exists for when it still
        // doesn't fit, but the common case should just fit rather than routinely triggering it.
        private const val BITS_PER_ITEM = 10
        private const val MIN_SIZE_BITS = 64
        private const val MAX_SIZE_BITS = 4096

        private const val BITS_PER_BYTE = 8

        /** Rounds up to a full byte so [toBits]/[BitSet.valueOf] round-trip on clean boundaries —
         *  not load-bearing for correctness ([hashIndexes] only ever produces indexes `< sizeBits`
         *  regardless), just keeps the encoded size predictable from [sizeBits] alone. */
        private fun sizeBitsFor(itemCount: Int): Int {
            val raw = (itemCount * BITS_PER_ITEM).coerceIn(MIN_SIZE_BITS, MAX_SIZE_BITS)
            return ((raw + BITS_PER_BYTE - 1) / BITS_PER_BYTE) * BITS_PER_BYTE
        }

        // Mixing constants for hashIndexes' double-hashing scheme — see that function's doc.
        private const val SEED_FOLD_SHIFT = 32 // half of Long.SIZE_BITS (64)
        private const val H1_MULTIPLIER = 31 // standard small-prime string-hash multiplier
        private const val H1_MIX_SHIFT = 16 // half of Int.SIZE_BITS (32), spreads high bits into low
        private const val H2_MULTIPLIER = 37 // a different small prime, decorrelates h2's walk from h1's
        private const val H2_DECORRELATION_CONSTANT = -1640531527 // 2^32 / golden ratio, as a signed Int
        private const val H2_MIX_SHIFT = 13 // arbitrary odd bit-mix shift, distinct from H1_MIX_SHIFT

        /** Builds a filter over [items] (the exact `"sos:<id>"` / `"evid:<id>"` / `"nick:<groupId>:
         *  <senderId>:<updatedAt>"` key strings [RelayResponder] already uses) with a fresh random
         *  seed. Re-randomizing the seed on every call — rather than reusing a stable one — is what
         *  keeps a false positive a one-connection inconvenience instead of a standing, repeatable
         *  miss: a different seed derives different bit positions for the same item, so an unlucky
         *  collision this round is (with overwhelming probability) simply not a collision next
         *  round, when a fresh filter gets built and sent again on the next reconnect.
         *
         *  [forcedSizeBits], when non-null, overrides [sizeBitsFor]'s own item-count-scaled sizing —
         *  added for `BeaconRadio`'s Tier B catalogue filter (decision 34, `docs/DECISIONS.md`):
         *  broadcasting a filter whose SIZE scales with item count would let any passive scanner
         *  (member or not) infer roughly how much content a group holds, and watch that estimate
         *  change over time — a real passive-observable signal nothing else on Tier B currently
         *  exposes. A fixed size removes that signal entirely (every group's filter looks identical
         *  regardless of how much it holds), at the cost of a worse false-positive rate for a large
         *  catalog — see decision 34 for the full reasoning and why that tradeoff was accepted.
         *  Caller's responsibility to pass an already byte-aligned value (unlike [sizeBitsFor],
         *  this does not round up) since Tier B's own constant is chosen byte-aligned already. */
        fun build(
            items: Collection<String>,
            seed: Long = SecureRandom().nextLong(),
            forcedSizeBits: Int? = null,
        ): CatalogFilter {
            val sizeBits = forcedSizeBits ?: sizeBitsFor(items.size)
            val bits = BitSet(sizeBits)
            val filter = CatalogFilter(bits, seed, sizeBits)
            for (item in items) filter.add(item)
            return filter
        }

        /** Reconstructs a filter received over the wire — [seed], [sizeBits], and [bits] must be
         *  exactly what the sender's [build]/[toBits] produced, since [seed] and [sizeBits]
         *  together determine which bit positions an item hashes to. */
        fun fromBits(bits: ByteArray, seed: Long, sizeBits: Int): CatalogFilter =
            CatalogFilter(BitSet.valueOf(bits), seed, sizeBits)
    }

    private fun add(item: String) {
        for (h in hashIndexes(item)) bits.set(h)
    }

    /** True if [item] is PROBABLY already in the set this filter was built from — see the class
     *  doc for what a false positive costs (a skipped send, not a lost item) and why it's safe. */
    fun mightContain(item: String): Boolean = hashIndexes(item).all { bits.get(it) }

    /** [BitSet.toByteArray] truncates trailing all-zero bytes, so this can be shorter than
     *  `sizeBits / 8` — that's fine on the receiving end too: [BitSet.get] on an index beyond a
     *  reconstructed BitSet's current length returns false, exactly the correct "not set" answer. */
    fun toBits(): ByteArray = bits.toByteArray()

    /** Standard double hashing (Kirsch-Mitzenmacher): two independent base hashes combined to
     *  derive [HASH_COUNT] indexes, rather than implementing that many separate hash functions —
     *  a well-analyzed technique that still gives each derived index good spread. Both base hashes
     *  fold [seed] in and are computed over the string's own characters directly (not via
     *  [String.hashCode]) so this class's behavior doesn't depend on the JVM's hashCode algorithm
     *  ever changing. */
    private fun hashIndexes(item: String): IntArray {
        // SEED_FOLD_SHIFT: half of Long.SIZE_BITS (64) — folds the upper 32 bits of the 64-bit
        // seed onto the lower 32 before truncating to Int, so both halves of the seed contribute.
        val seedFold = (seed xor (seed ushr SEED_FOLD_SHIFT)).toInt()
        var h1 = seedFold
        for (c in item) h1 = h1 * H1_MULTIPLIER + c.code
        h1 = h1 xor (h1 ushr H1_MIX_SHIFT)
        var h2 = seedFold * H2_DECORRELATION_CONSTANT
        for (i in item.length - 1 downTo 0) h2 = h2 * H2_MULTIPLIER + item[i].code
        h2 = h2 xor (h2 ushr H2_MIX_SHIFT)
        if (h2 == 0) h2 = 1 // an all-zero step would collapse every derived index onto h1 alone
        return IntArray(HASH_COUNT) { i -> Math.floorMod(h1 + i * h2, sizeBits) }
    }
}
