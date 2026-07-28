package org.offlinemesh.app.ble

import android.bluetooth.BluetoothAdapter

/**
 * BT5 capability detection, centralized here for the same reason [BleTuning] centralizes tuning
 * knobs — one place to check instead of scattered `isXSupported()` calls at every call site.
 *
 * Everything gated on this must degrade to exactly the pre-existing (legacy-only) behavior when
 * false: nothing here is load-bearing for core mesh function. Legacy advertising/scanning/GATT
 * (the only paths that have actually been live-device-tested across passes 1-21) work identically
 * regardless of what this reports — see [BeaconRadio]'s Coded PHY supplementary channel, which
 * only ever runs *in addition to*, never *instead of*, the proven legacy beacon.
 */
object BleCapabilities {
    // Deliberately broad catch, deliberately silent: this probes vendor Bluetooth stack behavior
    // that has no documented failure mode, only observed-in-the-wild quirkiness on some OEM
    // chipsets. Any exception here means "can't tell, so behave as unsupported" — the same
    // fail-closed-to-legacy-only outcome as a clean `false`, never a crash, never surfaced as an
    // error a caller needs to handle differently.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun extendedAdvertisingSupported(adapter: BluetoothAdapter?): Boolean =
        try { adapter?.isLeExtendedAdvertisingSupported == true } catch (e: Exception) { false }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun codedPhySupported(adapter: BluetoothAdapter?): Boolean =
        try { adapter?.isLeCodedPhySupported == true } catch (e: Exception) { false }

    /** Both capabilities are needed to actually gain anything from the long-range channel — extended
     *  advertising alone (without Coded PHY) still caps out at ordinary 1M PHY range. */
    fun longRangeBeaconSupported(adapter: BluetoothAdapter?): Boolean =
        extendedAdvertisingSupported(adapter) && codedPhySupported(adapter)
}
