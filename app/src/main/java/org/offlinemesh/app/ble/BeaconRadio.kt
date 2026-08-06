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
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.offlinemesh.app.crypto.CryptoUtils
import org.offlinemesh.app.diagnostics.DiagnosticsLog
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
 * never on a fixed timer. Stronger than that now: a payload change usually costs no radio
 * operation at all, because the beacon runs as an [AdvertisingSet] whose data is replaced in
 * place (`setAdvertisingData`) instead of stopped and restarted — see
 * [startAdvertisingSetOrLegacy]. Scanning runs continuously once started — one `startScan()` per power
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
// TooManyFunctions: the in-place-advertising work (startAdvertisingSetOrLegacy plus the two small
// BleTuning->AdvertisingSetParameters unit mappers) pushed this two over the threshold. Each is
// small and single-purpose, and this class already deliberately keeps one function per radio
// concern (advertise/scan/long-range/callbacks) rather than fewer, larger ones.
@Suppress("TooManyFunctions")
class BeaconRadio(
    private val bluetoothManager: BluetoothManager,
    private val repo: GroupRepository,
    private val hopTracker: HopTracker,
    private val serviceScope: CoroutineScope,
    private val currentTier: () -> MeshService.PowerTier,
    // rssi: PLAN-v2.md P3's real diversity signal (LinkSelector, via MeshGattClient's
    // considerEvicting) — a synthetic 1D "position" stood in for it in the simulator; this is the
    // actual measurement, straight from ScanResult, with no processing of our own.
    private val onDeviceSeen: (device: BluetoothDevice, rssi: Int) -> Unit,
) {
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var advertiseJob: Job? = null
    private var scanJob: Job? = null

    // What's currently actually being transmitted — compared against the next candidate payload so
    // the radio is only stopped/restarted when something real changed, not on every loop tick.
    private var currentPayloadKey: String? = null

    // Handle to the running legacy advertising set, when we have one — the whole point of using
    // startAdvertisingSet() for the legacy beacon (see startAdvertisingSetOrLegacy). Null means
    // we're on the plain startAdvertising fallback, where a payload change still costs a restart.
    // @Volatile: written from the OS advertising-set callback thread, read from the advertise loop.
    @Volatile private var advertisingSet: AdvertisingSet? = null

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

    // ---- broadcast-tier (Tier B) channel state — see the section below for what this is ----
    // @Volatile (unlike currentPayloadKey above): currentPayloadKey is single-writer, touched only
    // from within the one advertiseJob coroutine, so a plain var is safe there. These two are
    // written from THREE places — the advertiseJob coroutine (evaluateBroadcastTierAdvertising),
    // MeshService.onDestroy's caller thread (stop()), and the raw BLE callback thread
    // (broadcastTierAdvertisingSetCallback, an OS callback with no guaranteed relationship to the
    // coroutine dispatcher) — matching why matchTable/cachedGroups above are @Volatile too: any
    // state genuinely crossing threads in this class follows that same convention.
    @Volatile private var broadcastTierAdvertisingActive = false
    @Volatile private var broadcastTierCurrentPayloadKey: String? = null
    // Live-tested finding (from this channel's original, narrower Coded-PHY-only incarnation): a
    // device can report isLeExtendedAdvertisingSupported true (BleCapabilities' own gate above)
    // while still having no free advertising-set SLOT for a second, non-legacy advertiser — the
    // legacy advertiser already holds the chipset's only one, on some hardware. Without a circuit
    // breaker, that failure (ADVERTISE_FAILED_TOO_MANY_ADVERTISERS) retried every single check
    // cycle, forever, for an entire live session, on real hardware — the exact "repeated failed
    // radio operation churn" category decision 1 (docs/DECISIONS.md) already identified as capable
    // of destabilizing the whole BLE stack, just from a different source this time. After
    // BROADCAST_TIER_FAILURE_LIMIT consecutive failures, this stops retrying for the rest of the
    // session rather than hammering a slot that's demonstrably never going to free up — a stop()/
    // restart cycle (e.g. the "go offline"/"go active" toggle) resets this, in case conditions changed.
    @Volatile private var broadcastTierConsecutiveFailures = 0
    @Volatile private var broadcastTierDisabledForSession = false
    private val broadcastTierTrickle = TrickleTimer(minIntervalMs = 5_000L, maxIntervalMs = 60_000L)
    private var broadcastTierScanner: BluetoothLeScanner? = null
    private var broadcastTierScanJob: Job? = null
    // Degree signal for report-delay batching (PLAN-v2.md §9.2 item 1) — distinct addresses heard
    // on THIS channel (already hardware-filtered to app devices, member or not — see
    // startBroadcastTierScanning) within BROADCAST_TIER_DEGREE_WINDOW_MS. Deliberately raw local
    // density, not own-group degree: batching protects the callback thread from total nearby
    // app-device volume, which is a different question from TrickleTimer's own-group-scoped
    // redundancy count (decision 25) — the two "degree"s in this file measure different things on
    // purpose. ConcurrentHashMap: written from the scan-callback binder thread, pruned/read from
    // the broadcastTierScanJob coroutine.
    private val broadcastTierRecentAddresses = ConcurrentHashMap<String, Long>()
    @Volatile private var broadcastTierReportDelayActive = false

    /** Safe to call more than once, and safe to follow with a fresh [startAdvertising]/
     *  [startScanning] later (see [MeshService.setMeshActive]'s "go offline"/"go active" cycle) —
     *  resetting [currentPayloadKey]/[broadcastTierCurrentPayloadKey]/[broadcastTierAdvertisingActive]
     *  here is what makes a restart actually re-assert the radio instead of [ensureAdvertising] /
     *  [evaluateBroadcastTierAdvertising] wrongly believing the old payload is still being
     *  transmitted (it isn't — the radio was just stopped below) and skipping the real re-start for
     *  up to a full ~60s rotating-id window. */
    fun stop() {
        advertiseJob?.cancel()
        scanJob?.cancel()
        broadcastTierScanJob?.cancel()
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        try { broadcastTierScanner?.stopScan(broadcastTierScanCallback) } catch (_: Exception) {}
        try {
            if (broadcastTierAdvertisingActive) {
                bluetoothManager.adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(broadcastTierAdvertisingSetCallback)
            }
        } catch (_: Exception) {}
        try {
            if (advertisingSet != null) {
                bluetoothManager.adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(legacyAdvertisingSetCallback)
            }
        } catch (_: Exception) {}
        advertisingSet = null
        currentPayloadKey = null
        broadcastTierCurrentPayloadKey = null
        broadcastTierAdvertisingActive = false
        broadcastTierConsecutiveFailures = 0
        broadcastTierDisabledForSession = false
        broadcastTierRecentAddresses.clear()
        broadcastTierReportDelayActive = false
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
            var roundRobinSlotStartedAtMs = System.currentTimeMillis()
            // Round-robin dwell is ADAPTIVE — see roundRobinDwellMs. A fixed 60s dwell was tried
            // and reverted: it starved whichever group wasn't currently "up" of all advertising
            // airtime for up to a minute, breaking same-room discovery outright. Rotating on every
            // check tick (the original behavior) instead restarts the radio every ~3s for any
            // multi-group phone — confirmed live at 343 of 526 log lines — which is the
            // known-dangerous churn category of decision 1. The adaptive rule below takes neither
            // horn: rotate fast while we can't hear anyone (discovery is what matters, and there's
            // no established presence to protect), dwell once we can (stability is what matters,
            // and a peer already discovered doesn't need us re-announcing every 3s).
            while (isActive) {
                val profile = BleTuning.forTier(currentTier())
                refreshCaches()
                val groups = cachedGroups
                // Same "we're not hearing anyone at all" signal the scan side escalates on — reset
                // the broadcast tier's backoff too, so it comes back to full strength exactly when
                // it's most likely to actually help, not on its own independent clock.
                if (isBlind()) broadcastTierTrickle.reset()
                // Advances the trickle window on the same check cadence as everything else in this
                // loop; the return value is unused here — evaluateBroadcastTierAdvertising below
                // reads the resulting isSuppressed() state, not this call's edge-triggered pulse.
                broadcastTierTrickle.shouldTransmit()
                if (groups.isEmpty()) {
                    val genericRid = ByteArray(MeshProtocol.ROTATING_ID_LEN)
                    ensureAdvertising(
                        profile, MeshProtocol.ADV_TYPE_GENERIC, genericRid, MeshProtocol.UNKNOWN_HOP, "generic"
                    )
                } else {
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - roundRobinSlotStartedAtMs >= roundRobinDwellMs(isBlind())) {
                        roundRobin++
                        roundRobinSlotStartedAtMs = nowMs
                    }
                    val g = groups[roundRobin % groups.size]
                    val key = repo.getGroupKey(g.id)
                    if (key != null) {
                        val rid = CryptoUtils.rotatingAdvertisementId(key)
                        val sHop = bestSosHopFor(g.id)
                        val payloadKey = "${g.id}:${rid.toHex()}:$sHop"
                        ensureAdvertising(profile, MeshProtocol.ADV_TYPE_GROUP, rid, sHop, payloadKey)
                        evaluateBroadcastTierAdvertising(MeshProtocol.ADV_TYPE_GROUP, g.id, rid, sHop)
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
        val payload = MeshProtocol.encodeBeacon(type, rid, sHop)
        // Deliberately NOT addServiceUuid() — a Service UUID list AD structure (18 bytes) plus this
        // Service Data structure overflow legacy BLE's 31-byte limit. Service Data alone carries our
        // UUID as a prefix, read back via getServiceData in the scan callback below.
        val data = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(MeshProtocol.SERVICE_UUID), payload)
            .setIncludeDeviceName(false)
            .build()
        // Preferred path: mutate the payload of an already-running advertising set IN PLACE, never
        // touching the radio. This is the root fix for the churn that dominated live captures — a
        // multi-group phone changes payload every time round-robin moves to the next group, and the
        // old stop+restart path therefore tore down and rebuilt the advertiser every few seconds
        // (343 of 526 log lines in one capture), which is the destabilizing pattern decision 1
        // identified. setAdvertisingData() has no such cost: same session, new bytes.
        val set = advertisingSet
        if (set != null) {
            try {
                set.setAdvertisingData(data)
                currentPayloadKey = payloadKey
                return
            } catch (e: Exception) {
                // Fall through to the legacy path and let it re-establish from scratch.
                Log.w(TAG, "in-place advertising data update failed: ${e.message}")
                advertisingSet = null
            }
        }
        startAdvertisingSetOrLegacy(profile, data, payloadKey)
    }

    /** Starts a fresh advertising session. Prefers [BluetoothLeAdvertiser.startAdvertisingSet] —
     *  API 26, i.e. this app's `minSdk`, so it's always available — because that hands back an
     *  [AdvertisingSet] whose payload can later be changed in place by [ensureAdvertising] with no
     *  radio restart at all. `setLegacyMode(true)`/`setConnectable(true)` keep what goes on air
     *  byte-identical to the proven legacy beacon: same 31-byte legacy advertisement, same Service
     *  Data layout, same connectability that GATT depends on — this changes only HOW the payload is
     *  updated, never what a peer sees or how it connects.
     *
     *  Falls back to plain `startAdvertising` if the advertising-set call fails for any reason, so a
     *  chipset that refuses it still advertises exactly as it did before. */
    @SuppressLint("MissingPermission")
    private fun startAdvertisingSetOrLegacy(
        profile: BleTuning.Profile,
        data: AdvertiseData,
        payloadKey: String,
    ) {
        val adv = advertiser ?: return
        val setParams = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(true)
            .setScannable(true)
            .setInterval(legacyIntervalFor(profile))
            .setTxPowerLevel(legacyTxPowerFor(profile))
            .build()
        try {
            if (advertisingSet != null) adv.stopAdvertisingSet(legacyAdvertisingSetCallback)
            adv.startAdvertisingSet(setParams, data, null, null, null, legacyAdvertisingSetCallback)
            currentPayloadKey = payloadKey
            Log.d(TAG, "advertising set started (payload updates in place from here)")
            return
        } catch (e: Exception) {
            Log.w(TAG, "startAdvertisingSet failed, falling back to legacy startAdvertising: ${e.message}")
            advertisingSet = null
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(profile.advertiseMode)
            .setTxPowerLevel(profile.advertiseTxPower)
            .setConnectable(true)
            .build()
        try {
            adv.stopAdvertising(advertiseCallback)
            adv.startAdvertising(settings, data, advertiseCallback)
            currentPayloadKey = payloadKey
            Log.d(TAG, "legacy advertiser restarted (payload changed)")
            DiagnosticsLog.event("beacon", "legacy restart (no advertising-set support)")
        } catch (e: Exception) {
            Log.w(TAG, "advertise failed: ${e.message}")
        }
    }

    /** Receives the [AdvertisingSet] handle that makes in-place payload updates possible. */
    private val legacyAdvertisingSetCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
            if (status == ADVERTISE_SUCCESS && advertisingSet != null) {
                this@BeaconRadio.advertisingSet = advertisingSet
            } else {
                this@BeaconRadio.advertisingSet = null
                Log.w(TAG, "advertising set failed to start: status=$status")
                DiagnosticsLog.event("beacon", "advertising set start failed: $status")
            }
        }

        override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
            this@BeaconRadio.advertisingSet = null
        }
    }

    // AdvertisingSetParameters takes raw interval/TX-power units rather than the AdvertiseSettings
    // MODE_*/TX_POWER_* constants BleTuning is expressed in, so the two profiles are mapped across
    // here. Values are AdvertisingSetParameters' own named constants — chosen to match what each
    // BleTuning profile already asked for, not retuned.
    private fun legacyIntervalFor(profile: BleTuning.Profile): Int =
        if (profile.advertiseMode == AdvertiseSettings.ADVERTISE_MODE_LOW_POWER) {
            AdvertisingSetParameters.INTERVAL_HIGH
        } else {
            AdvertisingSetParameters.INTERVAL_MEDIUM
        }

    private fun legacyTxPowerFor(profile: BleTuning.Profile): Int =
        if (profile.advertiseTxPower == AdvertiseSettings.ADVERTISE_TX_POWER_HIGH) {
            AdvertisingSetParameters.TX_POWER_HIGH
        } else {
            AdvertisingSetParameters.TX_POWER_MEDIUM
        }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) { Log.w(TAG, "advertise start failure: $errorCode") }
    }

    // ---------------- broadcast tier (Tier B) — PLAN-v2.md §5.1, Part 7 P2 ----------------
    // Additive only: doesn't touch [currentPayloadKey]/[advertiser]/[scanner]/[matchTable] or any
    // other legacy state — nothing about it changes what a legacy-only peer sees. Generalized
    // 2026-08-06 (decision 26, docs/DECISIONS.md) from what was originally a Coded-PHY-only "long
    // range supplementary channel": that channel already had every piece Tier B needs (extended
    // advertising, in-place payload update, non-connectable, Trickle-governed) — resolving
    // PLAN-v2.md Part 8's open item on Coded PHY by RE-SCOPING it behind Tier B rather than
    // deleting it, exactly as that section names as one of the two options.
    //  - Gated on [BleCapabilities.extendedAdvertisingSupported] alone now, not the old, narrower
    //    [BleCapabilities.longRangeBeaconSupported] — this channel's actual purpose (a connectionless
    //    tier with no slot contention, PLAN-v2.md §5.1) needs extended advertising, not specifically
    //    Coded PHY. Coded PHY is now an opportunistic, additive upgrade: used for range ONLY when
    //    the adapter also reports [BleCapabilities.codedPhySupported], so hardware with extended-
    //    advertising-but-not-Coded-PHY support (broader than the old gate) still gets Tier B.
    //  - Carries [MeshProtocol.encodeBroadcastTierBeacon] — the legacy 8-byte beacon's fields plus
    //    an explicit presence hop-gradient (see that function's own doc) — not the bare legacy
    //    format, since extended advertising has none of the legacy 31-byte format's byte pressure.
    //  - Deliberately non-connectable (setConnectable(false)): GATT data transfer still rides only
    //    the legacy connectable beacon, same as before this channel existed.
    //  - [broadcastTierTrickle] suppresses transmitting when enough own-group neighbors are already
    //    covering this group on this channel (own-group-scoped sightings — decisions 24/25) — see
    //    [TrickleTimer]'s doc for why this, not a fixed schedule, is the actual crowd-scaling lever.
    //
    // Unsupported hardware (or any exception probing the adapter) is always a silent no-op, never a
    // crash, never a fallback that touches the legacy advertiser.
    //
    // NOT device-tested — same caveat this channel's Coded-PHY-only predecessor carried, now
    // broadened: the ScanFilter/report-delay-batching pieces below are ALSO new and untested on
    // hardware this session. Treat this exactly like this project's unverified iOS code: carefully
    // reviewed by hand against the documented AdvertisingSet/ScanSettings/ScanFilter API surface,
    // but unverified on real hardware. Needs its own live-device pass before being trusted the way
    // the legacy path now is.
    @SuppressLint("MissingPermission")
    private fun evaluateBroadcastTierAdvertising(type: Byte, groupId: String, rid: ByteArray, sHop: Int) {
        if (broadcastTierDisabledForSession) return
        val adapter = bluetoothManager.adapter ?: return
        if (!BleCapabilities.extendedAdvertisingSupported(adapter)) return
        val adv = adapter.bluetoothLeAdvertiser ?: return
        val presenceHop = hopTracker.myHop(groupId, "PRESENCE")
        val payloadKey = "$groupId:${rid.toHex()}:$sHop:$presenceHop"
        if (broadcastTierTrickle.isSuppressed()) {
            if (broadcastTierAdvertisingActive) {
                try { adv.stopAdvertisingSet(broadcastTierAdvertisingSetCallback) } catch (e: Exception) {
                    Log.w(TAG, "broadcast-tier advertise stop failed: ${e.message}")
                }
                broadcastTierAdvertisingActive = false
                broadcastTierCurrentPayloadKey = null
            }
            return
        }
        if (payloadKey == broadcastTierCurrentPayloadKey && broadcastTierAdvertisingActive) return
        val payload = MeshProtocol.encodeBroadcastTierBeacon(type, rid, sHop, presenceHop)
        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(false)
            .setConnectable(false)
            .apply {
                // Opportunistic range upgrade, not a requirement — see this section's own doc.
                if (BleCapabilities.codedPhySupported(adapter)) {
                    setPrimaryPhy(BluetoothDevice.PHY_LE_CODED)
                    setSecondaryPhy(BluetoothDevice.PHY_LE_CODED)
                }
            }
            .setInterval(AdvertisingSetParameters.INTERVAL_HIGH) // lower baseline power; Trickle already governs on/off
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH) // reaching every neighbour at once is the point
            .build()
        val data = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(MeshProtocol.SERVICE_UUID), payload)
            .setIncludeDeviceName(false)
            .build()
        try {
            if (broadcastTierAdvertisingActive) adv.stopAdvertisingSet(broadcastTierAdvertisingSetCallback)
            adv.startAdvertisingSet(params, data, null, null, null, broadcastTierAdvertisingSetCallback)
            broadcastTierAdvertisingActive = true
            broadcastTierCurrentPayloadKey = payloadKey
        } catch (e: Exception) {
            Log.w(TAG, "broadcast-tier advertise start failed: ${e.message}")
            broadcastTierAdvertisingActive = false
        }
    }

    private val broadcastTierAdvertisingSetCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
            if (status != ADVERTISE_SUCCESS) {
                broadcastTierAdvertisingActive = false
                broadcastTierConsecutiveFailures++
                if (broadcastTierConsecutiveFailures >= BROADCAST_TIER_FAILURE_LIMIT) {
                    broadcastTierDisabledForSession = true
                    Log.w(
                        TAG,
                        "broadcast-tier advertising set failed $broadcastTierConsecutiveFailures times in a " +
                            "row (status=$status) — giving up for this session rather than retrying forever"
                    )
                } else {
                    Log.w(TAG, "broadcast-tier advertising set failed to start: status=$status")
                }
            } else {
                broadcastTierConsecutiveFailures = 0
            }
        }
        override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
            broadcastTierAdvertisingActive = false
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
        startBroadcastTierScanning()
    }

    /** A single, separate, always-LOW_POWER scan for the broadcast tier above — kept entirely apart
     *  from [restartScan]/[scanCallback] so the legacy scan path stays byte-for-byte untouched
     *  regardless of whether this one works on a given chipset. LOW_POWER rather than tier-driven:
     *  this channel is a coverage extender, not a latency-sensitive one — the ordinary legacy scan
     *  above already carries the responsiveness requirement. Uses [ScanSettings.PHY_LE_ALL_SUPPORTED],
     *  not a specific PHY — the advertising side now opportunistically uses Coded PHY only when both
     *  ends support it (see [evaluateBroadcastTierAdvertising]), so the scanner must not restrict
     *  itself to just that PHY or it would miss every 1M-PHY-only broadcaster. Restores a hardware
     *  [ScanFilter] on [MeshProtocol.SERVICE_UUID] (PLAN-v2.md §9.2 item 1) — deliberately a
     *  **service-UUID** filter, not the service-DATA-with-mask filter decision 3 found unreliable
     *  across chipsets; these are different filter types in the BLE stack (a service-UUID filter
     *  matches the AD structure's UUID list, evaluated in controller firmware before the data
     *  payload is even parsed), and conflating them is why legacy scanning ended up with no hardware
     *  filtering at all. Safe to add here specifically because this is a brand new scan session with
     *  no live-tested history to regress — the legacy scan in [restartScan] is deliberately left
     *  unfiltered, unchanged, matching every other "additive only" caveat in this section.
     *
     *  Launches [broadcastTierScanJob], a periodic loop that does two things on the SAME low-risk,
     *  infrequent cadence: prunes [broadcastTierRecentAddresses] and toggles degree-gated report-
     *  delay batching (PLAN-v2.md §9.2 item 1's other half — [broadcastTierReportDelayMs]) by
     *  restarting the scan only when the batching decision actually flips, never on a fixed timer.
     *  Silently does nothing if the adapter, scanner, or hardware capability isn't there; never
     *  affects the legacy scan either way. */
    @SuppressLint("MissingPermission")
    private fun startBroadcastTierScanning() {
        val adapter = bluetoothManager.adapter ?: return
        if (!BleCapabilities.extendedAdvertisingSupported(adapter)) {
            Log.i(TAG, "Extended advertising not supported — broadcast tier off, legacy discovery unaffected")
            return
        }
        val s = adapter.bluetoothLeScanner ?: return
        broadcastTierScanner = s
        restartBroadcastTierScan(reportDelayMs = 0L)
        broadcastTierScanJob = serviceScope.launch {
            while (isActive) {
                delay(BROADCAST_TIER_DEGREE_CHECK_INTERVAL_MS)
                val nowMs = System.currentTimeMillis()
                broadcastTierRecentAddresses.entries.removeAll {
                    nowMs - it.value > BROADCAST_TIER_DEGREE_WINDOW_MS
                }
                val wantDelay = broadcastTierReportDelayMs(broadcastTierRecentAddresses.size)
                val wantBatching = wantDelay > 0L
                if (wantBatching != broadcastTierReportDelayActive) {
                    restartBroadcastTierScan(wantDelay)
                    broadcastTierReportDelayActive = wantBatching
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun restartBroadcastTierScan(reportDelayMs: Long) {
        val s = broadcastTierScanner ?: return
        try { s.stopScan(broadcastTierScanCallback) } catch (_: Exception) {}
        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setLegacy(false)
                .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
                .setReportDelay(reportDelayMs)
                .build()
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(MeshProtocol.SERVICE_UUID)).build()
            s.startScan(listOf(filter), settings, broadcastTierScanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "broadcast-tier scan (re)start failed: ${e.message}")
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
            onDeviceSeen(result.device, result.rssi)
        }
    }

    /** Presence-only counterpart to [scanCallback] for the broadcast tier — deliberately does NOT
     *  call [onDeviceSeen]/maybeConnect, since Tier B beacons are advertised non-connectable (see
     *  [evaluateBroadcastTierAdvertising]); attempting to connect to one would only churn a
     *  connection-attempt slot on a guaranteed failure. [onBatchScanResults] handles the batched
     *  delivery path Android uses once [restartBroadcastTierScan] sets a nonzero report delay —
     *  without this override, degree-gated batching would silently stop delivering anything the
     *  moment it engaged, since [onScanResult] alone isn't called while batching is active. Both
     *  paths funnel through [handleResult] so decode/hop-tracking/Trickle/degree logic lives once. */
    @SuppressLint("MissingPermission")
    private val broadcastTierScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handleResult(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach(::handleResult) }
        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "broadcast-tier scan failed: errorCode=$errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleResult(result: ScanResult) {
        val serviceData = result.scanRecord?.getServiceData(ParcelUuid(MeshProtocol.SERVICE_UUID)) ?: return
        val beacon = MeshProtocol.decodeBroadcastTierBeacon(serviceData) ?: return
        // Any valid Tier B beacon counts toward the degree signal — member or not, see
        // broadcastTierRecentAddresses' own doc for why this is deliberately raw local density.
        broadcastTierRecentAddresses[result.device.address] = System.currentTimeMillis()
        if (beacon.type != MeshProtocol.ADV_TYPE_GROUP) return
        val groupId = matchTable[beacon.rotatingGroupId.toHex()] ?: return
        // Direct hearing: the broadcaster is by definition 1 hop away — same shape as scanCallback.
        hopTracker.considerNeighborReport(groupId, "PRESENCE", 0, result.device.address)
        // Propagated multi-hop gradient: the broadcaster's OWN best-known presence distance, +1 —
        // see MeshProtocol.encodeBroadcastTierBeacon's doc for why this is safe/correct for
        // presence specifically (one target per group) where it wouldn't be for sosHop (many).
        hopTracker.considerNeighborReport(groupId, "PRESENCE", beacon.presenceHop, result.device.address)
        // Scoped to within ONE Trickle window only (tens of seconds) - see TrickleTimer.onSighting's
        // own doc for why the raw scanned address is fine here despite decision 15 moving longer-
        // lived peer state off it (decision 25, docs/DECISIONS.md).
        broadcastTierTrickle.onSighting(result.device.address)
    }

    companion object {
        private const val TAG = "BeaconRadio"

        // See broadcastTierConsecutiveFailures/broadcastTierDisabledForSession's doc above.
        private const val BROADCAST_TIER_FAILURE_LIMIT = 3

        // Degree-gated report-delay batching (PLAN-v2.md §9.2 item 1) — see
        // broadcastTierRecentAddresses' own doc for what "degree" means here. Floor of 5 matches
        // every other §5.4 low/high-degree split in this codebase (ForwardingPolicy, LinkSelector):
        // D <= 4 is the 3-phone case, adaptations are the identity function; D >= 5 is where they
        // engage. 1500ms sits inside the plan's stated "1-2s" range for this specific lever.
        internal const val BROADCAST_TIER_DEGREE_BATCHING_FLOOR = 5
        private const val BROADCAST_TIER_REPORT_DELAY_MS = 1_500L
        private const val BROADCAST_TIER_DEGREE_WINDOW_MS = 30_000L
        private const val BROADCAST_TIER_DEGREE_CHECK_INTERVAL_MS = 5_000L

        /** `internal`, pure — same testability shape as [roundRobinDwellMs]. Returns the
         *  [ScanSettings.setReportDelay] value to use for the current measured [degree] of distinct
         *  Tier B broadcasters heard recently: 0 (no batching, immediate delivery) at or below the
         *  floor so 3-phone discovery stays exactly as responsive as an unbatched scan, batched
         *  above it. Symmetric in both directions — a scan already batched drops back to 0 the next
         *  time degree is measured at or below the floor, matching §5.4's "every adaptation's
         *  low-degree case is the identity function" rule and its own fail-open framing (decisions
         *  23-25): nothing here can get stuck batched once the crowd that justified it thins out. */
        internal fun broadcastTierReportDelayMs(degree: Int): Long =
            if (degree >= BROADCAST_TIER_DEGREE_BATCHING_FLOOR) BROADCAST_TIER_REPORT_DELAY_MS else 0L

        /** How long one group holds the shared advertiser before round-robin moves to the next.
         *  Deliberately NOT the ~60s a single group's rotating id stays stable for (decision 1's
         *  number) — that's about how often ONE group's payload changes on its own, a different
         *  question from how long one group may monopolize a radio shared by several, and
         *  conflating the two is exactly what broke discovery in the reverted 60s attempt.
         *
         *  0 while [blind] (nothing heard from any group): rotate on every check tick, so a phone
         *  that can't hear anyone gets the original, known-working fast discovery behavior and no
         *  group can be starved while it still needs finding. Once presence IS established there's
         *  nothing left to discover urgently, so dwelling cuts radio restarts ~3x versus rotating
         *  every tick, without any group going dark for more than one dwell period.
         *
         *  `internal`, pure — unit-tested rather than reasoned about, given both neighboring values
         *  (~700-900ms and ~3s) are known to misbehave on real hardware. Still needs field
         *  verification: see docs/DECISIONS.md. */
        internal fun roundRobinDwellMs(blind: Boolean): Long = if (blind) 0L else ROUND_ROBIN_DWELL_MS

        private const val ROUND_ROBIN_DWELL_MS = 10_000L

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
