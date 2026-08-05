package org.offlinemesh.app.ble

import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanSettings

/**
 * Every Bluetooth frequency/power/timing knob in the mesh, in one place, per power tier.
 *
 * The point: changing "how hard the radio works" — TX power, scan mode, how often to re-check the
 * advertised payload — is a one-file edit here, not a hunt through BeaconRadio's loop bodies.
 * Nothing else in the BLE layer should hardcode a millisecond or a power constant; it reads from a
 * [Profile] handed to it by the current [MeshService.PowerTier].
 *
 * Both radio loops converged on the same principle after three rounds of live 2-phone testing:
 * **touch the radio only when something real changed, never on a fixed timer.**
 *  - An early version app-level start/stopped the *scanner* every few seconds to align with a
 *    shared wall-clock rendezvous slot. Android silently throttles apps that toggle scanning that
 *    often (an undocumented ~5-calls-per-30-seconds limit), inconsistently across devices —
 *    produced a real asymmetric "device A sees B, B doesn't see A" bug. Fixed: scanning starts
 *    once per tier and stays running; [scanMode] alone drives the OS's own internal duty-cycling.
 *  - A later version held the *advertiser* up briefly, then deliberately waited for a shared
 *    wall-clock slot boundary before advertising again — the radio transmitting as little as ~15%
 *    of the time. Fixed by removing the wait, refreshing back-to-back instead — which then turned
 *    out to be *worse*: stopping and restarting the advertiser every ~700-900ms, continuously,
 *    pushed real hardware into total, symmetric discovery failure on *both* test phones (rapid BLE
 *    advertise start/stop cycling is a known category of chipset instability — see
 *    `docs/DECISIONS.md`, decision 1, for the full story). Fixed properly: the
 *    advertiser is now only stopped/restarted when the actual payload changes (new rotating-id
 *    window, a different group in the round-robin, a shifted SOS hop) — [advertiseCheckIntervalMs]
 *    below governs how often that's *checked*, not how often the radio is touched. A stable
 *    single-group beacon now calls `startAdvertising` roughly once every ~60 seconds.
 */
object BleTuning {

    data class Profile(
        /** How often the advertise loop wakes to re-check whether the payload needs to change.
         *  Purely a check cadence (a cheap DB read + string comparison) — NOT a radio duty cycle;
         *  the radio itself is only touched when the payload actually differs from what's already
         *  transmitting (see BeaconRadio.ensureAdvertising). */
        val advertiseCheckIntervalMs: Long,
        /** android.bluetooth.le.ScanSettings.SCAN_MODE_* — scanning runs continuously once started;
         *  this is the only lever for scan duty-cycling, left to the OS/controller rather than
         *  reimplemented with app-level start/stop. */
        val scanMode: Int,
        /** android.bluetooth.le.AdvertiseSettings.ADVERTISE_MODE_* */
        val advertiseMode: Int,
        /** android.bluetooth.le.AdvertiseSettings.ADVERTISE_TX_POWER_* */
        val advertiseTxPower: Int,
        /** PLAN-v2.md P3: links are now PERSISTENT — kept open, not cycled on a fixed idle/max
         *  timer (see docs/DECISIONS.md decision 19 for the full reasoning and what this replaced).
         *  This is a distant safety-net backstop only: if a held connection somehow never gets
         *  evicted by [LinkSelector]'s diversity logic and never fails on its own, force a refresh
         *  after this long anyway, so a bug in the eviction path can't monopolize a slot forever.
         *  Deliberately minutes, not seconds — the whole point of P3 is that a link surviving past
         *  the old ~20s ceiling is normal, not a leak. */
        val connectionBackstopMs: Long,
    )

    /** On-screen: check for payload changes more often (faster group round-robin / SOS-hop pickup),
     *  though in practice the radio itself rarely restarts either way — see class doc. */
    val ACTIVE = Profile(
        advertiseCheckIntervalMs = 2000L,
        scanMode = ScanSettings.SCAN_MODE_LOW_LATENCY,
        // BALANCED (~250ms interval between actual radio bursts) — LOW_POWER's ~1000ms interval
        // historically didn't leave enough margin and silently transmitted nothing on some chipsets.
        advertiseMode = AdvertiseSettings.ADVERTISE_MODE_BALANCED,
        advertiseTxPower = AdvertiseSettings.ADVERTISE_TX_POWER_HIGH,
        connectionBackstopMs = 10 * 60_000L,
    )

    /** Backgrounded / power-saver: check less often — pure CPU/DB-query saving, since the radio
     *  duty cycle itself no longer depends on this value the way it used to. */
    val RELAY = Profile(
        advertiseCheckIntervalMs = 4000L,
        scanMode = ScanSettings.SCAN_MODE_BALANCED,
        advertiseMode = AdvertiseSettings.ADVERTISE_MODE_BALANCED,
        advertiseTxPower = AdvertiseSettings.ADVERTISE_TX_POWER_HIGH,
        connectionBackstopMs = 20 * 60_000L,
    )

    fun forTier(tier: MeshService.PowerTier): Profile =
        when (tier) {
            MeshService.PowerTier.ACTIVE -> ACTIVE
            MeshService.PowerTier.RELAY -> RELAY
        }
}
