package org.offlinemesh.app.sim

import org.offlinemesh.app.ble.TrickleTimer

/** One broadcasting node in the P2 Tier-1 sim: pairs an id with its own real [TrickleTimer]
 *  instance (the production class, not a reimplementation) governing whether its advertising set
 *  is currently suppressed. */
class BroadcastTierNode(
    val id: String,
    minIntervalMs: Long,
    maxIntervalMs: Long,
    redundancyConstant: Int = 2,
    now: () -> Long,
) {
    val trickle = TrickleTimer(minIntervalMs, maxIntervalMs, redundancyConstant, now)
}

/** [BroadcastTierEngine]'s tuning knobs, bundled so its constructor stays short (same reasoning as
 *  [SimNodeConfig]) — see each field's use at its call site in [BroadcastTierEngine.run]. */
data class BroadcastTierTuning(
    val pollIntervalMs: Long = 1_000L,
    val sightingIntervalMs: Long = 5_000L,
    val sightingCap: Int = 5,
)

/**
 * P2 Tier-1 sim (PLAN-v2.md §5.1 Tier B / Part 7 P2): the connectionless broadcast tier, governed
 * by the real production [TrickleTimer]. Deliberately narrow first pass — models §5.5's single
 * riskiest claim, "every suppression mechanism fails open" (I5), which Part 7 names as a P2
 * ACCEPTANCE CRITERION, not a later refinement ("Trickle without it turns 'walked out of the
 * crowd' into 'went silent'") — rather than the full presence/position/SOS/hop-gradient payload
 * model, degree-gated scan batching, or the legacy 31-byte fallback (all still open, see
 * `PLAN-v2.md`'s P2 entry).
 *
 * [degreeAt] must return **own-group degree** — how many of this node's OWN group's other members
 * it currently hears broadcasting the same group-presence signal, not total local/swarm density.
 * This matches production exactly: [org.offlinemesh.app.ble.BeaconRadio]'s `longRangeScanCallback`
 * only calls `onSighting()` after a successful `matchTable[groupId]` lookup — a beacon from a
 * group this device holds no key for is never counted, because it says nothing about whether THIS
 * node's own group already has its presence covered. Decisions 23/24 (`docs/DECISIONS.md`) settled
 * this after the first version of this engine and `P2GateTest` fed it raw swarm density (S3's
 * "D 300 → 2") as the sighting count, conflating two different things: swarm size (drives relay/
 * connection-slot pressure elsewhere, irrelevant to THIS timer) and own-group degree, which is
 * bounded by group size (3–8 people per PLAN-v2.md §9.1, so 0–7 other members max) regardless of
 * how many strangers are around. [degreeAt] lets a scenario script that own-group degree changing
 * over time.
 *
 * Sighting model, and TWO real bugs this caught across two sessions (decisions 23 and 25,
 * `docs/DECISIONS.md`) — both in the harness, neither in [TrickleTimer] itself:
 *
 * 1. (decision 23) [TrickleTimer.onSighting] must be called once per ACTUAL neighbour broadcast
 *    heard, accumulated over however long the current window happens to be open — it is not itself
 *    time-aware. This engine's first draft called it `degree` times on every 1 s poll tick, which
 *    inflated the count by the poll/window ratio. Fixed by injecting only once every
 *    [sightingIntervalMs] (default [TrickleTimer]'s own `minIntervalMs`, the fastest any real
 *    neighbour could plausibly re-announce) and capping the count per injection at
 *    `degree.coerceAtMost(sightingCap)`.
 * 2. (decision 25) [TrickleTimer.onSighting] takes a `sourceId` and dedupes within a window — it
 *    counts DISTINCT sources, not raw calls, because a real neighbour's continuously-running
 *    advertising set generates many packets from the same device per window, not one. This engine
 *    must pass a distinct, stable-within-the-window id per simulated neighbour (`"neighbor-$i"` for
 *    `i` in `0 until degree`) rather than an untagged call — re-injecting the SAME `degree` ids on
 *    every [sightingIntervalMs] tick is exactly what makes fix #1's periodic re-injection safe now:
 *    the set simply stays at `degree` distinct entries no matter how many times each tick repeats
 *    them, so [sightingIntervalMs]/[sightingCap] no longer need to be tuned to avoid inflating the
 *    count the way decision 23's fix did — they now only control how quickly a newly-arrived
 *    neighbour's id shows up when [degreeAt] changes mid-window.
 *
 * Uses [TrickleTimer.isSuppressed]'s level-style read, matching how a continuously-running BLE
 * advertising set is actually driven in production (see that method's own doc, and `BeaconRadio`'s
 * long-range channel) — not [TrickleTimer.shouldTransmit]'s one-shot pulse, which fits a per-packet
 * sender, not an advertising set left running. [TrickleTimer.shouldTransmit] is still called every
 * poll tick so the window actually advances/decides on its own real cadence.
 */
class BroadcastTierEngine(
    private val clock: SimClock,
    private val metrics: SimMetrics,
    private val nodes: List<BroadcastTierNode>,
    private val degreeAt: (nodeId: String, nowMs: Long) -> Int,
    private val tuning: BroadcastTierTuning = BroadcastTierTuning(),
) {
    private val lastSightingAt = mutableMapOf<String, Long>()

    fun run(untilMs: Long) {
        var t = clock.now()
        while (t <= untilMs) {
            clock.advanceTo(t)
            for (node in nodes) {
                val last = lastSightingAt[node.id]
                if (last == null || t - last >= tuning.sightingIntervalMs) {
                    val degree = degreeAt(node.id, t).coerceAtMost(tuning.sightingCap)
                    repeat(degree) { i -> node.trickle.onSighting("neighbor-$i") }
                    lastSightingAt[node.id] = t
                }
                node.trickle.shouldTransmit() // lets the window advance/decide on its own cadence
                if (!node.trickle.isSuppressed()) {
                    metrics.recordRadioTouch(node.id, t)
                }
            }
            t += tuning.pollIntervalMs
        }
    }
}
