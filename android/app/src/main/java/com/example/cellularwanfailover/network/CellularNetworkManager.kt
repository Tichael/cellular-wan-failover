package com.example.cellularwanfailover.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.example.cellularwanfailover.model.CellularState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket

class CellularNetworkManager(private val context: Context) {

    companion object {
        private const val TAG = "CellularNetworkManager"
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _cellularState = MutableStateFlow(CellularState.DISCONNECTED)
    val cellularState: StateFlow<CellularState> = _cellularState.asStateFlow()

    private val _cellularNetwork = MutableStateFlow<Network?>(null)
    val cellularNetwork: StateFlow<Network?> = _cellularNetwork.asStateFlow()

    private val mutex = Mutex()
    private var activeCallback: ConnectivityManager.NetworkCallback? = null
    private var pendingDeferred: CompletableDeferred<Network>? = null

    suspend fun requestCellular(timeoutMs: Long = 15000): Network {
        mutex.withLock {
            val currentNetwork = _cellularNetwork.value
            if (currentNetwork != null) {
                Log.d(TAG, "Cellular network already active: $currentNetwork")
                return currentNetwork
            }

            Log.i(TAG, "Requesting cellular network activation (timeout ${timeoutMs}ms)...")
            _cellularState.value = CellularState.CONNECTING

            // Clean up any stale callback
            cleanupCallbackLocked()

            val deferred = CompletableDeferred<Network>()
            pendingDeferred = deferred

            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(TAG, "Cellular network available. Network: $network")
                    _cellularNetwork.value = network
                    _cellularState.value = CellularState.CONNECTED
                    deferred.complete(network)
                }

                override fun onLost(network: Network) {
                    Log.w(TAG, "Cellular network lost: $network")
                    if (_cellularNetwork.value == network) {
                        _cellularNetwork.value = null
                        _cellularState.value = CellularState.DISCONNECTED
                    }
                }

                override fun onUnavailable() {
                    Log.e(TAG, "Cellular network request unavailable")
                    deferred.completeExceptionally(IOException("Cellular network unavailable"))
                }
            }

            activeCallback = callback

            try {
                connectivityManager.requestNetwork(request, callback)
            } catch (e: Exception) {
                _cellularState.value = CellularState.DISCONNECTED
                cleanupCallbackLocked()
                throw IOException("Failed to request cellular network: ${e.message}", e)
            }

            return try {
                withTimeout(timeoutMs) {
                    deferred.await()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Timeout or error waiting for cellular network: ${e.message}")
                _cellularState.value = CellularState.DISCONNECTED
                cleanupCallbackLocked()
                throw IOException("Timed out or failed waiting for cellular connection: ${e.message}", e)
            }
        }
    }

    fun releaseCellular() {
        Log.i(TAG, "Releasing cellular network...")
        cleanupCallback()
        _cellularNetwork.value = null
        _cellularState.value = CellularState.DISCONNECTED
    }

    private fun cleanupCallbackLocked() {
        val cb = activeCallback
        if (cb != null) {
            try {
                connectivityManager.unregisterNetworkCallback(cb)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering network callback: ${e.message}")
            }
            activeCallback = null
        }
        pendingDeferred = null
    }

    private fun cleanupCallback() {
        try {
            val cb = activeCallback
            if (cb != null) {
                connectivityManager.unregisterNetworkCallback(cb)
                activeCallback = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering network callback: ${e.message}")
        }
        pendingDeferred = null
    }

    fun bindSocket(socket: Socket): Boolean {
        val network = _cellularNetwork.value
        return if (network != null) {
            try {
                network.bindSocket(socket)
                Log.d(TAG, "Successfully bound TCP socket to cellular network ($network)")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind TCP socket to cellular network: ${e.message}", e)
                false
            }
        } else {
            Log.w(TAG, "Cellular network not active, socket left unbound")
            false
        }
    }

    fun bindSocket(socket: DatagramSocket): Boolean {
        val network = _cellularNetwork.value
        return if (network != null) {
            try {
                network.bindSocket(socket)
                Log.d(TAG, "Successfully bound UDP socket to cellular network ($network)")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind UDP socket to cellular network: ${e.message}", e)
                false
            }
        } else {
            Log.w(TAG, "Cellular network not active, UDP socket left unbound")
            false
        }
    }

    fun resolveAddress(host: String): InetAddress {
        val network = _cellularNetwork.value
        return if (network != null) {
            try {
                network.getByName(host)
            } catch (e: Exception) {
                Log.w(TAG, "Cellular DNS failed for $host, fallback to default: ${e.message}")
                InetAddress.getByName(host)
            }
        } else {
            InetAddress.getByName(host)
        }
    }
}
