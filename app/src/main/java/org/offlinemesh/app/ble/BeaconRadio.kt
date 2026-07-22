package org.offlinemesh.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.offlinemesh.app.crypto.CryptoUtils
import org.offlinemesh.app.data.GroupEntity
import org.offlinemesh.app.data.GroupRepository

/**
 * The connectionless half of the mesh: rotating-id beacon advertising and scanning for other
 * phones' beacons. Purely radio-level presence — GATT connections (actual data transfer) are a
 * separate concern owned by MeshGattClient/MeshGattServer; this class only decides "who's nearby"
 * and hands discovered devices off via [onDeviceSeen].
 *
 * Both loops favor discovery reliability over cleverness (see [BleTuning] for what got tried and
 * walked back). Scanning runs continuously once started — one `startScan()` per power tier, left
 * running, with [BleTuning.Profile.scanMode] as the only duty-cycle lever. Advertising follows the
 * same principle: the radio is only touched (stop+restart) when the *payload itself* needs to
 * change — a new rotating id window, a different group in the round-robin, a changed SOS hop — not
 * on any fixed timer. A version in between touched the radio on every loop tick regardless of
 * whether anything had changed (roughly every 700-900ms, continuously, for as long as the service
 * ran); a live 2-phone test went from "unreliable" to total, symmetric discovery failure on *both*
 * phones under that churn — consistent with the BLE stack itself getting into a bad state under
 * rapid stop/start cycling, a known category of chipset issue (see CHANGELOG Pass 7 for an earlier,
 * different instance of exactly this class of bug). With one stable group, the fix below now calls
 * `startAdvertising` roughly once every ~60 seconds (only when the rotating id actually rotates) and
 * otherwise leaves the same advertising session running untouched — the minimum possible churn.
 *
 * All frequency/power/timing numbers live in [BleTuning], not here — this class only sequences
 * them. Scan matching is a per-slot cache lookup: candidate rotating ids for every group are
 * recomputed once per slot (3 HMACs per group) rather than per scan result (which, in a crowd,
 * meant hundreds of HMACs and a DB query per second for a value that only changes every 60s).
 */
class BeaconRadio(
    private val bluetoothManager: BluetoothManager,
    private val repo: GroupRepository,
    private val hopTracker: HopTracker,
    private val serviceScope: CoroutineScope,
    private val currentTier: () -> MeshService.PowerTier,
    private val onDeviceSeen: (BluetoothDevice) -> Unit,
) {
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var advertiseJob: Job? = null
    private var scanJob: Job? = null

    // What's currently actually being transmitted — compared against the next candidate payload so
    // the radio is only stopped/restarted when something real changed, not on every loop tick.
    private var currentPayloadKey: String? = null

    // Published once per slot by the advertise loop, read (never mutated) by the scan callback on
    // a binder thread. Replaced wholesale each refresh, so a plain @Volatile reference is safe.
    @Volatile private var matchTable: Map<String, String> = emptyMap()   // rotatingId(hex) -> groupId
    @Volatile private var cachedGroups: List<GroupEntity> = emptyList()

    fun stop() {
        advertiseJob?.cancel()
        scanJob?.cancel()
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
    }

    /** 3 HMACs per group, once per slot — not per scan result. Also refreshes the active-group
     *  list the advertise round-robin walks, so neither hot path touches the DB. */
    private suspend fun refreshCaches() {
        val groups = repo.groupDao.getActiveGroups()
        val table = HashMap<String, String>(groups.size * 3)
        for (g in groups) {
            val key = repo.getGroupKey(g.id) ?: continue
            for (cand in CryptoUtils.candidateAdvertisementIds(key)) table[cand.toHex()] = g.id
        }
        matchTable = table
        cachedGroups = groups
    }

    // ---------------- advertising (beacon) ----------------

    @SuppressLint("MissingPermission")
    fun startAdvertising() {
        advertiser = bluetoothManager.adapter?.bluetoothLeAdvertiser
        // Diagnostic for the "other phone shows no one nearby" asymmetry: a device whose chipset
        // has no BLE peripheral/advertising support returns a null advertiser here, so it can scan
        // and connect but is itself invisible to everyone else's scan — which looks exactly like
        // one-directional discovery. Presence is fixed regardless via the GATT presence heartbeat
        // (see RelayResponder), but this log confirms the root cause on a specific device:
        //   adb logcat -s BeaconRadio
        val multiAdvSupported = bluetoothManager.adapter?.isMultipleAdvertisementSupported
        if (advertiser == null) {
            Log.w(TAG, "NO BLE ADVERTISER on this device — it cannot be discovered by others' scans " +
                "(BLE peripheral/advertising unsupported). It can still scan, connect, and relay.")
        } else {
            Log.i(TAG, "BLE advertiser present (isMultipleAdvertisementSupported=$multiAdvSupported)")
        }
        advertiseJob = serviceScope.launch {
            var roundRobin = 0
            while (isActive) {
                val profile = BleTuning.forTier(currentTier())
                refreshCaches()
                val groups = cachedGroups
                if (groups.isEmpty()) {
                    ensureAdvertising(profile, MeshProtocol.ADV_TYPE_GENERIC, ByteArray(MeshProtocol.ROTATING_ID_LEN), MeshProtocol.UNKNOWN_HOP, "generic")
                } else {
                    val g = groups[roundRobin % groups.size]
                    roundRobin++
                    val key = repo.getGroupKey(g.id)
                    if (key != null) {
                        val rid = CryptoUtils.rotatingAdvertisementId(key)
                        val sHop = bestSosHopFor(g.id)
                        ensureAdvertising(profile, MeshProtocol.ADV_TYPE_GROUP, rid, sHop, "${g.id}:${rid.toHex()}:$sHop")
                    }
                }
                // How often to re-check whether the payload needs to change (new rotating-id window,
                // round-robin to the next group, a shifted SOS hop) — NOT how often the radio itself
                // restarts. ensureAdvertising below only touches the radio when something actually
                // differs from what's currently transmitting.
                delay(profile.advertiseCheckIntervalMs)
            }
        }
    }

    private fun bestSosHopFor(groupId: String): Int =
        hopTracker.snapshot.value
            .filterKeys { it.groupId == groupId && it.target != "PRESENCE" }
            .values.minOrNull() ?: MeshProtocol.UNKNOWN_HOP

    /** No-ops if [payloadKey] matches what's already being transmitted — a stable single-group
     *  beacon ends up calling startAdvertising once and then not again until its rotating id
     *  actually rotates (~60s), rather than stopping/restarting on a fixed timer regardless of
     *  whether anything changed (see the class doc for why that was a real live-test bug). */
    @SuppressLint("MissingPermission")
    private fun ensureAdvertising(profile: BleTuning.Profile, type: Byte, rid: ByteArray, sHop: Int, payloadKey: String) {
        if (payloadKey == currentPayloadKey) return
        val adv = advertiser ?: return
        val payload = MeshProtocol.encodeBeacon(type, rid, sHop)
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(profile.advertiseMode)
            .setTxPowerLevel(profile.advertiseTxPower)
            .setConnectable(true)
            .build()
        // Deliberately NOT addServiceUuid() — a Service UUID list AD structure (18 bytes) plus this
        // Service Data structure overflow legacy BLE's 31-byte limit. Service Data alone carries our
        // UUID as a prefix, read back via getServiceData in the scan callback below.
        val data = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(MeshProtocol.SERVICE_UUID), payload)
            .setIncludeDeviceName(false)
            .build()
        try {
            adv.stopAdvertising(advertiseCallback)
            adv.startAdvertising(settings, data, advertiseCallback)
            currentPayloadKey = payloadKey
        } catch (e: Exception) {
            Log.w(TAG, "advertise failed: ${e.message}")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) { Log.w(TAG, "advertise start failure: $errorCode") }
    }

    // ---------------- scanning ----------------

    /** True once we're no longer hearing *any* of our groups — i.e. every group we're in has gone
     *  stale on presence (see [HopTracker.myHop]'s 90s staleness window). Empty group list doesn't
     *  count as "blind" — there's nothing to search for, so no reason to burn extra battery. */
    private fun isBlind(): Boolean {
        val groups = cachedGroups
        if (groups.isEmpty()) return false
        return groups.none { hopTracker.myHop(it.id, "PRESENCE") != MeshProtocol.UNKNOWN_HOP }
    }

    /** Starts (or restarts, if the effective scan mode changed) one continuous scan. Deliberately
     *  does NOT stop/start on a short timer — see the class doc on [BleTuning] for why that
     *  produced a real, device-dependent discovery bug. Duty-cycling is left entirely to the OS via
     *  the scan mode; we just poll every few seconds for whether the mode needs to change, and only
     *  touch the radio when it actually does.
     *
     *  The effective mode is the tier's mode, escalated to `LOW_LATENCY` whenever we're "blind" —
     *  present in a group but currently hearing no one in it — regardless of tier. This is the
     *  asymmetry the tradeoff is built on: `LOW_LATENCY` draws meaningfully more current than
     *  `BALANCED` (roughly continuous scanning vs. a controller-managed ~50% duty cycle — order of
     *  magnitude a few extra mA while it runs), but it's spent *only* while genuinely searching, not
     *  as a standing cost. In the RELAY (background) tier this is the whole win: normally cheap
     *  BALANCED scanning, escalating to aggressive LOW_LATENCY only for however long it actually
     *  takes to reacquire your group, then dropping straight back down once it does. The ACTIVE
     *  (foreground) tier is already LOW_LATENCY at all times — you're looking at the radar, finding
     *  people now matters more than battery in that moment — so blindness changes nothing there. */
    @SuppressLint("MissingPermission")
    fun startScanning() {
        scanner = bluetoothManager.adapter?.bluetoothLeScanner
        scanJob = serviceScope.launch {
            var activeScanMode: Int? = null
            while (isActive) {
                val tierMode = BleTuning.forTier(currentTier()).scanMode
                val wantMode = if (isBlind()) ScanSettings.SCAN_MODE_LOW_LATENCY else tierMode
                if (wantMode != activeScanMode) {
                    restartScan(wantMode)
                    activeScanMode = wantMode
                }
                delay(3000)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun restartScan(scanMode: Int) {
        val s = scanner ?: return
        try { s.stopScan(scanCallback) } catch (_: Exception) {}
        val settings = ScanSettings.Builder().setScanMode(scanMode).build()
        try {
            // No ScanFilter, deliberately — a hardware Service-Data-with-mask filter (used here in
            // an earlier version, pitched at the time as the biggest available battery lever) turned
            // out to be unreliable in live 2-phone testing: some BLE chipsets silently fail to honor
            // it and just return nothing, with no error surfaced anywhere. That produced a
            // deterministic (not intermittent) "this phone never sees anyone" on one specific test
            // device — a correctness bug, not a tuning tradeoff, so it's not worth keeping even for
            // the battery win. Scanning unfiltered and matching in onScanResult below (already how
            // the code was structured — the hardware filter only controlled whether the callback
            // fired at all, not the matching logic itself) is slower to wake the CPU on advertisements
            // from unrelated nearby Bluetooth devices, but works the same on every chipset.
            s.startScan(emptyList(), settings, scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "scan start failed: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val serviceData = result.scanRecord?.getServiceData(ParcelUuid(MeshProtocol.SERVICE_UUID)) ?: return
            val beacon = MeshProtocol.decodeBeacon(serviceData) ?: return
            if (beacon.type == MeshProtocol.ADV_TYPE_GROUP) {
                // O(1) lookup against the per-slot cache — no HMAC, no DB, on the hot path.
                val groupId = matchTable[beacon.rotatingGroupId.toHex()]
                if (groupId != null) {
                    // Hearing the beacon at all means a real member is 1 hop away; considerNeighborReport
                    // adds its own +1, so we feed it 0.
                    hopTracker.considerNeighborReport(groupId, "PRESENCE", 0)
                    // Deliberately NOT feeding beacon.sosHop into hop tracking (an earlier version did,
                    // via a "SOS_PENDING" key) — that was a second, rough, sosId-agnostic hop estimate
                    // sitting alongside the exact, TTL-derived per-SOS tracking, and the display took
                    // the minimum of both. With only 2 test phones the exact channel can only ever be 0
                    // or 1; a reported "2" traced back to this rough channel's stale reading leaking
                    // through (HopTracker.bestActiveSosHop is now the only thing the UI reads, and it's
                    // staleness-checked). The beacon still carries sosHop — it's just not fed into hop
                    // tracking anymore, only used to decide what this device itself advertises.
                }
            }
            // Blind-carrier policy: connect to every mesh phone heard, member or not — see
            // MeshGattClient/RelayResponder (relaying opaque bytes for groups we can't decrypt).
            onDeviceSeen(result.device)
        }
    }

    companion object {
        private const val TAG = "BeaconRadio"

        private val HEX = "0123456789abcdef".toCharArray()
        private fun ByteArray.toHex(): String {
            val out = CharArray(size * 2)
            for (i in indices) {
                val v = this[i].toInt() and 0xFF
                out[i * 2] = HEX[v ushr 4]
                out[i * 2 + 1] = HEX[v and 0x0F]
            }
            return String(out)
        }
    }
}
