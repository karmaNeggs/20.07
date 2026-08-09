package org.offlinemesh.app.ui

import org.offlinemesh.app.BuildConfig
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
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
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import org.offlinemesh.app.ble.MeshProtocol
import org.offlinemesh.app.ble.MeshService
import org.offlinemesh.app.ble.PositionTracker
import org.offlinemesh.app.diagnostics.DiagnosticsLog
import org.offlinemesh.app.data.GroupEntity
import org.offlinemesh.app.data.GroupRepository

@Suppress("CyclomaticComplexMethod", "LongMethod")
// a screen-level composable's branches are UI states (Bluetooth off / mesh offline / waiting for
// GPS / live radar), not tangled logic — matches NavigateScreen's identical suppress combo on
// these same rules for the same reason.
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
    // Single shared tick (see MeshService.RadarTick's doc) replaces this screen's own polling
    // loop — location/heading/hop/position derivations below now recompute on every real change
    // AND on the underlying 1s tick, so a quiet peer's staleness is always re-evaluated instead of
    // depending on incidental compass jitter to trigger recomposition (that dependency was itself
    // a latent bug: this file's dots computation didn't key on positionTracker at all before).
    val radarTick = rememberRadarTick(meshService)
    val myLocation = radarTick.location
    val heading = radarTick.headingDegrees
    val bluetoothEnabled by (meshService?.bluetoothEnabled?.collectAsState() ?: remember { mutableStateOf(true) })
    val meshActive by (meshService?.meshActive?.collectAsState() ?: remember { mutableStateOf(true) })

    val hopByGroup = remember(radarTick, groups, meshService) {
        val svc = meshService ?: return@remember emptyMap()
        groups.associate { it.id to svc.hopToGroupPresence(it.id) }
    }

    val dots = remember(radarTick, groups, meshService) {
        val me = myLocation
        val svc = meshService
        if (me == null || svc == null) {
            emptyList()
        } else {
            groups.flatMap { g ->
                val color = AppColors.colorForGroup(g.id)
                val records = svc.positionTracker.forGroup(g.id)
                records.mapNotNull { (_, record) ->
                    val ageSeconds = (System.currentTimeMillis() / 1000 - record.timestampSec).toFloat()
                    val maxAgeSeconds = PositionTracker.effectiveMaxAgeSecondsFor(record.hop).toFloat()
                    val placed = placePeerOnRadar(
                        me.latitude, me.longitude, me.accuracy, record.lat, record.lon, record.accuracyM, heading
                    )
                    placed?.let { RadarDot(color, it.distanceMeters, it.screenAngleDegrees, ageSeconds, maxAgeSeconds) }
                }
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
                        DiagnosticsExportRow()
                        Spacer(Modifier.height(10.dp))
                        BitchatSpikeRow()
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
    // Only re-evaluated when this row recomposes (a DB change, navigation, etc.), not on a live
    // per-second tick — unlike the radar's staleness, an hours-to-days expiry doesn't need that,
    // and adding a timer just for this would be a real periodic cost for a value nobody needs
    // updated mid-glance.
    val remainingMs = group.expiresAt - System.currentTimeMillis()
    val expiringSoon = remainingMs in 0..EXPIRING_SOON_THRESHOLD_MS
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
        Column(horizontalAlignment = Alignment.End) {
            Text(
                if (hop == MeshProtocol.UNKNOWN_HOP) "no one nearby" else "$hop hop(s) away",
                color = AppColors.OnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "expires in ${formatTimeRemaining(remainingMs)}",
                color = if (expiringSoon) AppColors.Warning else AppColors.OnSurfaceMuted,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

private const val EXPIRING_SOON_THRESHOLD_MS = 2 * 60 * 60 * 1000L // 2 hours

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

/** Debug-only "export the mesh event log" affordance — invisible in release builds, where
 *  [DiagnosticsLog] is a no-op and writes nothing (see its class doc: a durable log on disk would
 *  undercut this app's "nothing persisted to find on a seized phone" property, so it exists only
 *  behind the same debug boundary LeakCanary already sits behind). Shares via the FileProvider
 *  already used for evidence files, so the log can go straight to Drive/email with no cable.
 *  Long-press clears it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiagnosticsExportRow() {
    if (!BuildConfig.DEBUG) return
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(AppColors.Surface)
            .combinedClickable(
                onClick = {
                    val file = DiagnosticsLog.exportFile(context)
                    if (file == null) {
                        Toast.makeText(context, "No diagnostics recorded yet", Toast.LENGTH_SHORT).show()
                    } else {
                        shareDiagnostics(context, file)
                    }
                },
                onLongClick = {
                    DiagnosticsLog.clear(context)
                    Toast.makeText(context, "Diagnostics cleared", Toast.LENGTH_SHORT).show()
                }
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.BugReport, contentDescription = null, tint = AppColors.OnSurfaceMuted)
        Spacer(Modifier.width(10.dp))
        Column {
            Text("Export diagnostics", color = AppColors.OnSurface, style = MaterialTheme.typography.bodyMedium)
            Text(
                "debug build only \u2022 long-press to clear",
                color = AppColors.OnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/** P7 spike trigger (`PLAN-v2.md` Part 7, `docs/DECISIONS.md` decision 51's own "hard dependency,
 *  not skippable" note; decision 55 for this tool itself) — debug-only, same boundary
 *  [DiagnosticsExportRow] sits behind. One tap: scan for a real bitchat node nearby, write one
 *  forged test packet, report success/failure. NOT the real bridge — no production wiring, see
 *  [org.offlinemesh.app.bitchatbridge.BitchatSpikeTransport]'s own class doc for exactly what a
 *  success here does and doesn't confirm. */
@Composable
private fun BitchatSpikeRow() {
    if (!BuildConfig.DEBUG) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AppColors.Surface)
            .clickable(enabled = !running) {
                running = true
                scope.launch {
                    val marker = org.offlinemesh.app.bitchatbridge.BitchatSpikeTransport(context).sendTestPacket()
                    running = false
                    val message = if (marker != null) {
                        "Sent, marker=$marker — check DiagnosticsLog / a capture for propagation"
                    } else {
                        "Failed — see DiagnosticsLog (tag bitchat-spike) for why"
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Bolt, contentDescription = null, tint = AppColors.OnSurfaceMuted)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                if (running) "Sending…" else "Bitchat spike: send test packet",
                color = AppColors.OnSurface, style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "debug build only • P7 validation, not the real bridge",
                color = AppColors.OnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

// Broad catch: the share chooser can fail with ActivityNotFound, Security, or IllegalArgument
// depending on what the user has installed and how the OEM handles FileProvider grants — all of
// them mean the same thing here ("couldn't hand the file off"), and none should crash the app.
@Suppress("TooGenericExceptionCaught")
private fun shareDiagnostics(context: android.content.Context, file: java.io.File) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "20.07 mesh diagnostics")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Send diagnostics"))
    } catch (e: Exception) {
        Toast.makeText(context, "Couldn't share diagnostics: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
