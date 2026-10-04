package org.offlinemesh.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import org.offlinemesh.app.data.GroupRepository
import org.offlinemesh.app.data.JoinCode

private enum class Mode { JOIN, CREATE }

private const val MILLIS_PER_SECOND = 1000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddGroupScreen(repo: GroupRepository, prefillCode: String?, onDone: () -> Unit) {
    var mode by remember { mutableStateOf(Mode.JOIN) }
    // Prefilled from a scanned QR, a pasted code, or a mesh2007:// deep link alike — all three
    // entry paths populate this same field and go through the same preview-then-explicit-Join
    // flow below, rather than any of them auto-joining without showing what's being agreed to
    // (see the expiresPreview text: a stranger's group carries a real, non-obvious commitment —
    // when it expires — worth seeing before committing to it).
    var code by remember { mutableStateOf(prefillCode ?: "") }
    var name by remember { mutableStateOf("") }
    var selectedLifetimeIndex by remember { mutableStateOf(DEFAULT_GROUP_LIFETIME_INDEX) }
    var generatedCode by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showScanner by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Decoded read-only preview of whatever's currently in the code field — recomputed live as
    // code changes (typing, pasting, scanning, or the deep-link prefill), never mutates anything.
    // JoinCode.decode already rejects malformed/expired/implausibly-far-future codes, so a null
    // result here means "not previewable yet," not necessarily "wrong" (e.g. still mid-paste).
    val decodedPreview = remember(code) {
        if (code.isBlank()) null else JoinCode.decode(JoinCode.extractCode(code))
    }

    fun attemptJoin(raw: String) {
        scope.launch {
            val group = repo.joinGroup(raw)
            if (group != null) onDone() else error = "That code doesn't look right."
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showScanner = true
    }
    fun openScanner() {
        val hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (hasCamera) showScanner = true else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (showScanner) {
        // Populates the code field and returns to the normal Join tab — does NOT auto-join — so a
        // scanned stranger's QR gets the same preview-before-committing treatment as a pasted code.
        QrScannerScreen(
            onScanned = { text -> showScanner = false; code = text },
            onDismiss = { showScanner = false }
        )
        return
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = { TopAppBar(title = { Text("Add a group") }, colors = flushTopAppBarColors()) },
        containerColor = AppColors.Background
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
            if (generatedCode == null) {
                ModeToggle(mode = mode, onChange = { mode = it })
                Spacer(Modifier.height(32.dp))
            }

            when {
                generatedCode != null -> {
                    Text("Group created", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Share this so others can join the same group. Anyone with this code — pasted, read aloud, " +
                            "scanned, or sent as a link — is in. This group deletes itself, on every member's " +
                            "phone, in ${GROUP_LIFETIME_OPTIONS[selectedLifetimeIndex].label} from now — no need " +
                            "to remove it by hand.",
                        style = MaterialTheme.typography.bodyMedium, color = AppColors.OnSurfaceMuted
                    )
                    Spacer(Modifier.height(20.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        QrCodeCard(content = JoinCode.shareLink(generatedCode ?: ""))
                    }
                    Spacer(Modifier.height(20.dp))
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(AppColors.Surface).padding(18.dp)
                    ) {
                        Text(generatedCode ?: "", style = MaterialTheme.typography.bodyMedium, color = AppColors.OnSurface)
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = { copySensitiveText(context, generatedCode ?: "") },
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("Copy code") }
                        OutlinedButton(
                            onClick = { copySensitiveText(context, JoinCode.shareLink(generatedCode ?: "")) },
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("Copy link") }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = onDone,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) { Text("Done") }
                }

                mode == Mode.JOIN -> {
                    Text("Paste a code or link someone shared with you, or scan their QR code.", style = MaterialTheme.typography.bodyMedium, color = AppColors.OnSurfaceMuted)
                    Spacer(Modifier.height(20.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = code, onValueChange = { code = it; error = null },
                            label = { Text("Code or link") }, modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                capitalization = KeyboardCapitalization.None,
                                autoCorrect = false
                            )
                        )
                        // Camera permission is requested only on this tap, never at launch — the app
                        // works fully without it (paste/type a code, or tap a mesh2007:// link).
                        Box(
                            Modifier.size(48.dp).clip(CircleShape).background(AppColors.Surface)
                                .clickable { openScanner() },
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Filled.PhotoCamera, contentDescription = "Scan QR code", tint = AppColors.OnSurfaceMuted) }
                    }
                    if (error != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(error ?: "", color = AppColors.Danger, style = MaterialTheme.typography.bodySmall)
                    } else if (decodedPreview != null) {
                        // Shown BEFORE joining, not after — a stranger's group carries a real,
                        // easy-to-miss commitment (when it disappears from your phone), and this is
                        // the one moment that matters to see it, whether the code arrived by paste,
                        // scan, or a tapped mesh2007:// link.
                        Spacer(Modifier.height(8.dp))
                        val remainingMs =
                            decodedPreview.expiresAtEpochSec * MILLIS_PER_SECOND - System.currentTimeMillis()
                        Text(
                            "\"${decodedPreview.name}\" — expires in ${formatTimeRemaining(remainingMs)}",
                            color = AppColors.OnSurfaceMuted, style = MaterialTheme.typography.bodySmall
                        )
                    } else if (code.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Doesn't look like a valid code yet.",
                            color = AppColors.OnSurfaceMuted, style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        enabled = code.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent),
                        shape = RoundedCornerShape(16.dp),
                        onClick = { attemptJoin(code) },
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) { Text("Join group") }
                }

                else -> {
                    Text("Name your group — this is just a label, shown to whoever joins.", style = MaterialTheme.typography.bodyMedium, color = AppColors.OnSurfaceMuted)
                    Spacer(Modifier.height(20.dp))
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("Group name") }, modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "Deletes itself after",
                        style = MaterialTheme.typography.titleSmall, color = AppColors.OnSurfaceMuted
                    )
                    Spacer(Modifier.height(8.dp))
                    LifetimePicker(
                        selectedIndex = selectedLifetimeIndex,
                        onChange = { selectedLifetimeIndex = it }
                    )
                    Spacer(Modifier.weight(1f))
                    Button(
                        enabled = name.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent),
                        shape = RoundedCornerShape(16.dp),
                        onClick = {
                            scope.launch {
                                val (_, joinCode) = repo.createGroup(
                                    name.trim(), GROUP_LIFETIME_OPTIONS[selectedLifetimeIndex].millis
                                )
                                generatedCode = joinCode
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) { Text("Create group") }
                }
            }
        }
    }
}

@Composable
private fun ModeToggle(mode: Mode, onChange: (Mode) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.Surface)
            .padding(4.dp)
    ) {
        listOf(Mode.JOIN to "Join", Mode.CREATE to "Create").forEach { (m, label) ->
            val selected = mode == m
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) AppColors.Accent else Color.Transparent)
                    .clickable(onClick = { onChange(m) })
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, color = if (selected) Color.White else AppColors.OnSurfaceMuted, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** Same segmented-row shape as [ModeToggle], generalized to [GROUP_LIFETIME_OPTIONS]'s 5 choices —
 *  short labels (`"12h"`/`"48h"`/`"7d"`/`"30d"`/`"6mo"`) so all 5 fit one row on a phone width,
 *  matching [GroupLifetimeOption.shortLabel] specifically (the create-flow explainer text uses the
 *  fuller [GroupLifetimeOption.label] instead, where there's room for it). */
@Composable
private fun LifetimePicker(selectedIndex: Int, onChange: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.Surface)
            .padding(4.dp)
    ) {
        GROUP_LIFETIME_OPTIONS.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) AppColors.Accent else Color.Transparent)
                    .clickable(onClick = { onChange(index) })
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    option.shortLabel,
                    color = if (selected) Color.White else AppColors.OnSurfaceMuted,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}
