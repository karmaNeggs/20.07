package org.offlinemesh.app.ble

/**
 * A minimal, RFC 6206 (Trickle algorithm)-inspired suppression timer: transmit less often once
 * enough neighbors are already saying the same thing, transmit at full rate again once that stops
 * being true. Used by [BeaconRadio]'s supplementary Coded PHY channel — NOT wired into the legacy
 * beacon or GATT relay paths, which stay exactly as proven across passes 1-21 (see [BleTuning]'s
 * class doc for why "touch the radio only when something changed" was already hard-won there;
 * this is a deliberately separate, additive lever on a brand-new channel, not a retrofit).
 *
 * Simplified from the full RFC: no "inconsistency resets the interval" branch, because this
 * timer's one input (heard a same-purpose signal from another device recently) has no natural
 * "consistent version" to compare against — it's a plain count-of-recent-sightings gate. What's
 * kept from the original: an interval that geometrically backs off while sightings stay frequent
 * (bounded by [maxIntervalMs]), and a redundancy constant [redundancyConstant] capping how many
 * recent sightings are even worth counting past. This is the actual crowd-scaling property: a
 * device that keeps hearing plenty of neighbors backs off further and further (redundant traffic
 * scales with local density, not with a fixed schedule), while one that goes quiet — an actual
 * gap in coverage — re-checks again at the next (still-growing) window boundary and resumes
 * transmitting the moment sightings drop below the redundancy constant.
 *
 * [now] is injectable so backoff/reset timing is testable without waiting out real seconds.
 */
class TrickleTimer(
    private val minIntervalMs: Long,
    private val maxIntervalMs: Long,
    private val redundancyConstant: Int = 2,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var intervalMs = minIntervalMs
    private var windowStart = now()
    private var sightingsThisWindow = 0
    private var suppressed = false

    /** Call every time a same-purpose signal is heard from another device (e.g. a neighbor's own
     *  long-range beacon for the same group) — this is what lets [shouldTransmit] tell "I'm
     *  redundant, others already have this covered" apart from "I'm the only one out here." */
    fun onSighting() {
        sightingsThisWindow++
    }

    /** True if this device should transmit right now. Only actually decides (and rolls over to
     *  the next, longer window) once the current window has elapsed — calling this more often
     *  than that is harmless (returns false without side effects) but pointless; callers should
     *  poll it on their own check cadence, not spin on it. The decision is based on how many
     *  sightings arrived during the window that just closed, not the fresh one being started.
     *  Edge-triggered (a one-shot "transmit now" pulse) — the classic Trickle/RFC 6206 usage,
     *  for a "send one packet at this tick" caller. See [isSuppressed] for the alternative,
     *  level-style query a continuously-running radio should poll instead. */
    fun shouldTransmit(): Boolean {
        if (now() - windowStart < intervalMs) return false
        val fewEnoughSightings = sightingsThisWindow < redundancyConstant
        suppressed = !fewEnoughSightings
        windowStart = now()
        sightingsThisWindow = 0
        intervalMs = (intervalMs * 2).coerceAtMost(maxIntervalMs)
        return fewEnoughSightings
    }

    /** Level-style read of the most recently completed window's decision — true if enough
     *  neighbor sightings arrived that this device should currently be staying quiet. Unlike
     *  [shouldTransmit]'s one-shot pulse, this fits a radio that — once started — keeps
     *  transmitting on its own without further app action (BLE advertising works this way): the
     *  caller polls this on its own cadence to decide whether that radio should currently be ON
     *  or OFF, and only touches it when the answer actually flips (see BeaconRadio's long-range
     *  channel). Has no side effects and doesn't require a window to have elapsed since the last
     *  call — reflects whatever the last completed window decided, unchanged until the next one
     *  closes. Defaults to false (not suppressed) before any window has ever closed, matching
     *  [shouldTransmit]'s own default-to-permissive behavior on a fresh timer. */
    fun isSuppressed(): Boolean = suppressed

    /** Drops back to the minimum interval — call when local conditions change enough that
     *  cached backoff is no longer trustworthy (e.g. this device just lost track of the group
     *  entirely and needs to announce itself aggressively again). */
    fun reset() {
        intervalMs = minIntervalMs
        windowStart = now()
        sightingsThisWindow = 0
        suppressed = false
    }
}
