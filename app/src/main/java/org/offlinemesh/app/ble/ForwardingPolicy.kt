package org.offlinemesh.app.ble

import kotlin.math.ceil
import kotlin.math.ln
import kotlin.random.Random

/**
 * PLAN-v2.md §5.3/§5.4's forwarding-plane density adaptations, in one place — jitter, TTL
 * clamping, and fanout subsetting are all gated on the same signal, so bundling them mirrors how
 * `BleTuning.Profile` already bundles related radio config in this codebase.
 *
 * **"Degree" here means currently-OPEN links, not total reachable neighbours** — deliberately
 * narrower than §5.4's general table, because this is specifically what §5.3's own bullet
 * ("≥6 links → cap at 5; ≤2 links → full depth") measures against: the resource actually being
 * subset is which of the links a node is holding RIGHT NOW to send on, not how many strangers it
 * could theoretically reach. (Contrast `CatalogFilter`/`ConnectionAttemptTracker`, which reason
 * about addresses heard or attempted — this class only ever sees links already past setup.)
 *
 * The low-degree case is the identity function throughout (§5.4's governing rule): at
 * `openLinkCount <= 2`, every method here does what v1 would have done to every link — full TTL
 * depth, every link, tight jitter. Nothing is "enabled for crowds"; things are suppressed as
 * degree rises.
 *
 * **Deliberately decoupled from hop-count derivation.** `HopTracker`'s SOS distance currently
 * derives from TTL consumed (`DEFAULT_TTL - ttl + 1`, assuming exactly -1 per hop) — the TTL
 * clamp below can drop TTL by MORE than 1 in a single high-degree hop, which would silently
 * corrupt that arithmetic if hop-count ever reads this class's clamped TTL instead of a
 * dedicated, always-exactly-+1-per-hop counter. Any future wiring of this class into the wire
 * protocol MUST give relayable packets their own explicit hop field (the pattern positions
 * already use post-v0.4.0/decision 8 — see `MeshFrameCodec.Frame.PositionSealed.hop`), never
 * derive hop count from this class's TTL output.
 */
object ForwardingPolicy {

    private const val LOW_DEGREE_FLOOR = 2
    private const val HIGH_DEGREE_TTL_CLAMP_FLOOR = 6
    private const val TTL_CLAMP_CEILING = 5
    private const val JITTER_WIDE_FLOOR = 5
    private const val JITTER_TIGHT_MIN_MS = 10L
    private const val JITTER_TIGHT_MAX_MS = 30L
    private const val JITTER_WIDE_MIN_MS = 10L
    private const val JITTER_WIDE_MAX_MS = 220L
    private const val MIN_FANOUT_SUBSET_SIZE = 2

    /** TTL to stamp on a forwarded packet — plain -1 below [HIGH_DEGREE_TTL_CLAMP_FLOOR] open
     *  links; at or above it, ALSO capped at [TTL_CLAMP_CEILING] (§9.2 item 3: a ~5-regular graph
     *  over 400 nodes has diameter ~3.7, so `RelayEngine.DEFAULT_TTL` = 8 has more headroom than a
     *  dense region needs, and letting a packet ride unclamped there is pure redundant airtime).
     *  Callers must still separately check the result is `> 0` before forwarding. */
    fun forwardedTtl(incomingTtl: Int, openLinkCount: Int): Int {
        val decremented = incomingTtl - 1
        val clamped = decremented.coerceAtMost(TTL_CLAMP_CEILING)
        return if (openLinkCount >= HIGH_DEGREE_TTL_CLAMP_FLOOR) clamped else decremented
    }

    /** Jitter window to sleep before actually forwarding — tight at low degree (nothing to be
     *  polite to), wide at high degree (spreads simultaneous relays of the same packet out so
     *  they don't all key up the radio at the same instant). */
    fun jitterRangeMs(openLinkCount: Int): LongRange =
        if (openLinkCount < JITTER_WIDE_FLOOR) JITTER_TIGHT_MIN_MS..JITTER_TIGHT_MAX_MS
        else JITTER_WIDE_MIN_MS..JITTER_WIDE_MAX_MS

    fun pickJitterMs(openLinkCount: Int, random: Random = Random.Default): Long {
        val range = jitterRangeMs(openLinkCount)
        return random.nextLong(range.first, range.last + 1)
    }

    /** Which of [openLinks] to forward this specific packet on. At [LOW_DEGREE_FLOOR] or fewer
     *  open links, always all of them (bitchat's own "≤2 links → full incoming depth" rule —
     *  subsetting below this floor risks partitioning delivery, not saving airtime). Above it, a
     *  subset of size ≈log2(open link count), deterministically chosen from [messageIdSeed] so
     *  repeated evaluations of the SAME packet at the SAME node pick the SAME subset (defeats
     *  dedup's "one copy per link" intent otherwise) without needing any per-node mutable state. */
    fun <T> linksToForwardOn(openLinks: List<T>, messageIdSeed: Long, openLinkCount: Int): List<T> {
        if (openLinkCount <= LOW_DEGREE_FLOOR || openLinks.size <= LOW_DEGREE_FLOOR) return openLinks
        val subsetSize = ceil(ln(openLinkCount.toDouble()) / ln(2.0))
            .toInt()
            .coerceAtLeast(MIN_FANOUT_SUBSET_SIZE)
            .coerceAtMost(openLinks.size)
        return openLinks.shuffled(Random(messageIdSeed)).take(subsetSize)
    }
}
