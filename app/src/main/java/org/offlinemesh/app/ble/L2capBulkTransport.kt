package org.offlinemesh.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.offlinemesh.app.diagnostics.DiagnosticsLog
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * P5 item 3 (`PLAN-v2.md` §4.3, `docs/DECISIONS.md`'s own entry for this slice) — a real bulk pipe,
 * replacing GATT's ~400-byte-ATT-write-per-round-trip ceiling with a BLE L2CAP connection-oriented
 * channel (`createInsecureL2capChannel`, API 29+): a socket with credit-based flow control instead
 * of one characteristic write per round trip through [GattOperationQueue]. Only
 * [MeshFrameCodec.FRAME_SYMBOL_REQUEST]/`FRAME_EVID_SYMBOL` traffic ever moves over this — every
 * other frame type stays on GATT (see [RelayResponder.handleSymbolRequest]'s own doc for why that
 * scope is deliberate, not an oversight).
 *
 * **NOT device-tested.** Written against the documented `BluetoothAdapter.
 * listenUsingInsecureL2capChannel`/`BluetoothDevice.createInsecureL2capChannel` API surface, same
 * "broad catch, fail closed, never throws out of this class" discipline the retired
 * `WifiDirectAccelerator` used for its own unverified radio calls. A spike under Robolectric
 * (this session, not kept as a permanent test) confirmed `listenUsingInsecureL2capChannel()` does
 * NOT throw there, but returns a non-functional stub server socket (`psm = -1`) — Robolectric's
 * Bluetooth shadows have no real loopback simulation for this API, unlike the `java.net.Socket`
 * loopback pairs the retired `WifiDirectAcceleratorSocketTest` could use for WFD's plain-TCP
 * sockets. [BulkFraming] (pure `java.io` stream framing, no Bluetooth dependency) is what's
 * actually unit-tested here; connection establishment itself is compile-verified only, same
 * category `WifiDirectAccelerator`'s own `WifiP2pManager` mechanics already lived in.
 *
 * **API-29 floor is real, not theoretical.** This project's own `minSdk` is 26 — devices on 26-28
 * can never use this path at all, so GATT's existing 400-byte chunking is not merely "the universal
 * fallback" in name; it is the ONLY path on three OS versions this app still targets, and must stay
 * correct and maintained indefinitely, not treated as legacy code on its way out.
 *
 * **No initiator/responder role restriction, unlike the retired WFD accelerator.** WFD's
 * `WifiP2pManager.connect()` performed stateful GROUP FORMATION — two sides racing to call it could
 * corrupt shared P2P group state, which is why only one side (the initiator) was ever allowed to
 * dial. An L2CAP CoC `connect()` is an ordinary socket connect over a BLE ACL link that ALREADY
 * exists (the two devices are already GATT-connected) — there is no shared group state to corrupt.
 * The worst case of both sides racing to open a channel to each other is a harmless duplicate
 * channel, not corrupted topology, so [openFor] only needs to collapse concurrent attempts into one
 * (a per-address [Mutex]), not prevent them outright by role.
 */
class L2capBulkTransport(private val serviceScope: CoroutineScope) {

    /** Set once, after [RelayResponder] is constructed — same post-construction wiring
     *  `MeshService`'s `beaconRadio` scan-callback lambda already uses for an analogous
     *  construction-order cycle (this class must exist before [RelayResponder] can be built with a
     *  reference to [openFor], but routing a received frame needs [RelayResponder] to already
     *  exist). Defaults to a no-op so a frame arriving before wiring completes is dropped, not
     *  crashed on — the same window every other lazily-wired collaborator in this app already
     *  tolerates. */
    var onFrame: suspend (peerAddress: String, frame: ByteArray, respond: suspend (ByteArray) -> Unit) -> Unit =
        { _, _, _ -> }

    /** The PSM this device is currently listening on, or null if not listening (pre-API-29, no
     *  adapter, or `listenUsingInsecureL2capChannel` itself failed) — [RelayResponder]'s
     *  `localL2capPsm` collaborator reads this to decide whether to advertise
     *  [MeshFrameCodec.Frame.L2capCap] at all. */
    @Volatile var advertisedPsm: Int? = null
        private set

    private val channels = ConcurrentHashMap<String, RealBulkChannel>()
    private val knownDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val openLocks = ConcurrentHashMap<String, Mutex>()

    @Volatile private var serverSocket: BluetoothServerSocket? = null

    /** Records the [BluetoothDevice] backing [address]'s current BLE connection — called by both
     *  `MeshGattClient` and `MeshGattServer` at connection time, mirroring
     *  `RelayResponder.resetSessionBudget`'s own "called at the start of every connection by both
     *  roles" precedent. Needed because [openFor] is reached via [RelayResponder]'s role-agnostic
     *  `handleL2capCap`, which has no `BluetoothDevice` of its own to dial with — only whichever
     *  GATT role actually holds the live connection does. */
    fun noteDevice(address: String, device: BluetoothDevice) {
        knownDevices[address] = device
    }

    /** Already-open channel for [peerAddress], if any — read directly by
     *  `RelayResponder.handleSymbolRequest` to prefer this over GATT's own `respond`. */
    fun channelFor(peerAddress: String): BulkChannel? = channels[peerAddress]

    /** Called from both GATT roles' own disconnect handling, alongside their existing
     *  `writeQueue.clear`/`negotiatedMtu.remove` cleanup — an L2CAP channel's lifetime is tied to
     *  the BLE connection that carried its negotiation, so it has no reason to outlive it. */
    fun closeFor(address: String) {
        channels.remove(address)?.close()
        knownDevices.remove(address)
        openLocks.remove(address)
    }

    /** Mirrors the retired `WifiDirectAccelerator.abortCurrent` — idempotent, called from
     *  `MeshService.setMeshActive(false)`/`onDestroy`. */
    fun closeAll() {
        for (address in channels.keys.toList()) closeFor(address)
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Log.w(TAG, "closing L2CAP server socket failed: ${e.message}")
        }
        serverSocket = null
        advertisedPsm = null
    }

    /** [RelayResponder]'s `bulkChannelOpener` collaborator — called from `handleL2capCap` once a
     *  peer's advertised PSM is known. Returns the already-open channel unchanged if one exists
     *  (idempotent); on API &lt; 29 or an unknown [peerAddress] (no live connection noted via
     *  [noteDevice] yet), returns null without attempting anything. */
    @SuppressLint("MissingPermission")
    suspend fun openFor(peerAddress: String, psm: Int): BulkChannel? {
        val existing = channels[peerAddress]
        if (existing != null) return existing
        val device = knownDevices[peerAddress]
        return if (device != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectAndWrap(peerAddress, device, psm)
        } else {
            null
        }
    }

    /** The actual connect attempt, split out of [openFor] purely so its own lock/retry/error-
     *  handling shape doesn't thread extra returns through [openFor] itself — mirrors
     *  [FountainDecoder]'s own `addSymbol`/`tryInsert` split for the identical reason. */
    private suspend fun connectAndWrap(peerAddress: String, device: BluetoothDevice, psm: Int): BulkChannel? {
        val lock = openLocks.getOrPut(peerAddress) { Mutex() }
        return lock.withLock {
            val wonRace = channels[peerAddress]
            if (wonRace != null) return@withLock wonRace // opened while we waited for the lock
            try {
                val socket = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        val s = device.createInsecureL2capChannel(psm)
                        s.connect()
                        s
                    }
                }
                if (socket == null) {
                    DiagnosticsLog.event(
                        "l2cap",
                        "connect timed out: ${peerAddress.take(PEER_ID_LOG_CHARS)}"
                    )
                    null
                } else {
                    DiagnosticsLog.event(
                        "l2cap",
                        "connected out (dialed): ${peerAddress.take(PEER_ID_LOG_CHARS)}"
                    )
                    wrap(peerAddress, socket)
                }
            } catch (e: IOException) {
                Log.w(TAG, "L2CAP connect to $peerAddress failed: ${e.message}")
                DiagnosticsLog.event(
                    "l2cap",
                    "connect failed: ${peerAddress.take(PEER_ID_LOG_CHARS)} (${e.message})"
                )
                null
            }
        }
    }

    /** Opens the device-level listening socket once per radio session, alongside the GATT server
     *  (`MeshGattServer.start`) — not per-connection, the same "one server object for the whole
     *  session" shape the GATT server itself already has. Sets [advertisedPsm] on success. Safe to
     *  call on every `start()`/adapter-toggle cycle; failure just leaves [advertisedPsm] null. */
    @SuppressLint("MissingPermission")
    fun startListening(adapter: BluetoothAdapter?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || adapter == null) return
        try {
            val server = adapter.listenUsingInsecureL2capChannel()
            serverSocket = server
            advertisedPsm = server.psm
            DiagnosticsLog.event("l2cap", "listening, psm=${server.psm}")
            serviceScope.launch(Dispatchers.IO) { acceptLoop(server) }
        } catch (e: IOException) {
            Log.w(TAG, "L2CAP listen failed: ${e.message}")
            DiagnosticsLog.event("l2cap", "listen failed: ${e.message}")
            advertisedPsm = null
        }
    }

    private suspend fun acceptLoop(server: BluetoothServerSocket) {
        while (true) {
            val socket = try {
                withContext(Dispatchers.IO) { server.accept() }
            } catch (_: IOException) {
                // Either closeAll() closed the listening socket (expected, the loop's own exit) or
                // a real accept failure — either way, nothing left to do but stop accepting.
                return
            }
            val address = socket.remoteDevice.address
            knownDevices[address] = socket.remoteDevice
            DiagnosticsLog.event("l2cap", "connected in (accepted): ${address.take(PEER_ID_LOG_CHARS)}")
            wrap(address, socket)
        }
    }

    private fun wrap(peerAddress: String, socket: BluetoothSocket): BulkChannel {
        val channel = RealBulkChannel(socket)
        channels[peerAddress] = channel
        serviceScope.launch(Dispatchers.IO) { channel.receiveLoop(peerAddress) }
        return channel
    }

    private inner class RealBulkChannel(private val socket: BluetoothSocket) : BulkChannel {
        override suspend fun send(frame: ByteArray): Boolean = try {
            val padded = MeshFrameCodec.padGattFrame(frame)
            withContext(Dispatchers.IO) { BulkFraming.writeFrame(socket.outputStream, padded) }
            true
        } catch (_: IOException) {
            false
        }

        override fun close() {
            try {
                socket.close()
            } catch (e: IOException) {
                Log.w(TAG, "closing L2CAP socket failed: ${e.message}")
            }
        }

        suspend fun receiveLoop(peerAddress: String) {
            try {
                while (true) {
                    val frame = withContext(Dispatchers.IO) {
                        BulkFraming.readFrame(socket.inputStream)
                    }?.let { MeshFrameCodec.unpadGattFrame(it) } ?: break
                    onFrame(peerAddress, frame) { resp -> send(resp) }
                }
            } catch (e: IOException) {
                Log.w(TAG, "L2CAP receive loop for $peerAddress ended: ${e.message}")
                DiagnosticsLog.event(
                    "l2cap",
                    "channel closed: ${peerAddress.take(PEER_ID_LOG_CHARS)} (${e.message})"
                )
            } finally {
                channels.remove(peerAddress, this)
                close()
            }
        }
    }

    companion object {
        private const val TAG = "L2capBulkTransport"

        // No group-formation overhead to wait out (see class doc) — this is just a socket connect
        // over an already-live BLE link, so a short timeout is enough to avoid a hung coroutine
        // without inventing a WFD-scale multi-second budget for a fundamentally different operation.
        private const val CONNECT_TIMEOUT_MS = 10_000L

        // Only this much of a peer address goes into the exportable diagnostics log — same
        // convention/value as MeshGattClient's own private PEER_ID_LOG_CHARS.
        private const val PEER_ID_LOG_CHARS = 8
    }
}
