package com.example.cellularwanfailover.wireguard

import android.content.Context
import android.util.Log
import com.wireguard.crypto.KeyPair
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class WireGuardManager(private val context: Context) {

    companion object {
        private const val TAG = "WireGuardManager"
        private const val PREFS_NAME = "wireguard_settings"
        private const val KEY_PHONE_PRIVATE = "phone_private_key"
        private const val KEY_PI_PRIVATE = "pi_private_key"

        const val LISTEN_PORT = 51820
        const val TUNNEL_PHONE_IP = "10.100.0.1"
        const val TUNNEL_PI_IP = "10.100.0.2"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val phoneKeyPair: KeyPair = loadOrCreateKeyPair(KEY_PHONE_PRIVATE)
    val piKeyPair: KeyPair = loadOrCreateKeyPair(KEY_PI_PRIVATE)

    val phonePublicKey: String get() = phoneKeyPair.publicKey.toBase64()
    val piPublicKey: String get() = piKeyPair.publicKey.toBase64()
    val piPrivateKey: String get() = piKeyPair.privateKey.toBase64()

    private val _isRunning = AtomicBoolean(false)
    val isRunning: Boolean get() = _isRunning.get()

    val bytesTransmitted = AtomicLong(0L)

    var onLog: ((message: String, isError: Boolean) -> Unit)? = null

    private fun loadOrCreateKeyPair(keyPref: String): KeyPair {
        val savedPrivate = prefs.getString(keyPref, null)
        return if (savedPrivate != null) {
            try {
                val key = com.wireguard.crypto.Key.fromBase64(savedPrivate)
                KeyPair(key)
            } catch (e: Exception) {
                val fresh = KeyPair()
                prefs.edit().putString(keyPref, fresh.privateKey.toBase64()).apply()
                fresh
            }
        } else {
            val fresh = KeyPair()
            prefs.edit().putString(keyPref, fresh.privateKey.toBase64()).apply()
            fresh
        }
    }

    fun generatePiWgQuickConfig(phoneWifiIp: String): String {
        return """
            [Interface]
            Address = $TUNNEL_PI_IP/24
            PrivateKey = $piPrivateKey
            DNS = 1.1.1.1

            [Peer]
            PublicKey = $phonePublicKey
            Endpoint = $phoneWifiIp:$LISTEN_PORT
            AllowedIPs = 0.0.0.0/0
            PersistentKeepalive = 25
        """.trimIndent()
    }

    @Synchronized
    fun start(netHandle: Long = 0L) {
        if (_isRunning.get()) {
            log("WireGuard relay already active on port $LISTEN_PORT")
            return
        }

        try {
            log("Starting userspace WireGuard relay (Port $LISTEN_PORT, netId: $netHandle)...")
            val rc = WgRelay.startRelay(
                port = LISTEN_PORT,
                privKeyBase64 = phoneKeyPair.privateKey.toBase64(),
                peerPubKeyBase64 = piKeyPair.publicKey.toBase64(),
                netHandle = netHandle
            )
            if (rc != 0) {
                throw IllegalStateException("Failed to initialize WgRelay (exit code $rc)")
            }
            _isRunning.set(true)
            log("Userspace WireGuard relay started successfully (Port $LISTEN_PORT, Public Key: $phonePublicKey)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WireGuard", e)
            log("Failed to start WireGuard: ${e.javaClass.simpleName} - ${e.message}", true)
            _isRunning.set(false)
            throw e
        }
    }

    @Synchronized
    fun stop() {
        if (!_isRunning.getAndSet(false)) return

        log("Stopping userspace WireGuard relay...")
        try {
            WgRelay.stopRelay()
            log("WireGuard relay stopped")
        } catch (e: Exception) {
            log("Error while stopping WireGuard relay: ${e.message}", true)
        }
    }

    fun updateStatistics() {
        if (!_isRunning.get()) return
        try {
            val total = WgRelay.getBytesTransmitted()
            bytesTransmitted.set(total)
        } catch (e: Exception) {
            Log.d(TAG, "Failed to read WireGuard stats: ${e.message}")
        }
    }

    private fun log(msg: String, isError: Boolean = false) {
        if (isError) Log.e(TAG, msg) else Log.i(TAG, msg)
        onLog?.invoke(msg, isError)
    }
}
