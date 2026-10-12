package com.example.cellularwanfailover.auth

import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A confirmed pairing with one gateway, valid only on the Wi-Fi network it was made on. */
@Serializable
data class GatewayPairing(
    val gatewayId: String,
    val gatewayName: String,
    /** The ID this gateway gave the phone; also names the secret in the key store */
    val phoneId: String,
    val ssid: String,
    val pairedAt: Long
) {
    val keyAlias: String get() = "gateway-pairing-$phoneId"
}

/**
 * The phone's gateway pairings (at most one per gateway). Metadata lives in its own
 * SharedPreferences file, excluded from backups; secrets live in the [PairingKeyStore].
 * Loaded synchronously, so it is complete before anything (e.g. a pairing link) uses it.
 */
class PairingRepository(
    private val prefs: SharedPreferences,
    private val keyStore: PairingKeyStore
) {
    companion object {
        private const val TAG = "PairingRepository"
        const val PREFS_NAME = "gateway_pairings"
        private const val KEY_PAIRINGS = "pairings_json"
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _pairings = MutableStateFlow(load())
    val pairings: StateFlow<List<GatewayPairing>> = _pairings.asStateFlow()

    private fun load(): List<GatewayPairing> {
        val stored = prefs.getString(KEY_PAIRINGS, null) ?: return emptyList()
        val all = try {
            json.decodeFromString<List<GatewayPairing>>(stored)
        } catch (e: Exception) {
            Log.e(TAG, "Unreadable pairings, ignoring them", e)
            return emptyList()
        }
        // A pairing without its secret (e.g. data restored onto another phone) is useless
        val usable = all.filter { keyStore.signer(it.keyAlias) != null }
        if (usable.size != all.size) persist(usable)
        return usable
    }

    private fun persist(list: List<GatewayPairing>) {
        prefs.edit().putString(KEY_PAIRINGS, json.encodeToString(list)).apply()
    }

    fun find(gatewayId: String, phoneId: String): GatewayPairing? =
        _pairings.value.firstOrNull { it.gatewayId == gatewayId && it.phoneId == phoneId }

    fun forGateway(gatewayId: String): GatewayPairing? =
        _pairings.value.firstOrNull { it.gatewayId == gatewayId }

    fun signer(pairing: GatewayPairing): MessageSigner? = keyStore.signer(pairing.keyAlias)

    fun isPairedSsid(ssid: String?): Boolean = ssid != null && _pairings.value.any { it.ssid == ssid }

    /** Saves [pairing] with its secret, replacing any earlier pairing with the same gateway. */
    @Synchronized
    fun save(pairing: GatewayPairing, key: ByteArray) {
        keyStore.save(pairing.keyAlias, key)
        val previous = forGateway(pairing.gatewayId)
        val updated = _pairings.value.filterNot { it.gatewayId == pairing.gatewayId } + pairing
        persist(updated)
        _pairings.value = updated
        if (previous != null && previous.keyAlias != pairing.keyAlias) keyStore.delete(previous.keyAlias)
    }

    @Synchronized
    fun remove(gatewayId: String): GatewayPairing? {
        val removed = forGateway(gatewayId) ?: return null
        val updated = _pairings.value.filterNot { it.gatewayId == gatewayId }
        persist(updated)
        _pairings.value = updated
        keyStore.delete(removed.keyAlias)
        return removed
    }
}
