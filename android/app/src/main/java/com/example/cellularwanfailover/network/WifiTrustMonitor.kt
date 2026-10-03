package com.example.cellularwanfailover.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Build
import android.util.Log
import com.example.cellularwanfailover.model.WifiNetworkInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class WifiTrustMonitor(
    private val context: Context,
    private val trustedNetworkManager: TrustedNetworkManager
) {
    companion object {
        private const val TAG = "WifiTrustMonitor"
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _currentWifiInfo = MutableStateFlow<WifiNetworkInfo?>(null)
    val currentWifiInfo: StateFlow<WifiNetworkInfo?> = _currentWifiInfo.asStateFlow()

    private val _isNetworkTrusted = MutableStateFlow(false)
    val isNetworkTrusted: StateFlow<Boolean> = _isNetworkTrusted.asStateFlow()

    // Keeps a verified connection trusted while Android hides the SSID (app in background)
    private val stickyTrust = StickyWifiTrust { ssid -> trustedNetworkManager.isTrusted(ssid) }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val isMonitoring = AtomicBoolean(false)
    private var scope: CoroutineScope? = null
    private var observeJob: Job? = null

    @Synchronized
    fun start() {
        if (isMonitoring.getAndSet(true)) return

        val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope = coroutineScope

        // Initial check
        refresh()

        // Observe changes to the trusted network list
        observeJob = coroutineScope.launch {
            trustedNetworkManager.trustedNetworks.collect {
                evaluateTrust()
            }
        }

        // Register NetworkCallback for Wi-Fi changes
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Wi-Fi network available: $network")
                val caps = connectivityManager.getNetworkCapabilities(network)
                updateFromCapabilities(network, caps)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                updateFromCapabilities(network, networkCapabilities)
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Wi-Fi network lost: $network")
                stickyTrust.onNetworkLost(network.networkHandle)
                // Check if any other Wi-Fi network is still connected
                refresh()
            }
        }

        networkCallback = callback
        try {
            connectivityManager.registerNetworkCallback(request, callback)
            Log.i(TAG, "Registered Wi-Fi network callback")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    @Synchronized
    fun stop() {
        if (!isMonitoring.getAndSet(false)) return

        observeJob?.cancel()
        observeJob = null
        scope?.cancel()
        scope = null

        val cb = networkCallback
        if (cb != null) {
            try {
                connectivityManager.unregisterNetworkCallback(cb)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering network callback: ${e.message}")
            }
            networkCallback = null
        }
        Log.i(TAG, "Stopped Wi-Fi trust monitoring")
    }

    fun refresh() {
        val network = NetworkUtils.getWifiNetwork(context)
        if (network == null) {
            _currentWifiInfo.value = null
            evaluateTrust()
            return
        }
        updateFromCapabilities(network, connectivityManager.getNetworkCapabilities(network))
    }

    private fun updateFromCapabilities(network: Network, caps: NetworkCapabilities?) {
        var ssid: String? = null
        var bssid: String? = null

        if (caps != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val wifiInfo = caps.transportInfo as? WifiInfo
            if (wifiInfo != null) {
                ssid = NetworkUtils.normalizeSsid(wifiInfo.ssid)
                bssid = wifiInfo.bssid
            }
        }

        if (ssid == null) {
            val fallback = NetworkUtils.getCurrentWifiInfo(context)
            if (fallback != null) {
                ssid = NetworkUtils.normalizeSsid(fallback.ssid)
                if (bssid == null) bssid = fallback.bssid
            }
        }

        // Unreadable SSID (app in background): reuse the SSID verified for this same connection
        ssid = stickyTrust.resolveSsid(network.networkHandle, ssid)

        val cleanBssid = if (bssid != null && bssid != "02:00:00:00:00:00") bssid else null
        val newInfo = if (ssid != null) {
            WifiNetworkInfo(ssid = ssid, bssid = cleanBssid, isConnected = true)
        } else {
            WifiNetworkInfo(ssid = "<unknown ssid>", bssid = cleanBssid, isConnected = true)
        }

        _currentWifiInfo.value = newInfo
        evaluateTrust()
    }

    private fun evaluateTrust() {
        val info = _currentWifiInfo.value
        val isTrusted = if (info != null && info.ssid != "<unknown ssid>") {
            trustedNetworkManager.isTrusted(info.ssid)
        } else {
            false
        }

        val previous = _isNetworkTrusted.value
        _isNetworkTrusted.value = isTrusted

        if (previous != isTrusted) {
            Log.i(TAG, "Network trust state changed: $previous -> $isTrusted (SSID: ${info?.ssid})")
        }
    }
}
