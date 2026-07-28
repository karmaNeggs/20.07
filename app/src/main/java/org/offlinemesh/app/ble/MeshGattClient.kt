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
 * The bug this class exists to prevent: BluetoothGatt allows exactly one outstanding operation
 * per connection, of any kind. The original code fired the CCCD subscription write and then,
 * without waiting for it — there was no onDescriptorWrite handler at all — immediately started
 * writing data frames on the same connection. That collision meant the peer we *connected to*
 * (the one we could see) never reliably received anything from us, while the peer's own
 * server-side notifications back to us still worked fine, since those weren't racing anything on
 * our side. That is the exact "I can see them but can't send; they can't see me but their
 * message gets through" asymmetry reported from live testing — [writeQueue] now serializes every
 * operation (descriptor write included) against a single address, so nothing on this connection
 * can go out until the previous thing actually completed.
 *
 * Connection duration is activity-based, not a fixed timer: a connection stays open while writes
 * or notifications are still happening on it (evidence chunks arriving/leaving), and closes once
 * it's been idle for [BleTuning.Profile.connectionIdleMs] or hit the [BleTuning.Profile.connectionMaxMs]
 * hard cap — whichever comes first. Previously every connection was cut at a flat 8s regardless of
 * whether a transfer was still making real progress, so two stationary phones mid-transfer would
 * get disconnected and have to wait out [reconnectCooldownMs] before resuming. RelayResponder's own
 * per-session chunk budget (`maxChunksPerSession`) is what keeps one busy peer from starving the
 * rotation through others even with a longer-lived connection.
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
    // (equal to reconnectCooldownMs, i.e. no behavior change) as of Pass 20. An earlier version of
    // this pass set it to 180s, reasoning that an already-synced peer could afford to wait. That
    // was wrong: position dots are ONLY refreshed via a GATT reconnect (there's no separate
    // lightweight position channel), and PositionTracker/HopTracker expire a peer at 90s of no
    // update — a 180s cooldown would let an in-range, unmoving member's radar dot silently vanish
    // and reappear, which is the opposite of what "follow members on radar" needs. The
    // peer-selection idea (bias limited connection slots toward not-yet-synced peers in a dense
    // crowd) is still real and still worth having once there's actual multi-device density data to
    // tune it against — until then it's not worth trading radar freshness for an unvalidated guess,
    // especially right before a live test. Change this back above 90s only with real crowd-density
    // evidence that it's needed, and re-derive it from the *shorter* of PositionTracker's and
    // HopTracker's staleness windows, not picked independently.
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
        // undocumented Android BLE failure mode (most commonly the peer going out of range mid-
        // attempt, or Bluetooth itself getting toggled while a connection is pending). Without this,
        // that peer's address stays marked "connecting" forever and maybeConnect() silently refuses
        // to ever try it again — this was reported as "far away, connection breaks and doesn't come
        // back," "Bluetooth off/on breaks things," and, since every message exchange opens fresh
        // connection attempts, plausibly also "breaks after a handful of messages" (more attempts,
        // more chances to hit a connection that never resolves). If nothing has called back for this
        // exact address by the time this fires, force the cleanup ourselves instead of waiting
        // forever for a callback that isn't coming.
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
        for (bytes in responder.framesToPushOnConnect()) {
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
                gatt.close()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
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
