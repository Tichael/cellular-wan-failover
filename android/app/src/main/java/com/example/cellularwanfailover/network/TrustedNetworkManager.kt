package com.example.cellularwanfailover.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.cellularwanfailover.model.TrustedNetwork
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class TrustedNetworkManager(
    private val prefs: SharedPreferences,
    private val dispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO
) {
    companion object {
        private const val TAG = "TrustedNetworkManager"
        const val PREFS_NAME = "trusted_networks_prefs"
        private const val KEY_TRUSTED_NETWORKS = "trusted_networks_json"
    }

    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _trustedNetworks = MutableStateFlow<List<TrustedNetwork>>(emptyList())
    val trustedNetworks: StateFlow<List<TrustedNetwork>> = _trustedNetworks.asStateFlow()

    init {
        kotlinx.coroutines.CoroutineScope(dispatcher).launch {
            loadNetworks()
        }
    }

    @Synchronized
    private fun loadNetworks() {
        val storedJson = prefs.getString(KEY_TRUSTED_NETWORKS, null)
        if (!storedJson.isNullOrBlank()) {
            try {
                val list = json.decodeFromString<List<TrustedNetwork>>(storedJson)
                _trustedNetworks.value = list
            } catch (e: Exception) {
                Log.e(TAG, "Error deserializing trusted networks from prefs", e)
                _trustedNetworks.value = emptyList()
            }
        } else {
            _trustedNetworks.value = emptyList()
        }
    }

    @Synchronized
    private fun persistNetworks(list: List<TrustedNetwork>) {
        _trustedNetworks.value = list
        try {
            val encoded = json.encodeToString(list)
            prefs.edit().putString(KEY_TRUSTED_NETWORKS, encoded).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error persisting trusted networks to prefs", e)
        }
    }

    @Synchronized
    fun addNetwork(ssid: String, bssid: String? = null): Boolean {
        val normalized = NetworkUtils.normalizeSsid(ssid) ?: return false

        val currentList = _trustedNetworks.value
        // Avoid duplicate by matching SSID
        if (currentList.any { it.ssid.equals(normalized, ignoreCase = false) }) {
            Log.d(TAG, "Network with SSID '$normalized' is already trusted")
            return false
        }

        val newNetwork = TrustedNetwork(
            ssid = normalized,
            bssid = bssid
        )
        val updated = currentList + newNetwork
        persistNetworks(updated)
        Log.i(TAG, "Added trusted network: '$normalized' (bssid: $bssid)")
        return true
    }

    @Synchronized
    fun removeNetwork(id: String): Boolean {
        val currentList = _trustedNetworks.value
        val updated = currentList.filterNot { it.id == id }
        if (updated.size != currentList.size) {
            persistNetworks(updated)
            Log.i(TAG, "Removed trusted network with id: $id")
            return true
        }
        return false
    }

    @Synchronized
    fun clearAll() {
        persistNetworks(emptyList())
        Log.i(TAG, "Cleared all trusted networks")
    }

    fun isTrusted(ssid: String?): Boolean {
        if (ssid.isNullOrBlank()) return false
        val normalized = NetworkUtils.normalizeSsid(ssid) ?: return false
        return _trustedNetworks.value.any { it.ssid.equals(normalized, ignoreCase = false) }
    }
}
