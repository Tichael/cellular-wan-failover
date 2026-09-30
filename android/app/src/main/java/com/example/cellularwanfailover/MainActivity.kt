package com.example.cellularwanfailover

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.cellularwanfailover.controller.FailoverController
import com.example.cellularwanfailover.model.CellularState
import com.example.cellularwanfailover.model.FailoverState
import com.example.cellularwanfailover.model.LogEntry
import com.example.cellularwanfailover.model.TrustedNetwork
import com.example.cellularwanfailover.model.WifiNetworkInfo
import com.example.cellularwanfailover.service.FailoverForegroundService
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var controller: FailoverController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = FailoverController.getInstance(this)

        // Automatically ensure foreground service is started
        FailoverForegroundService.startService(this)

        setContent {
            MaterialTheme {
                MainScreen(
                    controller = controller,
                    onStartService = { FailoverForegroundService.startService(this) },
                    onStopService = { FailoverForegroundService.stopService(this) },
                    onRequestIgnoreBattery = { requestIgnoreBatteryOptimizations() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        FailoverForegroundService.updateNotification(this)
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(fallbackIntent)
                }
            }
        }
    }
}

@Composable
fun MainScreen(
    controller: FailoverController,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onRequestIgnoreBattery: () -> Unit
) {
    val failoverState by controller.failoverState.collectAsStateWithLifecycle()
    val cellularState by controller.cellularState.collectAsStateWithLifecycle()
    val wifiIp by controller.wifiIp.collectAsStateWithLifecycle()
    val bytesTransmitted by controller.bytesTransmitted.collectAsStateWithLifecycle()
    val logs by controller.logs.collectAsStateWithLifecycle()
    val isNetworkTrusted by controller.isNetworkTrusted.collectAsStateWithLifecycle()
    val currentWifiNetwork by controller.currentWifiNetwork.collectAsStateWithLifecycle()
    val trustedNetworks by controller.trustedNetworks.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var showWgDialog by remember { mutableStateOf(false) }

    // Request permissions for notifications and Wi-Fi SSID access (location)
    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        controller.refreshWifiNetworkInfo()
        FailoverForegroundService.updateNotification(context)
    }

    LaunchedEffect(Unit) {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionsLauncher.launch(perms.toTypedArray())
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            HeaderView(
                failoverState = failoverState,
                isNetworkTrusted = isNetworkTrusted
            )

            // Status Card
            StatusCard(
                failoverState = failoverState,
                cellularState = cellularState,
                wifiIp = wifiIp,
                currentWifi = currentWifiNetwork,
                isNetworkTrusted = isNetworkTrusted,
                bytesTransmitted = bytesTransmitted,
                wireguardPublicKey = controller.wireguardPublicKey
            )

            // Trusted Networks Card
            TrustedNetworksCard(
                isNetworkTrusted = isNetworkTrusted,
                currentWifi = currentWifiNetwork,
                trustedNetworks = trustedNetworks,
                onAddCurrentNetwork = { controller.addCurrentNetwork() },
                onRemoveNetwork = { id -> controller.removeTrustedNetwork(id) },
                onClearAll = { controller.clearTrustedNetworks() },
                onRequestLocationPermission = {
                    permissionsLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            )

            // Controls Card
            ControlsCard(
                failoverState = failoverState,
                isNetworkTrusted = isNetworkTrusted,
                onToggleFailover = {
                    scope.launch {
                        if (failoverState == FailoverState.ACTIVE) {
                            controller.stopFailover()
                        } else {
                            controller.startFailover()
                        }
                    }
                },
                onShowWireGuardConfig = { showWgDialog = true },
                onRequestIgnoreBattery = onRequestIgnoreBattery
            )

            // Real-time Event Log Viewer
            LogViewerCard(
                logs = logs,
                onClearLogs = { controller.clearLogs() },
                modifier = Modifier.weight(1f)
            )
        }
    }

    // WireGuard Raspberry Pi configuration modal dialog
    if (showWgDialog) {
        val piConfig = controller.getWireguardConfigString()
        AlertDialog(
            onDismissRequest = { showWgDialog = false },
            title = { Text("Raspberry Pi WireGuard Configuration") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Configuration file /etc/wireguard/wg0.conf for your Raspberry Pi:",
                        fontSize = 13.sp
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1E1E1E))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = piConfig,
                            color = Color(0xFFD4D4D4),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(piConfig))
                        showWgDialog = false
                    }
                ) {
                    Text("Copy Config")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWgDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
fun HeaderView(
    failoverState: FailoverState,
    isNetworkTrusted: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = "Cellular WAN Failover",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "Local WireGuard Gateway",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        val (badgeColor, badgeText) = if (!isNetworkTrusted) {
            Color(0xFFE65100) to "PAUSED (UNTRUSTED)"
        } else {
            when (failoverState) {
                FailoverState.IDLE -> Color(0xFF689F38) to "STANDBY"
                FailoverState.CONNECTING -> Color(0xFFF57C00) to "CONNECTING"
                FailoverState.ACTIVE -> Color(0xFFD32F2F) to "FAILOVER ACTIVE"
                FailoverState.ERROR -> Color(0xFFC2185B) to "ERROR"
            }
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(badgeColor.copy(alpha = 0.15f))
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(badgeColor)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = badgeText,
                    color = badgeColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun StatusCard(
    failoverState: FailoverState,
    cellularState: CellularState,
    wifiIp: String?,
    currentWifi: WifiNetworkInfo?,
    isNetworkTrusted: Boolean,
    bytesTransmitted: Long,
    wireguardPublicKey: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Network Status",
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = "Wi-Fi Interface (LAN):", fontSize = 13.sp)
                val wifiLabel = when {
                    wifiIp != null && currentWifi?.ssid != null && currentWifi.ssid != "<unknown ssid>" ->
                        "$wifiIp (${currentWifi.ssid})"
                    wifiIp != null -> wifiIp
                    else -> "Disconnected"
                }
                Text(
                    text = wifiLabel,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = if (wifiIp != null) MaterialTheme.colorScheme.primary else Color.Gray
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = "Trusted Wi-Fi Gating:", fontSize = 13.sp)
                Text(
                    text = if (isNetworkTrusted) "Approved (Trusted Wi-Fi)" else "Paused (Untrusted Network)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = if (isNetworkTrusted) Color(0xFF388E3C) else Color(0xFFE65100)
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = "Cellular Network (WAN):", fontSize = 13.sp)
                val (cellText, cellColor) = when (cellularState) {
                    CellularState.DISCONNECTED -> "Standby / Inactive" to Color.Gray
                    CellularState.CONNECTING -> "Connecting..." to Color(0xFFF57C00)
                    CellularState.CONNECTED -> "Ready / Connected" to Color(0xFF388E3C)
                }
                Text(text = cellText, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = cellColor)
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = "WireGuard Relay:", fontSize = 13.sp)
                val wgText = when {
                    failoverState == FailoverState.ACTIVE -> "Listening UDP :51820"
                    !isNetworkTrusted -> "Halted (Untrusted Network)"
                    else -> "Standby (Port :51820)"
                }
                Text(
                    text = wgText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (failoverState == FailoverState.ACTIVE) Color(0xFF388E3C) else Color.Gray
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = "Phone Public Key:", fontSize = 13.sp)
                Text(
                    text = wireguardPublicKey.take(12) + "...",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = "HTTP Control API:", fontSize = 13.sp)
                Text(
                    text = if (isNetworkTrusted) "Port 8989 (Active)" else "Port 8989 (Halted / Paused)",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isNetworkTrusted) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFFE65100)
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = "Relayed Data:", fontSize = 13.sp)
                Text(
                    text = formatBytes(bytesTransmitted),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun TrustedNetworksCard(
    isNetworkTrusted: Boolean,
    currentWifi: WifiNetworkInfo?,
    trustedNetworks: List<TrustedNetwork>,
    onAddCurrentNetwork: () -> Unit,
    onRemoveNetwork: (String) -> Unit,
    onClearAll: () -> Unit,
    onRequestLocationPermission: () -> Unit
) {
    var showClearConfirmDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Trusted Wi-Fi Whitelist",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                val (badgeBg, badgeFg, badgeLabel) = when {
                    currentWifi == null -> Triple(Color(0xFFE0E0E0), Color(0xFF616161), "NO WI-FI")
                    isNetworkTrusted -> Triple(Color(0xFFE8F5E9), Color(0xFF2E7D32), "TRUSTED")
                    else -> Triple(Color(0xFFFFEBEE), Color(0xFFC62828), "UNTRUSTED")
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(badgeBg)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = badgeLabel,
                        color = badgeFg,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Current Wi-Fi info line
            val currentSsid = currentWifi?.ssid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Current Network:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (currentSsid == "<unknown ssid>") {
                    TextButton(onClick = onRequestLocationPermission) {
                        Text("Grant Location Permission", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    Text(
                        text = currentSsid ?: "Not connected",
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp
                    )
                }
            }

            HorizontalDivider()

            // Buttons: Add Current Network & Clear All
            val canAddCurrent = currentWifi != null &&
                    currentSsid != null &&
                    currentSsid != "<unknown ssid>" &&
                    trustedNetworks.none { it.ssid.equals(currentSsid, ignoreCase = false) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onAddCurrentNetwork,
                    modifier = Modifier.weight(1f),
                    enabled = canAddCurrent
                ) {
                    val addText = when {
                        currentWifi == null -> "No Wi-Fi"
                        currentSsid == "<unknown ssid>" -> "Location Needed"
                        !canAddCurrent -> "Already Added"
                        else -> "Add Current Network"
                    }
                    Text(addText, fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = { showClearConfirmDialog = true },
                    enabled = trustedNetworks.isNotEmpty(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Clear All", fontSize = 12.sp)
                }
            }

            // List of trusted networks
            if (trustedNetworks.isEmpty()) {
                Text(
                    text = "No trusted networks added yet. Gateway services remain halted until an approved Wi-Fi network is added.",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 140.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    trustedNetworks.forEach { network ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = network.ssid,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                                if (network.bssid != null) {
                                    Text(
                                        text = "BSSID: ${network.bssid}",
                                        fontSize = 10.sp,
                                        color = Color.Gray,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                            TextButton(
                                onClick = { onRemoveNetwork(network.id) }
                            ) {
                                Text("Remove", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text("Clear All Trusted Networks?") },
            text = {
                Text("This will remove all approved networks. Gateway services will immediately halt until a network is added.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onClearAll()
                        showClearConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ControlsCard(
    failoverState: FailoverState,
    isNetworkTrusted: Boolean,
    onToggleFailover: () -> Unit,
    onShowWireGuardConfig: () -> Unit,
    onRequestIgnoreBattery: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "Manual Controls",
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )

            Button(
                onClick = onToggleFailover,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (failoverState == FailoverState.ACTIVE) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary
                ),
                enabled = isNetworkTrusted && failoverState != FailoverState.CONNECTING
            ) {
                if (!isNetworkTrusted) {
                    Text("Start Failover (Paused: Untrusted Wi-Fi)")
                } else if (failoverState == FailoverState.CONNECTING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Activating...")
                } else if (failoverState == FailoverState.ACTIVE) {
                    Text("Stop Failover Relay")
                } else {
                    Text("Start Failover Relay")
                }
            }

            OutlinedButton(
                onClick = onShowWireGuardConfig,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Show Raspberry Pi WireGuard Config", fontSize = 13.sp)
            }

            OutlinedButton(
                onClick = onRequestIgnoreBattery,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Disable Battery Optimizations", fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun LogViewerCard(
    logs: List<LogEntry>,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Event Log (${logs.size})",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Button(
                    onClick = onClearLogs,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Clear", color = Color.White, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No events recorded yet", color = Color.Gray, fontSize = 12.sp)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(logs, key = { it.id }) { log ->
                        val textColor = if (log.isError) Color(0xFFFF6B6B) else Color(0xFFD4D4D4)
                        val tagColor = when (log.tag) {
                            "WireGuard" -> Color(0xFFE91E63)
                            "KtorServer" -> Color(0xFF64B5F6)
                            "Socks5" -> Color(0xFF81C784)
                            "Discovery" -> Color(0xFFFFB74D)
                            else -> Color(0xFFBA68C8)
                        }

                        Row(modifier = Modifier.padding(vertical = 2.dp)) {
                            Text(
                                text = "${log.timestamp} ",
                                color = Color(0xFF888888),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "[${log.tag}] ",
                                color = tagColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = log.message,
                                color = textColor,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
    val pre = "KMGTPE"[exp - 1]
    return String.format(Locale.US, "%.2f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
}
