package com.example.cellularwanfailover

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.cellularwanfailover.controller.FailoverController
import com.example.cellularwanfailover.service.FailoverForegroundService
import com.example.cellularwanfailover.ui.AppActions
import com.example.cellularwanfailover.ui.AppState
import com.example.cellularwanfailover.ui.AppTab
import com.example.cellularwanfailover.ui.PermissionStatus
import com.example.cellularwanfailover.ui.SetupIssue
import com.example.cellularwanfailover.ui.components.AppIcons
import com.example.cellularwanfailover.ui.diagnostics.DiagnosticsScreen
import com.example.cellularwanfailover.ui.setup.SetupScreen
import com.example.cellularwanfailover.ui.status.StatusScreen
import com.example.cellularwanfailover.ui.theme.AppTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var controller: FailoverController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = FailoverController.getInstance(this)

        // Automatically ensure foreground service is started
        FailoverForegroundService.startService(this)

        setContent {
            AppTheme {
                App(controller = controller, onRequestIgnoreBattery = { requestIgnoreBatteryOptimizations() })
            }
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }
}

@Composable
fun App(controller: FailoverController, onRequestIgnoreBattery: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Re-read on resume: the user may come back from a system permission or settings screen
    var permissions by remember { mutableStateOf(readPermissionStatus(context)) }
    LifecycleResumeEffect(Unit) {
        permissions = readPermissionStatus(context)
        controller.refreshWifiNetworkInfo()
        onPauseOrDispose { }
    }

    val permissionsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissions = readPermissionStatus(context)
        controller.refreshWifiNetworkInfo()
    }
    // "Allow all the time": lets the app read the Wi-Fi name while locked / in the background,
    // so it can verify a trusted network (e.g. after coming home) without being opened
    val backgroundLocationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissions = readPermissionStatus(context)
        controller.refreshWifiNetworkInfo()
    }
    var showBackgroundLocationDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        permissionsLauncher.launch(perms.toTypedArray())
    }

    val state = AppState(
        failoverState = controller.failoverState.collectAsStateWithLifecycle().value,
        cellularState = controller.cellularState.collectAsStateWithLifecycle().value,
        wifiIp = controller.wifiIp.collectAsStateWithLifecycle().value,
        currentWifi = controller.currentWifiNetwork.collectAsStateWithLifecycle().value,
        isNetworkTrusted = controller.isNetworkTrusted.collectAsStateWithLifecycle().value,
        bytesTransmitted = controller.bytesTransmitted.collectAsStateWithLifecycle().value,
        logs = controller.logs.collectAsStateWithLifecycle().value,
        pairings = controller.pairings.collectAsStateWithLifecycle().value,
        pairingState = controller.pairingState.collectAsStateWithLifecycle().value,
        standbyMode = controller.standbyMode.collectAsStateWithLifecycle().value,
        standbyHot = controller.standbyHot.collectAsStateWithLifecycle().value,
        pluggedIn = controller.pluggedIn.collectAsStateWithLifecycle().value,
        batteryLevel = controller.batteryLevelState.collectAsStateWithLifecycle().value,
        batterySettings = controller.batterySettingsState.collectAsStateWithLifecycle().value,
        publicKey = controller.wireguardPublicKey,
        permissions = permissions
    )

    var tab by rememberSaveable { mutableStateOf(AppTab.STATUS) }

    val actions = AppActions(
        openTab = { tab = it },
        setStandbyMode = controller::setStandbyMode,
        setBatterySettings = controller::setBatterySettings,
        stopFailover = { scope.launch { controller.stopFailover() } },
        clearLogs = controller::clearLogs,
        openPairingLink = { link -> PairingActivity.start(context, link) },
        cancelPairing = controller::cancelPairing,
        acknowledgePairingResult = controller::acknowledgePairingResult,
        removeGateway = controller::removeGateway,
        fixIssue = { issue ->
            when (issue) {
                SetupIssue.LOCATION_PERMISSION -> permissionsLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                )
                SetupIssue.LOCATION_SERVICES -> context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                SetupIssue.BACKGROUND_LOCATION -> showBackgroundLocationDialog = true
                SetupIssue.BATTERY_OPTIMIZATION -> onRequestIgnoreBattery()
            }
        },
        requestNotifications = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionsLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                AppTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        label = { Text(item.label) },
                        icon = {
                            val icon = when (item) {
                                AppTab.STATUS -> AppIcons.Shield
                                AppTab.SETUP -> AppIcons.Sliders
                                AppTab.DIAGNOSTICS -> AppIcons.Pulse
                            }
                            // Setup carries a dot while something keeps the app from running on its own
                            if (item == AppTab.SETUP && state.issues.isNotEmpty()) {
                                BadgedBox(badge = { Badge() }) { Icon(icon, contentDescription = null) }
                            } else {
                                Icon(icon, contentDescription = null)
                            }
                        }
                    )
                }
            }
        }
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab) {
            AppTab.STATUS -> StatusScreen(state, actions, modifier)
            AppTab.SETUP -> SetupScreen(state, actions, modifier)
            AppTab.DIAGNOSTICS -> DiagnosticsScreen(state, actions, modifier)
        }
    }

    if (showBackgroundLocationDialog) {
        AlertDialog(
            onDismissRequest = { showBackgroundLocationDialog = false },
            title = { Text("Allow location all the time") },
            text = {
                Text(
                    "To confirm you're on a paired Wi-Fi network while the phone is locked or the app is " +
                        "closed, Android requires location access set to \"Allow all the time\".\n\n" +
                        "The app only reads the Wi-Fi name. It doesn't use or store your location.\n\n" +
                        "On the next screen, choose \"Allow all the time\".",
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(onClick = {
                    showBackgroundLocationDialog = false
                    // Android 11+ opens the app's location settings, where "Allow all the time" is chosen
                    backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { showBackgroundLocationDialog = false }) { Text("Not now") } }
        )
    }
}

private fun readPermissionStatus(context: Context): PermissionStatus {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return PermissionStatus(
        location = hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION),
        locationServicesOn = locationManager.isLocationEnabled,
        // Background location only exists on Android 10+; before that, foreground location covers it
        backgroundLocation = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            hasPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            hasPermission(context, Manifest.permission.POST_NOTIFICATIONS),
        batteryUnrestricted = powerManager.isIgnoringBatteryOptimizations(context.packageName)
    )
}

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
