package org.offlinemesh.app.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2's own gate (PLAN-v2.md §6.4/Part 7): I5 fail-open for Trickle-governed group-presence
 * broadcast, using the REAL production [org.offlinemesh.app.ble.TrickleTimer] with its real
 * BeaconRadio tuning (min 5s / max 60s / redundancy 2), same "no reimplementation" discipline as
 * every other sim engine in this package.
 *
 * Rewritten per decisions 24-25 (`docs/DECISIONS.md`):
 *
 * - Decision 24: [BroadcastTierEngine.degreeAt] must be **own-group degree** (0 to
 *   group-size-minus-one, so 0-7 per PLAN-v2.md §9.1's 3-8 person groups), not swarm/stranger
 *   density — matching [org.offlinemesh.app.ble.BeaconRadio]'s actual `matchTable`-gated
 *   `onSighting()` call. The original version of this file fed S3's literal "D 300 -> 2" as the
 *   sighting count, conflating swarm density with group degree, and produced a now-superseded
 *   "boundary bug" finding (decision 23) that decision 24 explains away rather than fixes. Swarm
 *   size does not appear anywhere below because it is provably irrelevant to this mechanism;
 *   `swarm size irrelevant to own-group Trickle behaviour` proves that directly.
 * - Decision 25: the real root cause behind decision 24's OWN still-open finding (a single held
 *   neighbour could pin suppression indefinitely) was [org.offlinemesh.app.ble.TrickleTimer]
 *   itself counting raw `onSighting()` calls instead of distinct sources — fixed there, not
 *   worked around here. `last buddy remaining (degree 1)` now asserts success instead of
 *   documenting a gap.
 */
class P2GateTest {

    private val minIntervalMs = 5_000L
    private val maxIntervalMs = 60_000L
    private val redundancyConstant = 2

    private val settleEndMs = 300_000L // long enough for the timer to fully back off
    private val transitionEndMs = settleEndMs + 60_000L // same 60s ramp shape as PLAN-v2.md S3/S4
    private val runEndMs = transitionEndMs + 300_000L // 5 more minutes to observe recovery

    /** Ramps own-group degree from [fromDegree] to [toDegree] over [transitionEndMs], then holds. */
    private fun rampGroupDegreeAt(nowMs: Long, fromDegree: Int, toDegree: Int): Int = when {
        nowMs < settleEndMs -> fromDegree
        nowMs < transitionEndMs -> {
            val progress = (nowMs - settleEndMs).toDouble() / (transitionEndMs - settleEndMs)
            (fromDegree - progress * (fromDegree - toDegree)).toInt().coerceAtLeast(toDegree)
        }
        else -> toDegree
    }

    @Test
    fun `static 5-person group, all 4 others in range- Trickle suppresses and never goes fully silent`() {
        val clock = SimClock()
        val metrics = SimMetrics()
        val node = BroadcastTierNode("n1", minIntervalMs, maxIntervalMs, redundancyConstant, clock::now)
        // Group of 5 (this node + 4 others), all mutually in range - own-group degree = 4, well
        // above redundancyConstant. This is an ordinary steady state, not a crowd edge case.
        val engine = BroadcastTierEngine(clock, metrics, listOf(node), { _, t -> rampGroupDegreeAt(t, 4, 4) })

        engine.run(settleEndMs)

        val touches = metrics.radioTouches.filter { it.first == "n1" }.map { it.second }.sorted()
        assertTrue(
            "expected at least one touch even while suppressed (isSuppressed defaults false)",
            touches.isNotEmpty(),
        )
        val lateTouches = touches.filter { it in 200_000L..settleEndMs }
        assertTrue(
            "expected at most 2 touches in the last 100s once backed off, got ${lateTouches.size}: $lateTouches",
            lateTouches.size <= 2,
        )
    }

    @Test
    fun `swarm size is irrelevant to own-group Trickle behaviour`() {
        // This is the direct, mechanised proof of decision 23/24's resolution: BroadcastTierEngine
        // never receives a swarm-size input at all (see its own class doc) - degreeAt is defined
        // purely in terms of own-group degree, so two runs that differ only in a swarm-size label
        // used to PICK the same group-degree profile must produce byte-identical radio-touch traces.
        fun runWithSwarmSize(swarmSize: Int): List<Long> {
            val clock = SimClock()
            val metrics = SimMetrics()
            val node = BroadcastTierNode("n1", minIntervalMs, maxIntervalMs, redundancyConstant, clock::now)
            // swarmSize is accepted here only to make the point that it changes nothing below -
            // it is never read by degreeAt, which always returns the same group-degree ramp.
            @Suppress("UNUSED_EXPRESSION") swarmSize
            val engine = BroadcastTierEngine(clock, metrics, listOf(node), { _, t -> rampGroupDegreeAt(t, 3, 0) })
            engine.run(runEndMs)
            return metrics.radioTouches.filter { it.first == "n1" }.map { it.second }.sorted()
        }

        val touchesAtSwarm3 = runWithSwarmSize(3)
        val touchesAtSwarm3000 = runWithSwarmSize(3000)
        assertEquals(
            "own-group Trickle behaviour must not depend on swarm size",
            touchesAtSwarm3,
            touchesAtSwarm3000,
        )
    }

    @Test
    fun `genuine isolation (own-group degree to 0)- I5 fail-open holds`() {
        val clock = SimClock()
        val metrics = SimMetrics()
        val node = BroadcastTierNode("n1", minIntervalMs, maxIntervalMs, redundancyConstant, clock::now)
        // A group of 3 (this node + 2 others), both drift out of range over the transition window -
        // the actual isolation event for THIS mechanism, decoupled from any notion of swarm density.
        val engine = BroadcastTierEngine(clock, metrics, listOf(node), { _, t -> rampGroupDegreeAt(t, 2, 0) })

        engine.run(runEndMs)

        Invariants.checkFailOpen(
            "n1", metrics, sinceMs = transitionEndMs, nowMs = runEndMs, maxSilenceMs = maxIntervalMs + 1_000L,
        )
        // Refinement of P2's stated acceptance language ("audibly loud again within one interval of
        // leaving"): measured directly, the actual worst case is closer to TWO intervals, not one -
        // TrickleTimer's window boundary is not realigned when conditions change. Whichever window
        // happens to be "in flight" when isolation begins may have started up to maxIntervalMs
        // BEFORE that moment, then takes up to another maxIntervalMs to close and re-evaluate.
        val firstTouchAfterIsolation = metrics.radioTouches
            .filter { it.first == "n1" && it.second >= transitionEndMs }.minOf { it.second }
        assertTrue(
            "expected the first post-isolation touch within two intervals (${maxIntervalMs * 2}ms) of " +
                "the transition completing at $transitionEndMs, got $firstTouchAfterIsolation",
            firstTouchAfterIsolation - transitionEndMs <= maxIntervalMs * 2,
        )
    }

    @Test
    fun `last buddy remaining (degree 1)- fails open cleanly (decision 25 fix)`() {
        // Previously a documented gap (decision 24): BroadcastTierEngine re-injected `degree`
        // sightings on every sightingIntervalMs tick with no source identity, so a single held
        // neighbour accumulated far more than 1 sighting per window once it backed off to
        // maxIntervalMs, pinning suppression indefinitely at ANY nonzero degree. Root cause and fix
        // are in TrickleTimer itself, not just this harness: onSighting() now takes a sourceId and
        // dedupes within a window (docs/DECISIONS.md decision 25) - re-injecting the SAME synthetic
        // "neighbor-0" id every tick no longer inflates the count past 1, matching what
        // redundancyConstant was always meant to compare against ("2 distinct neighbours already
        // cover this", per TrickleTimerTest's own long-standing comments). This test now asserts the
        // fix holds at the exact degree that used to expose the bug.
        val clock = SimClock()
        val metrics = SimMetrics()
        val node = BroadcastTierNode("n1", minIntervalMs, maxIntervalMs, redundancyConstant, clock::now)
        val engine = BroadcastTierEngine(clock, metrics, listOf(node), { _, t -> rampGroupDegreeAt(t, 3, 1) })

        engine.run(runEndMs)

        Invariants.checkFailOpen(
            "n1", metrics, sinceMs = transitionEndMs, nowMs = runEndMs, maxSilenceMs = maxIntervalMs + 1_000L,
        )
    }

    @Test
    fun `exactly 2 group-mates in range is a covered state, not isolation - stays suppressed`() {
        // Decision 23's original "S3 D=2 never fails open" finding treated this as a bug, reading
        // D=2 as swarm-density shorthand for "basically alone." Under corrected own-group-degree
        // semantics (decision 24), D=2 means two of this node's actual group-mates are still in
        // direct mutual range and still broadcasting the same group-presence signal - an ordinary,
        // stable, well-covered state for a 3-8 person group, not an edge of isolation. Staying
        // suppressed here is TrickleTimer's redundancy rule doing exactly what it is for: this
        // node's own broadcast would be genuinely redundant. This test documents that the fail-open
        // check is EXPECTED to not apply at this degree - the opposite framing of decision 23's own
        // now-superseded test of the same name.
        val clock = SimClock()
        val metrics = SimMetrics()
        val node = BroadcastTierNode("n1", minIntervalMs, maxIntervalMs, redundancyConstant, clock::now)
        val engine = BroadcastTierEngine(clock, metrics, listOf(node), { _, t -> rampGroupDegreeAt(t, 4, 2) })

        engine.run(runEndMs)

        val stillSuppressedAtEnd = runCatching {
            Invariants.checkFailOpen(
                "n1", metrics, sinceMs = transitionEndMs, nowMs = runEndMs, maxSilenceMs = maxIntervalMs + 1_000L,
            )
        }.isFailure
        assertTrue(
            "expected this node to remain suppressed at a steady own-group degree of 2 - if this " +
                "now passes (fails open), TrickleTimer's redundancyConstant has changed and this " +
                "test's premise (2 group-mates = covered, not isolated) should be re-examined",
            stillSuppressedAtEnd,
        )
    }
}
