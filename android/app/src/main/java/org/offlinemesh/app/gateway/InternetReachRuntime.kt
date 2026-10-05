package org.offlinemesh.app.gateway

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.offlinemesh.app.ble.MeshFrameCodec
import org.offlinemesh.app.ble.RelayResponder
import org.offlinemesh.app.ble.UplinkGateway
import org.offlinemesh.app.data.GroupRepository
import org.offlinemesh.app.diagnostics.DiagnosticsLog

/**
 * Owns everything the v3 "Internet reach" feature needs inside the running service (`PLAN-v2.md` §13):
 * the switch, connectivity, the throwaway gateway key and the [InternetReachController] loop. The service
 * only constructs it, calls [start]/[stop], and shows [status]. **Nothing runs unless the switch is on AND
 * the phone is online**, and it never touches the Bluetooth radios, so it works with Bluetooth off.
 */
class InternetReachRuntime(
    context: Context,
    private val scope: CoroutineScope,
    repo: GroupRepository,
    private val responder: RelayResponder,
    private val meshActive: StateFlow<Boolean>,
) {
    enum class Status { OFF, OFFLINE_MODE, WAITING_FOR_NETWORK, CONNECTING, ACTIVE }

    val settings = InternetReachSettings(context)
    private val network = NetworkMonitor(context)
    private val keys = GatewayKeyHolder().also { holder ->
        settings.savedKey()?.let { holder.restore(it.first, it.second) }
    }
    private var savedKeyCreatedAt = settings.savedKey()?.second ?: -1L
    private val _status = MutableStateFlow(Status.OFF)
    val status: StateFlow<Status> = _status
    private var job: Job? = null

    private val controller = InternetReachController(
        source = ResponderUplinkSource(repo, responder),
        newLink = { inject ->
            NostrGatewayLink(
                UplinkGateway(config = LOCAL_CONFIG), InternetReachSettings.DEFAULT_RELAYS,
                OkHttpRelayConnector(), keys, inject = inject,
            )
        },
        onInbound = ::handleInbound,
    )

    fun start() {
        if (job != null) return
        network.start()
        job = scope.launch {
            while (isActive) {
                tickOnce()
                delay(TICK_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        controller.stop()
        network.stop()
        _status.value = Status.OFF
    }

    private suspend fun tickOnce() {
        val on = settings.enabled.value
        val online = network.online.value
        // The app's own "Offline" mode silences every emitter, so it silences this one too.
        val allowed = meshActive.value
        if (on && online && allowed) controller.start() else controller.stop()
        _status.value = when {
            !on -> Status.OFF
            !allowed -> Status.OFFLINE_MODE
            !online -> Status.WAITING_FOR_NETWORK
            controller.relayConnected() -> Status.ACTIVE
            else -> Status.CONNECTING
        }
        try {
            controller.step()
            persistKeyIfChanged()
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            DiagnosticsLog.event("internet-reach", "step failed: ${e::class.simpleName} ${e.message}")
        }
    }

    private fun persistKeyIfChanged() {
        if (keys.createdAtMs != savedKeyCreatedAt) {
            settings.saveKey(keys.secret, keys.createdAtMs)
            savedKeyCreatedAt = keys.createdAtMs
        }
    }

    /** A frame that arrived over the internet goes through the same handler as one from a BLE neighbour. */
    private suspend fun handleInbound(cls: Int, inner: ByteArray) {
        if (cls == MeshFrameCodec.UPLINK_CLASS_POSITION_LAST_KNOWN) {
            responder.ingestLastKnownPosition(inner)
            return
        }
        val sos = MeshFrameCodec.decode(inner) as? MeshFrameCodec.Frame.SosSealed
        if (sos != null) controller.markUplinked("sos:${sos.id}")
        responder.handleIncoming(inner, RelayResponder.INTERNET_PEER) { }
    }

    companion object {
        private const val TICK_MS = 1000L

        /** A member endpoint publishes its own and its group's frames, so it needs far more headroom per tag
         *  than a blind carrier (about ten frames every ten seconds for a full group). */
        private val LOCAL_CONFIG = UplinkGateway.Config(liveKeepPerTag = 24, framesPerTagPerMinute = 180)
    }
}
