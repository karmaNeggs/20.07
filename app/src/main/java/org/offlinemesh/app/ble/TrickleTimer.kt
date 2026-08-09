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
 *
 * **Thread-safety (CR-7, `PLAN-v2.md` Part 10, 2026-08-09 review pass).** [onSighting] is called
 * from `BeaconRadio.handleResult`, which runs on the raw BLE scan-callback binder thread;
 * [shouldTransmit]/[reset] are called from the advertise coroutine. Found unsynchronized against a
 * plain `mutableSetOf`, unlike every other genuinely cross-thread field in `BeaconRadio` (careful
 * `@Volatile`/`ConcurrentHashMap`, per that class's own documented convention) — this class was the
 * one instance missed. All three mutating methods are `@Synchronized`: the critical sections are
 * trivial (one set add/clear plus a few `Long`/`Boolean` field writes), so a coarse lock costs
 * nothing measurable against the risk it closes — a `ConcurrentModificationException` here would
 * kill the advertise coroutine, which has no supervisor to restart it, silently ending Tier B
 * advertising for the rest of the session.
 *
 * **[redundancyConstant] counts DISTINCT sources per window, not raw [onSighting] calls** — see
 * decision 25 (`docs/DECISIONS.md`) for why this matters and isn't just a style choice: unlike
 * RFC 6206's own peers (which self-limit to at most one transmission per interval by the same
 * protocol), [BeaconRadio]'s advertising set — the actual sender this timer governs — stays
 * continuously ON for an entire un-suppressed period once started (see [isSuppressed]'s own doc),
 * so a real listener genuinely receives many separate packets from ONE present neighbor over the
 * course of a single window. Counting raw receptions against [redundancyConstant] would make a
 * single actively-broadcasting neighbor look "redundant" within seconds and pin suppression for as
 * long as that one neighbor keeps going, regardless of how many total neighbors are actually
 * present — the opposite of what this class exists to measure.
 */
class TrickleTimer(
    private val minIntervalMs: Long,
    private val maxIntervalMs: Long,
    private val redundancyConstant: Int = 2,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var intervalMs = minIntervalMs
    private var windowStart = now()
    private val sightingSourcesThisWindow = mutableSetOf<Any>()
    private var suppressed = false

    /** Call every time a same-purpose signal is heard from another device (e.g. a neighbor's own
     *  long-range beacon for the same group) — this is what lets [shouldTransmit] tell "I'm
     *  redundant, others already have this covered" apart from "I'm the only one out here."
     *  [sourceId] identifies WHICH device this sighting came from (e.g. the scanned BLE address) —
     *  repeated calls with the same [sourceId] inside one window count once, matching
     *  [redundancyConstant]'s "how many DISTINCT sources" intent (see this class's own doc). The
     *  identity only needs to stay stable for the life of one window (tens of seconds to low
     *  minutes) — far shorter than BLE address rotation (~15 min), so the raw scanned address is a
     *  fine [sourceId] here even though longer-lived state elsewhere in this app deliberately
     *  avoids keying on it (decision 15's peer-identity work is about state that must survive
     *  rotation; this is not that). */
    @Synchronized
    fun onSighting(sourceId: Any) {
        sightingSourcesThisWindow.add(sourceId)
    }

    /** True if this device should transmit right now. Only actually decides (and rolls over to
     *  the next, longer window) once the current window has elapsed — calling this more often
     *  than that is harmless (returns false without side effects) but pointless; callers should
     *  poll it on their own check cadence, not spin on it. The decision is based on how many
     *  sightings arrived during the window that just closed, not the fresh one being started.
     *  Edge-triggered (a one-shot "transmit now" pulse) — the classic Trickle/RFC 6206 usage,
     *  for a "send one packet at this tick" caller. See [isSuppressed] for the alternative,
     *  level-style query a continuously-running radio should poll instead. */
    @Synchronized
    fun shouldTransmit(): Boolean {
        if (now() - windowStart < intervalMs) return false
        val fewEnoughSightings = sightingSourcesThisWindow.size < redundancyConstant
        suppressed = !fewEnoughSightings
        windowStart = now()
        sightingSourcesThisWindow.clear()
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
    @Synchronized
    fun isSuppressed(): Boolean = suppressed

    /** Drops back to the minimum interval — call when local conditions change enough that
     *  cached backoff is no longer trustworthy (e.g. this device just lost track of the group
     *  entirely and needs to announce itself aggressively again). */
    @Synchronized
    fun reset() {
        intervalMs = minIntervalMs
        windowStart = now()
        sightingSourcesThisWindow.clear()
        suppressed = false
    }
}
