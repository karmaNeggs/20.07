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
 * A node's local degree (how many same-purpose broadcasters it currently hears) drives how many
 * sightings arrive; [degreeAt] lets a scenario script that degree changing over time (S2 static,
 * S3 walking out, S4 walking in — PLAN-v2.md §6.3).
 *
 * Sighting model, and a real bug this caught in the FIRST version of this engine: [TrickleTimer]
 * expects [TrickleTimer.onSighting] called once per ACTUAL neighbour broadcast heard, accumulated
 * over however long the current window happens to be open — it is not itself time-aware. A naive
 * "call onSighting() `degree` times on every poll tick" (this engine's first draft) inflates the
 * count by the poll/window ratio, not just degree: at a 1 s poll and a 60 s backed-off window that
 * is up to 60 injections of `degree` sightings each in a SINGLE window, so even an isolated D=2
 * node registers ~120 sightings — hopelessly over threshold, permanently "suppressed" regardless
 * of real degree. Fixed here by injecting sightings only once every [sightingIntervalMs] (default
 * [TrickleTimer]'s own `minIntervalMs`, the fastest any real neighbour could plausibly re-announce)
 * and capping the count per injection at `degree.coerceAtMost(sightingCap)` rather than raw degree
 * — a crowd should read as "clearly over the redundancy constant," not as an unbounded number.
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
                    repeat(degreeAt(node.id, t).coerceAtMost(tuning.sightingCap)) { node.trickle.onSighting() }
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
