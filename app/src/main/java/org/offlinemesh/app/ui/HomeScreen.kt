package org.offlinemesh.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import org.offlinemesh.app.ble.MeshProtocol
import org.offlinemesh.app.ble.MeshService
import org.offlinemesh.app.data.GroupEntity
import org.offlinemesh.app.data.GroupRepository

@Suppress("CyclomaticComplexMethod", "LongMethod", "FunctionNaming", "TooGenericExceptionCaught")
// a screen-level composable's branches are UI states (Bluetooth off / mesh offline / waiting for
// GPS / live radar), not tangled logic — matches NavigateScreen's identical suppress combo on
// these same rules for the same reason. FunctionNaming: PascalCase is the established Compose
// convention this whole app uses. TooGenericExceptionCaught: the refresh loop's catch is
// deliberately broad — anything is better caught+logged than left to silently kill the loop.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repo: GroupRepository,
    meshService: MeshService?,
    onAddGroup: () -> Unit,
    onOpenGroup: (String) -> Unit,
    onGeneralSos: () -> Unit,
) {
    val context = LocalContext.current
    val groups by repo.groupDao.observeGroups().collectAsState(initial = emptyList())
    var myLocation by remember { mutableStateOf<Location?>(null) }
    var heading by remember { mutableStateOf(0f) }
    var hopByGroup by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    val bluetoothEnabled by (meshService?.bluetoothEnabled?.collectAsState() ?: remember { mutableStateOf(true) })
    val meshActive by (meshService?.meshActive?.collectAsState() ?: remember { mutableStateOf(true) })

    LaunchedEffect(meshService, groups) {
        while (true) {
            // Caught, not left to propagate — an uncaught exception here would silently kill this
            // whole polling loop forever (LaunchedEffect doesn't restart on its own), leaving the
            // radar frozen until the composable re-keys (e.g. leaving and re-entering the screen).
            try {
                val svc = meshService
                if (svc != null) {
                    myLocation = svc.locationTracker.location.value
                    heading = svc.compassTracker.headingDegrees.value
                    hopByGroup = groups.associate { it.id to svc.hopToGroupPresence(it.id) }
                }
            } catch (e: Exception) {
                // Deliberately broad — anything here is better caught and logged than left to
                // propagate and silently kill this while(true) loop forever (LaunchedEffect
                // doesn't restart itself), which was the whole reason this wrap exists.
                Log.w("HomeScreen", "radar refresh tick failed: ${e.message}")
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
                val ageSeconds = (System.currentTimeMillis() / 1000 - record.timestampSec).toFloat()
                placePeerOnRadar(me.latitude, me.longitude, me.accuracy, record.lat, record.lon, record.accuracyM, heading)
                    ?.let { RadarDot(color, it.distanceMeters, it.screenAngleDegrees, ageSeconds) }
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
                actions = {
                    val isDark = AppColors.isDarkMode
                    IconButton(onClick = { AppColors.setDarkMode(context, !isDark) }) {
                        Icon(
                            if (isDark) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                            contentDescription = if (isDark) "Switch to light theme" else "Switch to dark theme",
                            tint = AppColors.OnSurface
                        )
                    }
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
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Radar (full size — the compact 150dp version tried here previously didn't work
            // out), toggle tiles (SOS included as a 4th, red one — no longer a separate
            // full-width pinned button, which is what actually frees up room for the groups
            // list below), WiFi row, and the groups list all live in ONE LazyColumn: making
            // them plain *items* in the same scrollable list as the groups is what makes them
            // scroll away as you scroll down and reappear when you scroll back to top, using
            // ordinary LazyColumn scroll physics, no custom nested-scroll-connection code.
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                item {
                    if (!bluetoothEnabled) {
                        MeshPausedNotice()
                    } else if (!meshActive) {
                        MeshPausedNotice(
                            title = "Mesh is offline",
                            subtitle = "Turn off Offline mode above to resume"
                        )
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
                }
                item {
                    Column(Modifier.fillMaxWidth()) {
                        Spacer(Modifier.height(16.dp))
                        QuickToggleTiles(meshService, onGeneralSos)
                        Spacer(Modifier.height(10.dp))
                        WifiDirectRow()
                        Spacer(Modifier.height(20.dp))
                    }
                }
                if (groups.isEmpty()) {
                    item {
                        Text(
                            "No groups yet. Tap + to create one or join with a code someone shared.",
                            color = AppColors.OnSurfaceMuted,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    item {
                        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            Text(
                                "Groups",
                                style = MaterialTheme.typography.titleSmall,
                                color = AppColors.OnSurfaceMuted
                            )
                        }
                    }
                    items(groups) { group: GroupEntity ->
                        GroupRow(
                            group = group,
                            hop = hopByGroup[group.id] ?: MeshProtocol.UNKNOWN_HOP,
                            onClick = { onOpenGroup(group.id) }
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
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
 * SOS plus three quick toggles in one row, each a compact tile instead of the full-width
 * descriptive rows this used to be (`PowerSaverRow`/`DisguiseRow`) — same underlying behavior,
 * just less space:
 * - **SOS**: not a toggle — a momentary action tile, always shown in [AppColors.Danger] red,
 *   that fires [onGeneralSos]. Was previously a separate full-width pinned button above this
 *   row; folding it in here instead is what actually frees up screen space for the groups list
 *   below, without giving up "reachable in one tap from anywhere on this screen."
 * - **Power**: off (default) auto-favors responsiveness while the app is open, on pins the
 *   battery-saving tier permanently, even in the foreground.
 * - **Disguise**: switches which launcher `<activity-alias>` entry is active (see [AppIdentity])
 *   — off shows "20.07," on shows one of a small library of plausible decoy identities instead
 *   (picked at random per install, held stable after that), so a glance at the home screen
 *   doesn't reveal this app is installed. See README's Security model for exactly what this does
 *   and does not protect (home screen/app switcher only — not the package name, permissions, or
 *   its entry in Android Settings > Apps).
 * - **Offline**: the mesh's actual on/off switch (see [MeshService.setMeshActive]) — off (default)
 *   is normal operation, on stops both radios and both sensors and removes the persistent
 *   notification entirely. Before this existed there was no way to stop any of that short of
 *   force-stopping the app from Android Settings.
 */
@Suppress("FunctionNaming") // PascalCase is the established Compose convention this whole file
// (and every other screen) already uses for composables — see detekt-baseline.xml, which
// grandfathers this exact violation for every pre-existing composable; same deliberate call here.
@Composable
private fun QuickToggleTiles(meshService: MeshService?, onGeneralSos: () -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SosTile(onClick = onGeneralSos, modifier = Modifier.weight(1f))

        val powerSaverOn by (meshService?.powerSaverForced?.collectAsState() ?: remember { mutableStateOf(false) })
        val powerDesc =
            if (powerSaverOn) "Power saver, on, battery-saving settings always on" else "Power saver, off, auto"
        ToggleTile(
            spec = TileSpec(Icons.Filled.Bolt, "Power", AppColors.Warning),
            active = powerSaverOn,
            contentDescription = powerDesc,
            modifier = Modifier.weight(1f),
            onToggle = { meshService?.setPowerSaverForced(it) }
        )

        var decoyActive by remember { mutableStateOf(AppIdentity.isDecoyActive(context)) }
        val decoyDesc =
            if (decoyActive) "Disguise app icon, on, shows as a decoy app" else "Disguise app icon, off, shows as 20.07"
        ToggleTile(
            spec = TileSpec(Icons.Filled.VisibilityOff, "Disguise", AppColors.Warning),
            active = decoyActive,
            contentDescription = decoyDesc,
            modifier = Modifier.weight(1f),
            onToggle = { AppIdentity.setDecoyActive(context, it); decoyActive = it }
        )

        val meshActive by (meshService?.meshActive?.collectAsState() ?: remember { mutableStateOf(true) })
        val offlineDesc = if (!meshActive) "Mesh offline, radios and sensors stopped" else "Mesh active, off"
        ToggleTile(
            spec = TileSpec(Icons.Filled.BluetoothDisabled, "Offline", AppColors.Warning),
            active = !meshActive,
            contentDescription = offlineDesc,
            modifier = Modifier.weight(1f),
            onToggle = { goOffline -> meshService?.setMeshActive(!goOffline) }
        )
    }
}

/** A tile's static appearance (icon/label/glow color) — separated from its dynamic [ToggleTile]
 *  state (active/description/callback) purely to keep that composable's parameter count small. */
private data class TileSpec(val icon: ImageVector, val label: String, val activeColor: Color)

/** A compact, square, icon+one-word toggle — the shared shape behind every tile in
 *  [QuickToggleTiles]. Compact visually, but not for accessibility: [Modifier.toggleable]'s
 *  `Role.Switch` gives a screen reader the same "this is a switch, here's its state" semantics a
 *  full-size [Switch] would, and [contentDescription] carries the complete sentence the visible
 *  one-word label doesn't have room for. Background/icon color cross-fades between a dim, muted
 *  resting state and [TileSpec.activeColor] via [animateColorAsState] — the "glow when active,
 *  fade when inactive" effect, done with Compose's built-in color animation, no new dependency. */
@Suppress("FunctionNaming") // see QuickToggleTiles' identical suppress above for why
@Composable
private fun ToggleTile(
    spec: TileSpec,
    active: Boolean,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onToggle: (Boolean) -> Unit,
) {
    val dimBg = AppColors.SurfaceVariant
    val dimFg = AppColors.OnSurfaceMuted
    val bg by animateColorAsState(if (active) spec.activeColor.copy(alpha = 0.18f) else dimBg, label = "tileBg")
    val fg by animateColorAsState(if (active) spec.activeColor else dimFg, label = "tileFg")
    // modifier is caller-supplied (RowScope.weight(1f) at each QuickToggleTiles call site) rather
    // than hardcoded here — `weight` is a RowScope/ColumnScope extension that only resolves inside
    // the Row{}/Column{} lambda that actually has that scope receiver, which this function's own
    // body does not.
    Column(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .toggleable(value = active, onValueChange = onToggle, role = Role.Switch)
            .semantics { this.contentDescription = contentDescription },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(spec.icon, contentDescription = null, tint = fg, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(6.dp))
        Text(spec.label, color = fg, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
    }
}

/** SOS as a compact action tile, not a toggle — always solid [AppColors.Danger] red (no dim/glow
 *  state to animate, unlike [ToggleTile]) since this fires an action rather than persisting an
 *  on/off state. Kept as its own composable rather than reusing [ToggleTile] because the toggle
 *  semantics (`Role.Switch`, active/inactive color animation) don't fit a momentary action. */
@Suppress("FunctionNaming") // see QuickToggleTiles' identical suppress above for why
@Composable
private fun SosTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(AppColors.Danger)
            .clickable(onClickLabel = "Send SOS", role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Send SOS to all your groups" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(6.dp))
        Text("SOS", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

/**
 * The WiFi Direct evidence accelerator's opt-in switch — off by default, kept as its own
 * descriptive row (not folded into [QuickToggleTiles]'s compact tiles) because it carries a
 * warning that doesn't fit a one-word tile: see [org.offlinemesh.app.ble.WifiDirectAccelerator]'s
 * class doc for what "experimental" means here specifically — `WifiP2pManager.connect()` may show
 * a system "Invitation to connect" dialog on the OTHER phone, which would visibly break both
 * phones' disguise the moment it fires. This has not been confirmed on real hardware; the warning
 * stays visible regardless of the switch's own state so it's read *before* turning this on, not
 * only after. `NEARBY_WIFI_DEVICES` (Android 13+) is requested only from this row's own permission
 * launcher, the moment the switch is turned on — never at app launch, matching the same
 * on-first-use precedent [Manifest.permission.CAMERA] already follows on the Join screen.
 */
@Suppress("FunctionNaming") // see QuickToggleTiles' identical suppress above for why
@Composable
private fun WifiDirectRow() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(WifiDirectSettings.isEnabled(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            WifiDirectSettings.setEnabled(context, true)
            enabled = true
        }
        // Denied: the switch simply doesn't turn on, no further nagging — matches this app's
        // conservative permission philosophy (see AddGroupScreen's camera flow).
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.Surface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val speedTint = if (enabled) AppColors.Warning else AppColors.OnSurfaceMuted
        Icon(Icons.Filled.Speed, contentDescription = null, tint = speedTint)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Speed up large file transfers",
                color = AppColors.OnSurface,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Experimental — the other phone may show a connection prompt",
                color = AppColors.Warning,
                style = MaterialTheme.typography.labelSmall
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = { turnOn ->
                handleWifiDirectToggle(turnOn, context, permissionLauncher) { enabled = it }
            },
            colors = SwitchDefaults.colors(
                checkedTrackColor = AppColors.Warning, checkedThumbColor = Color.White,
                uncheckedThumbColor = AppColors.OnSurfaceMuted,
                uncheckedTrackColor = Color(0xFF3A4149),
                uncheckedBorderColor = Color(0xFF3A4149)
            )
        )
    }
}

private fun handleWifiDirectToggle(
    turnOn: Boolean,
    context: android.content.Context,
    permissionLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    setEnabled: (Boolean) -> Unit,
) {
    if (!turnOn) {
        WifiDirectSettings.setEnabled(context, false)
        setEnabled(false)
        return
    }
    val grantState = ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES)
    val notYetGranted = grantState != PackageManager.PERMISSION_GRANTED
    val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && notYetGranted
    if (needsPermission) {
        permissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
    } else {
        WifiDirectSettings.setEnabled(context, true)
        setEnabled(true)
    }
}
