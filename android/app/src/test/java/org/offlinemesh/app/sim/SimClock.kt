package org.offlinemesh.app.sim

/**
 * A virtual, event-driven clock for the P0a Tier-1 simulator (PLAN-v2.md Part 6) — lets a scenario
 * compress a multi-hour crowd session into milliseconds of real wall-clock JVM test time. Same
 * injectable-clock shape already used throughout `ble/` ([org.offlinemesh.app.ble.ConnectionAttemptTracker],
 * [org.offlinemesh.app.ble.HopTracker], [org.offlinemesh.app.ble.TrickleTimer],
 * [org.offlinemesh.app.ble.OpaqueFrameRelay] all take a `now: () -> Long`) — this is what lets the
 * simulator hand its own clock straight to the real production classes instead of re-implementing
 * their timing logic.
 */
class SimClock(startMs: Long = 0L) {
    var nowMs: Long = startMs
        private set

    fun advanceTo(targetMs: Long) {
        check(targetMs >= nowMs) { "SimClock cannot move backwards ($targetMs < $nowMs)" }
        nowMs = targetMs
    }

    fun now(): Long = nowMs
}
