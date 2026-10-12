package com.example.cellularwanfailover.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac

/**
 * Gateway <-> phone message authentication (see PROTOCOL.md). Must stay byte-for-byte
 * compatible with gateway/pairing.py: both sides are tested against the same vectors.
 *
 * Every signed message is a list of fields joined by newlines, starting with its type,
 * signed with HMAC-SHA256 using the secret shared with one gateway at pairing time.
 */
object AuthProtocol {
    const val VERSION = 1
    const val SERVICE = "wan-failover"
    const val LINK_SCHEME = "wanfailover"
    const val LINK_HOST = "pair"

    const val HEADER_GATEWAY = "X-WF-Gateway"
    const val HEADER_PHONE = "X-WF-Phone"
    const val HEADER_CHALLENGE = "X-WF-Challenge"
    const val HEADER_NONCE = "X-WF-Nonce"
    const val HEADER_SIGNATURE = "X-WF-Signature"

    const val STANDBY_HOT = "hot"
    const val STANDBY_COLD = "cold"

    const val ID_BYTES = 16
    const val KEY_BYTES = 32

    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private val base64UrlChars = Regex("^[A-Za-z0-9_-]+$")

    fun b64urlEncode(raw: ByteArray): String = encoder.encodeToString(raw)

    /** Strict base64url (no padding): null for anything else, including non-canonical forms. */
    fun b64urlDecode(text: String?): ByteArray? {
        if (text.isNullOrEmpty() || !base64UrlChars.matches(text)) return null
        val raw = try {
            decoder.decode(text)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return if (b64urlEncode(raw) == text) raw else null
    }

    /** True when [text] is a base64url ID of the expected size. */
    fun isValidId(text: String?): Boolean = b64urlDecode(text)?.size == ID_BYTES

    fun newId(): String = b64urlEncode(ByteArray(ID_BYTES).also { random.nextBytes(it) })

    fun sha256Hex(body: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }

    fun requestFields(
        gatewayId: String, phoneId: String, method: String, path: String,
        challenge: String, nonce: String, body: ByteArray
    ) = listOf("wf1-req", gatewayId, phoneId, method, path, challenge, nonce, sha256Hex(body))

    fun responseFields(gatewayId: String, phoneId: String, nonce: String, status: Int, body: ByteArray) =
        listOf("wf1-resp", gatewayId, phoneId, nonce, status.toString(), sha256Hex(body))

    fun discoveryFields(gatewayId: String, phoneId: String, phoneIp: String) =
        listOf("wf1-disc", gatewayId, phoneId, phoneIp)

    /** Signed apart from the discovery entry, so older gateways and phones simply ignore it. */
    fun standbyFields(gatewayId: String, phoneId: String, phoneIp: String, standby: String) =
        listOf("wf1-standby", gatewayId, phoneId, phoneIp, standby)

    fun replaceFields(gatewayId: String, oldPhoneId: String, newPhoneId: String) =
        listOf("wf1-replace", gatewayId, oldPhoneId, newPhoneId)

    fun pairingCodeFields(gatewayId: String, phoneId: String) = listOf("wf1-code", gatewayId, phoneId)

    /**
     * Short code shown by `wanctl pair` and before the user confirms a pairing: matching codes
     * prove the link is the one that gateway displayed, not one from another source.
     */
    fun pairingCode(signer: MessageSigner, gatewayId: String, phoneId: String): String {
        val digest = requireNotNull(b64urlDecode(signer.sign(pairingCodeFields(gatewayId, phoneId))))
        val hex = digest.take(4).joinToString("") { "%02X".format(it) }
        return "${hex.take(4)}-${hex.drop(4)}"
    }

    /** The bytes that get signed; null if a field contains a newline (it could shift fields). */
    fun message(fields: List<String>): ByteArray? =
        if (fields.any { '\n' in it }) null else fields.joinToString("\n").toByteArray(Charsets.UTF_8)

    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}

/** Signs and verifies messages with one gateway's shared secret (HMAC-SHA256). */
class MessageSigner(private val newMac: () -> Mac) {

    fun sign(fields: List<String>): String {
        val message = requireNotNull(AuthProtocol.message(fields)) { "Signed fields must not contain newlines" }
        return AuthProtocol.b64urlEncode(newMac().doFinal(message))
    }

    fun verify(signature: String?, fields: List<String>): Boolean {
        if (signature.isNullOrEmpty() || AuthProtocol.message(fields) == null) return false
        return AuthProtocol.constantTimeEquals(sign(fields), signature)
    }
}
