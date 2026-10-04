package org.offlinemesh.app.ui

import android.location.Location
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.offlinemesh.app.ble.BroadcastSosPreview
import org.offlinemesh.app.ble.MeshProtocol
import org.offlinemesh.app.ble.MeshService
import org.offlinemesh.app.ble.PositionTracker
import org.offlinemesh.app.data.AppDatabase
import org.offlinemesh.app.data.SosEntity

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
/** One placed peer on this screen's radar — [ageSeconds] is how old the [PositionTracker.Record]
 *  it was placed from is, and [maxAgeSeconds] is that same record's own staleness budget
 *  (`PositionTracker.effectiveMaxAgeSecondsFor`, decision 33) — both fed to [RadarDot] so
 *  [RadarCanvas] can fade a stale one against its own real budget, not a flat window. */
private data class PlacedPeer(
    val senderId: String,
    val distanceMeters: Float,
    val screenAngleDegrees: Float,
    val ageSeconds: Float,
    val maxAgeSeconds: Float,
)

@Suppress("CyclomaticComplexMethod", "LongMethod")
// a screen-level composable's branches are UI states (no Bluetooth / no GPS fix / low compass
// confidence / empty peer list / active SOS), not tangled logic — matches MainActivity.onCreate's
// identical call on LongMethod for the same reason: comment-dense, explanatory branching here is
// a deliberate choice, not something to fragment across more functions just to satisfy a
// line-count-style metric.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigateScreen(groupId: String, meshService: MeshService?) {
    // Single shared tick (see MeshService.RadarTick's doc) replaces this screen's own polling loop.
    val radarTick = rememberRadarTick(meshService)
    val myLocation = radarTick.location
    val heading = radarTick.headingDegrees
    val compassLowAccuracy = radarTick.compassLowAccuracy
    val positions = remember(radarTick, groupId, meshService) {
        meshService?.positionTracker?.forGroup(groupId) ?: emptyMap()
    }
    val groupPresenceHop = remember(radarTick, groupId, meshService) {
        meshService?.hopToGroupPresence(groupId) ?: MeshProtocol.UNKNOWN_HOP
    }
    // bestActiveSos, not bestActiveSosHop, so sosPreview below can key off the SAME id this hop
    // count came from rather than a second, independent lookup.
    val sosBest = remember(radarTick, groupId, meshService) {
        meshService?.hopTracker?.bestActiveSos(groupId)
    }
    val sosHop = sosBest?.second ?: MeshProtocol.UNKNOWN_HOP
    val bluetoothEnabled by (meshService?.bluetoothEnabled?.collectAsState() ?: remember { mutableStateOf(true) })
    val meshActive by (meshService?.meshActive?.collectAsState() ?: remember { mutableStateOf(true) })

    val groupColor = remember(groupId) { AppColors.colorForGroup(groupId) }

    // Same nickname resolution GroupChatScreen's chat feed already uses for these same senderIds —
    // this list used to always show the raw senderId prefix regardless of a set nickname, an
    // inconsistency with how the same people are labeled one screen over.
    val context = LocalContext.current
    val db = remember { AppDatabase.get(context) }
    val nicknames by db.nicknameDao().observeForGroup(groupId).collectAsState(initial = emptyList())
    val nicknameMap = remember(nicknames) { nicknames.associate { it.senderId to it.username } }
    fun peerLabel(senderId: String): String = nicknameMap[senderId]?.takeIf { it.isNotBlank() } ?: senderId.take(8)

    // SOS itself carries no position — this is a best-effort *enhancement* over the hop-count
    // distance below, not a replacement: if the active SOS's own sender also has a recent,
    // reasonably-accurate position on file (the same PositionTracker data already powering the
    // radar dots above), show real GPS meters instead of a hop count. Falls back to hops
    // whenever that's not resolvable (no fix, no recent position, or the combined GPS accuracy is
    // too rough — placePeerOnRadar's own honesty gate, reused here rather than duplicated).
    val sosList by db.sosDao().observeForGroup(groupId).collectAsState(initial = emptyList())

    // Broadcast-tier SOS content preview (decision 29/30, docs/DECISIONS.md) — unconfirmed until
    // the GATT-authoritative SosEntity for this same id arrives, so hidden the moment that record
    // shows up in sosList rather than sitting alongside (and potentially disagreeing with) the
    // confirmed message already flowing through the group's normal chat feed.
    val sosPreview = remember(sosBest, sosList, meshService) {
        val bestId = sosBest?.first ?: return@remember null
        if (sosList.any { it.id == bestId }) return@remember null
        meshService?.broadcastSosPreview?.forGroupIfBest(groupId, bestId)
    }

    val placedPeers = remember(radarTick, positions, heading) {
        val me = myLocation ?: return@remember emptyList()
        positions.mapNotNull { (senderId, record) ->
            placePeerOnRadar(me.latitude, me.longitude, me.accuracy, record.lat, record.lon, record.accuracyM, heading)
                ?.let {
                    val ageSeconds = (System.currentTimeMillis() / 1000 - record.timestampSec).toFloat()
                    val maxAgeSeconds = PositionTracker.effectiveMaxAgeSecondsFor(record.hop).toFloat()
                    PlacedPeer(senderId, it.distanceMeters, it.screenAngleDegrees, ageSeconds, maxAgeSeconds)
                }
        }
    }

    // Nearest GPS-resolvable active SOS sender, if any — see the doc above sosList for why this
    // is an enhancement over sosHop, not a replacement.
    val sosGpsDistanceMeters = remember(myLocation, positions, sosList, heading) {
        nearestSosGpsDistance(myLocation, positions, sosList, heading)
    }

    // Which currently-plotted dots belong to an active SOS sender — see below for how this is
    // used. Set-membership only, no new data: SosEntity already carries senderId, and a sender
    // only shows up here at all if they're already one of the dots on placedPeers.
    val activeSosSenders = remember(sosList) { sosList.filter { it.ttl > 0 }.map { it.senderId }.toSet() }

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
                MeshPausedNotice()
            } else if (!meshActive) {
                Spacer(Modifier.height(24.dp))
                MeshPausedNotice(
                    title = "Mesh is offline",
                    subtitle = "Turn off Offline mode on Home to resume"
                )
            } else if (myLocation == null) {
                Spacer(Modifier.height(24.dp))
                Text("Waiting for GPS fix…", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Needs a clear-ish sky view. Meanwhile, hop-distance still works over Bluetooth alone:",
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                HopFallback(groupPresenceHop, sosHop, sosPreview)
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
                // A dot belonging to a sender with an active SOS is colored Danger instead of the
                // group color — this is the actual answer to "which one is the person who sent
                // it": the group palette (see Theme.kt) deliberately excludes red for exactly this,
                // so there's never ambiguity between "this group's color happens to be red" and
                // "this is an SOS." With 2+ active SOS, each resolvable sender's own dot turns red
                // at its own real bearing/distance, instead of collapsing to one number below.
                RadarCanvas(
                    dots = placedPeers.map { peer ->
                        val color = if (peer.senderId in activeSosSenders) AppColors.Danger else groupColor
                        RadarDot(
                            color, peer.distanceMeters, peer.screenAngleDegrees, peer.ageSeconds, peer.maxAgeSeconds,
                        )
                    },
                    headingDegrees = heading
                )
                Spacer(Modifier.height(16.dp))
                if (placedPeers.isEmpty()) {
                    Text("No group member's position heard yet", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 160.dp)) {
                        items(placedPeers.sortedBy { it.distanceMeters }) { peer ->
                            ListItem(
                                headlineContent = { Text(peerLabel(peer.senderId)) },
                                supportingContent = { Text("${peer.distanceMeters.toInt()}m away") }
                            )
                        }
                    }
                }
                if (sosHop != MeshProtocol.UNKNOWN_HOP) {
                    Spacer(Modifier.height(8.dp))
                    val sosLabel = sosGpsDistanceMeters?.let { "Active SOS: ${it.toInt()}m away" }
                        ?: "Active SOS: $sosHop hop(s) away"
                    Text(sosLabel, color = AppColors.Danger, style = MaterialTheme.typography.titleMedium)
                    SosPreviewText(sosPreview)
                }
            }
        }
    }
}

/** The nearest GPS distance among currently-relayable SOS whose sender also has a recent, on-file
 *  position — or null if none resolve (no fix, no matching position, or GPS too rough per
 *  [placePeerOnRadar]'s own accuracy gate), in which case the caller falls back to hop-count. */
private fun nearestSosGpsDistance(
    myLocation: Location?,
    positions: Map<String, PositionTracker.Record>,
    sosList: List<SosEntity>,
    headingDegrees: Float,
): Float? {
    val me = myLocation ?: return null
    return sosList.filter { it.ttl > 0 }
        .mapNotNull { sos -> positions[sos.senderId] }
        .mapNotNull { record ->
            val placement = placePeerOnRadar(
                me.latitude, me.longitude, me.accuracy, record.lat, record.lon, record.accuracyM, headingDegrees
            )
            placement?.distanceMeters
        }
        .minOrNull()
}

@Composable
private fun HopFallback(groupHop: Int, sosHop: Int, sosPreview: BroadcastSosPreview.Content?) {
    Text(
        if (groupHop == MeshProtocol.UNKNOWN_HOP) "No group member in range yet" else "$groupHop hop(s) to nearest group member",
        style = MaterialTheme.typography.titleMedium
    )
    if (sosHop != MeshProtocol.UNKNOWN_HOP) {
        Text("Active SOS: $sosHop hop(s) away", color = AppColors.Danger)
        SosPreviewText(sosPreview)
    }
}

/** The broadcast-tier SOS content preview (decision 29/30, `docs/DECISIONS.md`) — shown quoted and
 *  explicitly labeled "unconfirmed" so it reads as distinct from an actual chat-feed SOS message,
 *  which only appears once the GATT-authoritative record (with its own mac/signature) arrives. */
@Composable
private fun SosPreviewText(sosPreview: BroadcastSosPreview.Content?) {
    if (sosPreview == null) return
    Text(
        "“${sosPreview.message}” — unconfirmed preview, connecting to verify",
        style = MaterialTheme.typography.bodySmall, color = AppColors.Danger, textAlign = TextAlign.Center
    )
}
