package org.offlinemesh.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * GATT client role: we connect out to peers we heard beaconing and push our data to them,
 * subscribing to their notifications so their push comes back over the same connection.
 *
 * **Invariant: every outbound GATT operation on a connection — writes, the CCCD descriptor write
 * included — is serialized through [writeQueue] against that connection's address**, so nothing
 * goes out until the previous operation's completion callback has actually fired. `BluetoothGatt`
 * allows exactly one outstanding operation per connection, of any kind; see `docs/DECISIONS.md`,
 * decision 2, for the live-tested asymmetry ("I can see them but can't send") that not having this
 * produced.
 *
 * Connection duration is activity-based, not a fixed timer: a connection stays open while writes
 * or notifications are still happening on it, and closes once it's been idle for
 * [BleTuning.Profile.connectionIdleMs] or hit the [BleTuning.Profile.connectionMaxMs] hard cap —
 * whichever comes first, so two stationary phones mid-transfer aren't cut off just because a fixed
 * timer expired. RelayResponder's own per-session chunk budget (`maxChunksPerSession`) is what
 * keeps one busy peer from starving the rotation through others even with a longer-lived
 * connection.
 */
class MeshGattClient(
    private val context: Context,
    private val responder: RelayResponder,
    private val serviceScope: CoroutineScope,
    private val currentTier: () -> MeshService.PowerTier,
) {
    private val writeQueue = GattOperationQueue()
    private val maxConcurrentClientConnections = 3
    private val reconnectCooldownMs = 45_000L
    // Peer-selection lever (see ConnectionAttemptTracker's class doc) — DELIBERATELY NEUTRALIZED
    // (equal to reconnectCooldownMs, i.e. no behavior change): a longer cooldown here for an
    // already-synced peer once broke radar freshness (position dots refresh only via GATT
    // reconnect) — see docs/DECISIONS.md, decision 5, before raising this above 90s, and re-derive
    // any future value from the shorter of PositionTracker's/HopTracker's staleness windows, not
    // picked independently.
    private val syncedReconnectCooldownMs = reconnectCooldownMs
    private val connectTimeoutMs = 15_000L
    // The connection-attempt state machine itself lives in ConnectionAttemptTracker (unit-tested in
    // isolation there) — this class only owns the real connectGatt() call, the real timeout delay,
    // and closing the gatt object.
    private val attemptTracker = ConnectionAttemptTracker(
        maxConcurrentClientConnections, reconnectCooldownMs, syncedReconnectCooldownMs,
        currentEpoch = { responder.catalogEpoch }
    )
    // Last write/notify time per peer address — drives the idle-based disconnect below.
    private val lastActivity = ConcurrentHashMap<String, Long>()
    private fun touch(address: String) { lastActivity[address] = System.currentTimeMillis() }
    // Negotiated MTU per peer address, recorded in onMtuChanged — see pushOnConnect's use of it to
    // size RelayResponder.framesToPushOnConnect's catalog-filter-vs-eager-push decision to what
    // this specific connection can actually carry in one write. Cleared on disconnect alongside
    // lastActivity/writeQueue, same reasoning: a BLE address rotates every ~15min, so this isn't a
    // stable identity worth remembering past one connection's lifetime.
    private val negotiatedMtu = ConcurrentHashMap<String, Int>()
    // Set once pushOnConnect actually ran for this connection (i.e. we got far enough to offer our
    // content, not necessarily that every frame succeeded) — read once by onConnectionStateChange's
    // disconnect branch to pick which of the two cooldowns above applies, then cleared either way.
    private val syncedThisSession = ConcurrentHashMap.newKeySet<String>()

    @Synchronized
    @SuppressLint("MissingPermission")
    fun maybeConnect(device: BluetoothDevice) {
        val addr = device.address
        if (!attemptTracker.canAttempt(addr)) return
        attemptTracker.attemptStarted(addr)
        val gatt = device.connectGatt(context, false, callback)
        // Guard against connectGatt() never calling onConnectionStateChange at all — a real,
        // undocumented Android BLE failure mode (peer out of range mid-attempt, or Bluetooth
        // toggled while pending) that otherwise left a peer's address marked "connecting" forever
        // — see docs/DECISIONS.md, decision 5. If nothing has called back for this exact address
        // by the time this fires, force the cleanup ourselves instead of waiting forever.
        serviceScope.launch {
            delay(connectTimeoutMs)
            if (attemptTracker.isStuck(addr)) {
                Log.w("MeshGattClient", "connect attempt to $addr never got a callback — forcing cleanup")
                try { gatt.close() } catch (_: Exception) {}
                attemptTracker.connectionEnded(addr)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun write(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, data: ByteArray): Boolean =
        writeQueue.run(gatt.device.address) {
            characteristic.value = data
            try { gatt.writeCharacteristic(characteristic) } catch (e: Exception) { false }
        }

    @SuppressLint("MissingPermission")
    private suspend fun writeDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor): Boolean =
        writeQueue.run(gatt.device.address) {
            try { gatt.writeDescriptor(descriptor) } catch (e: Exception) { false }
        }

    private suspend fun pushOnConnect(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val address = gatt.device.address
        val mtu = negotiatedMtu[address] ?: MeshProtocol.DEFAULT_ATT_MTU
        val maxFrameBytes = mtu - MeshProtocol.ATT_WRITE_OVERHEAD_BYTES
        for (bytes in responder.framesToPushOnConnect(maxFrameBytes)) {
            write(gatt, characteristic, bytes)
        }
        // Reaching here means we got through MTU negotiation, service discovery, and the CCCD write
        // well enough to actually offer our content — that's "synced" for cooldown purposes even if
        // an individual frame above failed; see syncedReconnectCooldownMs.
        syncedThisSession.add(address)
    }

    /** Waits until either the connection has been idle for [BleTuning.Profile.connectionIdleMs] or
     *  has run for [BleTuning.Profile.connectionMaxMs] total, whichever comes first. */
    private suspend fun awaitIdleOrCap(address: String) {
        val profile = BleTuning.forTier(currentTier())
        val start = System.currentTimeMillis()
        while (true) {
            val now = System.currentTimeMillis()
            if (now - start >= profile.connectionMaxMs) return
            val idleFor = now - (lastActivity[address] ?: start)
            if (idleFor >= profile.connectionIdleMs) return
            delay(500)
        }
    }

    @SuppressLint("MissingPermission")
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            attemptTracker.callbackReceived(gatt.device.address)
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.requestMtu(517)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val address = gatt.device.address
                attemptTracker.connectionEnded(address, synced = syncedThisSession.remove(address))
                writeQueue.clear(address)
                lastActivity.remove(address)
                negotiatedMtu.remove(address)
                gatt.close()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            // Only record a genuinely successful negotiation — on failure, pushOnConnect's fallback
            // to MeshProtocol.DEFAULT_ATT_MTU (the safe, pre-negotiation floor) is more honest than
            // trusting whatever value a failed request happened to report.
            if (status == BluetoothGatt.GATT_SUCCESS) negotiatedMtu[gatt.device.address] = mtu
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val characteristic = gatt.getService(MeshProtocol.SERVICE_UUID)
                ?.getCharacteristic(MeshProtocol.RELAY_CHAR_UUID) ?: run { gatt.disconnect(); return }
            gatt.setCharacteristicNotification(characteristic, true)
            responder.resetSessionBudget(gatt.device.address)
            serviceScope.launch {
                val cccd = characteristic.getDescriptor(MeshGattServer.CCCD_UUID)
                if (cccd != null) {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    // Must complete before any data write goes out on this connection — see the
                    // class doc above for the bug this fixes.
                    writeDescriptor(gatt, cccd)
                }
                touch(gatt.device.address)
                pushOnConnect(gatt, characteristic)
                awaitIdleOrCap(gatt.device.address)
                try { gatt.disconnect() } catch (_: Exception) {}
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            touch(gatt.device.address)
            writeQueue.complete(gatt.device.address, status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            touch(gatt.device.address)
            writeQueue.complete(gatt.device.address, status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val address = gatt.device.address
            touch(address)
            serviceScope.launch {
                responder.handleIncoming(characteristic.value, address) { respBytes -> write(gatt, characteristic, respBytes) }
            }
        }
    }
}
