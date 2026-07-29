package org.offlinemesh.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
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
 * **Invariant: the radio is only ever touched (stop+restart) when the payload itself needs to
 * change** — a new rotating-id window, a different group in the round-robin, a changed SOS hop —
 * never on a fixed timer. Scanning runs continuously once started — one `startScan()` per power
 * tier, left running, with [BleTuning.Profile.scanMode] as the only duty-cycle lever, left to the
 * OS. See `docs/DECISIONS.md`, decision 1, for why this is an invariant, not a preference — live
 * 2-phone testing hit total, symmetric discovery failure under a version that touched the radio on
 * a fixed schedule instead.
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

    // Per-device jitter applied before an actual radio restart (never before the cheap
    // has-anything-changed check above). The rotating id itself flips on a shared wall-clock
    // minute boundary (CryptoUtils.ID_WINDOW_SECONDS) BY DESIGN — every phone has to agree on the
    // same window to compute the same id — but that also means at crowd density every phone's
    // *restart* lands in the same sub-second window every minute, all at once: a synchronized
    // stop/start burst across hundreds of radios instead of one phone's. A stable per-device offset
    // derived from deviceId spreads those restarts across [advertiseJitterRangeMs] instead — it
    // changes only *when* within that second the restart happens, never *whether* it happens, so
    // it can't reintroduce the "touch the radio on a timer" bug class this file already fought
    // through three rounds of live testing to eliminate (see class doc above).
    private val advertiseJitterRangeMs = JITTER_RANGE_MS
    private val advertiseJitterMs: Long by lazy {
        val h = CryptoUtils.sha256(repo.deviceId.toByteArray())
        val unsignedFirstTwoBytes = (h[0].toInt() and BYTE_MASK shl BYTE_SHIFT) or (h[1].toInt() and BYTE_MASK)
        unsignedFirstTwoBytes.toLong() % advertiseJitterRangeMs
    }

    // Published once per slot by the advertise loop, read (never mutated) by the scan callback on
    // a binder thread. Replaced wholesale each refresh, so a plain @Volatile reference is safe.
    @Volatile private var matchTable: Map<String, String> = emptyMap()   // rotatingId(hex) -> groupId
    @Volatile private var cachedGroups: List<GroupEntity> = emptyList()

    // ---- long-range supplementary channel state — see the section below for what this is ----
    // @Volatile (unlike currentPayloadKey above): currentPayloadKey is single-writer, touched only
    // from within the one advertiseJob coroutine, so a plain var is safe there. These two are
    // written from THREE places — the advertiseJob coroutine (evaluateLongRangeAdvertising),
    // MeshService.onDestroy's caller thread (stop()), and the raw BLE callback thread
    // (longRangeAdvertisingSetCallback, an OS callback with no guaranteed relationship to the
    // coroutine dispatcher) — matching why matchTable/cachedGroups above are @Volatile too: any
    // state genuinely crossing threads in this class follows that same convention.
    @Volatile private var longRangeAdvertisingActive = false
    @Volatile private var longRangeCurrentPayloadKey: String? = null
    private val longRangeTrickle = TrickleTimer(minIntervalMs = 5_000L, maxIntervalMs = 60_000L)
    private var longRangeScanner: BluetoothLeScanner? = null
    private var longRangeScanJob: Job? = null

    /** Safe to call more than once, and safe to follow with a fresh [startAdvertising]/
     *  [startScanning] later (see [MeshService.setMeshActive]'s "go offline"/"go active" cycle) —
     *  resetting [currentPayloadKey]/[longRangeCurrentPayloadKey]/[longRangeAdvertisingActive] here
     *  is what makes a restart actually re-assert the radio instead of [ensureAdvertising] /
     *  [evaluateLongRangeAdvertising] wrongly believing the old payload is still being transmitted
     *  (it isn't — the radio was just stopped below) and skipping the real re-start for up to a
     *  full ~60s rotating-id window. */
    fun stop() {
        advertiseJob?.cancel()
        scanJob?.cancel()
        longRangeScanJob?.cancel()
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        try { longRangeScanner?.stopScan(longRangeScanCallback) } catch (_: Exception) {}
        try {
            if (longRangeAdvertisingActive) {
                bluetoothManager.adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(longRangeAdvertisingSetCallback)
            }
        } catch (_: Exception) {}
        currentPayloadKey = null
        longRangeCurrentPayloadKey = null
        longRangeAdvertisingActive = false
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
                // Same "we're not hearing anyone at all" signal the scan side escalates on — reset
                // the long-range channel's backoff too, so it comes back to full strength exactly
                // when extra range is most likely to actually help, not on its own independent clock.
                if (isBlind()) longRangeTrickle.reset()
                // Advances the trickle window on the same check cadence as everything else in this
                // loop; the return value is unused here — evaluateLongRangeAdvertising below reads
                // the resulting isSuppressed() state, not this call's edge-triggered pulse.
                longRangeTrickle.shouldTransmit()
                if (groups.isEmpty()) {
                    val genericRid = ByteArray(MeshProtocol.ROTATING_ID_LEN)
                    ensureAdvertising(
                        profile, MeshProtocol.ADV_TYPE_GENERIC, genericRid, MeshProtocol.UNKNOWN_HOP, "generic"
                    )
                } else {
                    val g = groups[roundRobin % groups.size]
                    roundRobin++
                    val key = repo.getGroupKey(g.id)
                    if (key != null) {
                        val rid = CryptoUtils.rotatingAdvertisementId(key)
                        val sHop = bestSosHopFor(g.id)
                        val payloadKey = "${g.id}:${rid.toHex()}:$sHop"
                        ensureAdvertising(profile, MeshProtocol.ADV_TYPE_GROUP, rid, sHop, payloadKey)
                        evaluateLongRangeAdvertising(MeshProtocol.ADV_TYPE_GROUP, rid, sHop, payloadKey)
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
     *  whether anything changed (see the class doc for why that was a real live-test bug). When a
     *  restart genuinely is needed and this isn't the very first advertise since startup, waits
     *  [advertiseJitterMs] first — see that property's doc for why (smears the shared rotating-id
     *  boundary so a crowd doesn't restart every radio in the same instant). Skipped on the very
     *  first call ([currentPayloadKey] still null) so initial discovery stays fast. */
    @SuppressLint("MissingPermission")
    private suspend fun ensureAdvertising(
        profile: BleTuning.Profile, type: Byte, rid: ByteArray, sHop: Int, payloadKey: String
    ) {
        if (payloadKey == currentPayloadKey) return
        if (currentPayloadKey != null && advertiseJitterMs > 0) delay(advertiseJitterMs)
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

    // ---------------- long-range supplementary channel (BT5 Coded PHY) ----------------
    // Additive only: reuses the exact same 8-byte beacon wire format (MeshProtocol.encodeBeacon/
    // decodeBeacon) and the exact same discovery outcomes (hopTracker presence) as the legacy path
    // above — nothing about it changes what a legacy-only peer sees, and nothing about it touches
    // [currentPayloadKey]/[advertiser]/[scanner]/[matchTable] or any other legacy state. It exists
    // purely to extend range and coverage in a crowd, on hardware that supports it:
    //  - Coded PHY (LE Long Range, S=8) trades bitrate for forward-error-correction coding gain —
    //    roughly 2-4x usable range at the same TX power (Bluetooth SIG figures), so fewer relay
    //    hops are needed to cover the same physical area, and fewer GATT connection-slot attempts
    //    get spent trying to bridge gaps a longer-range beacon would have closed directly.
    //  - Extended advertising is what makes a non-legacy, Coded-PHY-carried advertising set
    //    possible on this radio at all — the payload itself is unchanged (still MeshProtocol's
    //    8-byte format), this isn't used to carry more data, only to reach further.
    //  - Deliberately non-connectable (setConnectable(false)): GATT data transfer still rides only
    //    the legacy connectable beacon, same as before this channel existed — this is a pure
    //    coverage/presence extension, not a second data path.
    //  - [longRangeTrickle] suppresses transmitting when enough neighbors are already covering a
    //    group on this channel — see [TrickleTimer]'s doc for why this, not a fixed schedule, is
    //    the actual crowd-scaling lever: redundant long-range traffic then scales with local
    //    density, not with a per-device timer that gets worse as the crowd gets bigger.
    //
    // Gated behind [BleCapabilities.longRangeBeaconSupported] — unsupported hardware (or any
    // exception probing the adapter) is always a silent no-op, never a crash, never a fallback
    // that touches the legacy advertiser.
    //
    // NOT device-tested. Passes 1-21 of this file earned the "touch the radio only when something
    // changed" principle through repeated live 2-phone testing on real hardware; BT5 Coded PHY
    // hardware wasn't available to repeat that process here. Treat this exactly like this project's
    // unverified iOS code: carefully reviewed by hand against the documented AdvertisingSet/
    // ScanSettings API surface, but unverified on real hardware. Needs its own live-device pass —
    // ideally two BT5 phones with confirmed Coded PHY support — before being trusted the way the
    // legacy path now is.
    @SuppressLint("MissingPermission")
    private fun evaluateLongRangeAdvertising(type: Byte, rid: ByteArray, sHop: Int, payloadKey: String) {
        val adapter = bluetoothManager.adapter ?: return
        if (!BleCapabilities.longRangeBeaconSupported(adapter)) return
        val adv = adapter.bluetoothLeAdvertiser ?: return
        if (longRangeTrickle.isSuppressed()) {
            if (longRangeAdvertisingActive) {
                try { adv.stopAdvertisingSet(longRangeAdvertisingSetCallback) } catch (e: Exception) {
                    Log.w(TAG, "long-range advertise stop failed: ${e.message}")
                }
                longRangeAdvertisingActive = false
                longRangeCurrentPayloadKey = null
            }
            return
        }
        if (payloadKey == longRangeCurrentPayloadKey && longRangeAdvertisingActive) return
        val payload = MeshProtocol.encodeBeacon(type, rid, sHop)
        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(false)
            .setConnectable(false)
            .setPrimaryPhy(BluetoothDevice.PHY_LE_CODED)
            .setSecondaryPhy(BluetoothDevice.PHY_LE_CODED)
            .setInterval(AdvertisingSetParameters.INTERVAL_HIGH) // lower baseline power; Trickle already governs on/off
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH) // range is the point of this channel
            .build()
        val data = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(MeshProtocol.SERVICE_UUID), payload)
            .setIncludeDeviceName(false)
            .build()
        try {
            if (longRangeAdvertisingActive) adv.stopAdvertisingSet(longRangeAdvertisingSetCallback)
            adv.startAdvertisingSet(params, data, null, null, null, longRangeAdvertisingSetCallback)
            longRangeAdvertisingActive = true
            longRangeCurrentPayloadKey = payloadKey
        } catch (e: Exception) {
            Log.w(TAG, "long-range advertise start failed: ${e.message}")
            longRangeAdvertisingActive = false
        }
    }

    private val longRangeAdvertisingSetCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
            if (status != ADVERTISE_SUCCESS) {
                Log.w(TAG, "long-range advertising set failed to start: status=$status")
                longRangeAdvertisingActive = false
            }
        }
        override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
            longRangeAdvertisingActive = false
        }
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
        startLongRangeScanning()
    }

    /** A single, separate, always-LOW_POWER scan for the long-range channel above — kept entirely
     *  apart from [restartScan]/[scanCallback] so the legacy scan path stays byte-for-byte
     *  untouched regardless of whether this one works on a given chipset. LOW_POWER rather than
     *  tier-driven: this channel is a coverage extender, not a latency-sensitive one — the
     *  ordinary legacy scan above already carries the responsiveness requirement. A single
     *  `startScan()` call left running, matching this file's established "leave it running, don't
     *  touch it on a timer" principle — see the class doc. Silently does nothing if the adapter,
     *  scanner, or hardware capability isn't there; never affects the legacy scan either way. */
    @SuppressLint("MissingPermission")
    private fun startLongRangeScanning() {
        val adapter = bluetoothManager.adapter ?: return
        if (!BleCapabilities.longRangeBeaconSupported(adapter)) {
            Log.i(TAG, "Coded PHY not supported — long-range channel off, legacy discovery unaffected")
            return
        }
        val s = adapter.bluetoothLeScanner ?: return
        longRangeScanner = s
        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setLegacy(false)
                .setPhy(BluetoothDevice.PHY_LE_CODED)
                .build()
            s.startScan(emptyList(), settings, longRangeScanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "long-range scan start failed: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun restartScan(scanMode: Int) {
        val s = scanner ?: return
        try { s.stopScan(scanCallback) } catch (_: Exception) {}
        val settings = ScanSettings.Builder().setScanMode(scanMode).build()
        try {
            // No ScanFilter, deliberately — a hardware filter silently fails to fire on some BLE
            // chipsets (see docs/DECISIONS.md, decision 3); matching in onScanResult below instead
            // is slower to wake the CPU on unrelated nearby Bluetooth traffic, but works the same
            // on every chipset.
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
                    // adds its own +1, so we feed it 0. The scanned device's address is this
                    // report's source — see HopTracker.updateHop's doc for what that's used for
                    // (a worse reading from a genuinely different peer can't downgrade a better
                    // one already established by someone else).
                    hopTracker.considerNeighborReport(groupId, "PRESENCE", 0, result.device.address)
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

    /** Presence-only counterpart to [scanCallback] for the long-range channel — deliberately does
     *  NOT call [onDeviceSeen]/maybeConnect, since long-range beacons are advertised non-connectable
     *  (see [evaluateLongRangeAdvertising]); attempting to connect to one would only churn a
     *  connection-attempt slot on a guaranteed failure. Feeds [longRangeTrickle] so a device that
     *  keeps hearing others covering a group on this channel backs off transmitting its own. */
    @SuppressLint("MissingPermission")
    private val longRangeScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val serviceData = result.scanRecord?.getServiceData(ParcelUuid(MeshProtocol.SERVICE_UUID)) ?: return
            val beacon = MeshProtocol.decodeBeacon(serviceData) ?: return
            if (beacon.type != MeshProtocol.ADV_TYPE_GROUP) return
            val groupId = matchTable[beacon.rotatingGroupId.toHex()] ?: return
            hopTracker.considerNeighborReport(groupId, "PRESENCE", 0, result.device.address)
            longRangeTrickle.onSighting()
        }
        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "long-range scan failed: errorCode=$errorCode")
        }
    }

    companion object {
        private const val TAG = "BeaconRadio"

        // advertiseJitterMs's range and byte-mixing constants — see that property's doc above.
        private const val JITTER_RANGE_MS = 2000L
        private const val BYTE_MASK = 0xFF // isolates one byte from a signed Kotlin Byte-to-Int conversion
        private const val BYTE_SHIFT = 8 // shifts the first byte into the high half of a 16-bit value

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
