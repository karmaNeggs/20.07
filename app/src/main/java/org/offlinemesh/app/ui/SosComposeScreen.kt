package org.offlinemesh.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.offlinemesh.app.ble.MeshProtocol
import org.offlinemesh.app.ble.MeshService
import org.offlinemesh.app.data.GroupRepository

/**
 * General/broadcast SOS: every group is pre-selected by default (in an emergency you likely
 * want maximum reach), uncheck whichever ones this doesn't concern. This is separate from the
 * quick in-group-only SOS available directly from a single group's chat screen — that one has
 * no checkboxes since it's already scoped to the group you're looking at.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SosComposeScreen(repo: GroupRepository, meshService: MeshService?, onSent: () -> Unit) {
    val groups by repo.groupDao.observeGroups().collectAsState(initial = emptyList())
    var message by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var initialized by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Pre-select every group once they've loaded, exactly once — not on every recomposition,
    // so unchecking one doesn't get silently reset if the group list flow re-emits.
    LaunchedEffect(groups) {
        if (!initialized && groups.isNotEmpty()) {
            selected = groups.map { it.id }.toSet()
            initialized = true
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = { TopAppBar(title = { Text("Send SOS") }, colors = flushTopAppBarColors()) },
        containerColor = AppColors.Background
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
            OutlinedTextField(
                value = message, onValueChange = { message = it },
                label = { Text("Message (optional)") }, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
            // Everything sent from this screen is isAlert = true (decision 35, docs/DECISIONS.md),
            // so the Tier B broadcast-preview cap is always relevant here, unlike GroupChatScreen's
            // shared compose box.
            if (message.isNotBlank()) {
                val alertBytes = remember(message) { message.toByteArray(Charsets.UTF_8).size }
                val overAlertPreviewLimit = alertBytes > MeshProtocol.MAX_BROADCAST_TIER_SOS_MESSAGE_BYTES
                Text(
                    if (overAlertPreviewLimit) {
                        "$alertBytes bytes — only a hop-count alert shows instantly; the message " +
                            "itself still arrives once connected"
                    } else {
                        "$alertBytes / ${MeshProtocol.MAX_BROADCAST_TIER_SOS_MESSAGE_BYTES} bytes — " +
                            "fits the instant broadcast preview"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (overAlertPreviewLimit) AppColors.Warning else AppColors.OnSurfaceMuted,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(Modifier.height(24.dp))
            Text("Send to", style = MaterialTheme.typography.titleSmall, color = AppColors.OnSurfaceMuted)
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(groups) { g ->
                    val color = AppColors.colorForGroup(g.id)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(AppColors.Surface)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = g.id in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + g.id else selected - g.id
                            },
                            colors = CheckboxDefaults.colors(checkedColor = color)
                        )
                        Text(g.name, color = AppColors.OnSurface)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Button(
                enabled = selected.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Danger),
                shape = RoundedCornerShape(16.dp),
                onClick = {
                    scope.launch {
                        for (groupId in selected) {
                            meshService?.sendSos(groupId, message.ifBlank { "SOS" }, isAlert = true)
                        }
                        onSent()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) { Text("Send SOS to ${selected.size} group(s)", fontWeight = FontWeight.SemiBold) }
        }
    }
}
