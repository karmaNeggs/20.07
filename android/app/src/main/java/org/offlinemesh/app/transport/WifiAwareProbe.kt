package org.offlinemesh.app.transport

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.aware.Characteristics
import android.net.wifi.aware.WifiAwareManager
import android.os.Build
import org.offlinemesh.app.diagnostics.DiagnosticsLog

/**
 * v3 groundwork (`docs/DECISIONS.md` decision 65): what Wi-Fi Aware can this phone actually do?
 * Read-only capability check, no radio session is opened, nothing is sent. Whether v3 is worth
 * building depends on how many of the real test phones answer "yes" here, so this exists before any
 * transport code does.
 *
 * Debug-only at the call site (HomeScreen's row sits behind `BuildConfig.DEBUG`); the report text
 * contains device model and radio limits only, no identifiers of peers, groups or positions.
 */
object WifiAwareProbe {

    /** Everything the report prints, as plain values so [format] is unit-testable without a phone.
     *  Null means "this Android version can't answer", not "no". */
    data class Snapshot(
        val model: String,
        val sdkInt: Int,
        val hasFeature: Boolean,
        val available: Boolean?,
        val maxDataPaths: Int?,
        val maxDataInterfaces: Int?,
        val maxPublishSessions: Int?,
        val maxSubscribeSessions: Int?,
        val pairingSupported: Boolean?,
        val instantModeSupported: Boolean?,
        val cipherSuites: Int?,
        val error: String?
    )

    fun probe(context: Context): Snapshot {
        val hasFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
        val base = Snapshot(
            model = "${Build.MANUFACTURER} ${Build.MODEL}",
            sdkInt = Build.VERSION.SDK_INT,
            hasFeature = hasFeature,
            available = null, maxDataPaths = null, maxDataInterfaces = null,
            maxPublishSessions = null, maxSubscribeSessions = null,
            pairingSupported = null, instantModeSupported = null, cipherSuites = null, error = null
        )
        return if (hasFeature) readManager(context, base) else base
    }

    private fun readManager(context: Context, base: Snapshot): Snapshot {
        val manager = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
            ?: return base.copy(error = "WifiAwareManager unavailable")
        return try {
            val chars: Characteristics? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) manager.characteristics else null
            base.copy(
                available = manager.isAvailable,
                maxDataPaths = chars?.numberOfSupportedDataPaths,
                maxDataInterfaces = chars?.numberOfSupportedDataInterfaces,
                maxPublishSessions = chars?.numberOfSupportedPublishSessions,
                maxSubscribeSessions = chars?.numberOfSupportedSubscribeSessions,
                pairingSupported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    chars?.isAwarePairingSupported
                } else {
                    null
                },
                instantModeSupported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    chars?.isInstantCommunicationModeSupported
                } else {
                    null
                },
                cipherSuites = chars?.supportedCipherSuites
            )
        } catch (e: SecurityException) {
            base.copy(error = "SecurityException: ${e.message}")
        }
    }

    /** Probes, records one line in [DiagnosticsLog] (tag `wifi-aware`), returns the full report. */
    fun report(context: Context): String {
        val text = format(probe(context))
        DiagnosticsLog.event("wifi-aware", text.replace('\n', ' '))
        return text
    }

    fun format(s: Snapshot): String = buildString {
        appendLine("Device: ${s.model} (Android SDK ${s.sdkInt})")
        appendLine("Wi-Fi Aware hardware/feature: ${yn(s.hasFeature)}")
        if (!s.hasFeature) {
            append("Verdict: NOT SUPPORTED, BLE only on this phone")
            return@buildString
        }
        appendLine("Available right now: ${yn(s.available)}")
        appendLine("Data paths / interfaces: ${s.maxDataPaths ?: "?"} / ${s.maxDataInterfaces ?: "?"}")
        appendLine("Publish / subscribe sessions: ${s.maxPublishSessions ?: "?"} / ${s.maxSubscribeSessions ?: "?"}")
        appendLine("Aware 4.0 pairing: ${yn(s.pairingSupported)}")
        appendLine("Instant communication mode: ${yn(s.instantModeSupported)}")
        appendLine("Cipher suites bitmask: ${s.cipherSuites ?: "?"}")
        s.error?.let { appendLine("Error: $it") }
        append(
            when {
                s.error != null -> "Verdict: UNKNOWN, see error"
                s.available == false -> "Verdict: SUPPORTED but off/busy now (Wi-Fi off, or location/hotspot conflict)"
                else -> "Verdict: SUPPORTED, usable for v3"
            }
        )
    }

    private fun yn(v: Boolean?): String = when (v) {
        true -> "yes"
        false -> "no"
        null -> "unknown on this Android version"
    }
}
