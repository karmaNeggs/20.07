package org.offlinemesh.app.ui

import android.content.Context
import android.widget.Toast
import org.offlinemesh.app.ble.MeshService

/**
 * Sends a message or SOS without ever crashing the screen or pretending it went out. Returns true only if the
 * mesh service accepted it. A group that has just expired or been deleted, or a service that is not bound yet,
 * shows a short notice and keeps the user's text; with Offline mode on it says plainly the message is saved.
 */
@Suppress("SwallowedException") // a rejected send is reported to the user, not an error to propagate
internal suspend fun safeSend(
    context: Context,
    meshService: MeshService?,
    groupId: String,
    text: String,
    isAlert: Boolean,
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
        val note = "Offline mode is on: saved, it will go out when you turn it off"
        Toast.makeText(context, note, Toast.LENGTH_LONG).show()
    }
    return sent
}
