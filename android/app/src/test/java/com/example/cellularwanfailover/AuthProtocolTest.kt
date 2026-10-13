package com.example.cellularwanfailover

import com.example.cellularwanfailover.auth.AuthProtocol
import com.example.cellularwanfailover.auth.PairingInvite
import com.example.cellularwanfailover.auth.memorySigner
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The expected values come from gateway/pairing.py (see gateway/test_pairing.py). */
class AuthProtocolTest {

    private val signer = memorySigner(Vectors.KEY)

    @Test
    fun keyEncoding() {
        assertEquals("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", AuthProtocol.b64urlEncode(Vectors.KEY))
    }

    @Test
    fun requestSignature() {
        val fields = AuthProtocol.requestFields(
            Vectors.GATEWAY, Vectors.PHONE, "POST", "/v1/failover/start",
            Vectors.CHALLENGE, Vectors.NONCE, Vectors.BODY.toByteArray()
        )
        assertEquals("vCQOwRK_Zpwbi3KbuZh57IVEsh4O1iC1McF5n34KcPM", signer.sign(fields))
    }

    @Test
    fun responseSignature() {
        val fields = AuthProtocol.responseFields(Vectors.GATEWAY, Vectors.PHONE, Vectors.NONCE, 200, """{"result":"active"}""".toByteArray())
        assertEquals("Nhk2S2diibbLHvgOw8ZO5f05LA3RO9iKbalJsf2pxwk", signer.sign(fields))
    }

    @Test
    fun discoverySignature() {
        val cold = AuthProtocol.discoveryFields(Vectors.GATEWAY, Vectors.PHONE, "192.168.1.23", "cold")
        assertEquals("TMjo4b_lKYB5BlcarcSHHhf8C7aSvSUhYqEjFcd1cGs", signer.sign(cold))
        val hot = AuthProtocol.discoveryFields(Vectors.GATEWAY, Vectors.PHONE, "192.168.1.23", "hot")
        assertEquals("nHAaCXKjpBSE27oH7pAXu6-cZu6zq0rgfHcQUg7P_xc", signer.sign(hot))
    }

    @Test
    fun replaceSignature() {
        val fields = AuthProtocol.replaceFields(Vectors.GATEWAY, "b2xkLXBob25lLWlkLTAwMQ", Vectors.PHONE)
        assertEquals("6EiGtiiw2MujSygFh6qP7hbD9_lR1eCe2KntmXjJWtk", signer.sign(fields))
    }

    @Test
    fun pairingCode() {
        assertEquals("77FB-236A", AuthProtocol.pairingCode(signer, Vectors.GATEWAY, Vectors.PHONE))
    }

    @Test
    fun emptyBodyHash() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", AuthProtocol.sha256Hex(ByteArray(0)))
    }

    @Test
    fun verifyRejectsWrongOrMissingSignatures() {
        val fields = AuthProtocol.discoveryFields(Vectors.GATEWAY, Vectors.PHONE, "192.168.1.23", "cold")
        assertTrue(signer.verify("TMjo4b_lKYB5BlcarcSHHhf8C7aSvSUhYqEjFcd1cGs", fields))
        assertFalse(signer.verify("TMjo4b_lKYB5BlcarcSHHhf8C7aSvSUhYqEjFcd1cGt", fields))
        assertFalse(signer.verify(null, fields))
        assertFalse(signer.verify("", fields))
        assertFalse(memorySigner(ByteArray(32)).verify("TMjo4b_lKYB5BlcarcSHHhf8C7aSvSUhYqEjFcd1cGs", fields))
    }

    @Test
    fun fieldsWithNewlinesAreNeverSigned() {
        assertNull(AuthProtocol.message(listOf("wf1-disc", "a\nb")))
        assertFalse(signer.verify("x", listOf("wf1-disc", "a\nb")))
    }

    @Test
    fun base64UrlDecodingIsStrict() {
        for (bad in listOf(null, "", "a+b", "a/b", "abc=", "AAECAw==", "A")) {
            assertNull("'$bad' should be rejected", AuthProtocol.b64urlDecode(bad))
        }
        assertArrayEquals(Vectors.KEY, AuthProtocol.b64urlDecode("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"))
    }

    @Test
    fun newIdsAreRandom16Bytes() {
        val id = AuthProtocol.newId()
        assertTrue(AuthProtocol.isValidId(id))
        assertFalse(id == AuthProtocol.newId())
    }
}

class PairingInviteTest {

    @Test
    fun parsesGatewayLink() {
        val invite = PairingInvite.parse(Vectors.LINK)
        assertNotNull(invite)
        invite!!
        assertEquals(Vectors.GATEWAY, invite.gatewayId)
        assertEquals("Pi Home & Co", invite.gatewayName)
        assertEquals(Vectors.PHONE, invite.phoneId)
        assertArrayEquals(Vectors.KEY, invite.key)
    }

    @Test
    fun acceptsSurroundingWhitespaceFromPaste() {
        assertNotNull(PairingInvite.parse("  ${Vectors.LINK}\n"))
    }

    @Test
    fun rejectsInvalidLinks() {
        val key = "k=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        val bad = listOf(
            null,
            "",
            "not a link",
            "https://pair?v=1&gw=${Vectors.GATEWAY}&pid=${Vectors.PHONE}&$key",
            "wanfailover://other?v=1&gw=${Vectors.GATEWAY}&pid=${Vectors.PHONE}&$key",
            "wanfailover://pair?v=2&gw=${Vectors.GATEWAY}&pid=${Vectors.PHONE}&$key",
            "wanfailover://pair?gw=${Vectors.GATEWAY}&pid=${Vectors.PHONE}&$key",
            "wanfailover://pair?v=1&gw=short&pid=${Vectors.PHONE}&$key",
            "wanfailover://pair?v=1&gw=${Vectors.GATEWAY}&$key",
            "wanfailover://pair?v=1&gw=${Vectors.GATEWAY}&pid=${Vectors.PHONE}&k=AAECAw",
            "wanfailover://pair?v=1&gw=${Vectors.GATEWAY}&pid=${Vectors.PHONE}",
            // A repeated parameter is ambiguous
            "wanfailover://pair?v=1&gw=${Vectors.GATEWAY}&gw=${Vectors.PHONE}&pid=${Vectors.PHONE}&$key"
        )
        for (link in bad) {
            assertNull("'$link' should be rejected", PairingInvite.parse(link))
        }
    }

    @Test
    fun gatewayNameIsSanitized() {
        val link = Vectors.LINK.replace("Pi%20Home%20%26%20Co", "%1B%5B2JEvil%0AName%E2%80%AE")
        assertEquals("[2JEvilName", PairingInvite.parse(link)!!.gatewayName)
        val unnamed = Vectors.LINK.replace("gn=Pi%20Home%20%26%20Co&", "")
        assertEquals("Gateway", PairingInvite.parse(unnamed)!!.gatewayName)
    }
}
