package com.example.cellularwanfailover

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.cellularwanfailover.auth.PairingInvite
import com.example.cellularwanfailover.auth.PairingState
import com.example.cellularwanfailover.controller.FailoverController
import com.example.cellularwanfailover.service.FailoverForegroundService

/**
 * Pairs the phone with a gateway from its QR code / link (camera app, in-app scanner or
 * pasted). Always asks for confirmation; the pairing is only saved once the gateway has
 * answered over the phone's current Wi-Fi network, which then becomes trusted.
 */
class PairingActivity : ComponentActivity() {

    companion object {
        /** Opens the pairing screen for a link scanned or pasted inside the app. */
        fun start(context: Context, link: String) {
            context.startActivity(
                Intent(context, PairingActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .setData(Uri.parse(link))
            )
        }
    }

    private var link by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        link = intent?.dataString
        val controller = FailoverController.getInstance(this)

        // The service answers the gateway while pairing, even if this screen is left
        FailoverForegroundService.startService(this)

        setContent {
            MaterialTheme {
                PairingScreen(
                    controller = controller,
                    link = link,
                    onClose = { finish() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Another link while this screen is open: it replaces the one shown (still needs confirming)
        setIntent(intent)
        link = intent.dataString
    }
}

@Composable
private fun PairingScreen(
    controller: FailoverController,
    link: String?,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val invite = remember(link) { PairingInvite.parse(link) }
    val pairingState by controller.pairingState.collectAsStateWithLifecycle()
    val pairings by controller.pairings.collectAsStateWithLifecycle()
    val currentWifi by controller.currentWifiNetwork.collectAsStateWithLifecycle()
    // Survives rotation: whether this screen started the pairing for this link
    var started by rememberSaveable(link) { mutableStateOf(false) }
    var startError by rememberSaveable(link) { mutableStateOf<String?>(null) }

    LifecycleResumeEffect(Unit) {
        controller.refreshWifiNetworkInfo()
        onPauseOrDispose { }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { controller.refreshWifiNetworkInfo() }

    fun close() {
        if (pairingState !is PairingState.Waiting) controller.acknowledgePairingResult()
        onClose()
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Pair a gateway", fontSize = 20.sp, fontWeight = FontWeight.Bold)

                    val state = pairingState
                    val ownState = invite != null && when (state) {
                        is PairingState.Waiting -> state.phoneId == invite.phoneId
                        is PairingState.Paired -> state.phoneId == invite.phoneId
                        is PairingState.Failed -> started
                        PairingState.Idle -> false
                    }
                    val alreadyPaired = invite?.let { inv -> pairings.firstOrNull { it.phoneId == inv.phoneId } }

                    when {
                        invite == null -> {
                            Message("This isn't a valid gateway pairing code. On the gateway, run 'wanctl pair' and scan the new QR code.")
                            ActionRow(primary = "Close" to { close() })
                        }

                        ownState && state is PairingState.Waiting -> {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                                Text(bold("Waiting for ", state.gatewayName, " on Wi-Fi ", state.ssid, "..."), fontSize = 15.sp)
                            }
                            Message("Keep 'wanctl pair' running on the gateway. It answers within a few seconds when it's on this network.")
                            ActionRow(
                                primary = "Hide" to { onClose() },
                                secondary = "Cancel pairing" to { controller.cancelPairing() }
                            )
                        }

                        ownState && state is PairingState.Paired -> {
                            Text(bold("Paired with ", state.gatewayName), fontSize = 16.sp, color = Color(0xFF2E7D32))
                            Message("Wi-Fi '${state.ssid}' is now trusted: this gateway can use the phone for failover while it's connected to it.")
                            ActionRow(primary = "Done" to { close() })
                        }

                        ownState && state is PairingState.Failed -> {
                            Text("Pairing failed", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                            Message(state.reason + " Nothing was saved.")
                            Message("Each QR code works once and expires after a few minutes: run 'wanctl pair' again if needed.")
                            ActionRow(primary = "Close" to { close() })
                        }

                        alreadyPaired != null -> {
                            Message("This phone is already paired with ${alreadyPaired.gatewayName} on Wi-Fi '${alreadyPaired.ssid}' with this code.")
                            ActionRow(primary = "Close" to { close() })
                        }

                        else -> {
                            val ssid = currentWifi?.ssid
                            Text(bold("Gateway: ", invite.gatewayName), fontSize = 15.sp)
                            when {
                                currentWifi == null -> {
                                    Message("Connect the phone to the Wi-Fi network your gateway is on. The pairing is tied to that network.")
                                    ActionRow(
                                        primary = "Wi-Fi settings" to { openWifiSettings(context) },
                                        secondary = "Cancel" to { close() }
                                    )
                                }

                                ssid == null || ssid == "<unknown ssid>" -> {
                                    Message(
                                        "Android hides the Wi-Fi name from the app. Allow location access (the app only reads " +
                                            "the Wi-Fi name) and make sure location is turned on."
                                    )
                                    ActionRow(
                                        primary = "Allow location" to {
                                            permissionLauncher.launch(
                                                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                                            )
                                        },
                                        secondary = "Cancel" to { close() }
                                    )
                                }

                                else -> {
                                    Text(bold("Wi-Fi network: ", ssid), fontSize = 15.sp)
                                    Message(
                                        "Pairing trusts this Wi-Fi network: the gateway can then use this phone's mobile data " +
                                            "for failover while the phone is connected to '$ssid'. The gateway must be on this " +
                                            "network to complete the pairing."
                                    )
                                    pairings.firstOrNull { it.gatewayId == invite.gatewayId }?.let { previous ->
                                        Message(
                                            "This replaces your current pairing with ${previous.gatewayName} (Wi-Fi '${previous.ssid}').",
                                            color = Color(0xFF8D6E00)
                                        )
                                    }
                                    startError?.let { Message(it, color = MaterialTheme.colorScheme.error) }
                                    ActionRow(
                                        primary = "Pair" to {
                                            startError = when (controller.beginPairing(invite)) {
                                                FailoverController.PairingStartResult.Started -> null.also { started = true }
                                                FailoverController.PairingStartResult.NoWifi -> "The phone is no longer on Wi-Fi."
                                                FailoverController.PairingStartResult.WifiNameUnknown -> "The Wi-Fi name can't be read."
                                            }
                                        },
                                        secondary = "Cancel" to { close() }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, fontSize = 13.sp, color = color)
}

@Composable
private fun ActionRow(primary: Pair<String, () -> Unit>, secondary: Pair<String, () -> Unit>? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
    ) {
        secondary?.let { (label, action) -> OutlinedButton(onClick = action) { Text(label) } }
        Button(onClick = primary.second) { Text(primary.first) }
    }
}

/** Alternates plain and bold parts: plain, bold, plain, bold... */
private fun bold(vararg parts: String) = buildAnnotatedString {
    parts.forEachIndexed { i, part ->
        if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(part) } else append(part)
    }
}

private fun openWifiSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        Intent(Settings.Panel.ACTION_WIFI)
    } else {
        Intent(Settings.ACTION_WIFI_SETTINGS)
    }
    context.startActivity(intent)
}
