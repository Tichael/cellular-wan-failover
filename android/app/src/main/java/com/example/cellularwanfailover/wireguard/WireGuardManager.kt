package com.example.cellularwanfailover.wireguard

import android.content.Context
import android.util.Log
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class WireGuardManager(private val context: Context) {

    companion object {
        private const val TAG = "WireGuardManager"
        private const val PREFS_NAME = "wireguard_settings"
        private const val KEY_PHONE_PRIVATE = "phone_private_key"
        // Gateway key used to be generated and served by the phone; it is now generated on the gateway
        private const val LEGACY_KEY_PI_PRIVATE = "pi_private_key"

        const val LISTEN_PORT = 51820
        const val TUNNEL_PHONE_IP = "10.100.0.1"
        const val TUNNEL_PI_IP = "10.100.0.2"

        /**
         * A gateway keeps its tunnel alive (PersistentKeepalive 25 s), so WireGuard renews the
         * handshake every 2 minutes; past this delay without one, the gateway is gone.
         */
        const val ORPHAN_TIMEOUT_SECONDS = 5 * 60L

        /** True when the relay started at [startedAt] has had no handshake for [timeoutSeconds] (Unix seconds). */
        fun isOrphaned(now: Long, startedAt: Long, lastHandshake: Long, timeoutSeconds: Long = ORPHAN_TIMEOUT_SECONDS): Boolean =
            now - maxOf(startedAt, lastHandshake) > timeoutSeconds
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val phoneKeyPair: KeyPair = loadOrCreateKeyPair(KEY_PHONE_PRIVATE)

    val phonePublicKey: String get() = phoneKeyPair.publicKey.toBase64()

    init {
        if (prefs.contains(LEGACY_KEY_PI_PRIVATE)) {
            prefs.edit().remove(LEGACY_KEY_PI_PRIVATE).apply()
        }
    }

    private val _isRunning = AtomicBoolean(false)
    val isRunning: Boolean get() = _isRunning.get()

    val bytesTransmitted = AtomicLong(0L)

    // Unix seconds
    private val startedAt = AtomicLong(0L)

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

    @Synchronized
    fun start(peerPublicKey: Key, bindAddress: String, wifiNetHandle: Long, netHandle: Long = 0L) {
        if (_isRunning.get()) {
            log("WireGuard relay already active on port $LISTEN_PORT")
            return
        }

        try {
            log("Starting userspace WireGuard relay ($bindAddress:$LISTEN_PORT, netId: $netHandle)...")
            val rc = WgRelay.startRelay(
                port = LISTEN_PORT,
                privKeyBase64 = phoneKeyPair.privateKey.toBase64(),
                peerPubKeyBase64 = peerPublicKey.toBase64(),
                bindAddress = bindAddress,
                wifiNetHandle = wifiNetHandle,
                netHandle = netHandle
            )
            if (rc != 0) {
                throw IllegalStateException("Failed to initialize WgRelay (exit code $rc)")
            }
            startedAt.set(System.currentTimeMillis() / 1000)
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

    /** Moves the relay's outbound connections to [netHandle] (0: no cellular network for now). */
    fun setCellularNetwork(netHandle: Long) {
        if (!_isRunning.get()) return
        try {
            WgRelay.setCellularNetwork(netHandle)
        } catch (e: Exception) {
            log("Failed to switch the relay's cellular network: ${e.message}", true)
        }
    }

    /** True when the gateway hasn't renewed the tunnel for a while (crashed, unplugged...). */
    fun isOrphaned(): Boolean {
        if (!_isRunning.get()) return false
        val lastHandshake = try {
            WgRelay.getLastHandshakeEpochSeconds()
        } catch (e: Exception) {
            Log.d(TAG, "Failed to read the last handshake: ${e.message}")
            return false
        }
        return isOrphaned(System.currentTimeMillis() / 1000, startedAt.get(), lastHandshake)
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
