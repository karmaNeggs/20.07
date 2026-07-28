package org.offlinemesh.app.ui

import android.content.Context

/** The WiFi Direct accelerator's opt-in flag — same direct-SharedPreferences pattern as
 *  [AppIdentity], same `"mesh_device"` prefs file [org.offlinemesh.app.data.GroupRepository]'s
 *  deviceId and [org.offlinemesh.app.ble.MeshService]'s decoy-notification-icon pick already use.
 *  Default OFF: this is an experimental accelerator (see [org.offlinemesh.app.ble.
 *  WifiDirectAccelerator]'s class doc), never something the app enables on someone's behalf. */
object WifiDirectSettings {
    private const val KEY_ENABLED = "wifi_direct_accel_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences("mesh_device", Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("mesh_device", Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
