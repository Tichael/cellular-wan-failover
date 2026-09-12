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
    val wireguard: WireGuardInfo? = null
)

@Serializable
data class FailoverActionResponse(
    val result: String,
    val message: String? = null
)

@Serializable
data class DiscoveryMessage(
    val device: String,
    val service: String = "wan-failover",
    val port: Int = 8989
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
