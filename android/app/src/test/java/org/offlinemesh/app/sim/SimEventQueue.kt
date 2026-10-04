package org.offlinemesh.app.sim

import java.util.PriorityQueue

/**
 * Minimal discrete-event scheduler driving [SimClock] forward from one scheduled event to the
 * next, rather than a fixed tick size. A fixed tick would force an impossible trade-off between
 * staying accurate at D=3 (needs sub-second resolution around connection setup) and staying fast
 * at D=400 over a multi-hour run (S7 "Kettle") — event-driven stepping needs neither.
 */
class SimEventQueue(private val clock: SimClock) {
    private data class Event(val atMs: Long, val seq: Long, val action: () -> Unit)

    private var seqCounter = 0L
    private val queue = PriorityQueue<Event>(compareBy({ it.atMs }, { it.seq }))

    fun schedule(atMs: Long, action: () -> Unit) {
        queue += Event(atMs, seqCounter++, action)
    }

    fun scheduleIn(delayMs: Long, action: () -> Unit) = schedule(clock.now() + delayMs, action)

    /** Runs every scheduled event up to and including [untilMs], advancing the clock to each
     *  event's own timestamp before invoking it — so an action reading [SimClock.now] sees the
     *  time it was scheduled for, not the time [runUntil] happened to be called. Actions may
     *  schedule further events (including at the current time), which are honoured within the
     *  same [runUntil] call as long as they land at or before [untilMs]. */
    fun runUntil(untilMs: Long) {
        while (true) {
            val next = queue.peek()
            if (next == null || next.atMs > untilMs) break
            queue.poll()
            clock.advanceTo(next.atMs)
            next.action()
        }
        clock.advanceTo(untilMs)
    }

    fun pendingCount(): Int = queue.size
}
