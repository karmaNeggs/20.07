package org.offlinemesh.app.ble

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.offlinemesh.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.offlinemesh.app.data.EvidenceEntity
import org.offlinemesh.app.diagnostics.DiagnosticsLog
import org.offlinemesh.app.data.GroupRepository
import org.offlinemesh.app.data.NicknameEntity
import org.offlinemesh.app.data.SosEntity
import org.offlinemesh.app.sensors.CompassTracker
import org.offlinemesh.app.sensors.LocationTracker
import org.offlinemesh.app.transport.wifidirect.WifiDirectAccelerator
import org.offlinemesh.app.transport.wifidirect.WifiDirectCapabilities
import org.offlinemesh.app.transport.wifidirect.WifiDirectHandoffCoordinator
import org.offlinemesh.app.ui.MainActivity

/**
 * Foreground service owning BLE duty-cycling and the mesh's persistent state (hop tracker,
 * position tracker, sensors). Actual radio work is delegated to three focused collaborators:
 * [BeaconRadio] (connectionless advertise/scan), [MeshGattServer] (peers push to us),
 * [MeshGattClient] (we push to peers) — both GATT sides share one [RelayResponder] for what to
 * send and how to react to what arrives, so the two roles can't drift into different behavior.
 */
class MeshService : Service() {

    inner class LocalBinder : Binder() {
        fun service(): MeshService = this@MeshService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var repo: GroupRepository
    private lateinit var relay: RelayEngine
    val hopTracker = HopTracker()
    val positionTracker = PositionTracker()
    // Shared between RelayResponder (writes) and MeshGattClient (reads) — see its class doc /
    // PLAN-v2.md §5.2 (P0b).
    private val peerIdentity = PeerIdentityResolver()
    // Shared between MeshGattClient, MeshGattServer (both write) and RelayResponder (reads) — see
    // its class doc / PLAN-v2.md P1 §5.3.
    private val connectionRegistry = ConnectionRegistry()
    lateinit var locationTracker: LocationTracker
        private set
    lateinit var compassTracker: CompassTracker
        private set

    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var responder: RelayResponder
    private lateinit var beaconRadio: BeaconRadio
    private lateinit var gattServer: MeshGattServer
    private lateinit var gattClient: MeshGattClient
    // A class field (not a local val in onCreate, which it started as) specifically so
    // setMeshActive(false) can reach it — a WFD transfer that happens to be mid-flight (socket
    // open, group formed) the moment "Offline" is flipped on would otherwise keep running in the
    // background, silently breaking that toggle's "no radio activity" promise. Found by an
    // automated regression scan of this session's changes, fixed here rather than left as a gap.
    private lateinit var wifiDirectAccelerator: WifiDirectAccelerator

    // Power tier: ACTIVE while the app is actually on-screen (favors responsiveness — you're
    // watching the radar or about to send something), RELAY the rest of the time (favors battery
    // — this is what runs for the hours the phone just sits there carrying mesh traffic). Driven
    // automatically by MainActivity's onStart/onStop; powerSaverForced is a manual override that
    // pins RELAY even in the foreground, for someone who explicitly wants to trade
    // responsiveness for runtime regardless of what they're doing.
    enum class PowerTier { ACTIVE, RELAY }
    @Volatile private var foregroundActive = false
    private val _powerSaverForced = MutableStateFlow(false)
    val powerSaverForced: StateFlow<Boolean> = _powerSaverForced

    fun setForegroundActive(active: Boolean) { foregroundActive = active }
    fun setPowerSaverForced(forced: Boolean) { _powerSaverForced.value = forced }
    private fun currentTier(): PowerTier =
        if (_powerSaverForced.value || !foregroundActive) PowerTier.RELAY else PowerTier.ACTIVE

    // User-facing "go offline" control — distinct from onDestroy: this never tears down the
    // Service object, serviceScope, or MainActivity's binding to it, only the things that actually
    // matter for "not discoverable, not draining battery" — both radios, the GPS/compass sensors,
    // and the persistent notification itself. Before this existed, the ONLY way to actually stop
    // any of that was to force-stop the app from Android Settings or uninstall — closing the app
    // (onStop) only lowered the power tier, it never stopped anything.
    private val _meshActive = MutableStateFlow(true)
    val meshActive: StateFlow<Boolean> = _meshActive

    /** Safe to call repeatedly / toggle back and forth — every stop()/start() this delegates to is
     *  already idempotent and already exercised once each by onCreate/onDestroy.
     *
     *  Client-role GATT connections are now explicitly closed here ([MeshGattClient.disconnectAll]),
     *  not left to idle out — PLAN-v2.md P3 made links persistent (`BleTuning.Profile.
     *  connectionBackstopMs` is now minutes, not the old ~20s `connectionMaxMs`), so the previous
     *  "it'll idle-close itself shortly" assumption behind toggling this off no longer holds; a
     *  held link left alone here could keep running for as long as the backstop allows. */
    fun setMeshActive(active: Boolean) {
        if (active == _meshActive.value) return
        if (active) {
            locationTracker.start()
            compassTracker.start()
            startRadios()
            startForegroundNotification()
        } else {
            stopRadios()
            locationTracker.stop()
            compassTracker.stop()
            wifiDirectAccelerator.abortCurrent()
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        _meshActive.value = active
    }

    // Split out of setMeshActive so the Bluetooth-adapter receiver below can restart just the
    // radio-dependent pieces (not location/compass/the notification, which have nothing to do with
    // Bluetooth) when the OS toggles the adapter off then back on underneath a still-active mesh.
    private fun startRadios() {
        gattServer.start()
        beaconRadio.startAdvertising()
        beaconRadio.startScanning()
    }

    private fun stopRadios() {
        beaconRadio.stop()
        gattClient.disconnectAll()
        gattServer.stop()
    }

    /** Replaces three near-identical `while(true)`/`try`/`catch`/`delay(1000)` polling loops that
     *  used to live one per screen (Home/GroupChat/Navigate), each independently re-reading
     *  [locationTracker]/[compassTracker] and each wrapped in the same defensive catch-and-log
     *  (an uncaught exception would otherwise silently kill that screen's loop forever, since
     *  Compose's `LaunchedEffect` doesn't restart on its own). Collected here instead, once.
     *
     *  The periodic tick (not just reacting to [locationTracker]/[compassTracker] emitting) exists
     *  because [HopTracker]/[PositionTracker] staleness is evaluated against wall-clock "now" — a
     *  peer going quiet needs periodic re-evaluation even when no new position/hop event arrives to
     *  naturally trigger one. Without a real tick, a screen deriving state from this flow could
     *  only be as fresh as whatever also happens to change compass heading or GPS fix — which is
     *  exactly the bug this replaces: HomeScreen's dot computation used to key off
     *  `remember(myLocation, heading, groups)`, omitting `positionTracker` entirely, and only
     *  appeared to work because compass jitter incidentally recomputed it once a second anyway. */
    data class RadarTick(val location: Location?, val headingDegrees: Float, val compassLowAccuracy: Boolean)

    private val _radarTick = MutableStateFlow(
        RadarTick(location = null, headingDegrees = 0f, compassLowAccuracy = false)
    )
    val radarTick: StateFlow<RadarTick> = _radarTick
    private var radarTickJob: Job? = null

    private fun startRadarTickLoop() {
        radarTickJob = serviceScope.launch {
            val ticker = flow { while (isActive) { emit(Unit); delay(RADAR_TICK_INTERVAL_MS) } }
            combine(
                locationTracker.location, compassTracker.headingDegrees, compassTracker.lowAccuracy, ticker
            ) { location, heading, lowAccuracy, _ -> RadarTick(location, heading, lowAccuracy) }
                .collect { _radarTick.value = it }
        }
    }

    private var pruneJob: Job? = null

    // Radars must never keep showing peer dots as if the mesh were live once the radio that feeds
    // them is off — cached positions/hops would otherwise sit on screen looking current when
    // nothing could possibly be arriving. All three radar screens read this one flow, so they go
    // in and out of "Bluetooth is off" together instead of drifting independently.
    private val _bluetoothEnabled = MutableStateFlow(true)
    val bluetoothEnabled: StateFlow<Boolean> = _bluetoothEnabled
    // Before this, an OS-level Bluetooth off->on cycle (manual toggle, airplane mode, some OEM
    // battery savers) left the mesh permanently stranded: this receiver only ever updated
    // [_bluetoothEnabled] for the radar screens' display, never actually restarted anything, so the
    // only working recovery was the user manually tapping the app's own offline/online toggle,
    // which happens to run [stopRadios]/[startRadios] for an unrelated reason. Live-tested finding
    // (2026-08-05): a 3-phone session where one phone's Bluetooth was toggled off then on needed
    // exactly that manual workaround to come back — nothing here did it on its own.
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            val wasEnabled = _bluetoothEnabled.value
            val nowEnabled = state == BluetoothAdapter.STATE_ON
            _bluetoothEnabled.value = nowEnabled
            // The user's own offline toggle already stopped the radios and means it on purpose —
            // don't fight that by restarting anything behind it.
            if (!_meshActive.value) return
            if (nowEnabled && !wasEnabled) {
                DiagnosticsLog.event("mesh", "bluetooth back on - restarting radios")
                // Defensive stop first: our own connection-side state (heldConnections,
                // subscribedDevices, ConnectionRegistry entries) still thinks links are open even
                // though the adapter died under them — same "clean slate before restart" reasoning
                // as every other defensive-cleanup path in this codebase (see MeshGattClient's hard-
                // deadline watchdog doc). stop() is idempotent even if there's nothing real to stop.
                stopRadios()
                startRadios()
            } else if (!nowEnabled && wasEnabled) {
                DiagnosticsLog.event("mesh", "bluetooth off - stopping radios")
                stopRadios()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        DiagnosticsLog.init(applicationContext)
        repo = GroupRepository(applicationContext)
        relay = RelayEngine(applicationContext, repo)
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        _bluetoothEnabled.value = bluetoothManager.adapter?.isEnabled ?: false
        registerReceiver(bluetoothStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        locationTracker = LocationTracker(applicationContext).also { it.start() }
        compassTracker = CompassTracker(applicationContext, locationTracker).also { it.start() }
        createSosNotificationChannel()
        // Experimental, opt-in (default OFF), see WifiDirectAccelerator's class doc — constructed
        // unconditionally (cheap: WifiP2pManager.initialize just registers a callback channel, no
        // radio activity starts) but never actually does anything unless WifiDirectSettings.
        // isEnabled is true, checked fresh on every connection by RelayResponder.
        wifiDirectAccelerator = WifiDirectAccelerator(applicationContext)
        val wifiDirectCoordinator = WifiDirectHandoffCoordinator(
            relay,
            wifiDirectAccelerator,
            serviceScope,
            capabilityCheck = { WifiDirectCapabilities.supported(applicationContext) },
        )
        responder = RelayResponder(
            repo, relay, hopTracker, positionTracker, locationTracker, peerIdentity, connectionRegistry,
            wifiDirectCoordinator,
        ) { sos, groupName -> notifySos(sos, groupName) }

        gattServer = MeshGattServer(
            this, bluetoothManager, responder, serviceScope, connectionRegistry, peerIdentity, ::currentTier,
        ).also { it.start() }
        gattClient = MeshGattClient(this, responder, serviceScope, ::currentTier, peerIdentity, connectionRegistry)
        beaconRadio = BeaconRadio(bluetoothManager, repo, hopTracker, serviceScope, ::currentTier) { device, rssi ->
            gattClient.maybeConnect(device, rssi)
        }

        startForegroundNotification()
        beaconRadio.startAdvertising()
        beaconRadio.startScanning()
        startPruning()
        startRadarTickLoop()
        // Once per process start, not periodic — see GroupRepository.sweepOrphanKeys' doc for why
        // that's sufficient (new orphans can only appear via a destructive schema migration, which
        // only happens across an app update, i.e. already a fresh start).
        serviceScope.launch { repo.sweepOrphanKeys() }
    }

    /** The `while` loop's body runs immediately on the first iteration (no delay before it) — so
     *  this doubles as "run once at startup" (a phone that was off past a group's expiry cleans up
     *  on next launch, not up to 30 minutes later) as well as the periodic sweep, with no separate
     *  call needed for the startup case. */
    private fun startPruning() {
        pruneJob = serviceScope.launch {
            while (isActive) {
                // expireGroups first: a group whose expiry lands in this exact tick has its
                // evidence files collected by pruneExpired's orphan sweep in the SAME pass, not
                // left dangling until the next one 30 minutes later.
                repo.expireGroups()
                relay.pruneExpired()
                delay(30 * 60 * 1000L) // every 30 min — this is housekeeping, not latency-sensitive
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        pruneJob?.cancel()
        radarTickJob?.cancel()
        try { unregisterReceiver(bluetoothStateReceiver) } catch (_: Exception) {}
        beaconRadio.stop()
        // Same reasoning as the WFD abort two lines down, and the same gap it was already fixed
        // for: serviceScope.cancel() below stops the coroutines holding P3's persistent links open
        // but does not itself call gatt.disconnect()/close() on them — without this, process
        // teardown would leak every currently-held client connection past the service's lifetime.
        gattClient.disconnectAll()
        gattServer.stop()
        locationTracker.stop()
        compassTracker.stop()
        // WFD groups left open are a real cost beyond just this service (see
        // WifiDirectAccelerator.teardown's own doc) — setMeshActive(false) already aborts this on
        // the "go offline" path, but process teardown (onDestroy) previously didn't, so a transfer
        // mid-flight at the exact moment the service is destroyed could leave a WFD group open
        // past the service's own lifetime. Found while reviewing this method for the radar-tick
        // job cleanup above; fixed alongside it rather than left as a separate pass.
        wifiDirectAccelerator.abortCurrent()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ---------------- public API for UI ----------------

    suspend fun sendSos(groupId: String, text: String): SosEntity {
        val sos = relay.createSos(groupId, text)
        hopTracker.markSosOrigin(groupId, sos.id)
        // PLAN-v2.md P1 §5.3 / docs/DECISIONS.md decision 20: without this, a freshly-authored
        // message only ever left the device via framesToPushOnConnect's one-shot push at the
        // START of a connection — now that P3 keeps links open far past that moment, an
        // already-open link at send time would otherwise never carry it until that link happens
        // to drop and a new one forms. Confirmed live: exactly this symptom (a message between
        // two already-connected phones sitting undelivered until a third phone's arrival forced
        // fresh connections).
        responder.floodForwardLocalSos(sos)
        return sos
    }

    suspend fun sendEvidence(groupId: String, plaintext: ByteArray, mimeType: String, originalLocalPath: String?): EvidenceEntity =
        relay.createEvidence(groupId, plaintext, mimeType, originalLocalPath)

    fun hopToGroupPresence(groupId: String): Int = hopTracker.myHop(groupId, "PRESENCE")

    /** Group-level, not global: the same device can carry a different display name per group. */
    suspend fun setNickname(groupId: String, username: String): NicknameEntity = relay.setNickname(groupId, username)
    suspend fun myNickname(groupId: String): NicknameEntity? = relay.myNickname(groupId)

    // ---------------- foreground notification ----------------

    private fun startForegroundNotification() {
        val channelId = "mesh_relay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Sync", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val (title, text) = decoyLabel()
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(decoyIconRes())
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        // Explicit serviceType (Q+) rather than relying on the manifest's foregroundServiceType
        // alone — with two types declared there ("connectedDevice|location"), Android 14+ wants
        // this call to confirm which of them are actually active for this particular
        // startForeground(), not just assume all declared types apply. Both are, for as long as
        // this service runs. Pre-Q has no such parameter; the manifest attribute alone covers it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(1, notification)
        }
    }

    // A small library of plain, generic-utility-looking status icons — one is picked at random the
    // first time this device ever shows the decoy notification, then reused every time after
    // (stored alongside GroupRepository's own per-install deviceId, same prefs file). Deliberately
    // NOT re-randomized on every service start: an icon that keeps changing on the SAME phone is
    // itself a tell ("why does this one app's notification icon keep moving"). The actual
    // anti-fingerprinting value is that DIFFERENT phones running this app show DIFFERENT icons —
    // there's no single, greppable "the mesh app always shows icon X" signature to look for.
    private val decoyIcons = intArrayOf(
        R.drawable.ic_decoy_dot, R.drawable.ic_decoy_bars, R.drawable.ic_decoy_grid,
        R.drawable.ic_decoy_check, R.drawable.ic_decoy_arrow_up, R.drawable.ic_decoy_lines,
        R.drawable.ic_decoy_ring, R.drawable.ic_decoy_triangle,
    )

    // Same reasoning as decoyIcons, applied to the title/text pair too — a fixed "Notes"/"Syncing"
    // string was itself a stable, greppable signature independent of which icon showed next to it
    // (anyone building a list of "known mesh-app notification text" only needed one entry). Picked
    // independently of the icon (not paired 1:1) for more combinations across installs.
    private val decoyLabels = arrayOf(
        "Notes" to "Syncing",
        "Files" to "Backing up",
        "Cloud" to "Syncing",
        "System" to "Optimizing",
        "Backup" to "In progress",
        "Storage" to "Indexing",
        "Updates" to "Checking",
        "Photos" to "Backing up",
    )

    private fun decoyIconRes(): Int =
        decoyIcons[pickOncePerInstall("decoy_icon_index", decoyIcons.size)]

    private fun decoyLabel(): Pair<String, String> =
        decoyLabels[pickOncePerInstall("decoy_label_index", decoyLabels.size)]

    /** Shared by [decoyIconRes] and [decoyLabel]: picks a random index in `0 until size` the first
     *  time this device ever needs one for [key], then reuses that same index forever after —
     *  see [decoyIcons]'s doc for why staying stable per-install (not re-randomizing on every
     *  service start) is what actually matters for the anti-fingerprinting goal. */
    private fun pickOncePerInstall(key: String, size: Int): Int {
        val prefs = getSharedPreferences("mesh_device", Context.MODE_PRIVATE)
        val stored = prefs.getInt(key, -1)
        return if (stored in 0 until size) stored else {
            (0 until size).random().also { prefs.edit().putInt(key, it).apply() }
        }
    }

    // ---------------- SOS alert notification ----------------
    // Separate, high-importance channel from the silent "Syncing" one above — an SOS is the one
    // thing in this app that should actually interrupt someone (sound/vibration/heads-up), which
    // IMPORTANCE_MIN on the sync channel deliberately never does.

    private fun createSosNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(SOS_CHANNEL_ID, "SOS alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "An SOS from one of your groups"
                enableVibration(true)
            }
        )
    }

    @SuppressLint("MissingPermission") // POST_NOTIFICATIONS is in requiredPermissions and gates
    // MeshService ever starting (see MainActivity) — granted by the time this can be called.
    private fun notifySos(sos: SosEntity, groupName: String) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_GROUP_ID, sos.groupId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, sos.id.hashCode(), openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, SOS_CHANNEL_ID)
            .setContentTitle("SOS — $groupName")
            .setContentText(sos.message.ifBlank { "SOS" })
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        NotificationManagerCompat.from(this).notify(sos.id.hashCode(), notification)
    }

    companion object {
        private const val SOS_CHANNEL_ID = "sos_alerts"

        // How often RadarTick re-emits purely to force staleness re-evaluation (HopTracker/
        // PositionTracker check age against wall-clock "now") — see RadarTick's own doc for why a
        // periodic tick, not just reacting to location/heading changes, is necessary here.
        private const val RADAR_TICK_INTERVAL_MS = 1000L
    }
}
