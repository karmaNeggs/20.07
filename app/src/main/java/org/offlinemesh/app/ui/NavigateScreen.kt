package org.offlinemesh.app.ui

import android.location.Location
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.offlinemesh.app.ble.MeshProtocol
import org.offlinemesh.app.ble.MeshService
import org.offlinemesh.app.ble.PositionTracker

/**
 * "Forward-up" navigation radar: peers are placed at their real bearing/distance (GPS,
 * android.location.Location.distanceBetween), then rotated by the phone's own compass heading
 * so "up" on screen means "in front of you right now" — walk toward a dot, don't read a map.
 * That's deliberately worth the compass's real weakness (magnetometers drift near barricades,
 * vehicles, rebar) for this use case: under stress, "walk toward the dot" beats "translate a
 * north-up map in your head," so we show a low-confidence warning instead of hiding the arrow.
 *
 * Falls back to hop-count hot/cold — no direction, just closer/farther — when there's no GPS
 * fix at all (no signal, indoors, still acquiring, permission denied). That fallback needs only
 * Bluetooth, no GPS and no compass.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigateScreen(groupId: String, meshService: MeshService?) {
    var myLocation by remember { mutableStateOf<Location?>(null) }
    var positions by remember { mutableStateOf<Map<String, PositionTracker.Record>>(emptyMap()) }
    var heading by remember { mutableStateOf(0f) }
    var compassLowAccuracy by remember { mutableStateOf(false) }
    var groupPresenceHop by remember { mutableStateOf(MeshProtocol.UNKNOWN_HOP) }
    var sosHop by remember { mutableStateOf(MeshProtocol.UNKNOWN_HOP) }
    val bluetoothEnabled by (meshService?.bluetoothEnabled?.collectAsState() ?: remember { mutableStateOf(true) })

    val groupColor = remember(groupId) { AppColors.colorForGroup(groupId) }

    LaunchedEffect(groupId, meshService) {
        while (true) {
            val svc = meshService
            if (svc != null) {
                myLocation = svc.locationTracker.location.value
                positions = svc.positionTracker.forGroup(groupId)
                heading = svc.compassTracker.headingDegrees.value
                compassLowAccuracy = svc.compassTracker.lowAccuracy.value
                groupPresenceHop = svc.hopToGroupPresence(groupId)
                sosHop = svc.hopTracker.bestActiveSosHop(groupId)
            }
            delay(1000)
        }
    }

    val placedPeers = remember(myLocation, positions, heading) {
        val me = myLocation ?: return@remember emptyList()
        positions.mapNotNull { (senderId, record) ->
            placePeerOnRadar(me.latitude, me.longitude, me.accuracy, record.lat, record.lon, record.accuracyM, heading)
                ?.let { Triple(senderId, it.distanceMeters, it.screenAngleDegrees) }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Navigate") }, colors = flushTopAppBarColors()) },
        containerColor = AppColors.Background
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!bluetoothEnabled) {
                Spacer(Modifier.height(24.dp))
                BluetoothOffNotice()
            } else if (myLocation == null) {
                Spacer(Modifier.height(24.dp))
                Text("Waiting for GPS fix…", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Needs a clear-ish sky view. Meanwhile, hop-distance still works over Bluetooth alone:",
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                HopFallback(groupPresenceHop, sosHop)
            } else {
                Text("Hold the phone flat — dots show which way to walk.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                if (compassLowAccuracy) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Compass confidence is low — move away from metal, or wave the phone in a figure-8 to recalibrate.",
                        style = MaterialTheme.typography.bodySmall, color = AppColors.Warning, textAlign = TextAlign.Center
                    )
                }
                Spacer(Modifier.height(12.dp))
                RadarCanvas(
                    dots = placedPeers.map { (_, dist, angle) -> RadarDot(groupColor, dist, angle) },
                    headingDegrees = heading
                )
                Spacer(Modifier.height(16.dp))
                if (placedPeers.isEmpty()) {
                    Text("No group member's position heard yet", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 160.dp)) {
                        items(placedPeers.sortedBy { it.second }) { (senderId, dist, _) ->
                            ListItem(
                                headlineContent = { Text(senderId.take(8)) },
                                supportingContent = { Text("${dist.toInt()}m away") }
                            )
                        }
                    }
                }
                if (sosHop != MeshProtocol.UNKNOWN_HOP) {
                    Spacer(Modifier.height(8.dp))
                    Text("Active SOS: $sosHop hop(s) away", color = AppColors.Danger, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun HopFallback(groupHop: Int, sosHop: Int) {
    Text(
        if (groupHop == MeshProtocol.UNKNOWN_HOP) "No group member in range yet" else "$groupHop hop(s) to nearest group member",
        style = MaterialTheme.typography.titleMedium
    )
    if (sosHop != MeshProtocol.UNKNOWN_HOP) {
        Text("Active SOS: $sosHop hop(s) away", color = AppColors.Danger)
    }
}
