package org.offlinemesh.app.ble

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.offlinemesh.app.crypto.CryptoUtils
import org.offlinemesh.app.data.EvidenceChunkEntity
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.ServerSocket
import java.net.Socket
import kotlin.coroutines.resume

/**
 * Owns the real `WifiP2pManager`/socket calls for one ephemeral, pairwise bulk transfer at a
 * time — the actual radio mechanics behind [WifiDirectHandoffCoordinator]'s decision logic, same
 * split as [MeshGattClient] (real `BluetoothGatt` calls) vs [ConnectionAttemptTracker] (pure state
 * machine).
 *
 * **NOT device-tested.** Written carefully against the documented `WifiP2pManager` API surface,
 * but this project's live-device testing (passes 1-21) never exercised a second radio (WiFi)
 * running concurrently with the proven BLE GATT link, and WiFi Direct's own group-formation
 * behavior is widely reported (Android developer community) to be slow, inconsistent across OEM
 * WiFi stacks, and prone to leaving stale P2P group state behind. Every call here is wrapped
 * exactly like [BleCapabilities]/`BeaconRadio.evaluateLongRangeAdvertising`: broad catch, fail
 * closed, never throws out of this class, never touches BLE state either way. **The single biggest
 * unverified risk**: `WifiP2pManager.connect()` is well known (Android developer community
 * consensus, not confirmed on any specific device here) to sometimes trigger a system-level
 * "Invitation to connect" dialog on the PEER phone that a human has to tap — which would visibly
 * break both phones' disguise the moment it fires. This is the first thing to check on a real
 * 2-phone test, before relying on this feature for anything real.
 *
 * **Role asymmetry.** Only the initiator (holds the chunks, [beginAsInitiator]) calls
 * `discoverPeers()`/`connect()` — calling `connect()` from both sides risks a double-connect race.
 * The responder ([beginAsResponder]) stays passive: it refreshes its own P2P visibility but waits
 * for the connection the initiator's `connect()` call forms, discovered by polling
 * `requestConnectionInfo` until `groupFormed` is true rather than registering a manifest/lifecycle-
 * scoped `BroadcastReceiver` for `WIFI_P2P_CONNECTION_CHANGED_ACTION` — simpler and more
 * self-contained for a single short-lived operation, at the cost of a small polling delay.
 *
 * **Token handshake before anything is trusted.** Directly reapplies the lesson from
 * [MeshGattClient]'s CCCD-before-data-write bug (see that class's doc): the first thing either side
 * does on a freshly opened socket is exchange and verify [WifiDirectHandoffCoordinator]'s token —
 * chunk bytes never flow before that succeeds.
 */
@Suppress(
    // A broad catch-and-log-and-fail-closed is this whole class's core, deliberate design — see
    // the class doc — not sloppy exception handling; every catch here exists specifically so an
    // unverified WifiP2pManager/socket failure can never propagate into BLE state. Guard-clause-
    // style early returns (bail out step by step the moment any stage fails) is the same reason
    // ReturnCount trips throughout this file — matches this codebase's established style for
    // exactly this kind of multi-stage, fail-fast radio sequence. TooManyFunctions is this class
    // broken into small, single-purpose private steps (mirrors MeshFrameCodec's own many-small-
    // functions shape) rather than a few large ones.
    "TooGenericExceptionCaught", "SwallowedException", "ReturnCount", "TooManyFunctions",
)
class WifiDirectAccelerator(private val context: Context) : WifiDirectTransport {
    private val manager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel: WifiP2pManager.Channel? =
        manager?.initialize(context, Looper.getMainLooper(), null)

    @Volatile private var currentSocket: Socket? = null
    @Volatile private var currentServerSocket: ServerSocket? = null

    @SuppressLint("MissingPermission")
    override suspend fun beginAsInitiator(
        peerAddress: String,
        token: ByteArray,
        readyAtEpochMs: Long,
        chunks: List<EvidenceChunkEntity>,
    ) {
        val m = manager ?: return
        val c = channel ?: return
        try {
            cleanupStaleGroup(m, c)
            val candidate = discoverSinglePeer(m, c) ?: return
            if (!requestConnect(m, c, candidate.deviceAddress, INITIATOR_GO_INTENT)) return
            val info = awaitConnectionInfo(m, c) ?: return
            awaitReadyTime(readyAtEpochMs)
            val socket = openSocket(info) ?: return
            currentSocket = socket
            if (!handshakeToken(socket, token)) return
            sendChunks(socket, chunks)
        } catch (e: Exception) {
            Log.w(TAG, "WFD initiator transfer to $peerAddress failed: ${e.message}")
        } finally {
            teardown(m, c)
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun beginAsResponder(
        peerAddress: String,
        token: ByteArray,
        readyAtEpochMs: Long,
        onChunk: suspend (EvidenceChunkEntity) -> Unit,
    ) {
        val m = manager ?: return
        val c = channel ?: return
        try {
            cleanupStaleGroup(m, c)
            try { m.discoverPeers(c, null) } catch (_: Exception) {
                // Best-effort visibility refresh only — connect() is the initiator's job, not ours
                // (see class doc on role asymmetry); a failure here doesn't block waiting below.
            }
            val info = awaitConnectionInfo(m, c) ?: return
            awaitReadyTime(readyAtEpochMs)
            val socket = openSocket(info) ?: return
            currentSocket = socket
            if (!handshakeToken(socket, token)) return
            receiveChunks(socket, onChunk)
        } catch (e: Exception) {
            Log.w(TAG, "WFD responder transfer from $peerAddress failed: ${e.message}")
        } finally {
            teardown(m, c)
        }
    }

    /** Every WFD failure mode converges here — idempotent, never throws. Called both from this
     *  class's own `finally` blocks and from [WifiDirectHandoffCoordinator.abort]. */
    override fun abortCurrent() {
        teardown(manager, channel)
    }

    // ---------------- group formation ----------------

    /** WiFi Direct is well known to leave a stale group behind from a previous run — always clear
     *  before starting a new attempt, on both roles. */
    private suspend fun cleanupStaleGroup(m: WifiP2pManager, c: WifiP2pManager.Channel) {
        val hasGroup = try {
            suspendCancellableCoroutine { cont ->
                m.requestGroupInfo(c) { group -> if (cont.isActive) cont.resume(group != null) }
            }
        } catch (e: Exception) {
            false
        }
        if (!hasGroup) return
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                m.removeGroup(c, object : WifiP2pManager.ActionListener {
                    // best-effort either way — success or failure, proceed the same
                    override fun onSuccess() { if (cont.isActive) cont.resume(Unit) }
                    override fun onFailure(reason: Int) { if (cont.isActive) cont.resume(Unit) }
                })
            }
        } catch (e: Exception) {
            // nothing more to do — proceed anyway, a stale group will surface as a connect failure
        }
    }

    /** Peer-list ambiguity (see class doc): aborts rather than guesses when more than one WiFi
     *  Direct-visible device is nearby, since a wrong `connect()` attempt can pop the "Invitation
     *  to connect" dialog on an uninvolved stranger's phone — a worse cost here than a missed
     *  acceleration opportunity. Only ever called by the initiator. */
    private suspend fun discoverSinglePeer(m: WifiP2pManager, c: WifiP2pManager.Channel): WifiP2pDevice? {
        val started = try {
            suspendCancellableCoroutine { cont ->
                m.discoverPeers(c, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { if (cont.isActive) cont.resume(true) }
                    override fun onFailure(reason: Int) { if (cont.isActive) cont.resume(false) }
                })
            }
        } catch (e: Exception) {
            false
        }
        if (!started) return null
        delay(PEER_DISCOVERY_WINDOW_MS) // give the OS time to actually populate its peer list
        val list = try {
            suspendCancellableCoroutine<List<WifiP2pDevice>> { cont ->
                m.requestPeers(c) { peers -> if (cont.isActive) cont.resume(peers.deviceList.toList()) }
            }
        } catch (e: Exception) {
            emptyList()
        }
        return if (list.size == 1) list[0] else null
    }

    private suspend fun requestConnect(
        m: WifiP2pManager,
        c: WifiP2pManager.Channel,
        deviceAddress: String,
        groupOwnerIntent: Int,
    ): Boolean {
        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
            this.groupOwnerIntent = groupOwnerIntent
        }
        return try {
            suspendCancellableCoroutine { cont ->
                m.connect(c, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { if (cont.isActive) cont.resume(true) }
                    override fun onFailure(reason: Int) { if (cont.isActive) cont.resume(false) }
                })
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Polls rather than registers a `BroadcastReceiver` — see class doc. Bounded by
     *  [WifiDirectTuning.OVERALL_HANDOFF_TIMEOUT_MS], the same one deadline covering this whole
     *  handoff on the [WifiDirectHandoffCoordinator] side. */
    private suspend fun awaitConnectionInfo(m: WifiP2pManager, c: WifiP2pManager.Channel): WifiP2pInfo? {
        val deadline = System.currentTimeMillis() + WifiDirectTuning.OVERALL_HANDOFF_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val info = try {
                suspendCancellableCoroutine<WifiP2pInfo?> { cont ->
                    m.requestConnectionInfo(c) { i -> if (cont.isActive) cont.resume(i) }
                }
            } catch (e: Exception) {
                null
            }
            if (info?.groupFormed == true) return info
            delay(CONNECTION_POLL_INTERVAL_MS)
        }
        return null
    }

    // ---------------- socket + token handshake ----------------

    private suspend fun openSocket(info: WifiP2pInfo): Socket? = try {
        if (info.isGroupOwner) {
            val server = ServerSocket(WifiDirectTuning.SOCKET_PORT)
            currentServerSocket = server
            withTimeoutOrNull(WifiDirectTuning.OVERALL_HANDOFF_TIMEOUT_MS) { server.accept() }
        } else {
            Socket(info.groupOwnerAddress, WifiDirectTuning.SOCKET_PORT)
        }
    } catch (e: Exception) {
        Log.w(TAG, "WFD socket open failed: ${e.message}")
        null
    }

    /** Writes this side's token, then reads and compares the peer's — nothing past this point is
     *  trusted until both match. See class doc for why this mirrors the CCCD-before-data-write
     *  lesson from [MeshGattClient]. */
    private suspend fun handshakeToken(socket: Socket, token: ByteArray): Boolean = try {
        withTimeoutOrNull(WifiDirectTuning.TOKEN_HANDSHAKE_TIMEOUT_MS) {
            val out = DataOutputStream(socket.getOutputStream())
            out.writeInt(token.size)
            out.write(token)
            out.flush()
            val din = DataInputStream(socket.getInputStream())
            val peerLen = din.readInt()
            val peerToken = ByteArray(peerLen)
            din.readFully(peerToken)
            CryptoUtils.constantTimeEquals(token, peerToken)
        } ?: false
    } catch (e: Exception) {
        Log.w(TAG, "WFD token handshake failed: ${e.message}")
        false
    }

    // ---------------- chunk transfer ----------------
    // Reuses MeshFrameCodec's existing FRAME_EVID_CHUNK encode/decode verbatim — the exact same
    // frame format and RelayEngine.ingestChunk ingestion path as BLE, just a different transport
    // underneath. Length-prefixed since a raw socket stream has no natural frame boundary the way
    // a single BLE characteristic write does.

    private fun sendChunks(socket: Socket, chunks: List<EvidenceChunkEntity>) {
        val out = DataOutputStream(socket.getOutputStream())
        for (chunk in chunks) {
            val bytes = MeshFrameCodec.encodeChunk(chunk)
            out.writeInt(bytes.size)
            out.write(bytes)
        }
        out.flush()
    }

    private suspend fun receiveChunks(socket: Socket, onChunk: suspend (EvidenceChunkEntity) -> Unit) {
        val din = DataInputStream(socket.getInputStream())
        while (true) {
            val len = try { din.readInt() } catch (e: EOFException) { break }
            val bytes = ByteArray(len)
            din.readFully(bytes)
            val frame = MeshFrameCodec.decode(bytes)
            if (frame is MeshFrameCodec.Frame.EvidChunk) onChunk(frame.chunk)
        }
    }

    private suspend fun awaitReadyTime(readyAtEpochMs: Long) {
        val waitMs = readyAtEpochMs - System.currentTimeMillis()
        if (waitMs > 0) delay(waitMs)
    }

    /** WFD groups left open are a real, separate cost (can interfere with the phone's normal
     *  WiFi/hotspot use) — teardown is not optional cleanup, it's required on every exit path. */
    private fun teardown(m: WifiP2pManager?, c: WifiP2pManager.Channel?) {
        try { currentSocket?.close() } catch (_: Exception) {}
        try { currentServerSocket?.close() } catch (_: Exception) {}
        currentSocket = null
        currentServerSocket = null
        try { if (m != null && c != null) m.removeGroup(c, null) } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "WifiDirectAccelerator"
        // Deterministic-by-role GO election (see WifiDirectHandoffCoordinator's class doc) — the
        // initiator (holds the chunks) proposes the higher intent, the responder the lower one.
        private const val INITIATOR_GO_INTENT = 15
        private const val PEER_DISCOVERY_WINDOW_MS = 3_000L
        private const val CONNECTION_POLL_INTERVAL_MS = 500L
    }
}
