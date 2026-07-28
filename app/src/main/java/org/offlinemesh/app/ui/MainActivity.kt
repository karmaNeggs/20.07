package org.offlinemesh.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.offlinemesh.app.ble.MeshService
import org.offlinemesh.app.data.GroupRepository

class MainActivity : ComponentActivity() {

    private var meshService by mutableStateOf<MeshService?>(null)
    private lateinit var repo: GroupRepository
    // Bumped on every onResume and every permission-request result, so the permission check
    // below re-runs instead of freezing on whatever it read at first composition (found live:
    // both test phones got stuck on "Grant permissions" doing nothing after the OS silently
    // stopped showing the dialog post-denial, and even granting via system Settings wouldn't
    // have been noticed without this, short of a force-close).
    private var resumeTick by mutableStateOf(0)
    private var bindRequested = false
    private var pendingJoinCode by mutableStateOf<String?>(null)
    private var pendingOpenGroupId by mutableStateOf<String?>(null) // set by tapping an SOS notification
    private var isStarted = false // true between onStart/onStop — drives the mesh power tier

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as MeshService.LocalBinder).service()
            meshService = service
            service.setForegroundActive(isStarted) // apply current visibility once bound, in case onStart already fired before the bind completed
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            meshService = null
        }
    }

    override fun onStart() {
        super.onStart()
        isStarted = true
        meshService?.setForegroundActive(true)
    }

    override fun onStop() {
        super.onStop()
        isStarted = false
        meshService?.setForegroundActive(false)
    }

    private val requiredPermissions: Array<String>
        get() = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(android.Manifest.permission.BLUETOOTH_SCAN)
                add(android.Manifest.permission.BLUETOOTH_ADVERTISE)
                add(android.Manifest.permission.BLUETOOTH_CONNECT)
            }
            // Real, intentional use now: GPS for the radar screen. Requested on every OS
            // version for that reason (not just the pre-12 BLE-scan quirk from before).
            add(android.Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    private fun hasAllPermissions(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    /** One-time, best-effort nudge — not part of the required-permission gate, so a "no" here
     *  never blocks using the app. A correctly-declared foreground service (MeshService) is
     *  Android's own defense against being killed in the background; this is what actually
     *  matters against OEM battery managers that kill foreground services anyway. Fires once
     *  (own prefs flag, separate from the permission-gate one) after core permissions are granted. */
    private fun maybeRequestBatteryOptimizationExemption(prefs: android.content.SharedPreferences) {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val alreadyHandled = prefs.getBoolean("asked_battery_opt", false) ||
            pm == null || pm.isIgnoringBatteryOptimizations(packageName)
        if (alreadyHandled) return
        prefs.edit().putBoolean("asked_battery_opt", true).apply()
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        try {
            startActivity(intent)
        } catch (_: Exception) {
            // Rare OEM that doesn't handle this intent — nothing more to do from code; the
            // equivalent toggle still exists manually in system battery settings.
        }
    }

    @Suppress("LongMethod") // one-time launch setup (permission launcher, service bind, compose
    // tree) — this project's own detekt config treats comment-dense, explanatory code as a
    // deliberate choice rather than something to break apart just to satisfy a line count.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Security-audit finding: without this, Android's recent-apps switcher shows a live
        // screenshot of this Activity's real UI (chat feed, radar, SOS) regardless of which
        // launcher identity (real "20.07" or decoy "Notes") is currently enabled — someone
        // flipping through recent apps on a searched/seized phone would see straight through the
        // disguise. FLAG_SECURE blanks that thumbnail (shows a plain placeholder instead) and also
        // blocks screenshots/screen recording of the app generally, a reasonable default either way.
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )
        // The manifest's system Activity theme only covers window background before Compose's
        // first frame — status/navigation bar color is a separate system-drawn layer that theme
        // doesn't fully pin down on every OEM skin, especially on large screens with a taller/
        // different-shaped nav bar. Set explicitly to the app's own dark background so nothing
        // ever shows the light default (was surfacing as a stray white bar on some devices), and
        // mark both bars "not light" so their icons render light-on-dark instead of invisible.
        window.statusBarColor = AppColors.Background.toArgb()
        window.navigationBarColor = AppColors.Background.toArgb()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        repo = GroupRepository(applicationContext)
        pendingJoinCode = extractJoinCode(intent)
        pendingOpenGroupId = intent.getStringExtra(EXTRA_OPEN_GROUP_ID)
        val prefs = getSharedPreferences("app_perms", Context.MODE_PRIVATE)

        val launcher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            // Don't call startAndBindMesh() here directly — only ever from the single
            // LaunchedEffect(resumeTick) below, so a grant can never trigger bindService()
            // twice on the same ServiceConnection. This callback's only job is to record that
            // we've asked and nudge recomposition so the permission check re-runs.
            prefs.edit().putBoolean("asked_once", true).apply()
            resumeTick++
        }

        setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val allGranted = remember(resumeTick) { hasAllPermissions() }
                    val askedBefore = prefs.getBoolean("asked_once", false)
                    // shouldShowRequestPermissionRationale is false both before ever asking and
                    // after the OS decides to stop asking (denied w/ "don't ask again", or
                    // denied enough times) — askedBefore disambiguates which case we're in.
                    val permanentlyDenied = remember(resumeTick) {
                        askedBefore && !allGranted && requiredPermissions.any { perm ->
                            ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED &&
                                !shouldShowRequestPermissionRationale(perm)
                        }
                    }

                    LaunchedEffect(resumeTick) {
                        // bindRequested (not just meshService == null) guards the brief window
                        // where a bind is already in flight but onServiceConnected hasn't fired
                        // yet — otherwise a quick background/foreground right after launch could
                        // still trigger a second bindService() before the first one connects.
                        if (allGranted && !bindRequested) {
                            bindRequested = true
                            startAndBindMesh()
                        }
                    }
                    LaunchedEffect(Unit) {
                        if (!allGranted && !askedBefore) launcher.launch(requiredPermissions)
                    }
                    LaunchedEffect(allGranted) {
                        if (allGranted) maybeRequestBatteryOptimizationExemption(prefs)
                    }

                    if (allGranted) {
                        AppNavHost(
                            repo = repo, meshService = meshService,
                            pendingJoinCode = pendingJoinCode, onJoinCodeConsumed = { pendingJoinCode = null },
                            pendingOpenGroupId = pendingOpenGroupId, onOpenGroupIdConsumed = { pendingOpenGroupId = null }
                        )
                    } else {
                        PermissionGate(
                            permanentlyDenied = permanentlyDenied,
                            onRequest = {
                                if (permanentlyDenied) {
                                    startActivity(
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                                    )
                                } else {
                                    launcher.launch(requiredPermissions)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractJoinCode(intent)?.let { pendingJoinCode = it }
        intent.getStringExtra(EXTRA_OPEN_GROUP_ID)?.let { pendingOpenGroupId = it }
    }

    private fun extractJoinCode(intent: Intent?): String? {
        val uri = intent?.data ?: return null
        return uri.getQueryParameter("c")
    }

    override fun onResume() {
        super.onResume()
        resumeTick++ // catches permissions granted via system Settings while we were backgrounded
    }

    private fun startAndBindMesh() {
        val intent = Intent(this, MeshService::class.java)
        startForegroundService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unbindService(connection) } catch (_: Exception) {}
    }

    companion object {
        /** Intent extra key an SOS notification's tap target uses to jump straight to that group. */
        const val EXTRA_OPEN_GROUP_ID = "openGroupId"
    }
}

@Composable
fun PermissionGate(permanentlyDenied: Boolean, onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("This needs Bluetooth to find nearby group members over the mesh, and location for the GPS radar screen. Positions are never stored — only kept in memory for a couple of minutes.")
        if (permanentlyDenied) {
            Spacer(Modifier.height(8.dp))
            Text("Android has stopped asking after an earlier denial — grant these in system Settings instead.")
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRequest) { Text(if (permanentlyDenied) "Open Settings" else "Grant permissions") }
    }
}

@Composable
fun AppNavHost(
    repo: GroupRepository,
    meshService: MeshService?,
    pendingJoinCode: String?,
    onJoinCodeConsumed: () -> Unit,
    pendingOpenGroupId: String? = null,
    onOpenGroupIdConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()

    LaunchedEffect(pendingJoinCode) {
        if (pendingJoinCode != null) {
            navController.navigate("addGroup")
        }
    }

    // Tapping an SOS notification jumps straight to that group's chat.
    LaunchedEffect(pendingOpenGroupId) {
        if (pendingOpenGroupId != null) {
            navController.navigate("group/$pendingOpenGroupId")
            onOpenGroupIdConsumed()
        }
    }

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                repo = repo,
                meshService = meshService,
                onAddGroup = { navController.navigate("addGroup") },
                onOpenGroup = { groupId -> navController.navigate("group/$groupId") },
                onGeneralSos = { navController.navigate("sos") }
            )
        }
        composable("addGroup") {
            AddGroupScreen(
                repo = repo,
                prefillCode = pendingJoinCode,
                onDone = {
                    onJoinCodeConsumed()
                    navController.popBackStack("home", inclusive = false)
                }
            )
        }
        composable("sos") {
            SosComposeScreen(repo = repo, meshService = meshService, onSent = { navController.popBackStack() })
        }
        composable(
            "group/{groupId}",
            arguments = listOf(navArgument("groupId") { type = NavType.StringType })
        ) { backStackEntry ->
            val groupId = backStackEntry.arguments?.getString("groupId") ?: return@composable
            GroupChatScreen(
                groupId = groupId,
                repo = repo,
                meshService = meshService,
                onExpandRadar = { navController.navigate("navigate/$groupId") },
                onDeleted = { navController.popBackStack("home", inclusive = false) }
            )
        }
        composable(
            "navigate/{groupId}",
            arguments = listOf(navArgument("groupId") { type = NavType.StringType })
        ) { backStackEntry ->
            val groupId = backStackEntry.arguments?.getString("groupId") ?: return@composable
            NavigateScreen(groupId = groupId, meshService = meshService)
        }
    }
}
