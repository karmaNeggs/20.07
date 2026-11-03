package org.offlinemesh.app.ui

import android.content.Context
import android.widget.Toast
import org.offlinemesh.app.ble.MeshService

/**
 * Saves a message or SOS for the mesh without crashing the screen or claiming recipient delivery. Returns true
 * only if the mesh service accepted it locally. A group that has just expired or been deleted,
 * or a service that is not bound yet, shows a short notice and keeps the user's text;
 * with Offline mode on it says plainly the message is saved.
 */
// Explicit send arguments plus a UI-only switch to avoid duplicate notices in the multi-group SOS screen.
@Suppress("SwallowedException", "LongParameterList")
internal suspend fun safeSend(
    context: Context,
    meshService: MeshService?,
    groupId: String,
    text: String,
    isAlert: Boolean,
    showAlertStatus: Boolean = true,
): Boolean {
    if (meshService == null) {
        Toast.makeText(context, "Not ready yet, try again in a moment", Toast.LENGTH_SHORT).show()
        return false
    }
    val sent = try {
        meshService.sendSos(groupId, text, isAlert)
        true
    } catch (e: IllegalStateException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    }
    if (!sent) {
        Toast.makeText(context, "Couldn't send: this group has ended", Toast.LENGTH_LONG).show()
    } else if (!meshService.meshActive.value) {
        val note = "Offline mode: saved on this phone; turn it off to try delivery"
        Toast.makeText(context, note, Toast.LENGTH_LONG).show()
    } else if (isAlert && showAlertStatus) {
        Toast.makeText(context, "SOS saved to send; another person's receipt is unconfirmed", Toast.LENGTH_LONG).show()
    }
    return sent
}
