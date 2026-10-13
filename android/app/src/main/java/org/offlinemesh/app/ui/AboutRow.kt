package org.offlinemesh.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.offlinemesh.app.BuildConfig
import java.security.MessageDigest

private const val SHORT_GROUPS = 4
private const val HEX_MASK = 0xFF

/** SHA-256 of the certificate this installed APK is signed with, as colon-separated hex; null if unreadable. */
@Suppress("SwallowedException", "DEPRECATION") // an unreadable signature is shown as "unavailable"
internal fun signingFingerprint(context: Context): String? = try {
    val pm = context.packageManager
    val certBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            .signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
    } else {
        pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()?.toByteArray()
    }
    certBytes?.let {
        MessageDigest.getInstance("SHA-256").digest(it).joinToString(":") { b -> "%02X".format(b.toInt() and HEX_MASK) }
    }
} catch (e: PackageManager.NameNotFoundException) {
    null
}

/**
 * Version and signing fingerprint, so a sideloaded install can be checked against the fingerprint published on the
 * project website, offline. Tap to show the full value; tap the full value to copy it.
 */
@Composable
internal fun AboutRow() {
    val context = LocalContext.current
    val fingerprint = remember { signingFingerprint(context) }
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).clickable {
            if (expanded && fingerprint != null) {
                val clip = ClipData.newPlainText("20.07 signing fingerprint", fingerprint)
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                Toast.makeText(context, "Fingerprint copied", Toast.LENGTH_SHORT).show()
            }
            expanded = !expanded
        }
    ) {
        val short = fingerprint?.split(":")?.take(SHORT_GROUPS)?.joinToString(":")?.let { "$it…" } ?: "unavailable"
        Text(
            "Version ${BuildConfig.VERSION_NAME} · signed ${if (expanded) "(tap again to copy)" else short}",
            color = AppColors.OnSurfaceMuted, style = MaterialTheme.typography.labelSmall
        )
        if (expanded) {
            Text(
                fingerprint ?: "Signature could not be read",
                color = AppColors.OnSurfaceMuted, style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace
            )
            Text(
                "Compare with the fingerprint on rescue2007.com/verify.html. A debug build shows a different value.",
                color = AppColors.OnSurfaceMuted, style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
