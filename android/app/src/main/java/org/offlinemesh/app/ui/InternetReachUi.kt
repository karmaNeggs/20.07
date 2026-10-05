@file:Suppress("MatchingDeclarationName") // several small, related declarations; the file is named for the feature

package org.offlinemesh.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.offlinemesh.app.ble.MeshService
import org.offlinemesh.app.data.GroupEntity
import org.offlinemesh.app.gateway.InternetReachRuntime

// The Internet-reach switch, its status line and the "Last seen" list (decisions 68, 73), kept out of HomeScreen.kt
// so that file stays within the project's function-count limit.

/** One "last seen" line: who, how long ago, and roughly where (distance and compass direction from this phone). */
internal data class LastSeenRow(val color: Color, val name: String, val ageSeconds: Long, val where: String?)

private const val LAST_SEEN_MAX_ROWS = 8
private val DOT_SIZE = 10.dp
private const val OLD_DOT_ALPHA = 0.5f
private const val SEPARATOR = " \u2022 "
private val COMPASS_POINTS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
private const val DEGREES_PER_COMPASS_POINT = 45f
private const val FULL_CIRCLE_DEGREES = 360f
private const val HALF = 2f
private const val MS_PER_SEC = 1000L
private const val METERS_PER_KM = 1000f
private const val SECONDS_PER_MINUTE = 60L
private const val MINUTES_PER_HOUR = 60L

internal fun buildLastSeenRows(
    svc: MeshService,
    groups: List<GroupEntity>,
    me: android.location.Location?,
    nicknames: Map<String, String>,
): List<LastSeenRow> {
    val nowSec = System.currentTimeMillis() / MS_PER_SEC
    val rows = groups.flatMap { g ->
        svc.positionTracker.lastSeenForGroup(g.id).map { (senderId, record) ->
            val name = nicknames["${g.id}:$senderId"] ?: "Group member"
            val where = me?.let { describeWhere(it, record.lat, record.lon) }
            LastSeenRow(AppColors.colorForGroup(g.id), name, nowSec - record.timestampSec, where)
        }
    }
    return rows.sortedBy { it.ageSeconds }.take(LAST_SEEN_MAX_ROWS)
}

private fun describeWhere(me: android.location.Location, lat: Double, lon: Double): String {
    val out = FloatArray(2)
    android.location.Location.distanceBetween(me.latitude, me.longitude, lat, lon, out)
    val bearing = (out[1] + FULL_CIRCLE_DEGREES) % FULL_CIRCLE_DEGREES
    val index = ((bearing + DEGREES_PER_COMPASS_POINT / HALF) / DEGREES_PER_COMPASS_POINT).toInt()
    val point = COMPASS_POINTS[index % COMPASS_POINTS.size]
    val distance = if (out[0] >= METERS_PER_KM) "%.1f km".format(out[0] / METERS_PER_KM) else "${out[0].toInt()} m"
    return "$distance $point"
}

private fun ageText(seconds: Long): String {
    val minutes = (seconds / SECONDS_PER_MINUTE).coerceAtLeast(0)
    return when {
        minutes < 1 -> "just now"
        minutes < MINUTES_PER_HOUR -> "$minutes min ago"
        else -> "${minutes / MINUTES_PER_HOUR} h ${minutes % MINUTES_PER_HOUR} min ago"
    }
}

@Composable
internal fun LastSeenList(rows: List<LastSeenRow>) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text("Last seen", color = AppColors.OnSurface, style = MaterialTheme.typography.titleSmall)
        for (row in rows) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(DOT_SIZE).clip(CircleShape).background(row.color.copy(alpha = OLD_DOT_ALPHA)))
                Spacer(Modifier.width(8.dp))
                Text(
                    listOfNotNull(row.name, ageText(row.ageSeconds), row.where).joinToString(SEPARATOR),
                    color = AppColors.OnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** The single "Internet reach" switch (decision 68), off by default. Turning it ON asks first, in plain words. */
@Composable
internal fun InternetReachTile(meshService: MeshService?, modifier: Modifier) {
    val reach = meshService?.internetReach
    val on by (reach?.settings?.enabled?.collectAsState() ?: remember { mutableStateOf(false) })
    var askConsent by remember { mutableStateOf(false) }
    ToggleTile(
        spec = TileSpec(Icons.Filled.Public, "Internet", AppColors.Accent),
        active = on,
        contentDescription = if (on) "Internet reach, on" else "Internet reach, off",
        modifier = modifier,
        onToggle = { wantOn -> if (wantOn) askConsent = true else reach?.settings?.setEnabled(false) },
    )
    if (askConsent) {
        AlertDialog(
            onDismissRequest = { askConsent = false },
            title = { Text("Reach beyond Bluetooth?") },
            text = {
                Text(
                    "When this is on, your phone can send your group's messages and live locations over the " +
                        "internet (Wi-Fi or mobile data), and can carry them for group members who have no " +
                        "signal. It works even with Bluetooth off.\n\n" +
                        "Everything stays encrypted for your group. The public relay servers it uses only see " +
                        "scrambled data, your phone's internet address and the timing.\n\n" +
                        "Your mates need this on too. It uses some mobile data and battery. " +
                        "You can turn it off any time.",
                )
            },
            confirmButton = {
                TextButton(onClick = { reach?.settings?.setEnabled(true); askConsent = false }) { Text("Turn on") }
            },
            dismissButton = { TextButton(onClick = { askConsent = false }) { Text("Not now") } },
        )
    }
}

/** One quiet line under the tiles saying what Internet reach is doing, shown only while it is switched on. */
@Composable
internal fun InternetReachStatusLine(meshService: MeshService?) {
    val reach = meshService?.internetReach ?: return
    val on by reach.settings.enabled.collectAsState()
    val status by reach.status.collectAsState()
    if (!on) return
    val text = when (status) {
        InternetReachRuntime.Status.ACTIVE -> "Internet reach: connected, relaying for your group"
        InternetReachRuntime.Status.CONNECTING -> "Internet reach: connecting\u2026"
        InternetReachRuntime.Status.WAITING_FOR_NETWORK -> "Internet reach: waiting for Wi-Fi or mobile data"
        InternetReachRuntime.Status.OFFLINE_MODE -> "Internet reach: paused by Offline mode"
        InternetReachRuntime.Status.OFF -> return
    }
    Text(
        text,
        modifier = Modifier.padding(top = 8.dp),
        color = AppColors.OnSurfaceMuted,
        style = MaterialTheme.typography.bodySmall,
    )
}

