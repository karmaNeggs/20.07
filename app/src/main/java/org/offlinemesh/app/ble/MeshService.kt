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
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.offlinemesh.app.data.EvidenceEntity
import org.offlinemesh.app.data.GroupRepository
import org.offlinemesh.app.data.NicknameEntity
import org.offlinemesh.app.data.SosEntity
import org.offlinemesh.app.sensors.CompassTracker
import org.offlinemesh.app.sensors.LocationTracker
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
    lateinit var locationTracker: LocationTracker
        private set
    lateinit var compassTracker: CompassTracker
        private set

    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var responder: RelayResponder
    private lateinit var beaconRadio: BeaconRadio
    private lateinit var gattServer: MeshGattServer
    private lateinit var gattClient: MeshGattClient

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

    private var pruneJob: Job? = null

    // Radars must never keep showing peer dots as if the mesh were live once the radio that feeds
    // them is off — cached positions/hops would otherwise sit on screen looking current when
    // nothing could possibly be arriving. All three radar screens read this one flow, so they go
    // in and out of "Bluetooth is off" together instead of drifting independently.
    private val _bluetoothEnabled = MutableStateFlow(true)
    val bluetoothEnabled: StateFlow<Boolean> = _bluetoothEnabled
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            _bluetoothEnabled.value = state == BluetoothAdapter.STATE_ON
        }
    }

    override fun onCreate() {
        super.onCreate()
        repo = GroupRepository(applicationContext)
        relay = RelayEngine(applicationContext, repo)
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        _bluetoothEnabled.value = bluetoothManager.adapter?.isEnabled ?: false
        registerReceiver(bluetoothStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        locationTracker = LocationTracker(applicationContext).also { it.start() }
        compassTracker = CompassTracker(applicationContext).also { it.start() }
        createSosNotificationChannel()
        responder = RelayResponder(repo, relay, hopTracker, positionTracker, locationTracker) { sos, groupName ->
            notifySos(sos, groupName)
        }

        gattServer = MeshGattServer(this, bluetoothManager, responder, serviceScope).also { it.start() }
        gattClient = MeshGattClient(this, responder, serviceScope, ::currentTier)
        beaconRadio = BeaconRadio(bluetoothManager, repo, hopTracker, serviceScope, ::currentTier) { device ->
            gattClient.maybeConnect(device)
        }

        startForegroundNotification()
        beaconRadio.startAdvertising()
        beaconRadio.startScanning()
        startPruning()
    }

    private fun startPruning() {
        pruneJob = serviceScope.launch {
            while (isActive) {
                relay.pruneExpired()
                delay(30 * 60 * 1000L) // every 30 min — this is housekeeping, not latency-sensitive
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        pruneJob?.cancel()
        try { unregisterReceiver(bluetoothStateReceiver) } catch (_: Exception) {}
        beaconRadio.stop()
        gattServer.stop()
        locationTracker.stop()
        compassTracker.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ---------------- public API for UI ----------------

    suspend fun sendSos(groupId: String, text: String): SosEntity {
        val sos = relay.createSos(groupId, text)
        hopTracker.markSosOrigin(groupId, sos.id)
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
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Notes")
            .setContentText("Syncing")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        startForeground(1, notification)
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
    }
}
