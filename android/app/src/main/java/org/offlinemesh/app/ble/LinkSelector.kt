package org.offlinemesh.app.ble

import kotlin.math.abs

/**
 * PLAN-v2.md §5.4/P3's link-selection rule: at high degree, first-heard (v1's actual current
 * behaviour — whichever neighbour happens to connect first keeps the slot until it naturally ends)
 * clusters held links on whoever is physically nearest, which §9.2 item 2 calls "close to
 * worst-case" for reachability. Select for DIVERSITY instead: when the held set is full, evict the
 * currently-held link that's most redundant with the others — not the oldest — if a new candidate
 * would improve the held set's spread.
 *
 * "Diversity" here is a caller-supplied scalar per link — production: an RSSI-band proxy, since
 * physically distinct neighbours in a real crowd tend to sit in distinct signal-strength bands.
 * This class has no opinion on what the scalar physically means, only that closer values mean more
 * redundant coverage — kept generic so the simulator can drive it with a synthetic position value
 * (see `sim/PersistentForwardingEngine`'s class doc for that stand-in, and why it's an honestly-
 * documented simplification rather than a physical radio model).
 */
object LinkSelector {

    /** Index into [heldDiversityValues] of the link to evict in favour of [candidateDiversity], or
     *  null if the candidate isn't an improvement. Only meaningful when the held set is already at
     *  capacity — a caller with a free slot should just take the candidate directly, never call
     *  this. [minSeparation] is the "far enough to be worth it" threshold: a candidate within
     *  [minSeparation] of EVERY held value adds no real coverage, so nothing is evicted for it. */
    fun evictionCandidate(
        heldDiversityValues: List<Double>,
        candidateDiversity: Double,
        minSeparation: Double,
    ): Int? {
        if (heldDiversityValues.isEmpty()) return null
        val candidateAddsCoverage = heldDiversityValues.all { abs(it - candidateDiversity) >= minSeparation }
        if (!candidateAddsCoverage) return null
        // The held value with the smallest distance to its own nearest OTHER held value is the
        // most redundant one — evicting it loses the least coverage. Index-based comparison
        // (rather than filtering by value) so two genuinely-identical held values correctly see
        // each other as a zero-distance neighbour instead of silently excluding one another.
        return heldDiversityValues.indices.minByOrNull { i ->
            heldDiversityValues.indices
                .filter { it != i }
                .minOfOrNull { j -> abs(heldDiversityValues[i] - heldDiversityValues[j]) }
                ?: Double.MAX_VALUE
        }
    }
}
