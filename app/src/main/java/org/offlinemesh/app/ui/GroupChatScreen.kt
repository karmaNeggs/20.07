// Compose screens naturally decompose into many small, single-purpose composables (P5 slice 1,
// docs/DECISIONS.md decision 45, added FeedThumbnail/FeedRowHeader/feedRowClickAction/fileBodyText
// specifically to keep FeedRow itself within its own length/complexity limits) — a flat function
// count threshold tuned for business-logic classes doesn't fit this file's shape.
@file:Suppress("TooManyFunctions")

package org.offlinemesh.app.ui

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.offlinemesh.app.ble.MeshFrameCodec
import org.offlinemesh.app.ble.MeshProtocol
import org.offlinemesh.app.ble.MeshService
import org.offlinemesh.app.ble.PositionTracker
import org.offlinemesh.app.ble.RelayEngine
import org.offlinemesh.app.data.AppDatabase
import org.offlinemesh.app.data.EvidenceEntity
import org.offlinemesh.app.data.GroupRepository
import org.offlinemesh.app.data.JoinCode
import org.offlinemesh.app.data.SosEntity
import org.offlinemesh.app.evidence.EvidenceCapture
import java.text.DateFormat
import java.util.Date

private sealed class FeedItem(val timestamp: Long) {
    class Message(val sos: SosEntity) : FeedItem(sos.timestamp)
    class File(val evidence: EvidenceEntity, val receivedChunks: Int) : FeedItem(evidence.timestamp)
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("CyclomaticComplexMethod", "LongMethod")
// a screen-level composable's branches are UI states, not tangled logic — same reasoning already
// applied to HomeScreen/NavigateScreen.
@Composable
fun GroupChatScreen(
    groupId: String,
    repo: GroupRepository,
    meshService: MeshService?,
    onExpandRadar: () -> Unit,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
    val db = remember { AppDatabase.get(context) }
    val scope = rememberCoroutineScope()
    val groupColor = remember(groupId) { AppColors.colorForGroup(groupId) }

    var groupName by remember { mutableStateOf("") }
    var messageText by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var inviteCode by remember { mutableStateOf<String?>(null) }
    var myNickname by remember { mutableStateOf<String?>(null) }
    var showNicknameDialog by remember { mutableStateOf(false) }
    var nicknameInput by remember { mutableStateOf("") }

    LaunchedEffect(groupId, meshService) { myNickname = meshService?.myNickname(groupId)?.username }

    // Single shared tick (see MeshService.RadarTick's doc) replaces this screen's own polling loop.
    val radarTick = rememberRadarTick(meshService)
    val myLocation = radarTick.location
    val heading = radarTick.headingDegrees
    val bluetoothEnabled by (meshService?.bluetoothEnabled?.collectAsState() ?: remember { mutableStateOf(true) })
    val meshActive by (meshService?.meshActive?.collectAsState() ?: remember { mutableStateOf(true) })

    LaunchedEffect(groupId) { groupName = repo.groupDao.getGroup(groupId)?.name ?: groupId }

    val radarDots = remember(radarTick, groupId, meshService, groupColor) {
        val svc = meshService ?: return@remember emptyList()
        val me = myLocation ?: return@remember emptyList()
        svc.positionTracker.forGroup(groupId).mapNotNull { (_, record) ->
            val ageSeconds = (System.currentTimeMillis() / 1000 - record.timestampSec).toFloat()
            val maxAgeSeconds = PositionTracker.effectiveMaxAgeSecondsFor(record.hop).toFloat()
            placePeerOnRadar(
                me.latitude, me.longitude, me.accuracy,
                record.lat, record.lon, record.accuracyM, heading
            )?.let { RadarDot(groupColor, it.distanceMeters, it.screenAngleDegrees, ageSeconds, maxAgeSeconds) }
        }
    }

    val sosList by db.sosDao().observeForGroup(groupId).collectAsState(initial = emptyList())
    val evidenceList by db.evidenceDao().observeForGroup(groupId).collectAsState(initial = emptyList())
    val nicknames by db.nicknameDao().observeForGroup(groupId).collectAsState(initial = emptyList())
    val nicknameMap = remember(nicknames) { nicknames.associate { it.senderId to it.username } }
    var chunkCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

    LaunchedEffect(evidenceList, meshService) {
        val svc = meshService ?: return@LaunchedEffect
        while (true) {
            // decision 47 (docs/DECISIONS.md): decodeRank, not the retired EvidenceChunkDao.
            // receivedCount — a FountainDecoder's rank is the meaningful "progress toward
            // decodable" number now, not a raw stored-row count (see that function's own doc).
            chunkCounts = evidenceList.filter { !it.complete }
                .associate { it.id to svc.decodeRank(it.id) }
            if (evidenceList.all { it.complete }) break
            delay(2000)
        }
    }

    val feed = remember(sosList, evidenceList, chunkCounts) {
        (sosList.map { FeedItem.Message(it) } + evidenceList.map { FeedItem.File(it, chunkCounts[it.id] ?: 0) })
            .sortedBy { it.timestamp }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null && meshService != null) {
            scope.launch {
                val bitmap = loadBitmap(context, uri)
                if (bitmap != null) {
                    // P5 slice 1 (docs/DECISIONS.md decision 45): both derived from the SAME
                    // already-downsampled bitmap — see EvidenceCapture.compressThumbnail's own doc
                    // for why this never re-decodes the source a second time.
                    meshService.sendEvidence(
                        groupId, EvidenceCapture.compress(bitmap), "image/jpeg", null,
                        EvidenceCapture.compressThumbnail(bitmap),
                    )
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(groupName) },
                colors = flushTopAppBarColors(),
                actions = {
                    IconButton(onClick = { nicknameInput = myNickname ?: ""; showNicknameDialog = true }) {
                        Icon(Icons.Filled.Badge, contentDescription = "Your name in this group")
                    }
                    IconButton(onClick = { scope.launch { inviteCode = repo.getShareCode(groupId) } }) {
                        Icon(Icons.Filled.PersonAdd, contentDescription = "Invite")
                    }
                }
            )
        },
        containerColor = AppColors.Background
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp).clickable(onClick = onExpandRadar),
                contentAlignment = Alignment.Center
            ) {
                if (!bluetoothEnabled) {
                    MeshPausedNotice(sizeDp = 150.dp)
                } else if (!meshActive) {
                    MeshPausedNotice(
                        sizeDp = 150.dp,
                        title = "Mesh is offline",
                        subtitle = "Turn off Offline mode on Home to resume"
                    )
                } else {
                    RadarCanvas(dots = radarDots, headingDegrees = heading, sizeDp = 150.dp)
                }
            }

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                item { Spacer(Modifier.height(4.dp)) }
                items(feed) { item ->
                    FeedRow(
                        item, groupColor, nicknameMap,
                        onViewFile = { evidence -> viewEvidenceFile(context, evidence) },
                        onRequestFullRes = { id -> meshService?.let { scope.launch { it.requestFullResolution(id) } } },
                        decryptThumbnail = { evidence -> meshService?.decryptedThumbnail(evidence) },
                    )
                }
                item { Spacer(Modifier.height(4.dp)) }
            }

            // Decision 35 (docs/DECISIONS.md): every message here is still the same SosEntity/
            // relay pipeline underneath — this counter only matters for the SOS action below, since
            // a quiet (non-alert) message never competes for Tier B's broadcast-preview budget at
            // all. Shown only once there's text to judge, so an empty compose row stays uncluttered.
            if (messageText.isNotBlank()) {
                val alertBytes = remember(messageText) { messageText.toByteArray(Charsets.UTF_8).size }
                val overAlertPreviewLimit = alertBytes > MeshProtocol.MAX_BROADCAST_TIER_SOS_MESSAGE_BYTES
                Text(
                    if (overAlertPreviewLimit) {
                        "$alertBytes bytes — if sent as SOS, only shows a hop-count alert instantly; " +
                            "the message itself still arrives once connected"
                    } else {
                        "$alertBytes / ${MeshProtocol.MAX_BROADCAST_TIER_SOS_MESSAGE_BYTES} bytes — " +
                            "fits the instant SOS broadcast preview"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (overAlertPreviewLimit) AppColors.Warning else AppColors.OnSurfaceMuted,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(AppColors.Surface)
                        .clickable { photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.AttachFile, contentDescription = "Share evidence", tint = AppColors.OnSurfaceMuted) }

                OutlinedTextField(
                    value = messageText, onValueChange = { messageText = it },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(20.dp),
                    placeholder = { Text("Type message or share evidence") }
                )

                // Quiet by default (isAlert = false) — decision 35: no loud notification, no Tier B
                // hop-gradient/preview, just relayed and catalog-filter-synced like everything else.
                Box(
                    Modifier.size(44.dp).clip(CircleShape)
                        .background(if (messageText.isNotBlank()) groupColor else AppColors.Surface)
                        .clickable(enabled = messageText.isNotBlank()) {
                            scope.launch {
                                meshService?.sendSos(groupId, messageText)
                                messageText = ""
                            }
                        },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = if (messageText.isNotBlank()) Color.White else AppColors.OnSurfaceMuted) }

                // The dedicated alert action (isAlert = true) — decision 35: only this button feeds
                // the SOS hop-gradient, the Tier B broadcast preview, and the loud notification.
                // Deliberately its own always-Danger-tinted control (not a mode toggle on the same
                // Send button) so raising a real SOS is never a matter of remembering to flip a
                // setting mid-crisis.
                Box(
                    Modifier.size(44.dp).clip(CircleShape)
                        .background(AppColors.Danger)
                        .clickable(enabled = messageText.isNotBlank()) {
                            scope.launch {
                                meshService?.sendSos(groupId, messageText, isAlert = true)
                                messageText = ""
                            }
                        },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Warning, contentDescription = "Send as SOS", tint = Color.White) }
            }

            TextButton(
                onClick = { showDeleteDialog = true },
                colors = ButtonDefaults.textButtonColors(contentColor = AppColors.Danger),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Delete group")
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete group?") },
            text = { Text("Removes this group, its key, and its messages/files from your device. Doesn't affect copies already relayed to others.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        repo.dismantleGroup(groupId)
                        // PositionTracker/BroadcastSosPreview are in-memory, owned by MeshService, not
                        // Room - dismantleGroup can't reach them itself (see PositionTracker
                        // .clearForGroup's own doc, decision 30).
                        meshService?.positionTracker?.clearForGroup(groupId)
                        meshService?.broadcastSosPreview?.clearForGroup(groupId)
                        showDeleteDialog = false
                        onDeleted()
                    }
                }) { Text("Delete", color = AppColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") } }
        )
    }

    if (showNicknameDialog) {
        AlertDialog(
            onDismissRequest = { showNicknameDialog = false },
            title = { Text("Your name in $groupName") },
            text = {
                Column {
                    Text(
                        "Shown to this group only — you can use a different name in each group. Leave blank to just show as a short id.",
                        style = MaterialTheme.typography.bodySmall, color = AppColors.OnSurfaceMuted
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = nicknameInput,
                        onValueChange = { if (it.length <= MeshFrameCodec.MAX_USERNAME_CHARS) nicknameInput = it },
                        label = { Text("Name") },
                        supportingText = { Text("${nicknameInput.length}/${MeshFrameCodec.MAX_USERNAME_CHARS}") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        meshService?.setNickname(groupId, nicknameInput)
                        myNickname = nicknameInput.trim().ifBlank { null }
                        showNicknameDialog = false
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showNicknameDialog = false }) { Text("Cancel") } }
        )
    }

    if (inviteCode != null) {
        AlertDialog(
            onDismissRequest = { inviteCode = null },
            title = { Text("Invite to $groupName") },
            text = {
                Column {
                    Text(
                        "Any member can generate this — it's not tied to whoever originally created the group, so the group still works even if they're gone.",
                        style = MaterialTheme.typography.bodySmall, color = AppColors.OnSurfaceMuted
                    )
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        QrCodeCard(content = JoinCode.shareLink(inviteCode ?: ""), sizeDp = 160.dp)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(inviteCode ?: "", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { copySensitiveText(context, inviteCode ?: "") }) { Text("Copy code") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        copySensitiveText(context, JoinCode.shareLink(inviteCode ?: ""))
                    }) { Text("Copy link") }
                    TextButton(onClick = { inviteCode = null }) { Text("Close") }
                }
            }
        )
    }
}

/** Console-style feed row: flat, no card/background, monospace — replaces an earlier chat-bubble
 *  design that (per direct feedback) took up too much vertical space for what this screen actually
 *  needs to show. Sender identity is carried by text color ([groupColor] for others, muted for
 *  "you") instead of a background tint, since there's no card left to tint. Keeps exactly the same
 *  information and the same tap-to-view behavior as before — this is a density/style change only.
 *
 *  P5 slice 1 (docs/DECISIONS.md decision 45, PLAN-v2.md §4.3): a [FeedItem.File] row now has THREE
 *  states, not two — complete (tap to view, unchanged), not-yet-requested (tap to pull full
 *  resolution, new), and pulling (already requested, receiving chunks, unchanged text but no
 *  longer clickable while waiting). A blind-carried item (`groupId == null` — we're not a member,
 *  can never decrypt it) shows only the thumbnail/label, never clickable — there's nothing this
 *  device could ever pull. */
@Composable
@Suppress("LongParameterList") // Compose callback params, one per distinct row interaction
private fun FeedRow(
    item: FeedItem,
    groupColor: Color,
    nicknameMap: Map<String, String>,
    onViewFile: (EvidenceEntity) -> Unit,
    onRequestFullRes: (String) -> Unit,
    decryptThumbnail: suspend (EvidenceEntity) -> ByteArray?,
) {
    fun label(senderId: String, isMe: Boolean): String =
        if (isMe) "you" else nicknameMap[senderId]?.takeIf { it.isNotBlank() } ?: senderId.take(8)

    val row = when (item) {
        is FeedItem.Message -> Quint(
            label(item.sos.senderId, item.sos.senderIsMe),
            item.sos.senderIsMe, item.sos.message, item.sos.timestamp, item.sos.isAlert
        )
        is FeedItem.File -> Quint(
            label(item.evidence.senderId, item.evidence.senderIsMe),
            item.evidence.senderIsMe,
            fileBodyText(item.evidence, item.receivedChunks),
            item.evidence.timestamp, false
        )
    }
    val sender = row.sender
    val isMe = row.isMe
    val body = row.body
    val time = row.time
    val isAlert = row.isAlert
    val onRowClick = feedRowClickAction(item, onViewFile, onRequestFullRes)
    val timeText = remember(time) { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(time)) }
    Row(
        Modifier
            .fillMaxWidth()
            .let { m -> onRowClick?.let { m.clickable(onClick = it) } ?: m }
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (item is FeedItem.File && item.evidence.thumbnail.isNotEmpty()) {
            FeedThumbnail(item.evidence, decryptThumbnail)
            Spacer(Modifier.width(8.dp))
        }
        Column {
            FeedRowHeader(timeText, sender, isMe, isAlert, groupColor)
            Text(
                body,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isAlert) AppColors.Danger else AppColors.OnSurface
            )
        }
    }
}

/** Complete -> view it. Not complete, own group, not yet requested -> tap requests it. Anything
 *  else (already pulling, or blind-carried with no key to ever view it) -> not clickable. Split
 *  out purely to keep [FeedRow] itself within detekt's length/complexity limits, same reason
 *  [fileBodyText] is its own function. */
private fun feedRowClickAction(
    item: FeedItem, onViewFile: (EvidenceEntity) -> Unit, onRequestFullRes: (String) -> Unit,
): (() -> Unit)? = when {
    item !is FeedItem.File -> null
    item.evidence.complete -> { { onViewFile(item.evidence) } }
    item.evidence.groupId != null && !item.evidence.wantsFullRes -> { { onRequestFullRes(item.evidence.id) } }
    else -> null
}

/** The time/sender/SOS-badge line — split out purely to keep [FeedRow] itself within detekt's
 *  length/complexity limits. */
@Composable
private fun FeedRowHeader(timeText: String, sender: String, isMe: Boolean, isAlert: Boolean, groupColor: Color) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            timeText,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.OnSurfaceMuted
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "$sender>",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
            color = if (isMe) AppColors.OnSurfaceMuted else groupColor,
            fontWeight = FontWeight.SemiBold
        )
        // Decision 35 (docs/DECISIONS.md): the one visual marker distinguishing a flagged
        // emergency from an ordinary quiet message in the feed — AppColors.Danger is reserved for
        // exactly this, nothing else in this app's palette uses it.
        if (isAlert) {
            Spacer(Modifier.width(6.dp))
            Text(
                "SOS", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall,
                color = AppColors.Danger, fontWeight = FontWeight.Bold
            )
        }
    }
}

/** The [FeedItem.File] status line — split out purely to keep [FeedRow]'s own `when` readable now
 *  that there are three real states instead of two (see [FeedRow]'s own doc). */
private fun fileBodyText(evidence: EvidenceEntity, receivedChunks: Int): String = when {
    evidence.complete -> "File received — tap to view"
    evidence.groupId == null -> "Preview only — not a member of this group"
    evidence.wantsFullRes -> "Receiving file: $receivedChunks / ${evidence.totalChunks} chunks"
    else -> "Preview — tap to load full resolution"
}

/** Small fixed-size preview for a [FeedItem.File] row. [evidence.thumbnail] carries
 *  [org.offlinemesh.app.ble.MeshFrameCodec.sealThumbnail]'s AES-GCM output, not a raw JPEG — see
 *  that function's own doc for why (P5 slice 1's own follow-up correction). [decryptThumbnail]
 *  opens it, so this composable needs a `LaunchedEffect` (a suspend, Keystore-touching call), not a
 *  synchronous decode the way the entity's own bytes could be handled directly. Renders nothing
 *  for a blind-carried item (the decrypt call itself refuses, same as
 *  [org.offlinemesh.app.ble.RelayEngine.decryptedThumbnail]'s own doc) — there is no key to ever
 *  show a preview with. */
@Composable
private fun FeedThumbnail(evidence: EvidenceEntity, decryptThumbnail: suspend (EvidenceEntity) -> ByteArray?) {
    var decrypted by remember(evidence.id) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(evidence.id) { decrypted = decryptThumbnail(evidence) }
    val plaintext = decrypted
    val bitmap = remember(plaintext) {
        plaintext?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Photo preview",
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp))
        )
    }
}

private data class Quint(val sender: String, val isMe: Boolean, val body: String, val time: Long, val isAlert: Boolean)

/** Opens a completed evidence file in whatever app the user has for its mime type (gallery, photo
 *  viewer) — the file itself never leaves app-private storage; [FileProvider] only grants the
 *  receiving app a temporary read on this one URI. Toasts rather than crashes on the two ways this
 *  can fail: the file is gone (pruned past its 48h retention window, or was never actually
 *  reassembled despite the feed row saying "complete" — shouldn't happen, but a stale row is
 *  cheaper to handle here than to prevent), or there's no app installed that can view this mime
 *  type at all (rare for image/jpeg or video/mp4, but not impossible on a stripped-down phone). */
internal fun viewEvidenceFile(context: android.content.Context, evidence: EvidenceEntity) {
    val file = RelayEngine.outputFile(context, evidence.id, evidence.mimeType)
    if (!file.exists()) {
        Toast.makeText(context, "File is no longer available", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, evidence.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app installed to open this file", Toast.LENGTH_SHORT).show()
    }
}

/** Copies a join code/link to the clipboard, marked sensitive on Android 13+ (`EXTRA_IS_SENSITIVE`)
 *  so the OS skips clipboard history and cross-device/cloud clipboard sync for it and clears it
 *  faster than ordinary clipboard content — this text contains the group's raw 256-bit decryption
 *  key. A no-op flag on older Android (ignored harmlessly; clipboard behaves as it did before this
 *  existed). Uses the platform ClipboardManager directly rather than Compose's LocalClipboardManager,
 *  which doesn't expose ClipDescription extras in the Compose BOM this app is on. */
internal fun copySensitiveText(context: android.content.Context, text: String) {
    val cm = context.getSystemService(android.content.ClipboardManager::class.java) ?: return
    val clip = android.content.ClipData.newPlainText("mesh group code", text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    cm.setPrimaryClip(clip)
}

// Power-of-two sample size — both ImageDecoder and BitmapFactory only accept those; compress()
// does the precise final resize, so an approximate sample here is fine.
private fun downsampleFactor(longestSide: Int, target: Int): Int =
    if (longestSide > target) Integer.highestOneBit(longestSide / target).coerceAtLeast(1) else 1

@Suppress("DEPRECATION") // BitmapFactory stream decode is the only pre-P downsampling path available
private fun loadBitmapLegacy(context: android.content.Context, uri: Uri, target: Int): Bitmap? {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
    val opts = android.graphics.BitmapFactory.Options().apply {
        inSampleSize = downsampleFactor(maxOf(bounds.outWidth, bounds.outHeight), target)
    }
    return context.contentResolver.openInputStream(uri)?.use {
        android.graphics.BitmapFactory.decodeStream(it, null, opts)
    }
}

// Downsamples *during* decode rather than after — a gallery photo from a modern camera (12-48MP)
// can be 8MB+ on disk and, decoded at full resolution, 50-150MB as an in-memory ARGB_8888 Bitmap
// on older/lower-RAM phones (the kind this app explicitly targets) before EvidenceCapture.compress()
// ever gets a chance to throw almost all of that away. Sampling to roughly MAX_DIMENSION up front
// avoids that peak-memory spike entirely; compress() still does the final exact resize/JPEG encode,
// so output size/quality is unchanged from before — this only fixes how much RAM decoding costs.
private fun loadBitmap(context: android.content.Context, uri: Uri): Bitmap? = try {
    val target = EvidenceCapture.MAX_DIMENSION
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.setTargetSampleSize(downsampleFactor(maxOf(info.size.width, info.size.height), target))
        }
    } else {
        loadBitmapLegacy(context, uri, target)
    }
} catch (e: Exception) {
    null
}
