package com.example.cellularwanfailover.ui

import com.example.cellularwanfailover.model.FailoverState
import java.util.Locale

/** The one state the Status tab leads with, worked out from the controller's flows. */
enum class Headline {
    /** No gateway paired yet */
    NOT_SET_UP,
    /** Not on a paired Wi-Fi network: gateway services are halted */
    PAUSED,
    /** On a paired network, ready to start cellular when the gateway asks */
    PROTECTED,
    /** Hot standby: the tunnel is up so the router can switch to it at once */
    TUNNEL_READY,
    /** Cellular and the relay are starting */
    STARTING,
    /** Failover on demand: the gateway is routing traffic through this phone */
    CARRYING,
    /** The relay failed to start */
    ERROR
}

fun headline(
    failoverState: FailoverState,
    isNetworkTrusted: Boolean,
    hasPairings: Boolean,
    standbyHot: Boolean
): Headline = when {
    failoverState == FailoverState.ERROR -> Headline.ERROR
    failoverState == FailoverState.CONNECTING -> Headline.STARTING
    // In hot standby the gateway keeps the relay up: that alone doesn't mean an outage
    failoverState == FailoverState.ACTIVE -> if (standbyHot) Headline.TUNNEL_READY else Headline.CARRYING
    !hasPairings -> Headline.NOT_SET_UP
    !isNetworkTrusted -> Headline.PAUSED
    else -> Headline.PROTECTED
}

/** What Android currently lets the app do. */
data class PermissionStatus(
    val location: Boolean,
    val locationServicesOn: Boolean,
    /** "Allow all the time": reads the Wi-Fi name while locked */
    val backgroundLocation: Boolean,
    val notifications: Boolean,
    /** Battery optimization off for the app */
    val batteryUnrestricted: Boolean
)

/** Problems that keep the app from running on its own, most blocking first. */
enum class SetupIssue { LOCATION_PERMISSION, LOCATION_SERVICES, BACKGROUND_LOCATION, BATTERY_OPTIMIZATION }

fun setupIssues(status: PermissionStatus): List<SetupIssue> = buildList {
    if (!status.location) {
        add(SetupIssue.LOCATION_PERMISSION)
    } else {
        if (!status.locationServicesOn) add(SetupIssue.LOCATION_SERVICES)
        if (!status.backgroundLocation) add(SetupIssue.BACKGROUND_LOCATION)
    }
    if (!status.batteryUnrestricted) add(SetupIssue.BATTERY_OPTIMIZATION)
}

/** Event log filters; each log tag belongs to one. */
enum class LogCategory(val label: String) {
    FAILOVER("Failover"),
    CONTROL_API("Control API"),
    PAIRING("Pairing"),
    POWER("Power"),
    OTHER("Other")
}

fun logCategory(tag: String): LogCategory = when (tag) {
    "FailoverController", "WireGuard" -> LogCategory.FAILOVER
    "KtorServer", "Discovery" -> LogCategory.CONTROL_API
    "Pairing" -> LogCategory.PAIRING
    "Standby", "Battery" -> LogCategory.POWER
    else -> LogCategory.OTHER
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    var value = bytes.toDouble()
    var unit = 0
    val units = "KMGTPE"
    while (value >= 1024 && unit < units.length) {
        value /= 1024
        unit++
    }
    return String.format(Locale.US, if (value >= 100) "%.0f %sB" else "%.1f %sB", value, units[unit - 1])
}
