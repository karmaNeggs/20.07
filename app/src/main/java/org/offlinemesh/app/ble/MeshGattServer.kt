package org.offlinemesh.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * GATT server role: peers connect to us and push data our way; we push ours back as
 * notifications once they subscribe. All outbound notifications for one peer are serialized
 * through [writeQueue] so a manifest push can never race a reactive response frame.
 *
 * **Cross-peer notify race (fixed).** [BluetoothGattServer.notifyCharacteristicChanged]'s
 * pre-API-33 overload has no `value` parameter — it notifies whatever is currently set on the
 * *shared* [BluetoothGattCharacteristic] instance this whole service owns. [writeQueue] only ever
 * serialized notifies *to the same address*; two concurrent notifies to two *different* peers had
 * no mutual exclusion at all, so peer A's bytes could be delivered to peer B. Inbound connections
 * are deliberately uncapped (see [maxConcurrentServerConnections]'s doc), so this was live at
 * exactly the density this app targets. Android 13 (API 33) added an overload that takes `value`
 * as a parameter instead of shared state, which sidesteps the race entirely; below that, [notify]
 * serializes the ENTIRE operation — the synchronous set-and-notify call *and* the async wait for
 * [BluetoothGattServerCallback.onNotificationSent] — behind one server-wide [notifyLegacyMutex],
 * not a per-address one, since the shared characteristic's value must stay untouched by any other
 * peer's notify until this one has actually completed at the controller level, not just been
 * issued.
 */
@Suppress("LongParameterList") // one collaborator per constructor param, same shape RelayResponder
// and MeshGattClient already use elsewhere in this file — not a candidate for a params-object
// without adding an abstraction this codebase doesn't otherwise use.
class MeshGattServer(
    private val context: Context,
    private val bluetoothManager: BluetoothManager,
    private val responder: RelayResponder,
    private val serviceScope: CoroutineScope,
    // Shared with MeshGattClient and RelayResponder (PLAN-v2.md P1 §5.3) — see
    // ConnectionRegistry's class doc.
    private val connectionRegistry: ConnectionRegistry,
    // Same shared instance RelayResponder writes to (PLAN-v2.md §5.2 / P0b). Read only here, to
    // resolve a registry key consistent with every other peer-keyed view in this app.
    private val peerIdentity: PeerIdentityResolver,
    // Drives BleTuning.Profile.presenceRefreshIntervalMs for the periodic presence/position
    // refresh on held connections — see periodicRefresh's doc / decision 20.
    private val currentTier: () -> MeshService.PowerTier,
    // Shared with MeshGattClient and RelayResponder (P5 item 3, docs/DECISIONS.md's own entry for
    // this slice) — start() opens the device-level L2CAP listening socket here, alongside the GATT
    // server itself; this side also records/tears down the BluetoothDevice for its own inbound
    // connections, same as MeshGattClient does for outbound ones.
    private val l2capTransport: L2capBulkTransport,
) {
    private var gattServer: BluetoothGattServer? = null
    private val subscribedDevices = ConcurrentHashMap.newKeySet<BluetoothDevice>() // mutated from BLE callback threads
    private val writeQueue = GattOperationQueue()

    // Unlike MeshGattClient (capped at 3 outbound connections), incoming connections here have no
    // enforced cap — anyone who can see our advertisement can connect, and nothing stops a dense
    // crowd from piling more simultaneous inbound links onto us than a chipset's shared
    // central+peripheral GATT pool (commonly ~4-7 total) can handle. Enforcing a cap here
    // previously caused total, symmetric mesh failure in live testing — see docs/DECISIONS.md,
    // decision 4, before re-attempting enforcement.
    @Suppress("MagicNumber") // self-documented by the property name + the comment above
    private val maxConcurrentServerConnections = 4
    // Keyed by address, like every other per-peer structure in this class and in MeshGattClient —
    // NOT by the BluetoothDevice object itself, which has no guaranteed stable equals() across
    // separate callback deliveries from the stack (see docs/DECISIONS.md, decision 4).
    private val connectedDevices = ConcurrentHashMap.newKeySet<String>()

    // Negotiated MTU per peer address — same purpose and same disconnect-time cleanup reasoning as
    // MeshGattClient's identical field; see pushOnConnect's use of it below.
    private val negotiatedMtu = ConcurrentHashMap<String, Int>()

    // The ConnectionRegistry key used for THIS connection's registration — captured once, at
    // registration time, and reused at unregister time. Same reasoning as MeshGattClient's
    // activeTrackerKey: peerIdentity.resolve() can start returning a different value mid-
    // connection once a presence frame teaches it who this address actually is, and re-resolving
    // at disconnect time would then unregister the wrong key, leaking the real one forever.
    private val registeredKey = ConcurrentHashMap<String, String>()

    // Only ever acquired on pre-API-33 devices (see notify's doc and the class-level "cross-peer
    // notify race" note) — API 33+ never touches shared characteristic state, so there's nothing
    // for this to protect there. A single global mutex, not one per address: the thing being
    // protected (the one shared BluetoothGattCharacteristic instance's value) is itself global,
    // not per-peer.
    private val notifyLegacyMutex = Mutex()

    @SuppressLint("MissingPermission")
    fun start() {
        gattServer = bluetoothManager.openGattServer(context, callback)
        // P5 item 3 (docs/DECISIONS.md's own entry for this slice) — one listening socket for the
        // whole radio session, not per-connection, same shape as the GATT server object itself.
        // No-ops below API 29 or if listen fails (l2capTransport.advertisedPsm stays null either
        // way) — see L2capBulkTransport's own class doc.
        l2capTransport.startListening(bluetoothManager.adapter)
        val service = BluetoothGattService(MeshProtocol.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = BluetoothGattCharacteristic(
            MeshProtocol.RELAY_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val cccd = BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        characteristic.addDescriptor(cccd)
        service.addCharacteristic(characteristic)
        gattServer?.addService(service)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        try { gattServer?.close() } catch (_: Exception) {}
    }

    /** See the class-level "cross-peer notify race" doc for why this branches on API level. */
    @SuppressLint("MissingPermission")
    private suspend fun notify(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic, data: ByteArray): Boolean {
        val server = gattServer ?: return false
        // Bucket-padded here, not by the caller — every outgoing frame goes through this one
        // function, so this is the choke point where padding applies uniformly to all frame types
        // without each call site having to remember to pad. See padGattFrame's own doc.
        val padded = MeshFrameCodec.padGattFrame(data)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // value is a parameter here, not shared characteristic state — two concurrent notifies
            // to different peers can never cross-deliver each other's bytes, so per-address
            // serialization (writeQueue) is all that's needed, same as every other write in this app.
            writeQueue.run(device.address) {
                try {
                    server.notifyCharacteristicChanged(device, characteristic, false, padded) ==
                        BluetoothStatusCodes.SUCCESS
                } catch (e: Exception) { false }
            }
        } else {
            // Pre-33: held for the FULL operation, including the suspend inside writeQueue.run that
            // awaits onNotificationSent — releasing it any earlier would let a second peer's notify
            // mutate the shared characteristic's value before this one has actually completed at
            // the controller level, which is the exact race this exists to close.
            notifyLegacyMutex.withLock {
                writeQueue.run(device.address) {
                    characteristic.value = padded
                    try {
                        server.notifyCharacteristicChanged(device, characteristic, false)
                    } catch (e: Exception) { false }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun pushOnConnect(device: BluetoothDevice) {
        val server = gattServer ?: return
        val service = server.getService(MeshProtocol.SERVICE_UUID) ?: return
        val characteristic = service.getCharacteristic(MeshProtocol.RELAY_CHAR_UUID) ?: return
        val mtu = negotiatedMtu[device.address] ?: MeshProtocol.DEFAULT_ATT_MTU
        val maxFrameBytes = mtu - MeshProtocol.ATT_WRITE_OVERHEAD_BYTES
        for (bytes in responder.framesToPushOnConnect(maxFrameBytes, device.address)) {
            notify(device, characteristic, bytes)
        }
    }

    /** Server-role counterpart to `MeshGattClient.periodicRefresh` — same reasoning (decision 20):
     *  presence/position must not go stale for a persistent link's entire lifetime just because
     *  [pushOnConnect] only ever fires once, at connection start. Loops for as long as [device]
     *  stays in [connectedDevices], checked at the top of every iteration. */
    private suspend fun periodicRefresh(device: BluetoothDevice, registryKey: String) {
        while (connectedDevices.contains(device.address)) {
            delay(BleTuning.forTier(currentTier()).presenceRefreshIntervalMs)
            if (!connectedDevices.contains(device.address)) return
            pushRefresh(device, registryKey)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun pushRefresh(device: BluetoothDevice, registryKey: String) {
        val server = gattServer ?: return
        val characteristic = server.getService(MeshProtocol.SERVICE_UUID)
            ?.getCharacteristic(MeshProtocol.RELAY_CHAR_UUID) ?: return
        for (bytes in responder.refreshFramesToPush(registryKey)) {
            notify(device, characteristic, bytes)
        }
    }

    @SuppressLint("MissingPermission")
    private val callback = object : BluetoothGattServerCallback() {
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            // add() is atomic and returns false if this device was already subscribed — a client
            // whose own onServicesDiscovered re-fires (see MeshGattClient's handledGatts doc) issues
            // a second CCCD write for the same still-open link; without this guard that would spawn
            // a second periodicRefresh loop here that lives for the connection's whole lifetime.
            if (!subscribedDevices.add(device)) return
            responder.resetSessionBudget(device.address)
            l2capTransport.noteDevice(device.address, device)
            // Registered as soon as the link can actually accept a notify — see
            // ConnectionRegistry's class doc and registeredKey's doc on why the key is captured
            // once here, not re-resolved at unregister time.
            val key = peerIdentity.resolve(device.address)
            registeredKey[device.address] = key
            connectionRegistry.register(key) { bytes ->
                val characteristic = gattServer?.getService(MeshProtocol.SERVICE_UUID)
                    ?.getCharacteristic(MeshProtocol.RELAY_CHAR_UUID) ?: return@register false
                notify(device, characteristic, bytes)
            }
            serviceScope.launch { pushOnConnect(device) }
            serviceScope.launch { periodicRefresh(device, key) }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            val frame = MeshFrameCodec.unpadGattFrame(value) ?: return
            serviceScope.launch {
                responder.handleIncoming(frame, device.address) { respBytes ->
                    notify(device, characteristic, respBytes)
                }
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            writeQueue.complete(device.address, status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            // Unlike the client role's onMtuChanged, this callback carries no status — it only
            // ever fires here once the platform has actually applied a new MTU for this
            // connection, so there's no failure case to filter out the way MeshGattClient does.
            negotiatedMtu[device.address] = mtu
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedDevices.add(device.address)
                if (connectedDevices.size > maxConcurrentServerConnections) {
                    // Deliberately NOT calling cancelConnection here — see the class doc on
                    // maxConcurrentServerConnections for why enforcement is disabled for now.
                    // Logging only, so a real dense-crowd session tells us whether this actually
                    // needs enforcing before another live-tested guess breaks the mesh again.
                    Log.w(TAG, "over the soft inbound-connection cap ($maxConcurrentServerConnections): " +
                        "${connectedDevices.size} tracked — not enforced yet, see class doc")
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedDevices.remove(device.address)
                subscribedDevices.remove(device)
                writeQueue.clear(device.address)
                negotiatedMtu.remove(device.address)
                l2capTransport.closeFor(device.address)
                // Same key register() used, never a fresh resolve — see registeredKey's doc. A
                // connection that disconnects before ever subscribing (no CCCD write, so never
                // registered) has no entry here, and unregister on an absent key is a no-op.
                registeredKey.remove(device.address)?.let { connectionRegistry.unregister(it) }
            }
        }
    }

    companion object {
        private const val TAG = "MeshGattServer"
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
