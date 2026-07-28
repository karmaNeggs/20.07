package org.offlinemesh.app.ui

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.location.Location
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import org.offlinemesh.app.ble.MeshService
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

    var myLocation by remember { mutableStateOf<Location?>(null) }
    var heading by remember { mutableStateOf(0f) }
    var radarDots by remember { mutableStateOf<List<RadarDot>>(emptyList()) }
    val bluetoothEnabled by (meshService?.bluetoothEnabled?.collectAsState() ?: remember { mutableStateOf(true) })

    LaunchedEffect(groupId) { groupName = repo.groupDao.getGroup(groupId)?.name ?: groupId }

    LaunchedEffect(groupId, meshService) {
        while (true) {
            val svc = meshService
            if (svc != null) {
                myLocation = svc.locationTracker.location.value
                heading = svc.compassTracker.headingDegrees.value
                val me = myLocation
                radarDots = if (me == null) emptyList() else svc.positionTracker.forGroup(groupId).mapNotNull { (_, record) ->
                    placePeerOnRadar(me.latitude, me.longitude, me.accuracy, record.lat, record.lon, record.accuracyM, heading)
                        ?.let { RadarDot(groupColor, it.distanceMeters, it.screenAngleDegrees) }
                }
            }
            delay(1000)
        }
    }

    val sosList by db.sosDao().observeForGroup(groupId).collectAsState(initial = emptyList())
    val evidenceList by db.evidenceDao().observeForGroup(groupId).collectAsState(initial = emptyList())
    val nicknames by db.nicknameDao().observeForGroup(groupId).collectAsState(initial = emptyList())
    val nicknameMap = remember(nicknames) { nicknames.associate { it.senderId to it.username } }
    var chunkCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

    LaunchedEffect(evidenceList) {
        while (true) {
            chunkCounts = evidenceList.filter { !it.complete }
                .associate { it.id to db.evidenceChunkDao().receivedCount(it.id) }
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
                    meshService.sendEvidence(groupId, EvidenceCapture.compress(bitmap), "image/jpeg", null)
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
                if (bluetoothEnabled) {
                    RadarCanvas(dots = radarDots, headingDegrees = heading, sizeDp = 150.dp)
                } else {
                    BluetoothOffNotice(sizeDp = 150.dp)
                }
            }

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                item { Spacer(Modifier.height(4.dp)) }
                items(feed) { item ->
                    FeedRow(item, groupColor, nicknameMap) { evidence -> viewEvidenceFile(context, evidence) }
                }
                item { Spacer(Modifier.height(4.dp)) }
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
 *  information and the same tap-to-view behavior as before — this is a density/style change only. */
@Suppress("FunctionNaming") // PascalCase is the established Compose convention this whole file
// (and every other screen) already uses for composables — see detekt-baseline.xml, which
// grandfathers this exact violation for every pre-existing composable; same deliberate call here.
@Composable
private fun FeedRow(
    item: FeedItem,
    groupColor: Color,
    nicknameMap: Map<String, String>,
    onViewFile: (EvidenceEntity) -> Unit,
) {
    fun label(senderId: String, isMe: Boolean): String =
        if (isMe) "you" else nicknameMap[senderId]?.takeIf { it.isNotBlank() } ?: senderId.take(8)

    val (sender, isMe, body, time) = when (item) {
        is FeedItem.Message -> Quad(
            label(item.sos.senderId, item.sos.senderIsMe),
            item.sos.senderIsMe, item.sos.message, item.sos.timestamp
        )
        is FeedItem.File -> Quad(
            label(item.evidence.senderId, item.evidence.senderIsMe),
            item.evidence.senderIsMe,
            if (item.evidence.complete) "File received — tap to view" else "Receiving file: ${item.receivedChunks} / ${item.evidence.totalChunks} chunks",
            item.evidence.timestamp
        )
    }
    val viewable = item is FeedItem.File && item.evidence.complete
    val timeText = remember(time) { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(time)) }
    Column(
        Modifier
            .fillMaxWidth()
            .let { m -> if (viewable) m.clickable { onViewFile((item as FeedItem.File).evidence) } else m }
            .padding(vertical = 3.dp)
    ) {
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
        }
        Text(
            body,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.OnSurface
        )
    }
}

private data class Quad(val sender: String, val isMe: Boolean, val body: String, val time: Long)

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
