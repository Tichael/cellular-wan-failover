package com.example.cellularwanfailover

import android.content.SharedPreferences
import com.example.cellularwanfailover.auth.AuthProtocol
import com.example.cellularwanfailover.auth.GatewayAuthenticator
import com.example.cellularwanfailover.auth.MessageSigner
import com.example.cellularwanfailover.auth.PairingInvite
import com.example.cellularwanfailover.auth.PairingKeyStore
import com.example.cellularwanfailover.auth.PairingRepository
import com.example.cellularwanfailover.auth.SignedRequest
import com.example.cellularwanfailover.auth.memorySigner

/** Shared with gateway/test_pairing.py: both implementations must agree on these. */
object Vectors {
    val KEY = ByteArray(32) { it.toByte() }
    const val GATEWAY = "Z2F0ZXdheS1pZC0wMDAwMQ"
    const val PHONE = "cGhvbmUtaWQtMDAwMDAwMQ"
    const val CHALLENGE = "Y2hhbGxlbmdlLTAwMDAwMQ"
    const val NONCE = "bm9uY2UtMDAwMDAwMDAwMQ"
    const val BODY = """{"gateway_public_key":"YNqHbfBQKaGvzefSSMvi/A7nLC4sQ+iAo56KNNQAo2E="}"""
    const val LINK = "wanfailover://pair?v=1&gw=Z2F0ZXdheS1pZC0wMDAwMQ&gn=Pi%20Home%20%26%20Co" +
        "&pid=cGhvbmUtaWQtMDAwMDAwMQ&k=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
}

/** Key store stand-in: the JVM test runtime has no Android Keystore. */
class InMemoryKeyStore : PairingKeyStore {
    val keys = mutableMapOf<String, ByteArray>()
    override fun save(alias: String, key: ByteArray) { keys[alias] = key.copyOf() }
    override fun signer(alias: String): MessageSigner? = keys[alias]?.let { memorySigner(it) }
    override fun delete(alias: String) { keys.remove(alias) }
}

/** A gateway as seen by the phone: pairing invite plus request signing. */
class TestGateway(
    val gatewayId: String = AuthProtocol.newId(),
    val name: String = "Pi Home",
    val phoneId: String = AuthProtocol.newId(),
    val key: ByteArray = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
) {
    val signer = memorySigner(key)
    val invite get() = PairingInvite(gatewayId, name, phoneId, key)

    fun request(
        challenge: String,
        method: String = "GET",
        path: String = "/v1/status",
        body: ByteArray = ByteArray(0),
        nonce: String = AuthProtocol.newId(),
        signWith: MessageSigner = signer
    ) = SignedRequest(
        gatewayId = gatewayId,
        phoneId = phoneId,
        challenge = challenge,
        nonce = nonce,
        signature = signWith.sign(AuthProtocol.requestFields(gatewayId, phoneId, method, path, challenge, nonce, body)),
        method = method,
        path = path,
        body = body
    )
}

fun newAuthenticator(
    prefs: FakeSharedPreferences = FakeSharedPreferences(),
    keyStore: InMemoryKeyStore = InMemoryKeyStore(),
    clock: () -> Long = { 0L }
) = GatewayAuthenticator(PairingRepository(prefs, keyStore), clock)

/** Pairs [gateway] on [ssid] the way the real flow does: pending, then a signed completion. */
fun GatewayAuthenticator.pair(gateway: TestGateway, ssid: String, phoneName: String = "Pixel") =
    beginPairing(gateway.invite, ssid).let {
        val request = gateway.request(issueChallenge(), "POST", "/v1/pair/complete", "{}".toByteArray())
        val accepted = authenticate(request, ssid, allowPending = true) as com.example.cellularwanfailover.auth.AuthResult.Accepted
        completePairing(accepted.context, phoneName)
    }

class FakeSharedPreferences : SharedPreferences {
    private val data = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = HashMap(data)
    override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = data[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = FakeEditor(data)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    class FakeEditor(private val data: MutableMap<String, Any?>) : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var clear = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            key?.let { pending[it] = value }
            return this
        }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            key?.let { pending[it] = values }
            return this
        }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            key?.let { pending[it] = value }
            return this
        }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            key?.let { pending[it] = value }
            return this
        }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            key?.let { pending[it] = value }
            return this
        }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            key?.let { pending[it] = value }
            return this
        }
        override fun remove(key: String?): SharedPreferences.Editor {
            key?.let { pending[it] = null }
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            clear = true
            return this
        }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            if (clear) data.clear()
            for ((k, v) in pending) {
                if (v == null) data.remove(k) else data[k] = v
            }
        }
    }
}
