package org.offlinemesh.app.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import org.offlinemesh.app.ui.WifiDirectSettings

/**
 * WiFi Direct capability + opt-in gate, mirroring [BleCapabilities]'s role for the BT5 channel:
 * everything gated on this must degrade to "the WFD accelerator stays off, BLE is completely
 * unaffected" when false — nothing here is ever load-bearing for core mesh function. Fails closed
 * on any exception, same discipline as [BleCapabilities].
 */
object WifiDirectCapabilities {
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun supported(context: Context): Boolean = try {
        WifiDirectSettings.isEnabled(context) &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) &&
            hasRuntimePermission(context)
    } catch (e: Exception) {
        false
    }

    private fun hasRuntimePermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            // Pre-33: WifiP2pManager peer discovery has historically run under
            // ACCESS_FINE_LOCATION, which this app already holds unconditionally (see
            // MainActivity.requiredPermissions) — nothing extra to check here on older OS versions.
            true
        }
}
