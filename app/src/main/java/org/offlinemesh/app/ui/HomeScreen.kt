package org.offlinemesh.app.ui

import android.location.Location
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.offlinemesh.app.ble.MeshProtocol
import org.offlinemesh.app.ble.MeshService
import org.offlinemesh.app.data.GroupEntity
import org.offlinemesh.app.data.GroupRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repo: GroupRepository,
    meshService: MeshService?,
    onAddGroup: () -> Unit,
    onOpenGroup: (String) -> Unit,
    onGeneralSos: () -> Unit,
) {
    val groups by repo.groupDao.observeGroups().collectAsState(initial = emptyList())
    var myLocation by remember { mutableStateOf<Location?>(null) }
    var heading by remember { mutableStateOf(0f) }
    var hopByGroup by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    val bluetoothEnabled by (meshService?.bluetoothEnabled?.collectAsState() ?: remember { mutableStateOf(true) })

    LaunchedEffect(meshService, groups) {
        while (true) {
            val svc = meshService
            if (svc != null) {
                myLocation = svc.locationTracker.location.value
                heading = svc.compassTracker.headingDegrees.value
                hopByGroup = groups.associate { it.id to svc.hopToGroupPresence(it.id) }
            }
            delay(1000)
        }
    }

    val dots = remember(myLocation, heading, groups) {
        val me = myLocation
        val svc = meshService
        if (me == null || svc == null) emptyList() else groups.flatMap { g ->
            val color = AppColors.colorForGroup(g.id)
            svc.positionTracker.forGroup(g.id).mapNotNull { (_, record) ->
                placePeerOnRadar(me.latitude, me.longitude, me.accuracy, record.lat, record.lon, record.accuracyM, heading)
                    ?.let { RadarDot(color, it.distanceMeters, it.screenAngleDegrees) }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "20.07",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Black,
                        fontSize = 34.sp,
                        letterSpacing = (-1).sp
                    )
                },
                colors = flushTopAppBarColors()
            )
        },
        containerColor = AppColors.Background,
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddGroup,
                containerColor = AppColors.Accent,
                contentColor = Color.White
            ) { Icon(Icons.Filled.Add, contentDescription = "Add group") }
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(12.dp))

            if (!bluetoothEnabled) {
                BluetoothOffNotice()
            } else if (myLocation == null) {
                Box(
                    Modifier.size(260.dp).clip(RoundedCornerShape(20.dp)).background(AppColors.Surface),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Waiting for GPS fix…", color = AppColors.OnSurfaceMuted)
                }
            } else {
                RadarCanvas(dots = dots, headingDegrees = heading)
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = onGeneralSos,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Danger),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) { Text("SOS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }

            Spacer(Modifier.height(14.dp))
            PowerSaverRow(meshService)

            Spacer(Modifier.height(28.dp))

            if (groups.isEmpty()) {
                Spacer(Modifier.height(40.dp))
                Text(
                    "No groups yet. Tap + to create one or join with a code someone shared.",
                    color = AppColors.OnSurfaceMuted,
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Text("Groups", style = MaterialTheme.typography.titleSmall, color = AppColors.OnSurfaceMuted)
                }
                LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(groups) { group: GroupEntity ->
                        GroupRow(
                            group = group,
                            hop = hopByGroup[group.id] ?: MeshProtocol.UNKNOWN_HOP,
                            onClick = { onOpenGroup(group.id) }
                        )
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }
}

@Composable
private fun GroupRow(group: GroupEntity, hop: Int, onClick: () -> Unit) {
    val color = AppColors.colorForGroup(group.id)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(50))
                .background(color)
        )
        Spacer(Modifier.width(14.dp))
        Text(
            group.name,
            color = AppColors.OnSurface,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            if (hop == MeshProtocol.UNKNOWN_HOP) "no one nearby" else "$hop hop(s) away",
            color = AppColors.OnSurfaceMuted,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/**
 * Off (default): the app automatically favors responsiveness while you have it open and
 * battery-saving settings the rest of the time. On: pins the battery-saving tier permanently,
 * even while you're actively using the app — for when you know you're low and want to trade
 * responsiveness for runtime no matter what you're doing.
 */
@Composable
private fun PowerSaverRow(meshService: MeshService?) {
    val forced by (meshService?.powerSaverForced?.collectAsState() ?: remember { mutableStateOf(false) })
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.Surface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Bolt, contentDescription = null, tint = if (forced) AppColors.Warning else AppColors.Safe)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Power saver", color = AppColors.OnSurface, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (forced) "Battery-saving settings always on" else "Auto — full power while app is open",
                color = AppColors.OnSurfaceMuted, style = MaterialTheme.typography.labelSmall
            )
        }
        Switch(
            checked = forced,
            onCheckedChange = { meshService?.setPowerSaverForced(it) },
            colors = SwitchDefaults.colors(
                checkedTrackColor = AppColors.Warning, checkedThumbColor = Color.White,
                // Explicit off-state colors — left to Material3's defaults these come out too close
                // to AppColors.Surface (this row's own background) in a heavily-customized dark
                // scheme, making the toggle hard to see when off. A lighter, purpose-picked gray for
                // both track and thumb keeps it legible without touching the on-state warning color.
                uncheckedThumbColor = AppColors.OnSurfaceMuted,
                uncheckedTrackColor = Color(0xFF3A4149),
                uncheckedBorderColor = Color(0xFF3A4149)
            )
        )
    }
}
