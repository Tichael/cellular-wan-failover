package com.example.cellularwanfailover.ui

import com.example.cellularwanfailover.auth.GatewayPairing
import com.example.cellularwanfailover.auth.PairingState
import com.example.cellularwanfailover.model.CellularState
import com.example.cellularwanfailover.model.FailoverState
import com.example.cellularwanfailover.model.LogEntry
import com.example.cellularwanfailover.model.WifiNetworkInfo
import com.example.cellularwanfailover.power.BatteryPolicy
import com.example.cellularwanfailover.power.BatterySettings
import com.example.cellularwanfailover.power.StandbyMode

enum class AppTab(val label: String) { STATUS("Status"), SETUP("Setup"), DIAGNOSTICS("Diagnostics") }

/** Everything the screens show, read from the controller and Android. */
data class AppState(
    val failoverState: FailoverState,
    val cellularState: CellularState,
    val wifiIp: String?,
    val currentWifi: WifiNetworkInfo?,
    val isNetworkTrusted: Boolean,
    val bytesTransmitted: Long,
    val logs: List<LogEntry>,
    val pairings: List<GatewayPairing>,
    val pairingState: PairingState,
    val standbyMode: StandbyMode,
    val standbyHot: Boolean,
    val pluggedIn: Boolean,
    val batteryLevel: Int,
    val batterySettings: BatterySettings,
    val publicKey: String,
    val permissions: PermissionStatus
) {
    val headline: Headline get() = headline(failoverState, isNetworkTrusted, pairings.isNotEmpty(), standbyHot)
    val issues: List<SetupIssue> get() = setupIssues(permissions)

    /** The Wi-Fi name, null when not on Wi-Fi or when Android hides it */
    val currentSsid: String? get() = currentWifi?.ssid?.takeIf { it != UNKNOWN_SSID }
    val wifiNameHidden: Boolean get() = currentWifi?.ssid == UNKNOWN_SSID

    val batteryLow: Boolean get() = BatteryPolicy.isLow(batteryLevel, pluggedIn, batterySettings)

    /** Gateways paired on the current network */
    val gatewaysHere: List<GatewayPairing> get() = pairings.filter { it.ssid == currentSsid }

    companion object {
        const val UNKNOWN_SSID = "<unknown ssid>"
    }
}

/** What the screens can ask for. */
class AppActions(
    val openTab: (AppTab) -> Unit,
    val setStandbyMode: (StandbyMode) -> Unit,
    val setBatterySettings: (BatterySettings) -> Unit,
    val stopFailover: () -> Unit,
    val clearLogs: () -> Unit,
    val openPairingLink: (String) -> Unit,
    val cancelPairing: () -> Unit,
    val acknowledgePairingResult: () -> Unit,
    val removeGateway: (String) -> Unit,
    val fixIssue: (SetupIssue) -> Unit,
    val requestNotifications: () -> Unit
)
