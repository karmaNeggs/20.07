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
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * GATT server role: peers connect to us and push data our way; we push ours back as
 * notifications once they subscribe. All outbound notifications for one peer are serialized
 * through [writeQueue] so a manifest push can never race a reactive response frame.
 */
class MeshGattServer(
    private val context: Context,
    private val bluetoothManager: BluetoothManager,
    private val responder: RelayResponder,
    private val serviceScope: CoroutineScope,
) {
    private var gattServer: BluetoothGattServer? = null
    private val subscribedDevices = ConcurrentHashMap.newKeySet<BluetoothDevice>() // mutated from BLE callback threads
    private val writeQueue = GattOperationQueue()

    // Unlike MeshGattClient (capped at 3 outbound connections), incoming connections here have no
    // cap — anyone who can see our advertisement can connect, and nothing stops a dense crowd from
    // piling more simultaneous inbound links onto us than a chipset's shared central+peripheral GATT
    // pool (commonly ~4-7 total) can handle. That's a real gap for this app's ~10-person/100m²
    // target, but an *enforced* cap (actively cancelling connections over the limit) was tried in
    // this same pass and immediately caused total, symmetric mesh failure in live testing — a
    // BluetoothDevice-object-keyed tracking set failed to dedupe the same physical peer across this
    // app's routine ~45s reconnects, so the (miscounted) cap was crossed within a couple of cycles
    // even with only 2-3 real phones, and every connection after that got cancelled. Re-enabling
    // enforcement needs: (1) confirmation the fix below (address-keyed, not object-keyed) is
    // actually sufficient — Pass 16 found an analogous "disconnect callback never fires" failure
    // mode on the *client* role that needed an explicit forced-timeout cleanup; the server role
    // hasn't been proven immune to the same thing, so a leak here could still happen silently over
    // a long-running session even with the key type fixed. Until then this only counts and logs,
    // never rejects — a real crowd will find the actual failure mode (or lack of one) faster and
    // more honestly than another guess would.
    @Suppress("MagicNumber") // self-documented by the property name + the comment above
    private val maxConcurrentServerConnections = 4
    // Keyed by address, like every other per-peer structure in this class (writeQueue,
    // subscribedDevices notwithstanding) and in MeshGattClient (lastActivity, ConnectionAttemptTracker,
    // PeerDeliveryTracker) — NOT by the BluetoothDevice object itself. A first version of this used
    // Set<BluetoothDevice>, which does not reliably dedupe the *same* physical peer across the
    // repeated reconnects this app does every ~45s (BluetoothDevice has no guaranteed stable
    // equals() across separate callback deliveries from the stack) — stale entries piled up, the
    // cap was crossed within a couple of reconnect cycles even with only 2-3 real phones, and every
    // connection after that got cancelled immediately: total, symmetric mesh failure (no radar, no
    // messages, no SOS) caught in live testing.
    private val connectedDevices = ConcurrentHashMap.newKeySet<String>()

    @SuppressLint("MissingPermission")
    fun start() {
        gattServer = bluetoothManager.openGattServer(context, callback)
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

    @SuppressLint("MissingPermission")
    private suspend fun notify(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic, data: ByteArray): Boolean {
        val server = gattServer ?: return false
        return writeQueue.run(device.address) {
            characteristic.value = data
            try { server.notifyCharacteristicChanged(device, characteristic, false) } catch (e: Exception) { false }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun pushOnConnect(device: BluetoothDevice) {
        val server = gattServer ?: return
        val service = server.getService(MeshProtocol.SERVICE_UUID) ?: return
        val characteristic = service.getCharacteristic(MeshProtocol.RELAY_CHAR_UUID) ?: return
        val address = device.address
        for (item in responder.framesToPushOnConnect(address)) {
            val ok = notify(device, characteristic, item.bytes)
            // Only mark delivered on a confirmed successful notify — see MeshGattClient's identical
            // comment: an optimistic mark would let a failed send silently never retry.
            if (ok && item.dedupKey != null) responder.markDelivered(address, item.dedupKey)
        }
    }

    @SuppressLint("MissingPermission")
    private val callback = object : BluetoothGattServerCallback() {
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            subscribedDevices.add(device)
            if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            responder.resetSessionBudget(device.address)
            serviceScope.launch { pushOnConnect(device) }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            serviceScope.launch {
                responder.handleIncoming(value, device.address) { respBytes -> notify(device, characteristic, respBytes) }
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            writeQueue.complete(device.address, status == BluetoothGatt.GATT_SUCCESS)
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
            }
        }
    }

    companion object {
        private const val TAG = "MeshGattServer"
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
