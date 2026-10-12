package com.example.cellularwanfailover.auth

import java.net.URI
import java.net.URLDecoder

/**
 * The content of a gateway's pairing QR code / link:
 * wanfailover://pair?v=1&gw=<gateway id>&gn=<gateway name>&pid=<phone id>&k=<secret>
 */
class PairingInvite(
    val gatewayId: String,
    val gatewayName: String,
    val phoneId: String,
    val key: ByteArray
) {
    /** Must match the code `wanctl pair` shows next to the QR code */
    val code: String by lazy { AuthProtocol.pairingCode(memorySigner(key), gatewayId, phoneId) }

    companion object {
        private const val MAX_NAME_LENGTH = 64

        /** Parses a pairing link (scanned, pasted or opened); null if it isn't a valid one. */
        fun parse(text: String?): PairingInvite? {
            val uri = try {
                URI(text?.trim() ?: return null)
            } catch (e: Exception) {
                return null
            }
            if (!uri.scheme.equals(AuthProtocol.LINK_SCHEME, ignoreCase = true)) return null
            if (!uri.host.equals(AuthProtocol.LINK_HOST, ignoreCase = true)) return null
            val params = parseQuery(uri.rawQuery ?: return null) ?: return null

            if (params["v"] != AuthProtocol.VERSION.toString()) return null
            val gatewayId = params["gw"]?.takeIf { AuthProtocol.isValidId(it) } ?: return null
            val phoneId = params["pid"]?.takeIf { AuthProtocol.isValidId(it) } ?: return null
            val key = AuthProtocol.b64urlDecode(params["k"])?.takeIf { it.size == AuthProtocol.KEY_BYTES } ?: return null
            val name = sanitizeLabel(params["gn"]).ifEmpty { "Gateway" }
            return PairingInvite(gatewayId, name, phoneId, key)
        }

        private fun parseQuery(rawQuery: String): Map<String, String>? {
            val params = mutableMapOf<String, String>()
            for (part in rawQuery.split('&')) {
                if (part.isEmpty()) continue
                val eq = part.indexOf('=')
                if (eq <= 0) return null
                val name = part.substring(0, eq)
                val value = try {
                    URLDecoder.decode(part.substring(eq + 1), "UTF-8")
                } catch (e: Exception) {
                    return null
                }
                // A repeated parameter is ambiguous: reject the whole link
                if (params.put(name, value) != null) return null
            }
            return params
        }

        /** Display text from outside the app: no control characters, bounded length. */
        fun sanitizeLabel(value: String?, maxLength: Int = MAX_NAME_LENGTH): String =
            value.orEmpty().filterNot { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() }
                .trim().take(maxLength)
    }
}
