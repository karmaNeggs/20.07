package org.offlinemesh.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Flips which of the launcher `<activity-alias>` entries (see AndroidManifest.xml) is active — the
 * real "20.07" identity, or one of a small library of decoy identities. All point at the same
 * MainActivity; only the launcher-visible icon/label differ. See README's Security model for what
 * this disguise does and does not protect (UI-level only — package name, permissions, and
 * Settings > Apps listing are unaffected either way, regardless of which identity is active).
 *
 * The decoy is a library, not one fixed identity — same anti-fingerprinting reasoning already
 * applied to the notification icon ([org.offlinemesh.app.ble.MeshService.decoyIcons]/`decoyLabel`):
 * a single fixed "shows as Notes" identity is itself a greppable signature independent of anything
 * else. Which decoy an install gets is picked at random the first time the disguise is ever turned
 * on, then held stable after that (same `"mesh_device"` prefs file, same reasoning as the
 * notification's own stable-per-install pick — an identity that kept changing on the SAME phone
 * would itself be a tell).
 */
object AppIdentity {
    private const val REAL_ALIAS = "org.offlinemesh.app.LauncherReal"
    private val DECOY_ALIASES = listOf(
        "org.offlinemesh.app.LauncherDecoy",
        "org.offlinemesh.app.LauncherDecoy2",
        "org.offlinemesh.app.LauncherDecoy3",
        "org.offlinemesh.app.LauncherDecoy4",
    )

    /** True if *any* decoy alias is currently enabled — which one doesn't matter for this check. */
    fun isDecoyActive(context: Context): Boolean {
        val pm = context.packageManager
        return DECOY_ALIASES.any { alias ->
            pm.getComponentEnabledSetting(ComponentName(context.packageName, alias)) ==
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
    }

    /** [PackageManager.DONT_KILL_APP] is required here — MeshService may be actively running the
     *  mesh when this is tapped, and flipping a cosmetic launcher icon has no reason to interrupt
     *  that (the default behavior without this flag restarts the app's process). */
    fun setDecoyActive(context: Context, active: Boolean) {
        val pm = context.packageManager
        val enabled = PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        val disabled = PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        val chosenAlias = if (active) pickDecoyAlias(context) else null
        pm.setComponentEnabledSetting(
            ComponentName(context.packageName, REAL_ALIAS),
            if (active) disabled else enabled,
            PackageManager.DONT_KILL_APP
        )
        for (alias in DECOY_ALIASES) {
            pm.setComponentEnabledSetting(
                ComponentName(context.packageName, alias),
                if (alias == chosenAlias) enabled else disabled,
                PackageManager.DONT_KILL_APP
            )
        }
    }

    /** Picks one decoy alias at random the first time this is called, then reuses that same
     *  choice forever after — see the class doc for why a stable-per-install pick, not a fresh
     *  random one on every toggle, is what actually matters for the anti-fingerprinting goal. */
    private fun pickDecoyAlias(context: Context): String {
        val prefs = context.getSharedPreferences("mesh_device", Context.MODE_PRIVATE)
        val stored = prefs.getInt("decoy_launcher_index", -1)
        val index = if (stored in DECOY_ALIASES.indices) stored else {
            DECOY_ALIASES.indices.random().also { prefs.edit().putInt("decoy_launcher_index", it).apply() }
        }
        return DECOY_ALIASES[index]
    }
}
