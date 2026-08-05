package org.offlinemesh.app.sim

/**
 * Configurable Android-platform/radio misbehaviour, specified directly from PLAN-v2.md §6.1's
 * table of this project's *actual* bug history. The point of this class: a rig that only modelled
 * radio physics would have caught almost none of v1's real bugs — the failure distribution was
 * dominated by Android platform behaviour and state-machine lifecycle bugs. Every knob below
 * defaults to "off" (an idealised radio) and is named after the pass that found the corresponding
 * real bug, so a scenario can deliberately reintroduce a specific historical failure mode and
 * confirm the rig — and the invariants in [Invariants] — would have caught it.
 */
data class PlatformQuirks(
    /** Pass 12: some fraction of nodes cannot advertise at all (null `bluetoothLeAdvertiser`) —
     *  fully functional scanners/connectors, invisible to everyone else's scans. */
    val advertiseIncapableFraction: Double = 0.0,

    /** §9.2 item 1: some fraction of nodes can't apply a hardware ScanFilter (old chipset), so
     *  they pay the full unfiltered callback-storm cost at high degree regardless of the fix.
     *  Not yet consumed by [CatalogSyncEngine] — reserved for the P2 scenario work that models
     *  scan-callback cost directly; kept here now so the knob exists before it's needed. */
    val scanFilterIncapableFraction: Double = 0.0,

    /** Pass 13->14: touching the radio more than [radioChurnInstabilityThreshold] times within
     *  [radioChurnWindowMs] pushes that node's radio into TOTAL failure (not graceful degradation)
     *  for [radioChurnOutageMs] — the failure mode that actually happened on real chipsets, and
     *  the mechanised form of I1. */
    val radioChurnInstabilityThreshold: Int = Int.MAX_VALUE,
    val radioChurnWindowMs: Long = 60_000L,
    val radioChurnOutageMs: Long = 120_000L,

    /** Pass 16 (decision 5): probability a `connectGatt()` attempt never fires
     *  `onConnectionStateChange` at all — the pre-CONNECTED stuck-forever bug. */
    val callbackNeverArrivesProbability: Double = 0.0,

    /** decision A2: probability a connection reaches CONNECTED then goes silent with no
     *  DISCONNECTED callback ever — distinct from [callbackNeverArrivesProbability], which never
     *  leaves the pre-connect state at all and is caught by a different watchdog. */
    val halfOpenProbability: Double = 0.0,

    /** Pass 22 / NEXT_STEPS D1: how often a node's on-air BLE address rotates. Only consumed under
     *  [PeerKeyMode.ROTATING_ADDRESS] — [PeerKeyMode.STABLE_PUBKEY] (P0b) ignores it entirely,
     *  which is the whole point of P0b and exactly what makes its sim gate falsifiable by running
     *  the identical scenario under both modes. */
    val addressRotationIntervalMs: Long = Long.MAX_VALUE,

    /** §6.1 / Pass 23 / S9: some fraction of nodes decline the honest catalogue exchange (malformed
     *  frames, replays — modelled here as "contributes nothing, corrupts nothing," which is what
     *  Pass 23's fixes guarantee a well-behaved receiver against). */
    val maliciousFraction: Double = 0.0,
)
