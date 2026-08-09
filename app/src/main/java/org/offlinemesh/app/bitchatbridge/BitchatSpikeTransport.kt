package org.offlinemesh.app.bitchatbridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.offlinemesh.app.BuildConfig
import org.offlinemesh.app.diagnostics.DiagnosticsLog
import java.security.SecureRandom
import java.util.UUID
import kotlin.coroutines.resume

/**
 * P7 spike (`PLAN-v2.md` Part 7 / `docs/DECISIONS.md` decision 51's own "hard dependency, not
 * skippable" note, decision 55 for this tool itself) — **not the real bridge**. Fires once per
 * call: scans for a real bitchat node's own advertised service, connects, writes ONE forged
 * `BitchatPacketEncoder.encodeGroupMessage` packet to their characteristic, disconnects. No
 * decrypt, no real payload, no production wiring anywhere near `MeshService`/`RelayResponder` —
 * this class is never constructed by them and touches nothing of this app's own mesh.
 *
 * **What a successful call actually confirms, and what it doesn't.** A clean write completion
 * confirms bitchat's own peripheral GATT server accepted a structurally well-formed
 * `groupMessage` packet from an unfamiliar sender with no prior handshake — the first, necessary
 * half of decision 51's hard dependency. It does NOT by itself confirm multi-hop relay actually
 * happened; that needs a third device (or physical movement) checking whether this call's
 * [markerHex] keeps showing up further from the injection point than one BLE hop should reach —
 * a raw HCI snoop capture is the practical way to check that, a separate manual step from what
 * this class does.
 *
 * **Debug-only by construction**, same boundary [DiagnosticsLog] already sits behind — this is a
 * one-off validation probe, not a feature, and has no reason to exist in a release build at all.
 *
 * **bitchat advertises two different service UUIDs** depending on their own build config — the
 * release/mainnet one and a separate `#if DEBUG` testnet variant. [scanForBitchatNode] matches
 * either, and [DiagnosticsLog] records which one actually answered, since a debug bitchat install
 * will never answer on the mainnet UUID and this is a one-line, easy-to-miss reason for a "no
 * device found" result that isn't actually about relay behavior at all.
 */
class BitchatSpikeTransport(private val context: Context) {

    /** Scans for either of bitchat's own service UUIDs, connects to the first match, writes one
     *  forged `groupMessage` packet carrying a random marker, and disconnects. Returns the marker
     *  (hex-encoded, safe to note down / grep for in a packet capture) on a clean write, or null on
     *  any failure — every failure mode is also logged to [DiagnosticsLog] under tag `bitchat-
     *  spike` with enough detail to tell "no bitchat node in range" apart from "found one but the
     *  write itself failed." */
    suspend fun sendTestPacket(): String? {
        if (!BuildConfig.DEBUG) return null
        val (device, matchedUuid) = findBitchatNode() ?: return null
        val markerBytes = ByteArray(MARKER_BYTES).also { SecureRandom().nextBytes(it) }
        val markerHex = markerBytes.joinToString("") { "%02x".format(it) }
        val senderId = ByteArray(BitchatPacketEncoder.SENDER_ID_BYTES).also { SecureRandom().nextBytes(it) }
        val packet = BitchatPacketEncoder.encodeGroupMessage(senderId, ("20.07-SPIKE-$markerHex").toByteArray())
        val wrote = withTimeoutOrNull(WRITE_TIMEOUT_MS) { connectAndWrite(device, packet) } ?: false
        DiagnosticsLog.event(
            "bitchat-spike",
            if (wrote) "wrote ${packet.size}B ok, marker=$markerHex" else "write failed or timed out"
        )
        return markerHex.takeIf { wrote }
    }

    /** Adapter check + scan, split out of [sendTestPacket] purely so its own two failure-logging
     *  branches don't push that function's own return count past detekt's limit — mirrors
     *  [L2capBulkTransport]'s own `openFor`/`connectAndWrap` split for the identical reason. */
    private suspend fun findBitchatNode(): Pair<android.bluetooth.BluetoothDevice, UUID>? {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            DiagnosticsLog.event("bitchat-spike", "no enabled Bluetooth adapter")
            return null
        }
        val found = withTimeoutOrNull(SCAN_TIMEOUT_MS) { scanForBitchatNode(adapter) }
        if (found == null) {
            DiagnosticsLog.event("bitchat-spike", "no bitchat node found within ${SCAN_TIMEOUT_MS}ms")
        } else {
            DiagnosticsLog.event(
                "bitchat-spike",
                "found node ${found.first.address.take(ADDRESS_LOG_CHARS)} on ${found.second.label()}"
            )
        }
        return found
    }

    // Broad catch, deliberately, in every branch below: this is a one-shot debug probe bridging a
    // callback API into a suspend function — any of these calls throwing must resolve the waiting
    // coroutine with a logged failure rather than crash the caller, same "boundary code that can't
    // propagate" reasoning DiagnosticsLog.flush()'s own suppress gives.
    @Suppress("TooGenericExceptionCaught")
    @SuppressLint("MissingPermission")
    private suspend fun scanForBitchatNode(adapter: BluetoothAdapter): Pair<android.bluetooth.BluetoothDevice, UUID>? =
        suspendCancellableCoroutine { cont ->
            val scanner = adapter.bluetoothLeScanner
            if (scanner == null) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            val filters = listOf(SERVICE_UUID_MAINNET, SERVICE_UUID_DEBUG_TESTNET).map {
                ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build()
            }
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val matched = result.scanRecord?.serviceUuids
                        ?.map { it.uuid }
                        ?.firstOrNull { it == SERVICE_UUID_MAINNET || it == SERVICE_UUID_DEBUG_TESTNET }
                        ?: return
                    try {
                        scanner.stopScan(this)
                    } catch (_: Exception) {
                        // stopScan can throw if the adapter cycled mid-scan — the result we already
                        // have is still valid, nothing to recover.
                    }
                    if (cont.isActive) cont.resume(result.device to matched)
                }
                override fun onScanFailed(errorCode: Int) {
                    DiagnosticsLog.event("bitchat-spike", "scan failed: errorCode=$errorCode")
                    if (cont.isActive) cont.resume(null)
                }
            }
            cont.invokeOnCancellation {
                try {
                    scanner.stopScan(callback)
                } catch (_: Exception) {
                    // Best-effort only — the coroutine is already being torn down.
                }
            }
            try {
                scanner.startScan(filters, settings, callback)
            } catch (e: Exception) {
                DiagnosticsLog.event("bitchat-spike", "scan start failed: ${e.message}")
                cont.resume(null)
            }
        }

    // See scanForBitchatNode's own suppress — same boundary-code reasoning.
    @Suppress("TooGenericExceptionCaught")
    @SuppressLint("MissingPermission")
    private suspend fun connectAndWrite(device: android.bluetooth.BluetoothDevice, packet: ByteArray): Boolean =
        suspendCancellableCoroutine { cont ->
            var resumed = false
            fun finish(result: Boolean) {
                if (resumed) return
                resumed = true
                if (cont.isActive) cont.resume(result)
            }
            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        gatt.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        finish(false)
                        gatt.close()
                    }
                }
                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    val characteristic = gatt.services
                        ?.firstOrNull { it.uuid == SERVICE_UUID_MAINNET || it.uuid == SERVICE_UUID_DEBUG_TESTNET }
                        ?.getCharacteristic(CHARACTERISTIC_UUID)
                    if (characteristic == null) {
                        DiagnosticsLog.event("bitchat-spike", "characteristic not found after discovery")
                        finish(false)
                        gatt.disconnect()
                        return
                    }
                    characteristic.value = packet
                    val started = try {
                        gatt.writeCharacteristic(characteristic)
                    } catch (e: Exception) {
                        DiagnosticsLog.event("bitchat-spike", "writeCharacteristic threw: ${e.message}")
                        false
                    }
                    if (!started) finish(false)
                }
                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    finish(status == BluetoothGatt.GATT_SUCCESS)
                    gatt.disconnect()
                }
            }
            cont.invokeOnCancellation { finish(false) }
            device.connectGatt(context, false, callback)
        }

    private fun UUID.label(): String = if (this == SERVICE_UUID_MAINNET) "mainnet UUID" else "DEBUG testnet UUID"

    private companion object {
        // Confirmed against bitchat's own BLEService.swift this session (docs/DECISIONS.md
        // decisions 51/55) — the release build's advertised service/characteristic.
        val SERVICE_UUID_MAINNET: UUID = UUID.fromString("F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C")
        // bitchat's own #if DEBUG build advertises this instead — differs only in the last byte.
        val SERVICE_UUID_DEBUG_TESTNET: UUID = UUID.fromString("F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5A")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D")

        const val SCAN_TIMEOUT_MS = 15_000L
        const val WRITE_TIMEOUT_MS = 10_000L
        const val MARKER_BYTES = 4
        const val ADDRESS_LOG_CHARS = 8
    }
}
