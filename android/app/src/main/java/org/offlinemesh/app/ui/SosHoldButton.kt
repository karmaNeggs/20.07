package org.offlinemesh.app.ui

import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val HOLD_MS = 1500
private val BUTTON_SIZE = 44.dp

/**
 * The chat screen's SOS button. A tap only shows a hint; the alert is sent after the button has been held for
 * [HOLD_MS], with a filling ring, so a tap in a pocket or a stray press next to Send cannot raise an alert.
 * Works with an empty message box ([onSend] is then called with a blank string and the caller supplies "SOS").
 * TalkBack users get a "Send SOS" action instead of the hold.
 */
@Composable
internal fun SosHoldButton(onSend: () -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    Box(
        Modifier
            .size(BUTTON_SIZE)
            .clip(CircleShape)
            .background(AppColors.SosButton)
            .semantics {
                contentDescription = "SOS, hold to send"
                customActions = listOf(CustomAccessibilityAction("Send SOS") { onSend(); true })
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { Toast.makeText(context, "Hold the SOS button to send", Toast.LENGTH_SHORT).show() },
                    onPress = {
                        val hold: Job = scope.launch {
                            progress.animateTo(1f, tween(HOLD_MS, easing = LinearEasing))
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSend()
                        }
                        tryAwaitRelease()
                        hold.cancel()
                        scope.launch { progress.snapTo(0f) }
                    },
                )
            },
        contentAlignment = Alignment.Center
    ) {
        if (progress.value > 0f) {
            CircularProgressIndicator(
                progress = { progress.value },
                modifier = Modifier.size(BUTTON_SIZE),
                color = AppColors.OnGroupColor,
                trackColor = AppColors.SosButton,
                strokeWidth = 4.dp,
            )
        }
        Text("SOS", color = AppColors.OnGroupColor, style = MaterialTheme.typography.labelLarge)
    }
}
