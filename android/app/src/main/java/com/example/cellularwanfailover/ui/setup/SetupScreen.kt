package com.example.cellularwanfailover.ui.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cellularwanfailover.auth.GatewayPairing
import com.example.cellularwanfailover.power.BatterySettings
import com.example.cellularwanfailover.power.StandbyMode
import com.example.cellularwanfailover.ui.AppActions
import com.example.cellularwanfailover.ui.AppState
import com.example.cellularwanfailover.ui.SetupIssue
import com.example.cellularwanfailover.ui.components.AppIcons
import com.example.cellularwanfailover.ui.components.CardDivider
import com.example.cellularwanfailover.ui.components.HelpText
import com.example.cellularwanfailover.ui.components.ScreenTitle
import com.example.cellularwanfailover.ui.components.SectionCard
import com.example.cellularwanfailover.ui.components.SectionHeader
import com.example.cellularwanfailover.ui.status.modeName
import com.example.cellularwanfailover.ui.theme.LocalStatusColors
import java.text.DateFormat
import java.util.Date

@Composable
fun SetupScreen(state: AppState, actions: AppActions, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle("Setup")
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GatewaysSection(state, actions)
            StandbySection(state, actions)
            BatterySection(state, actions)
            PermissionsSection(state, actions)
        }
    }
}

// --- Gateways & trusted Wi-Fi ------------------------------------------------------------

@Composable
private fun GatewaysSection(state: AppState, actions: AppActions) {
    var gatewayToRemove by remember { mutableStateOf<GatewayPairing?>(null) }
    val good = LocalStatusColors.current.good

    SectionHeader("Gateways & trusted Wi-Fi")
    HelpText(
        "A Wi-Fi network is trusted once a gateway has been paired on it. The app only runs on trusted networks.",
        modifier = Modifier.padding(horizontal = 4.dp)
    )

    if (state.pairings.isEmpty()) {
        SectionCard(contentPadding = 16.dp) {
            HelpText(
                "No gateway paired yet. On the gateway, run 'docker exec -it wan-failover-gateway wanctl pair' " +
                    "and scan the QR code while this phone is on the same Wi-Fi network."
            )
        }
    } else {
        SectionCard {
            val byNetwork = state.pairings.groupBy { it.ssid }
            byNetwork.entries.forEachIndexed { index, (ssid, gateways) ->
                if (index > 0) CardDivider()
                Row(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(AppIcons.Wifi, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(ssid, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    if (ssid == state.currentSsid) {
                        Surface(shape = RoundedCornerShape(10.dp), color = good.container, contentColor = good.content) {
                            Text("Connected", fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                        }
                    }
                }
                gateways.forEach { pairing ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 44.dp, end = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(pairing.gatewayName, fontSize = 14.sp)
                            Text(
                                "Paired " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(pairing.pairedAt)),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { gatewayToRemove = pairing }) {
                            Icon(AppIcons.Trash, contentDescription = "Remove ${pairing.gatewayName}", modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }

    PairingStatusRow(state.pairingState, actions)
    PairingButtons(actions, stacked = false, scanLabel = "Pair a gateway")

    gatewayToRemove?.let { pairing ->
        AlertDialog(
            onDismissRequest = { gatewayToRemove = null },
            title = { Text("Remove ${pairing.gatewayName}?") },
            text = {
                Text(
                    "This gateway won't be able to use the phone anymore, and Wi-Fi '${pairing.ssid}' is no longer " +
                        "trusted unless another gateway is paired on it. Also run 'wanctl unpair' on the gateway.",
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        actions.removeGateway(pairing.gatewayId)
                        gatewayToRemove = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { gatewayToRemove = null }) { Text("Cancel") } }
        )
    }
}

// --- Standby mode ------------------------------------------------------------------------

private data class ModeOption(val mode: StandbyMode, val tag: String, val description: String)

private val modeOptions = listOf(
    ModeOption(StandbyMode.ON_DEMAND, "Cold", "Cellular starts only during an outage. Saves battery and data."),
    ModeOption(StandbyMode.WHILE_PLUGGED, "Auto", "Always-on tunnel while charging, on demand on battery. Your router switches faster."),
    ModeOption(StandbyMode.ALWAYS, "Hot", "Always-on tunnel, even on battery. Fastest switch, keeps mobile data up.")
)

@Composable
private fun StandbySection(state: AppState, actions: AppActions) {
    SectionHeader("Standby mode")
    Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        modeOptions.forEach { option ->
            val selected = state.standbyMode == option.mode
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceContainerLowest,
                border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { actions.setStandbyMode(option.mode) })
            ) {
                Row(modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)) {
                    RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
                    Column(modifier = Modifier.weight(1f).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(modeName(option.mode), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                                Text(option.tag, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                            }
                        }
                        HelpText(option.description)
                        if (selected) {
                            Text(
                                text = when (option.mode) {
                                    StandbyMode.ON_DEMAND -> "Cold now"
                                    StandbyMode.ALWAYS -> "Hot now"
                                    StandbyMode.WHILE_PLUGGED -> when {
                                        state.pluggedIn -> "Plugged in now: hot"
                                        state.standbyHot -> "Just unplugged: back to cold in a moment"
                                        else -> "On battery now: cold until you plug in"
                                    }
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- Battery -----------------------------------------------------------------------------

@Composable
private fun BatterySection(state: AppState, actions: AppActions) {
    val settings = state.batterySettings
    SectionHeader("Battery")
    SectionCard(contentPadding = 16.dp) {
        // Follows the slider while dragging; saved (and sent to the gateway) when released
        var draft by remember(settings.minBattery) { mutableFloatStateOf(settings.minBattery.toFloat()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Minimum battery", fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("${draft.toInt()}%", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { actions.setBatterySettings(settings.copy(minBattery = draft.toInt())) },
            valueRange = 0f..BatterySettings.MAX_MIN_BATTERY.toFloat(),
            steps = BatterySettings.MAX_MIN_BATTERY / BatterySettings.MIN_BATTERY_STEP - 1,
            modifier = Modifier.semantics { contentDescription = "Minimum battery" }
        )
        HelpText(
            "Battery now ${state.batteryLevel}%" + (if (state.pluggedIn) ", plugged in" else ", on battery") +
                ". Below the minimum on battery, the gateway prefers another phone and this one gets no always-on tunnel."
        )
        CardDivider(inset = 0.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .toggleable(
                    value = settings.lastResort,
                    role = Role.Switch,
                    onValueChange = { actions.setBatterySettings(settings.copy(lastResort = it)) }
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Use as last resort", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                HelpText(
                    if (settings.lastResort) {
                        "Below the minimum, still carries an outage if no other phone can."
                    } else {
                        "Below the minimum, never carries an outage. The house stays offline if no other phone can."
                    }
                )
            }
            Switch(checked = settings.lastResort, onCheckedChange = null)
        }
    }
}

// --- Permissions -------------------------------------------------------------------------

@Composable
private fun PermissionsSection(state: AppState, actions: AppActions) {
    val p = state.permissions
    SectionHeader("Permissions")
    SectionCard {
        PermissionRow(
            title = "Location",
            ok = p.location && p.backgroundLocation && p.locationServicesOn,
            detail = when {
                !p.location -> "Not allowed · the app can't read the Wi-Fi name"
                !p.locationServicesOn -> "Location is off · the Wi-Fi name is hidden"
                !p.backgroundLocation -> "Only while using the app · can't check Wi-Fi while locked"
                else -> "Allowed all the time · reads the Wi-Fi name only"
            },
            action = when {
                !p.location -> "Allow" to { actions.fixIssue(SetupIssue.LOCATION_PERMISSION) }
                !p.locationServicesOn -> "Settings" to { actions.fixIssue(SetupIssue.LOCATION_SERVICES) }
                !p.backgroundLocation -> "Allow" to { actions.fixIssue(SetupIssue.BACKGROUND_LOCATION) }
                else -> null
            }
        )
        CardDivider()
        PermissionRow(
            title = "Notifications",
            ok = p.notifications,
            warn = false,
            detail = if (p.notifications) "Allowed" else "Not allowed · the service's status notification is hidden",
            action = if (p.notifications) null else "Allow" to actions.requestNotifications
        )
        CardDivider()
        PermissionRow(
            title = "Battery optimization",
            ok = p.batteryUnrestricted,
            detail = if (p.batteryUnrestricted) "Off · the service runs freely" else "On · Android may stop the service",
            action = if (p.batteryUnrestricted) null else "Turn off" to { actions.fixIssue(SetupIssue.BATTERY_OPTIMIZATION) }
        )
    }
}

@Composable
private fun PermissionRow(title: String, ok: Boolean, detail: String, action: Pair<String, () -> Unit>?, warn: Boolean = true) {
    val colors = LocalStatusColors.current
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when {
            ok -> Icon(AppIcons.Check, contentDescription = "OK", modifier = Modifier.size(20.dp), tint = colors.good.accent)
            warn -> Icon(AppIcons.Warning, contentDescription = "Needs attention", modifier = Modifier.size(20.dp), tint = colors.attention.accent)
            else -> Surface(shape = CircleShape, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(6.dp).size(8.dp)) {}
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 15.sp)
            Text(
                detail,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = if (!ok && warn) colors.attention.accent else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        action?.let { (label, onClick) ->
            OutlinedButton(onClick = onClick) { Text(label) }
        }
    }
}
