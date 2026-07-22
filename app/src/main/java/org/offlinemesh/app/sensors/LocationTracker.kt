package org.offlinemesh.app.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Plain android.location.LocationManager, deliberately not Play Services FusedLocationProvider —
 * this needs to work on de-Googled / custom ROM phones that privacy-conscious users often run,
 * without adding a Google Play Services dependency.
 */
class LocationTracker(private val context: Context) {
    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location

    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val listener = LocationListener { loc -> _location.value = loc }

    @SuppressLint("MissingPermission")
    fun start() {
        // Register on every provider that exists on this device, regardless of whether it's
        // enabled *right now* — if we only checked isProviderEnabled() once here (as an earlier
        // version did), a phone with Location off at launch would never recover GPS even if the
        // user turns it on mid-session, short of restarting the app. requestLocationUpdates is
        // safe to call on a currently-disabled provider; it just won't deliver until enabled.
        // 8s / 5m instead of a near-continuous 3s / 2m — GPS is one of the biggest battery
        // draws on the phone, and this app was flagged as high battery use on device. At
        // walking pace, 8 seconds is still plenty responsive for "which way do I walk," and
        // relaxing the interval lets the chip actually duty-cycle instead of staying locked on.
        val known = manager.allProviders
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (provider !in known) continue
            try {
                manager.requestLocationUpdates(provider, 8000L, 5f, listener)
            } catch (_: Exception) {
            }
        }
        manager.getProviders(true).firstOrNull()?.let { _location.value = manager.getLastKnownLocation(it) }
    }

    fun stop() {
        try { manager.removeUpdates(listener) } catch (_: Exception) {}
    }
}
