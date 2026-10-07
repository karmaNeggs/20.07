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
    private val registry = InterestRegistry()
    private var lastLoggedStatus: Status? = null
    private var lastSummaryAt = 0L
    private val inboundByClass = IntArray(INBOUND_CLASSES)

    private val controller = InternetReachController(
        source = ResponderUplinkSource(repo, responder),
        newLink = { inject ->
            NostrGatewayLink(
                UplinkGateway(config = LOCAL_CONFIG), InternetReachSettings.DEFAULT_RELAYS,
                OkHttpRelayConnector(), keys, inject = inject,
            )
        },
        onInbound = ::handleInbound,
        extraInterest = { registry.tags() },
        // Author decision 2026-10-07: any online phone carries files too, on mobile data as well as Wi-Fi.
        // The size cap, separate bulk budget, pacing and congestion pause protect relays and data plans.
        bulkAllowed = { network.online.value },
    )

    private val bridge = UplinkBleBridge(controller, registry) { settings.enabled.value && meshActive.value }

    fun start() {
        if (job != null) return
        responder.uplinkHook = bridge
        network.start()
        job = scope.launch {
            while (isActive) {
                tickOnce()
                delay(TICK_MS)
            }
        }
    }

    fun stop() {
        responder.uplinkHook = null
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
        // Runs whenever the switch is on, online or not: an offline phone still wraps and holds its frames for a
        // Bluetooth neighbour that has internet (stranger carrying). Only publishing needs a connection.
        if (on && allowed) controller.start() else controller.stop()
        _status.value = when {
            !on -> Status.OFF
            !allowed -> Status.OFFLINE_MODE
            !online -> Status.WAITING_FOR_NETWORK
            controller.relayConnected() -> Status.ACTIVE
            else -> Status.CONNECTING
        }
        logChanges()
        try {
            controller.step()
            persistKeyIfChanged()
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            DiagnosticsLog.event("internet-reach", "step failed: ${e::class.simpleName} ${e.message}")
        }
    }

    /** Diagnostics only (a no-op in release builds): status changes at once, and a relay/inbound summary every 30 s. */
    private fun logChanges() {
        val s = _status.value
        if (s != lastLoggedStatus) {
            DiagnosticsLog.event("internet-reach", "status $lastLoggedStatus -> $s")
            lastLoggedStatus = s
        }
        val t = System.currentTimeMillis()
        if (s != Status.OFF && t - lastSummaryAt >= SUMMARY_MS) {
            lastSummaryAt = t
            DiagnosticsLog.event(
                "internet-reach",
                "relays ${controller.summary()} inbound live/last/text/alert/meta/sym=" +
                    inboundByClass.joinToString("/"),
            )
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
        if (cls in 0 until INBOUND_CLASSES) inboundByClass[cls]++
        if (cls == MeshFrameCodec.UPLINK_CLASS_POSITION_LAST_KNOWN) {
            // A member records it as last seen; a stranger cannot open it, so it enters the mesh like any sealed frame.
            if (!responder.ingestLastKnownPosition(inner)) responder.handleIncoming(inner,
                RelayResponder.INTERNET_PEER) { }
            return
        }
        val sos = MeshFrameCodec.decode(inner) as? MeshFrameCodec.Frame.SosSealed
        if (sos != null) controller.markUplinked("sos:${sos.id}")
        responder.handleIncoming(inner, RelayResponder.INTERNET_PEER) { }
    }

    companion object {
        private const val TICK_MS = 1000L
        private const val SUMMARY_MS = 30_000L
        private const val INBOUND_CLASSES = 6

        /** A member endpoint publishes its own and its group's frames, so it needs far more headroom per tag
         *  than a blind carrier (about ten frames every ten seconds for a full group). */
        private val LOCAL_CONFIG = UplinkGateway.Config(liveKeepPerTag = 24, framesPerTagPerMinute = 180)
    }
}
