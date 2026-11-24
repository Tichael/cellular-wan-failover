package com.example.cellularwanfailover.ui.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cellularwanfailover.model.CellularState
import com.example.cellularwanfailover.power.StandbyMode
import com.example.cellularwanfailover.ui.AppActions
import com.example.cellularwanfailover.ui.AppState
import com.example.cellularwanfailover.ui.AppTab
import com.example.cellularwanfailover.ui.Headline
import com.example.cellularwanfailover.ui.SetupIssue
import com.example.cellularwanfailover.ui.components.AppIcons
import com.example.cellularwanfailover.ui.components.CardDivider
import com.example.cellularwanfailover.ui.components.NavRow
import com.example.cellularwanfailover.ui.components.ScreenTitle
import com.example.cellularwanfailover.ui.components.SectionCard
import com.example.cellularwanfailover.ui.components.StatusBadge
import com.example.cellularwanfailover.ui.components.StatusTile
import com.example.cellularwanfailover.ui.formatBytes
import com.example.cellularwanfailover.ui.setup.PairingButtons
import com.example.cellularwanfailover.ui.setup.PairingStatusRow
import com.example.cellularwanfailover.ui.theme.LocalStatusColors
import com.example.cellularwanfailover.ui.theme.StatusPalette

@Composable
fun StatusScreen(state: AppState, actions: AppActions, modifier: Modifier = Modifier) {
    val headline = state.headline
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle("WAN Failover")
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (headline == Headline.NOT_SET_UP) {
                FirstRunContent(state, actions)
                return@Column
            }

            HeroCard(state, actions)
            if (state.issues.isNotEmpty()) FixListCard(state.issues, actions)
            Tiles(state)
            NavRow(
                icon = AppIcons.Bolt,
                title = "Mode: " + modeName(state.standbyMode),
                subtitle = modeSummary(state.standbyMode, state.standbyHot),
                onClick = { actions.openTab(AppTab.SETUP) }
            )
            NavRow(
                icon = AppIcons.Router,
                title = if (state.pairings.size == 1) "1 paired gateway" else "${state.pairings.size} paired gateways",
                subtitle = state.pairings.joinToString(" · ") { p ->
                    p.gatewayName + if (p.ssid == state.currentSsid) " (here)" else ""
                },
                onClick = { actions.openTab(AppTab.SETUP) }
            )
        }
    }
}

// --- Hero card ---------------------------------------------------------------------------

@Composable
private fun HeroCard(state: AppState, actions: AppActions) {
    val colors = LocalStatusColors.current
    when (state.headline) {
        Headline.PROTECTED -> Hero(
            palette = colors.good,
            icon = AppIcons.ShieldCheck,
            title = "Protected",
            subtitle = "Ready to take over if your internet goes down"
        ) {
            StandbyChipRow(colors.good, hot = state.standbyHot, detail = if (state.standbyHot) "Waiting for the gateway to open the tunnel" else "Cellular starts on demand")
        }
        Headline.TUNNEL_READY -> Hero(
            palette = colors.good,
            icon = AppIcons.ShieldCheck,
            title = "Protected",
            subtitle = "Backup tunnel is up: your router can switch to it at once"
        ) {
            StandbyChipRow(colors.good, hot = true, detail = formatBytes(state.bytesTransmitted) + " relayed")
        }
        Headline.STARTING -> Hero(
            palette = colors.active,
            icon = null,
            title = "Starting failover",
            subtitle = "Bringing up mobile data and the tunnel…"
        )
        Headline.CARRYING -> CarryingHero(state, colors.active, actions)
        Headline.ERROR -> Hero(
            palette = colors.attention,
            icon = AppIcons.Warning,
            title = "Failover failed",
            subtitle = "The relay couldn't start. The gateway will try again."
        ) {
            OutlinedButton(onClick = { actions.openTab(AppTab.DIAGNOSTICS) }, colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.attention.content)) {
                Text("See the event log")
            }
        }
        Headline.PAUSED -> Hero(
            palette = colors.paused,
            icon = AppIcons.Pause,
            title = "Paused",
            subtitle = if (state.currentWifi == null) "Not connected to Wi-Fi" else "Not on a paired Wi-Fi network"
        ) {
            val pairedNetworks = state.pairings.map { it.ssid }.distinct().joinToString(" or ")
            Text(
                text = buildAnnotatedString {
                    val ssid = state.currentSsid
                    when {
                        ssid != null -> {
                            append("No gateway is paired on ")
                            withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(ssid) }
                            append(". ")
                        }
                        state.wifiNameHidden -> append("Android is hiding the Wi-Fi name, so the app can't check it. ")
                    }
                    append("Protection resumes by itself on $pairedNetworks.")
                },
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }
        Headline.NOT_SET_UP -> Unit
    }
}

@Composable
private fun CarryingHero(state: AppState, palette: StatusPalette, actions: AppActions) {
    var confirmStop by remember { mutableStateOf(false) }
    Hero(
        palette = palette,
        icon = AppIcons.Transfer,
        title = "Carrying your internet",
        subtitle = "Your gateway is routing home traffic through cellular"
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Relayed this session", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(formatBytes(state.bytesTransmitted), fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium)
            }
            OutlinedButton(
                onClick = { confirmStop = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.content),
                border = androidx.compose.foundation.BorderStroke(1.dp, palette.accent),
                modifier = Modifier.heightIn(min = 44.dp)
            ) {
                Icon(AppIcons.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Stop", modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(AppIcons.Info, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "The gateway ends failover by itself once your home internet is back. Stop only if you need to cut cellular now.",
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    if (confirmStop) StopFailoverDialog(onConfirm = { confirmStop = false; actions.stopFailover() }, onDismiss = { confirmStop = false })
}

@Composable
fun StopFailoverDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stop failover?") },
        text = { Text("Your home network loses the backup connection until the gateway starts failover again.", fontSize = 14.sp) },
        confirmButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) {
                Text("Stop")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun Hero(
    palette: StatusPalette,
    icon: ImageVector?,
    title: String,
    subtitle: String,
    footer: (@Composable () -> Unit)? = null
) {
    Surface(shape = RoundedCornerShape(24.dp), color = palette.container, contentColor = palette.content) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                StatusBadge(palette) {
                    if (icon != null) {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.5.dp, color = palette.onAccent)
                    }
                }
                Column {
                    Text(title, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium)
                    Text(subtitle, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
            if (footer != null) {
                HorizontalDivider(color = palette.content.copy(alpha = 0.15f))
                footer()
            }
        }
    }
}

@Composable
private fun StandbyChipRow(palette: StatusPalette, hot: Boolean, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerLowest, contentColor = palette.content) {
            Text(
                if (hot) "Hot standby" else "Cold standby",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
            )
        }
        Text(detail, fontSize = 13.sp)
    }
}

// --- Fix list ----------------------------------------------------------------------------

@Composable
private fun FixListCard(issues: List<SetupIssue>, actions: AppActions) {
    val attention = LocalStatusColors.current.attention
    SectionCard {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(AppIcons.Warning, contentDescription = null, modifier = Modifier.size(18.dp), tint = attention.accent)
            Text(
                if (issues.size == 1) "1 thing keeps it from running on its own" else "${issues.size} things keep it from running on its own",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = attention.accent
            )
        }
        issues.forEachIndexed { index, issue ->
            if (index > 0) CardDivider()
            val (title, detail, action) = issueText(issue)
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(detail, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = { actions.fixIssue(issue) }) { Text(action) }
            }
        }
    }
}

/** Title, explanation and button label of a setup issue. */
fun issueText(issue: SetupIssue): Triple<String, String, String> = when (issue) {
    SetupIssue.LOCATION_PERMISSION -> Triple(
        "Allow location",
        "Android needs it to show the app the Wi-Fi name. Location isn't used or stored.",
        "Allow"
    )
    SetupIssue.LOCATION_SERVICES -> Triple(
        "Turn on location",
        "Android hides the Wi-Fi name while location is off.",
        "Settings"
    )
    SetupIssue.BACKGROUND_LOCATION -> Triple(
        "Allow location all the time",
        "Lets the app check the Wi-Fi while the phone is locked.",
        "Allow"
    )
    SetupIssue.BATTERY_OPTIMIZATION -> Triple(
        "Turn off battery optimization",
        "Keeps Android from stopping the service.",
        "Turn off"
    )
}

// --- Tiles -------------------------------------------------------------------------------

@Composable
private fun Tiles(state: AppState) {
    val colors = LocalStatusColors.current
    // Accent colors keep 4.5:1 on the tile surface in both themes
    val warnColor = colors.paused.accent
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusTile(
                icon = AppIcons.Wifi,
                label = "Wi-Fi",
                value = when {
                    state.currentWifi == null -> "Not connected"
                    state.currentSsid == null -> "Name hidden"
                    else -> state.currentSsid!!
                },
                detail = when {
                    state.currentWifi == null -> "Connect to a paired network"
                    state.isNetworkTrusted -> "Paired · " + state.gatewaysHere.joinToString(", ") { it.gatewayName }.ifEmpty { "gateway" }
                    else -> "Not paired"
                },
                detailColor = if (state.isNetworkTrusted) MaterialTheme.colorScheme.onSurfaceVariant else warnColor,
                modifier = Modifier.weight(1f)
            )
            StatusTile(
                icon = AppIcons.Signal,
                label = "Cellular",
                value = when (state.cellularState) {
                    CellularState.DISCONNECTED -> "Off"
                    CellularState.CONNECTING -> "Connecting"
                    CellularState.CONNECTED -> "Connected"
                },
                detail = when {
                    state.cellularState == CellularState.CONNECTED -> "Mobile data in use"
                    state.cellularState == CellularState.CONNECTING -> "Starting mobile data"
                    !state.isNetworkTrusted -> "Services halted"
                    state.standbyHot -> "Starts with the tunnel"
                    else -> "Starts on demand"
                },
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusTile(
                icon = if (state.pluggedIn) AppIcons.BatteryCharging else AppIcons.Battery,
                label = "Battery",
                value = "${state.batteryLevel}%",
                detail = when {
                    state.pluggedIn -> "Charging"
                    state.batteryLow -> "Below ${state.batterySettings.minBattery}% minimum"
                    else -> "On battery · min ${state.batterySettings.minBattery}%"
                },
                detailColor = if (state.batteryLow) warnColor else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            StatusTile(
                icon = AppIcons.Transfer,
                label = "Relayed",
                value = formatBytes(state.bytesTransmitted),
                detail = "Current relay",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

fun modeName(mode: StandbyMode): String = when (mode) {
    StandbyMode.ON_DEMAND -> "On demand"
    StandbyMode.WHILE_PLUGGED -> "Plugged in"
    StandbyMode.ALWAYS -> "Always"
}

private fun modeSummary(mode: StandbyMode, hot: Boolean): String = when (mode) {
    StandbyMode.ON_DEMAND -> "Cold standby · cellular only during an outage"
    StandbyMode.WHILE_PLUGGED -> if (hot) "Hot while charging · hot now" else "Hot while charging · cold now (on battery)"
    StandbyMode.ALWAYS -> "Hot standby · always-on tunnel"
}

// --- First run ---------------------------------------------------------------------------

@Composable
private fun FirstRunContent(state: AppState, actions: AppActions) {
    val neutral = LocalStatusColors.current.neutral
    val good = LocalStatusColors.current.good
    Surface(shape = RoundedCornerShape(24.dp), color = neutral.container, contentColor = neutral.content) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusBadge(neutral) { Icon(AppIcons.Router, contentDescription = null, modifier = Modifier.size(28.dp)) }
            Text("Pair your gateway", fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium)
            Text(
                "The gateway on your home network uses this phone as its backup internet. Pair once and the app runs by itself.",
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }
    }

    if (state.issues.isNotEmpty()) FixListCard(state.issues, actions)

    SectionCard(contentPadding = 16.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Step(1, "On the gateway, run") {
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Text(
                        "docker exec -it wan-failover-gateway wanctl pair",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }
            Step(2, "Join the same Wi-Fi") {
                val ssid = state.currentSsid
                if (ssid != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp), tint = good.accent)
                        Text("Connected to $ssid", fontSize = 13.sp, color = good.accent)
                    }
                } else {
                    Text(
                        if (state.wifiNameHidden) "Wi-Fi name hidden: allow location above" else "Not connected to Wi-Fi",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Step(3, "Scan the QR code it shows") {
                Text(
                    "This Wi-Fi becomes a trusted network once pairing completes.",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    PairingStatusRow(state.pairingState, actions)
    Spacer(Modifier.height(4.dp))
    PairingButtons(actions, stacked = true)
}

@Composable
private fun Step(number: Int, title: String, content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(28.dp)) {
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text("$number", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            content()
        }
    }
}
