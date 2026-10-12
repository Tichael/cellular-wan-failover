package com.example.cellularwanfailover.controller

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.example.cellularwanfailover.auth.AndroidPairingKeyStore
import com.example.cellularwanfailover.auth.AuthProtocol
import com.example.cellularwanfailover.auth.GatewayAuthenticator
import com.example.cellularwanfailover.auth.GatewayPairing
import com.example.cellularwanfailover.auth.PairingInvite
import com.example.cellularwanfailover.auth.PairingRepository
import com.example.cellularwanfailover.auth.PairingState
import com.example.cellularwanfailover.discovery.DiscoveryBroadcaster
import com.example.cellularwanfailover.http.ControlHttpServer
import com.example.cellularwanfailover.http.FailoverControlDelegate
import com.example.cellularwanfailover.http.FailoverStartResult
import com.example.cellularwanfailover.model.CellularState
import com.example.cellularwanfailover.model.FailoverState
import com.example.cellularwanfailover.model.LogEntry
import com.example.cellularwanfailover.model.WifiNetworkInfo
import com.example.cellularwanfailover.network.CellularNetworkManager
import com.example.cellularwanfailover.network.NetworkUtils
import com.example.cellularwanfailover.network.WifiTrustMonitor
import com.example.cellularwanfailover.power.PowerMonitor
import com.example.cellularwanfailover.power.StandbyMode
import com.example.cellularwanfailover.power.StandbyPolicy
import com.wireguard.crypto.Key
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

import com.example.cellularwanfailover.wireguard.WireGuardManager

class FailoverController private constructor(
    private val appContext: Context
) : FailoverControlDelegate {

    companion object {
        private const val TAG = "FailoverController"
        private const val MAX_LOGS = 200
        private const val PAIRING_TIMEOUT_MS = 5 * 60_000L
        // Replaced by gateway pairings (each pairing trusts the Wi-Fi it was made on)
        private const val LEGACY_TRUSTED_NETWORKS_PREFS = "trusted_networks_prefs"
        private const val UNKNOWN_SSID = "<unknown ssid>"
        private const val STANDBY_PREFS = "standby_prefs"
        private const val KEY_STANDBY_MODE = "standby_mode"

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
    private val pairingRepository = PairingRepository(
        appContext.getSharedPreferences(PairingRepository.PREFS_NAME, Context.MODE_PRIVATE),
        AndroidPairingKeyStore()
    )
    val authenticator = GatewayAuthenticator(pairingRepository)
    val httpServer = ControlHttpServer(port = 8989, delegate = this, authenticator = authenticator)
    val broadcaster = DiscoveryBroadcaster(
        broadcastPort = 8990,
        wifiNetworkProvider = { NetworkUtils.getWifiNetwork(appContext) },
        payloadProvider = { authenticator.discoveryPayload(currentSsid, _wifiIp.value, standby) }
    )
    // Gateway services run on the networks of the phone's pairings (and of a pairing in progress)
    val wifiTrustMonitor = WifiTrustMonitor(appContext, authenticator::isServiceSsid, authenticator.trustChanges)

    val isNetworkTrusted: StateFlow<Boolean> = wifiTrustMonitor.isNetworkTrusted
    val currentWifiNetwork: StateFlow<WifiNetworkInfo?> = wifiTrustMonitor.currentWifiInfo
    val pairings: StateFlow<List<GatewayPairing>> = authenticator.pairings
    val pairingState: StateFlow<PairingState> = authenticator.pairingState

    private val powerMonitor = PowerMonitor(appContext)
    private val standbyPrefs = appContext.getSharedPreferences(STANDBY_PREFS, Context.MODE_PRIVATE)

    private val _standbyMode = MutableStateFlow(StandbyMode.fromStorage(standbyPrefs.getString(KEY_STANDBY_MODE, null)))
    val standbyMode: StateFlow<StandbyMode> = _standbyMode.asStateFlow()

    /** True while the phone offers its gateway an always-on tunnel (hot standby) */
    private val _standbyHot = MutableStateFlow(false)
    val standbyHot: StateFlow<Boolean> = _standbyHot.asStateFlow()

    val pluggedIn: StateFlow<Boolean> = powerMonitor.pluggedIn

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
    private var pairingJob: Job? = null

    // Gateway that started the active failover: only it may stop it, others get "busy"
    @Volatile
    private var activeGatewayId: String? = null

    // FailoverControlDelegate properties
    override val failoverStatusString: String
        get() = _failoverState.value.wireValue

    override val currentWifiIp: String?
        get() = _wifiIp.value

    override val currentSsid: String?
        get() = wifiTrustMonitor.currentWifiInfo.value?.ssid?.takeIf { it != UNKNOWN_SSID }

    override val deviceName: String
        get() = Settings.Global.getString(appContext.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()

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

    override val standby: String
        get() = if (_standbyHot.value) AuthProtocol.STANDBY_HOT else AuthProtocol.STANDBY_COLD

    init {
        // Forward logs from components
        wireGuardManager.onLog = { msg, isErr -> logEvent("WireGuard", msg, isErr) }
        broadcaster.onLog = { msg, isErr -> logEvent("Discovery", msg, isErr) }

        appContext.deleteSharedPreferences(LEGACY_TRUSTED_NETWORKS_PREFS)

        // Observe cellular state changes
        scope.launch {
            cellularManager.cellularState.collect { state ->
                _cellularState.value = state
            }
        }

        // While the relay runs, Android may replace the cellular network (handover, carrier
        // session reset, airplane mode...): the request stays filed, so follow the new one
        scope.launch {
            var previous: Long? = null
            cellularManager.cellularNetwork.collect { network ->
                val handle = network?.networkHandle ?: 0L
                if (wireGuardManager.isRunning && previous != null && handle != previous) {
                    if (network == null) {
                        logEvent("FailoverController", "Cellular network lost: waiting for Android to bring it back...", true)
                    } else {
                        logEvent("FailoverController", "Relay switched to cellular network $network")
                    }
                }
                wireGuardManager.setCellularNetwork(handle)
                previous = handle
            }
        }
    }

    private var isServiceRunning = false
    private var trustObservationJob: Job? = null
    private var standbyJob: Job? = null
    private val httpServerLock = Any()
    private var lastHttpBindError: String? = null

    fun startServiceComponents() {
        if (isServiceRunning) return
        isServiceRunning = true

        logEvent("FailoverController", "Starting service and network trust monitoring...")
        wifiTrustMonitor.start()
        powerMonitor.start()
        startStandbyPolicy()

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
        logEvent("FailoverController", "Connected to a paired network ($ssid). Starting gateway services...")

        // Bound to the current Wi-Fi IP only; the monitoring loop keeps it in sync
        ensureHttpServer()

        try {
            broadcaster.start()
        } catch (e: Exception) {
            logEvent("FailoverController", "Failed to start UDP broadcaster: ${e.message}", true)
        }

        startMonitoringLoop()
    }

    /**
     * Keeps the HTTP server listening on the current Wi-Fi IP while the service runs on a
     * trusted network: starts it, rebinds it after an IP change, and retries failed starts.
     */
    private fun ensureHttpServer() {
        synchronized(httpServerLock) {
            val ip = _wifiIp.value
            val shouldRun = isServiceRunning && wifiTrustMonitor.isNetworkTrusted.value && ip != null
            if (!shouldRun) {
                if (httpServer.isRunning) httpServer.stop()
                return
            }
            if (httpServer.isRunning && httpServer.bindAddress == ip) return

            if (httpServer.isRunning) httpServer.stop()
            try {
                httpServer.start(ip!!)
                lastHttpBindError = null
            } catch (e: Exception) {
                val error = "$ip: ${e.message}"
                if (error != lastHttpBindError) {
                    logEvent("FailoverController", "Failed to start HTTP server on $error (retrying)", true)
                    lastHttpBindError = error
                }
            }
        }
    }

    private fun pauseGatewayServices() {
        logEvent("FailoverController", "No paired gateway on this network (or mobile data alone). Halting gateway services (Paused)...")
        monitorJob?.cancel()
        monitorJob = null

        broadcaster.stop()
        synchronized(httpServerLock) {
            httpServer.stop()
        }

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
        standbyJob?.cancel()
        standbyJob = null
        powerMonitor.stop()
        pauseGatewayServices()
    }

    fun setStandbyMode(mode: StandbyMode) {
        if (_standbyMode.value == mode) return
        standbyPrefs.edit().putString(KEY_STANDBY_MODE, mode.storageValue).apply()
        _standbyMode.value = mode
        logEvent("Standby", "Standby mode: ${mode.storageValue}")
    }

    /** Works out hot/cold standby from the mode and the power state, and announces changes. */
    private fun startStandbyPolicy() {
        standbyJob?.cancel()
        standbyJob = scope.launch {
            var wasPlugged: Boolean? = null
            // Unplugged when the service starts: no grace, the phone is simply on battery
            var unpluggedAt = Long.MIN_VALUE / 2
            combine(_standbyMode, powerMonitor.pluggedIn) { mode, plugged -> mode to plugged }
                .collectLatest { (mode, plugged) ->
                    if (wasPlugged == true && !plugged) unpluggedAt = SystemClock.elapsedRealtime()
                    wasPlugged = plugged
                    while (true) {
                        val unpluggedFor = if (plugged) null else SystemClock.elapsedRealtime() - unpluggedAt
                        updateStandby(StandbyPolicy.isHot(mode, unpluggedFor))
                        val wait = StandbyPolicy.millisUntilChange(mode, unpluggedFor) ?: break
                        delay(wait + 100)
                    }
                }
        }
    }

    private fun updateStandby(hot: Boolean) {
        if (_standbyHot.value == hot) return
        _standbyHot.value = hot
        logEvent(
            "Standby",
            if (hot) "Hot standby: offering the gateway an always-on tunnel"
            else "Cold standby: cellular only during an outage"
        )
        // The gateway learns about it with the next broadcast: send it now
        broadcaster.broadcastNow()
    }

    sealed interface PairingStartResult {
        data object Started : PairingStartResult
        data object NoWifi : PairingStartResult
        /** Android hides the Wi-Fi name (location permission or location services off) */
        data object WifiNameUnknown : PairingStartResult
    }

    /**
     * Starts pairing with the gateway of [invite] on the current Wi-Fi network. Nothing is saved
     * until that gateway reaches the phone over this network (see GatewayAuthenticator); the
     * pairing is dropped after [PAIRING_TIMEOUT_MS] or if the phone leaves the network first.
     */
    fun beginPairing(invite: PairingInvite): PairingStartResult {
        refreshWifiNetworkInfo()
        val wifi = wifiTrustMonitor.currentWifiInfo.value ?: return PairingStartResult.NoWifi
        val ssid = wifi.ssid.takeIf { it != UNKNOWN_SSID } ?: return PairingStartResult.WifiNameUnknown

        pairingJob?.cancel()
        authenticator.beginPairing(invite, ssid)
        // Re-read the Wi-Fi now that it counts as trusted: this connection is then remembered as
        // verified, so it stays recognized if Android hides the SSID once the app is in the background
        wifiTrustMonitor.refresh()
        logEvent("Pairing", "Waiting for gateway '${invite.gatewayName}' to answer on Wi-Fi '$ssid'...")

        pairingJob = scope.launch {
            val outcome = withTimeoutOrNull(PAIRING_TIMEOUT_MS) {
                combine(authenticator.pairingState, wifiTrustMonitor.currentWifiInfo) { state, current -> state to current?.ssid }
                    .first { (state, currentSsid) ->
                        state !is PairingState.Waiting || state.phoneId != invite.phoneId || currentSsid != ssid
                    }
            }
            val reason = when {
                outcome == null ->
                    "Gateway '${invite.gatewayName}' didn't answer on Wi-Fi '$ssid'. Is it on this network?"
                (outcome.first as? PairingState.Waiting)?.phoneId == invite.phoneId ->
                    "The phone left Wi-Fi '$ssid' before gateway '${invite.gatewayName}' answered."
                else -> null // paired (or replaced by another pairing)
            }
            if (reason != null) {
                authenticator.cancelPairing(reason)
                logEvent("Pairing", reason, true)
            }
        }
        broadcaster.broadcastNow()
        return PairingStartResult.Started
    }

    fun cancelPairing() {
        pairingJob?.cancel()
        authenticator.cancelPairing("Pairing cancelled.")
    }

    fun acknowledgePairingResult() {
        authenticator.acknowledgePairingResult()
    }

    fun removeGateway(gatewayId: String) {
        val removed = authenticator.removePairing(gatewayId) ?: return
        logEvent("Pairing", "Removed gateway '${removed.gatewayName}' (Wi-Fi '${removed.ssid}')")
        if (activeGatewayId == gatewayId) {
            scope.launch { stopFailover() }
        }
    }

    fun refreshWifiNetworkInfo() {
        wifiTrustMonitor.refresh()
        refreshWifiIp()
    }

    override suspend fun startFailover(gatewayPublicKey: Key, gatewayId: String): FailoverStartResult {
        mutex.withLock {
            if (!wifiTrustMonitor.isNetworkTrusted.value) {
                logEvent("FailoverController", "Cannot start failover: untrusted network or mobile data alone", true)
                return FailoverStartResult.Failed("Phone is not on a paired Wi-Fi network")
            }

            val owner = activeGatewayId
            if (_failoverState.value == FailoverState.ACTIVE && owner != null && owner != gatewayId) {
                logEvent("FailoverController", "Refused failover start: relay in use by another gateway", true)
                return FailoverStartResult.Busy
            }

            // The relay is bound to the Wi-Fi network so replies reach the gateway even when
            // Wi-Fi has lost internet access and Android has made cellular the default network
            val wifiNetwork = NetworkUtils.getWifiNetwork(appContext)
            val bindAddress = wifiNetwork?.let { NetworkUtils.getIpv4Address(appContext, it) }
            if (wifiNetwork == null || bindAddress == null) {
                logEvent("FailoverController", "Cannot start failover: no Wi-Fi network to bind the relay to", true)
                return FailoverStartResult.Failed("No Wi-Fi network to bind the relay to")
            }

            if (_failoverState.value == FailoverState.ACTIVE) {
                // The gateway generates a new key on every activation (e.g. after a restart),
                // so restart the relay with the new peer key; cellular stays up.
                logEvent("FailoverController", "Relay already active: restarting it with the new gateway key")
                wireGuardManager.stop()
            }

            _failoverState.value = FailoverState.CONNECTING
            logEvent("FailoverController", "Cellular failover activation requested...")

            return try {
                val network = cellularManager.requestCellular(timeoutMs = 15000)
                logEvent("FailoverController", "Cellular connected ($network). Starting WireGuard tunnel on :51820...")

                try {
                    wireGuardManager.start(gatewayPublicKey, bindAddress, wifiNetwork.networkHandle, network.networkHandle)
                    logEvent("FailoverController", "WireGuard tunnel active on :51820 (userspace)")
                } catch (e: Exception) {
                    logEvent("FailoverController", "WireGuard startup error: ${e.message}", true)
                    throw e
                }

                activeGatewayId = gatewayId
                _failoverState.value = FailoverState.ACTIVE
                logEvent("FailoverController", "WireGuard relay active and operational")
                FailoverStartResult.Started
            } catch (e: Exception) {
                _failoverState.value = FailoverState.ERROR
                logEvent("FailoverController", "Cellular activation failed: ${e.message}", true)
                wireGuardManager.stop()
                cellularManager.releaseCellular()
                activeGatewayId = null
                _failoverState.value = FailoverState.IDLE
                FailoverStartResult.Failed("Cellular connection failed: ${e.message}")
            }
        }
    }

    override suspend fun stopFailover(gatewayId: String): Boolean {
        mutex.withLock {
            val owner = activeGatewayId
            if (_failoverState.value != FailoverState.IDLE && owner != null && owner != gatewayId) {
                logEvent("FailoverController", "Refused failover stop: relay in use by another gateway", true)
                return false
            }
            stopRelayLocked()
            return true
        }
    }

    /** Stops the relay whichever gateway started it (user action, network change...). */
    suspend fun stopFailover() {
        mutex.withLock { stopRelayLocked() }
    }

    private fun stopRelayLocked() {
        logEvent("FailoverController", "Deactivating failover relay...")
        wireGuardManager.stop()
        cellularManager.releaseCellular()
        activeGatewayId = null
        _failoverState.value = FailoverState.IDLE
        logEvent("FailoverController", "WireGuard relay stopped, mobile cellular radio released")
    }

    private fun activeGatewayName(): String {
        val id = activeGatewayId ?: return "?"
        return pairings.value.firstOrNull { it.gatewayId == id }?.let { "'${it.gatewayName}'" } ?: id
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
                ensureHttpServer()
                wireGuardManager.updateStatistics()
                _bytesTransmitted.value = wireGuardManager.bytesTransmitted.get()
                if (_failoverState.value == FailoverState.ACTIVE && wireGuardManager.isOrphaned()) {
                    logEvent(
                        "FailoverController",
                        "Gateway ${activeGatewayName()} has not renewed the tunnel for " +
                            "${WireGuardManager.ORPHAN_TIMEOUT_SECONDS / 60} min: releasing the relay",
                        true
                    )
                    stopFailover()
                }
                delay(2000L)
            }
        }
    }
}
