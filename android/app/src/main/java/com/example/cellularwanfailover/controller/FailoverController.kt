package com.example.cellularwanfailover.controller

import android.content.Context
import android.util.Log
import com.example.cellularwanfailover.discovery.DiscoveryBroadcaster
import com.example.cellularwanfailover.http.ControlHttpServer
import com.example.cellularwanfailover.http.FailoverControlDelegate
import com.example.cellularwanfailover.model.CellularState
import com.example.cellularwanfailover.model.FailoverState
import com.example.cellularwanfailover.model.LogEntry
import com.example.cellularwanfailover.model.TrustedNetwork
import com.example.cellularwanfailover.model.WifiNetworkInfo
import com.example.cellularwanfailover.network.CellularNetworkManager
import com.example.cellularwanfailover.network.NetworkUtils
import com.example.cellularwanfailover.network.TrustedNetworkManager
import com.example.cellularwanfailover.network.WifiTrustMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import com.example.cellularwanfailover.wireguard.WireGuardManager

class FailoverController private constructor(
    private val appContext: Context
) : FailoverControlDelegate {

    companion object {
        private const val TAG = "FailoverController"
        private const val MAX_LOGS = 200

        @Volatile
        private var instance: FailoverController? = null

        fun getInstance(context: Context): FailoverController {
            return instance ?: synchronized(this) {
                instance ?: FailoverController(context.applicationContext).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mutex = Mutex()

    val cellularManager = CellularNetworkManager(appContext)
    val wireGuardManager = WireGuardManager(appContext)
    val httpServer = ControlHttpServer(port = 8989, delegate = this)
    val broadcaster = DiscoveryBroadcaster(httpPort = 8989, broadcastPort = 8990)
    val trustedNetworkManager = TrustedNetworkManager(appContext)
    val wifiTrustMonitor = WifiTrustMonitor(appContext, trustedNetworkManager)

    val isNetworkTrusted: StateFlow<Boolean> = wifiTrustMonitor.isNetworkTrusted
    val currentWifiNetwork: StateFlow<WifiNetworkInfo?> = wifiTrustMonitor.currentWifiInfo
    val trustedNetworks: StateFlow<List<TrustedNetwork>> = trustedNetworkManager.trustedNetworks

    private val _failoverState = MutableStateFlow(FailoverState.IDLE)
    val failoverState: StateFlow<FailoverState> = _failoverState.asStateFlow()

    private val _cellularState = MutableStateFlow(CellularState.DISCONNECTED)
    val cellularState: StateFlow<CellularState> = _cellularState.asStateFlow()

    private val _wifiIp = MutableStateFlow<String?>(null)
    val wifiIp: StateFlow<String?> = _wifiIp.asStateFlow()

    private val _bytesTransmitted = MutableStateFlow(0L)
    val bytesTransmitted: StateFlow<Long> = _bytesTransmitted.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private var monitorJob: Job? = null

    // FailoverControlDelegate properties
    override val failoverStatusString: String
        get() = _failoverState.value.wireValue

    override val currentWifiIp: String?
        get() = _wifiIp.value

    override val isCellularReady: Boolean
        get() = _cellularState.value == CellularState.CONNECTED

    override val currentBytesTransmitted: Long
        get() = _bytesTransmitted.value

    override val wireguardPort: Int
        get() = WireGuardManager.LISTEN_PORT

    override val wireguardPublicKey: String
        get() = wireGuardManager.phonePublicKey

    override val wireguardTunnelIp: String
        get() = WireGuardManager.TUNNEL_PHONE_IP

    override val wireguardPeerIp: String
        get() = WireGuardManager.TUNNEL_PI_IP

    override fun getWireguardConfigString(): String =
        wireGuardManager.generatePiWgQuickConfig(_wifiIp.value ?: "10.20.0.X")

    init {
        // Forward logs from components
        wireGuardManager.onLog = { msg, isErr -> logEvent("WireGuard", msg, isErr) }
        broadcaster.onLog = { msg, isErr -> logEvent("Discovery", msg, isErr) }

        // Observe cellular state changes
        scope.launch {
            cellularManager.cellularState.collect { state ->
                _cellularState.value = state
            }
        }
    }

    private var isServiceRunning = false
    private var trustObservationJob: Job? = null

    fun startServiceComponents() {
        if (isServiceRunning) return
        isServiceRunning = true

        logEvent("FailoverController", "Starting service and network trust monitoring...")
        wifiTrustMonitor.start()

        trustObservationJob?.cancel()
        trustObservationJob = scope.launch {
            wifiTrustMonitor.isNetworkTrusted.collect { isTrusted ->
                if (isTrusted) {
                    resumeGatewayServices()
                } else {
                    pauseGatewayServices()
                }
            }
        }
    }

    private fun resumeGatewayServices() {
        refreshWifiIp()
        val ssid = wifiTrustMonitor.currentWifiInfo.value?.ssid ?: "Wi-Fi"
        logEvent("FailoverController", "Connected to trusted network ($ssid). Starting gateway services...")

        try {
            httpServer.start()
        } catch (e: Exception) {
            logEvent("FailoverController", "Failed to start HTTP server: ${e.message}", true)
        }

        try {
            broadcaster.start()
        } catch (e: Exception) {
            logEvent("FailoverController", "Failed to start UDP broadcaster: ${e.message}", true)
        }

        startMonitoringLoop()
    }

    private fun pauseGatewayServices() {
        logEvent("FailoverController", "Untrusted network or mobile data alone. Halting gateway services (Paused)...")
        monitorJob?.cancel()
        monitorJob = null

        broadcaster.stop()
        httpServer.stop()

        scope.launch {
            stopFailover()
        }
    }

    fun stopServiceComponents() {
        logEvent("FailoverController", "Stopping gateway components...")
        isServiceRunning = false
        trustObservationJob?.cancel()
        trustObservationJob = null

        wifiTrustMonitor.stop()
        pauseGatewayServices()
    }

    fun addCurrentNetwork(): Boolean {
        val info = wifiTrustMonitor.currentWifiInfo.value ?: return false
        if (info.ssid == "<unknown ssid>" || info.ssid.isBlank()) return false
        val added = trustedNetworkManager.addNetwork(info.ssid, info.bssid)
        if (added) {
            logEvent("FailoverController", "Added current network '${info.ssid}' to trusted whitelist")
        }
        return added
    }

    fun removeTrustedNetwork(id: String): Boolean {
        return trustedNetworkManager.removeNetwork(id)
    }

    fun clearTrustedNetworks() {
        trustedNetworkManager.clearAll()
    }

    fun refreshWifiNetworkInfo() {
        wifiTrustMonitor.refresh()
        refreshWifiIp()
    }

    override suspend fun startFailover(): Boolean {
        mutex.withLock {
            if (!wifiTrustMonitor.isNetworkTrusted.value) {
                logEvent("FailoverController", "Cannot start failover: untrusted network or mobile data alone", true)
                return false
            }

            if (_failoverState.value == FailoverState.ACTIVE) {
                logEvent("FailoverController", "Cellular failover relay is already active")
                return true
            }

            _failoverState.value = FailoverState.CONNECTING
            logEvent("FailoverController", "Cellular failover activation requested...")

            return try {
                val network = cellularManager.requestCellular(timeoutMs = 15000)
                logEvent("FailoverController", "Cellular connected ($network). Starting WireGuard tunnel on :51820...")

                try {
                    wireGuardManager.start(network.networkHandle)
                    logEvent("FailoverController", "WireGuard tunnel active on :51820 (userspace)")
                } catch (e: Exception) {
                    logEvent("FailoverController", "WireGuard startup error: ${e.message}", true)
                    throw e
                }

                _failoverState.value = FailoverState.ACTIVE
                logEvent("FailoverController", "WireGuard relay active and operational")
                true
            } catch (e: Exception) {
                _failoverState.value = FailoverState.ERROR
                logEvent("FailoverController", "Cellular activation failed: ${e.message}", true)
                wireGuardManager.stop()
                cellularManager.releaseCellular()
                _failoverState.value = FailoverState.IDLE
                false
            }
        }
    }

    override suspend fun stopFailover() {
        mutex.withLock {
            logEvent("FailoverController", "Deactivating failover relay...")
            wireGuardManager.stop()
            cellularManager.releaseCellular()
            _failoverState.value = FailoverState.IDLE
            logEvent("FailoverController", "WireGuard relay stopped, mobile cellular radio released")
        }
    }

    fun refreshWifiIp() {
        val ip = NetworkUtils.getWifiIpAddress(appContext)
        _wifiIp.value = ip
    }

    override fun logEvent(tag: String, message: String, isError: Boolean) {
        if (isError) {
            Log.e(tag, message)
        } else {
            Log.i(tag, message)
        }

        val entry = LogEntry(tag = tag, message = message, isError = isError)
        val currentList = _logs.value
        val updated = if (currentList.size >= MAX_LOGS) {
            currentList.drop(currentList.size - MAX_LOGS + 1) + entry
        } else {
            currentList + entry
        }
        _logs.value = updated
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    private fun startMonitoringLoop() {
        monitorJob?.cancel()
        monitorJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                // Update Wi-Fi IP and transmitted bytes counter
                refreshWifiIp()
                wireGuardManager.updateStatistics()
                _bytesTransmitted.value = wireGuardManager.bytesTransmitted.get()
                delay(2000L)
            }
        }
    }
}
