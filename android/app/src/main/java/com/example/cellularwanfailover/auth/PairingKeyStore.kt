package com.example.cellularwanfailover.auth

import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import java.security.KeyStore
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Where pairing secrets live once a pairing is confirmed. */
interface PairingKeyStore {
    fun save(alias: String, key: ByteArray)
    fun signer(alias: String): MessageSigner?
    fun delete(alias: String)
}

/** A signer over a secret held in memory (pairing in progress, tests). */
fun memorySigner(key: ByteArray): MessageSigner {
    val spec = SecretKeySpec(key.copyOf(), "HmacSHA256")
    return MessageSigner { Mac.getInstance("HmacSHA256").apply { init(spec) } }
}

/**
 * Android Keystore: the secret is imported once and can then only be used to sign,
 * never read back by the app (or anything that compromises the app's storage).
 */
class AndroidPairingKeyStore : PairingKeyStore {

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(PROVIDER).apply { load(null) } }

    override fun save(alias: String, key: ByteArray) {
        keyStore.setEntry(
            alias,
            KeyStore.SecretKeyEntry(SecretKeySpec(key, KeyProperties.KEY_ALGORITHM_HMAC_SHA256)),
            KeyProtection.Builder(KeyProperties.PURPOSE_SIGN).build()
        )
    }

    override fun signer(alias: String): MessageSigner? {
        if (!keyStore.containsAlias(alias)) return null
        return MessageSigner {
            val key = keyStore.getKey(alias, null)
            Mac.getInstance("HmacSHA256").apply { init(key) }
        }
    }

    override fun delete(alias: String) {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
    }
}
