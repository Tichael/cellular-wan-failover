package com.example.cellularwanfailover.model

import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class FailoverState(val wireValue: String) {
    IDLE("idle"),
    CONNECTING("connecting"),
    ACTIVE("active"),
    ERROR("error")
}

enum class CellularState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

@Serializable
data class WireGuardInfo(
    val port: Int = 51820,
    val public_key: String,
    val tunnel_ip: String = "10.100.0.1",
    val peer_ip: String = "10.100.0.2"
)

@Serializable
data class StatusResponse(
    val status: String,
    val mode: String = "wireguard",
    val wifi_ip: String?,
    val cellular_ready: Boolean,
    val bytes_transmitted: Long,
    val wireguard: WireGuardInfo? = null,
    /** "hot": the gateway may keep the tunnel up at all times; "cold": only during an outage */
    val standby: String = "cold",
    /** Battery state of charge (%); the gateway prefers phones plugged in or with more battery */
    val battery: Int,
    val plugged: Boolean,
    /** The phone's settings: below min_battery (on battery), only carries an outage as a last resort, if allowed */
    val min_battery: Int,
    val last_resort: Boolean
)

@Serializable
data class StartFailoverRequest(
    val gateway_public_key: String
)

@Serializable
data class FailoverActionResponse(
    val result: String,
    val message: String? = null
)

@Serializable
data class ChallengeResponse(
    val challenge: String
)

data class LogEntry(
    val id: Long = nextId.incrementAndGet(),
    val timestamp: String = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date()),
    val tag: String,
    val message: String,
    val isError: Boolean = false
) {
    companion object {
        private val nextId = java.util.concurrent.atomic.AtomicLong(0L)
    }
}

data class WifiNetworkInfo(
    val ssid: String,
    val bssid: String? = null,
    val isConnected: Boolean = true
)

