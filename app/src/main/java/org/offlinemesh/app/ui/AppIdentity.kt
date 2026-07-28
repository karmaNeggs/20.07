package org.offlinemesh.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Flips which of the two launcher `<activity-alias>` entries (see AndroidManifest.xml) is active —
 * the real "20.07" identity, or the decoy "Notes" one. Both point at the same MainActivity; only
 * the launcher-visible icon/label differ. See README's Security model for what this disguise does
 * and does not protect (UI-level only — package name, permissions, and Settings > Apps listing are
 * unaffected either way, regardless of which identity is active).
 */
object AppIdentity {
    private const val REAL_ALIAS = "org.offlinemesh.app.LauncherReal"
    private const val DECOY_ALIAS = "org.offlinemesh.app.LauncherDecoy"

    /** The decoy alias defaults to `COMPONENT_ENABLED_STATE_DEFAULT` (i.e. disabled, per the
     *  manifest) until explicitly flipped — so "explicitly ENABLED" is the only state that means
     *  the decoy identity is currently active. */
    fun isDecoyActive(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(ComponentName(context.packageName, DECOY_ALIAS)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    /** [PackageManager.DONT_KILL_APP] is required here — MeshService may be actively running the
     *  mesh when this is tapped, and flipping a cosmetic launcher icon has no reason to interrupt
     *  that (the default behavior without this flag restarts the app's process). */
    fun setDecoyActive(context: Context, active: Boolean) {
        val pm = context.packageManager
        val enabled = PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        val disabled = PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        pm.setComponentEnabledSetting(
            ComponentName(context.packageName, REAL_ALIAS),
            if (active) disabled else enabled,
            PackageManager.DONT_KILL_APP
        )
        pm.setComponentEnabledSetting(
            ComponentName(context.packageName, DECOY_ALIAS),
            if (active) enabled else disabled,
            PackageManager.DONT_KILL_APP
        )
    }
}
