package org.offlinemesh.app.sim

/**
 * PLAN-v2.md §6.2's eight invariants, mechanised as pure functions over a completed scenario run
 * rather than left as prose ("assertions that fail a run, not dashboards"). Each throws
 * [IllegalStateException] with a specific message on violation, so a failing scenario test names
 * exactly which invariant broke rather than just "assertion failed."
 */
object Invariants {

    /** I1 — radio touched only when payload actually changed, mechanised here as: touching the
     *  radio more than [PlatformQuirks.radioChurnInstabilityThreshold] times inside
     *  [PlatformQuirks.radioChurnWindowMs] must always have tripped the outage breaker — i.e. the
     *  harness's own churn model, and by extension any mechanism run through it, cannot silently
     *  exceed the instability threshold without consequence. */
    fun checkNoUngovernedRadioChurn(node: SimNode, quirks: PlatformQuirks, metrics: SimMetrics) {
        val touches = metrics.radioTouches.filter { it.first == node.id }.map { it.second }.sorted()
        for (start in touches) {
            val count = touches.count { it in start..(start + quirks.radioChurnWindowMs) }
            check(count <= quirks.radioChurnInstabilityThreshold) {
                "I1 violated: node ${node.id} touched its radio $count times within " +
                    "${quirks.radioChurnWindowMs}ms starting at ${start}ms " +
                    "(threshold ${quirks.radioChurnInstabilityThreshold})"
            }
        }
    }

    /** I2 — no peer-keyed structure grows unbounded under address rotation. */
    fun checkBoundedPeerState(node: SimNode, maxExpected: Int) {
        val tracked = node.connectionAttemptTracker.trackedAddressCount()
        check(tracked <= maxExpected) {
            "I2 violated: node ${node.id} has $tracked tracked peer-state entries, expected <= $maxExpected"
        }
    }

    /** I3 — no connection slot held past its deadline. Checked via the half-open-recovery trace:
     *  every half-open event this run recorded must show a recovery, landing at or before its own
     *  hard deadline (plus [slackMs] for the deadline-check event itself). */
    fun checkNoSlotLeak(metrics: SimMetrics, connectionMaxMs: Long, slackMs: Long = 2_000L) {
        for (event in metrics.halfOpenEvents) {
            val recovered = event.recoveredAtMs
            checkNotNull(recovered) {
                "I3 violated: node ${event.nodeId}'s half-open connection at ${event.atMs}ms was never recovered"
            }
            check(recovered - event.atMs <= connectionMaxMs + slackMs) {
                "I3 violated: node ${event.nodeId}'s half-open connection held its slot for " +
                    "${recovered - event.atMs}ms, past the ${connectionMaxMs}ms deadline"
            }
        }
    }

    /** I4 — every §5.4 density adaptation's low-degree case equals the identity function. Generic:
     *  given the same decision's output at a degree at-or-below [floor], it must equal
     *  [identityResult]. Adaptation-specific tests (P1's fanout subsetting, first) call this
     *  rather than this file hardcoding any one adaptation. */
    fun <T> checkLowDegreeIsIdentity(degree: Int, floor: Int, actual: T, identityResult: T) {
        if (degree > floor) return
        check(actual == identityResult) {
            "I4 violated: at degree $degree (<= floor $floor) adaptation produced $actual, " +
                "expected the identity behaviour $identityResult"
        }
    }

    /** I5 — no node goes silent: every suppression mechanism fails open. The node must have
     *  touched its radio at least once within [maxSilenceMs] before [nowMs] (counting from
     *  [sinceMs], so a scenario can check "silent since leaving the crowd" rather than the whole
     *  run). */
    fun checkFailOpen(node: SimNode, metrics: SimMetrics, sinceMs: Long, nowMs: Long, maxSilenceMs: Long) {
        val lastTouch = metrics.radioTouches
            .filter { it.first == node.id && it.second in sinceMs..nowMs }
            .maxOfOrNull { it.second }
        check(lastTouch != null && nowMs - lastTouch <= maxSilenceMs) {
            "I5 violated: node ${node.id} went silent for more than ${maxSilenceMs}ms " +
                "(last radio touch: $lastTouch, now: $nowMs)"
        }
    }

    /** I6 — yield floor: more than [minYieldFraction] of completed syncs must carry something new. */
    fun checkYieldFloor(metrics: SimMetrics, minYieldFraction: Double) {
        val yieldFraction = 1.0 - metrics.emptySyncRate()
        check(yieldFraction >= minYieldFraction) {
            "I6 violated: yield ${"%.1f".format(yieldFraction * 100)}%, expected >= " +
                "${"%.1f".format(minYieldFraction * 100)}%"
        }
    }

    /** I7 — group delivery: every member of [group] must hold [item] by [deadlineMs]. Pass [nowMs]
     *  as the run's actual end time — the check is only meaningful once the run has reached (or
     *  passed) the deadline it's judging. */
    fun checkGroupDelivery(group: List<SimNode>, item: String, deadlineMs: Long, nowMs: Long) {
        if (nowMs < deadlineMs) return
        val missing = group.filterNot { item in it.catalogItems }.map { it.id }
        check(missing.isEmpty()) {
            "I7 violated: '$item' not delivered to ${missing.size}/${group.size} group members " +
                "by ${deadlineMs}ms (missing: $missing)"
        }
    }

    /** I8 — bounded per-node work as swarm size rises, given constant group size: per-node radio
     *  touch RATE for a constant-size group must not grow with total swarm size beyond
     *  [maxGrowthFactor] — the direct test of §5.5's "only blind relay scales" decomposition. */
    fun checkBoundedGroupWork(smallSwarmTouchRate: Double, largeSwarmTouchRate: Double, maxGrowthFactor: Double) {
        check(largeSwarmTouchRate <= smallSwarmTouchRate * maxGrowthFactor) {
            "I8 violated: per-node radio-touch rate grew from $smallSwarmTouchRate to " +
                "$largeSwarmTouchRate (> ${maxGrowthFactor}x) as swarm size rose, for a constant group size"
        }
    }
}
