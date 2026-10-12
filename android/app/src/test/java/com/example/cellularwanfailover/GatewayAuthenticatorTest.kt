package com.example.cellularwanfailover

import com.example.cellularwanfailover.auth.AuthProtocol
import com.example.cellularwanfailover.auth.AuthResult
import com.example.cellularwanfailover.auth.DiscoveryPayload
import com.example.cellularwanfailover.auth.GatewayAuthenticator
import com.example.cellularwanfailover.auth.PairingRepository
import com.example.cellularwanfailover.auth.PairingState
import com.example.cellularwanfailover.auth.memorySigner
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GatewayAuthenticatorTest {

    private var now = 1_000L
    private lateinit var prefs: FakeSharedPreferences
    private lateinit var keyStore: InMemoryKeyStore
    private lateinit var auth: GatewayAuthenticator
    private val gateway = TestGateway()

    @Before
    fun setUp() {
        prefs = FakeSharedPreferences()
        keyStore = InMemoryKeyStore()
        auth = newAuthenticator(prefs, keyStore) { now }
    }

    private fun rejectionOf(result: AuthResult) = (result as? AuthResult.Rejected)?.reason

    // --- Challenges ---

    @Test
    fun challengeIsSingleUse() {
        auth.pair(gateway, "Home")
        val challenge = auth.issueChallenge()
        assertTrue(auth.authenticate(gateway.request(challenge), "Home") is AuthResult.Accepted)
        assertEquals("unknown or expired challenge", rejectionOf(auth.authenticate(gateway.request(challenge), "Home")))
    }

    @Test
    fun challengeExpires() {
        auth.pair(gateway, "Home")
        val challenge = auth.issueChallenge()
        now += GatewayAuthenticator.CHALLENGE_TTL_MS + 1
        assertEquals("unknown or expired challenge", rejectionOf(auth.authenticate(gateway.request(challenge), "Home")))
    }

    @Test
    fun challengesAreBounded() {
        auth.pair(gateway, "Home")
        val first = auth.issueChallenge()
        repeat(GatewayAuthenticator.MAX_CHALLENGES) { auth.issueChallenge() }
        // Flooding the phone with challenge requests only evicts the oldest ones
        assertEquals("unknown or expired challenge", rejectionOf(auth.authenticate(gateway.request(first), "Home")))
        assertTrue(auth.authenticate(gateway.request(auth.issueChallenge()), "Home") is AuthResult.Accepted)
    }

    @Test
    fun invalidChallengeStillConsumesIt() {
        auth.pair(gateway, "Home")
        val challenge = auth.issueChallenge()
        val forged = gateway.request(challenge, signWith = memorySigner(ByteArray(32)))
        assertEquals("bad signature", rejectionOf(auth.authenticate(forged, "Home")))
        // No second try with the same challenge
        assertEquals("unknown or expired challenge", rejectionOf(auth.authenticate(gateway.request(challenge), "Home")))
    }

    // --- Requests ---

    @Test
    fun acceptsPairedGatewayOnItsWifi() {
        auth.pair(gateway, "Home")
        val result = auth.authenticate(gateway.request(auth.issueChallenge()), "Home")
        val context = (result as AuthResult.Accepted).context
        assertEquals(gateway.gatewayId, context.gatewayId)
        assertEquals("Pi Home", context.gatewayName)
    }

    @Test
    fun rejectsPairedGatewayOnAnotherWifi() {
        auth.pair(gateway, "Home")
        assertEquals("paired on another Wi-Fi network", rejectionOf(auth.authenticate(gateway.request(auth.issueChallenge()), "Home-Guest")))
        assertEquals("paired on another Wi-Fi network", rejectionOf(auth.authenticate(gateway.request(auth.issueChallenge()), null)))
    }

    @Test
    fun rejectsUnknownGateway() {
        auth.pair(gateway, "Home")
        val stranger = TestGateway()
        assertEquals("unknown gateway or pairing", rejectionOf(auth.authenticate(stranger.request(auth.issueChallenge()), "Home")))
    }

    @Test
    fun rejectsTamperedRequests() {
        auth.pair(gateway, "Home")
        val signed = gateway.request(auth.issueChallenge(), "POST", "/v1/failover/start", "{\"gateway_public_key\":\"a\"}".toByteArray())
        val tampered = listOf(
            signed.copy(body = "{\"gateway_public_key\":\"b\"}".toByteArray()),
            signed.copy(path = "/v1/failover/stop"),
            signed.copy(method = "GET")
        )
        for (request in tampered) {
            val fresh = request.copy(challenge = auth.issueChallenge())
            assertEquals("bad signature", rejectionOf(auth.authenticate(fresh, "Home")))
        }
    }

    @Test
    fun rejectsMissingHeaders() {
        auth.pair(gateway, "Home")
        val good = gateway.request(auth.issueChallenge())
        for (request in listOf(good.copy(signature = null), good.copy(nonce = null), good.copy(gatewayId = "x"), good.copy(challenge = null))) {
            assertEquals("missing or malformed authentication headers", rejectionOf(auth.authenticate(request, "Home")))
        }
    }

    @Test
    fun responseIsSignedForTheGatewayNonce() {
        auth.pair(gateway, "Home")
        val nonce = AuthProtocol.newId()
        val context = (auth.authenticate(gateway.request(auth.issueChallenge(), nonce = nonce), "Home") as AuthResult.Accepted).context
        val body = """{"status":"idle"}""".toByteArray()
        val signature = auth.signResponse(context, 200, body)
        assertTrue(gateway.signer.verify(signature, AuthProtocol.responseFields(gateway.gatewayId, gateway.phoneId, nonce, 200, body)))
        assertFalse(gateway.signer.verify(signature, AuthProtocol.responseFields(gateway.gatewayId, gateway.phoneId, nonce, 500, body)))
    }

    // --- Pairing ---

    @Test
    fun pendingPairingOnlyCompletesPairing() {
        auth.beginPairing(gateway.invite, "Home")
        assertEquals("unknown gateway or pairing", rejectionOf(auth.authenticate(gateway.request(auth.issueChallenge()), "Home")))
        val completion = gateway.request(auth.issueChallenge(), "POST", "/v1/pair/complete")
        assertTrue(auth.authenticate(completion, "Home", allowPending = true) is AuthResult.Accepted)
    }

    @Test
    fun pendingPairingRequiresItsWifi() {
        auth.beginPairing(gateway.invite, "Home")
        val completion = gateway.request(auth.issueChallenge(), "POST", "/v1/pair/complete")
        assertEquals("paired on another Wi-Fi network", rejectionOf(auth.authenticate(completion, "Elsewhere", allowPending = true)))
        assertTrue(auth.pairings.value.isEmpty())
    }

    @Test
    fun completingPairingSavesItAndTrustsTheWifi() {
        assertFalse(auth.isServiceSsid("Home"))
        auth.beginPairing(gateway.invite, "Home")
        // The services run on the pending network so the gateway can reach the phone
        assertTrue(auth.isServiceSsid("Home"))
        assertEquals(PairingState.Waiting("Pi Home", "Home", gateway.phoneId), auth.pairingState.value)

        val response = auth.pair(gateway, "Home", phoneName = "Mom's Pixel")

        assertEquals("Mom's Pixel", response.name)
        assertEquals("Home", response.ssid)
        assertNull(response.replaces)
        assertEquals(PairingState.Paired("Pi Home", "Home", gateway.phoneId), auth.pairingState.value)
        val saved = auth.pairings.value.single()
        assertEquals(gateway.gatewayId, saved.gatewayId)
        assertEquals("Home", saved.ssid)
        assertTrue(keyStore.keys.containsKey(saved.keyAlias))
        assertTrue(auth.isServiceSsid("Home"))
        assertFalse(auth.isServiceSsid("Elsewhere"))
    }

    @Test
    fun cancelledPairingSavesNothing() {
        auth.beginPairing(gateway.invite, "Home")
        auth.cancelPairing("Timed out.")
        assertEquals(PairingState.Failed("Pi Home", "Timed out."), auth.pairingState.value)
        assertFalse(auth.isServiceSsid("Home"))
        val completion = gateway.request(auth.issueChallenge(), "POST", "/v1/pair/complete")
        assertEquals("unknown gateway or pairing", rejectionOf(auth.authenticate(completion, "Home", allowPending = true)))
        assertTrue(auth.pairings.value.isEmpty())
        auth.acknowledgePairingResult()
        assertEquals(PairingState.Idle, auth.pairingState.value)
    }

    @Test
    fun retriedCompletionGetsTheSameAnswer() {
        val first = auth.pair(gateway, "Home")
        val retry = gateway.request(auth.issueChallenge(), "POST", "/v1/pair/complete", "{}".toByteArray())
        val context = (auth.authenticate(retry, "Home", allowPending = true) as AuthResult.Accepted).context
        assertEquals(first, auth.completePairing(context, "Pixel"))
        assertEquals(1, auth.pairings.value.size)
    }

    @Test
    fun repairingProvesOwnershipOfOldPairing() {
        auth.pair(gateway, "Old-SSID")
        val oldAlias = auth.pairings.value.single().keyAlias
        val renewed = TestGateway(gatewayId = gateway.gatewayId, name = "Pi Home")

        val response = auth.pair(renewed, "New-SSID")

        val proof = response.replaces!!
        assertEquals(gateway.phoneId, proof.phone_id)
        assertTrue(gateway.signer.verify(proof.sig, AuthProtocol.replaceFields(gateway.gatewayId, gateway.phoneId, renewed.phoneId)))
        val saved = auth.pairings.value.single()
        assertEquals(renewed.phoneId, saved.phoneId)
        assertEquals("New-SSID", saved.ssid)
        assertFalse(keyStore.keys.containsKey(oldAlias))
        assertFalse(auth.isServiceSsid("Old-SSID"))
    }

    @Test
    fun severalGatewaysOnSeveralNetworks() {
        val cottage = TestGateway(name = "Cottage")
        auth.pair(gateway, "Home")
        auth.pair(cottage, "Cottage-WiFi")
        assertEquals(2, auth.pairings.value.size)
        assertTrue(auth.authenticate(gateway.request(auth.issueChallenge()), "Home") is AuthResult.Accepted)
        assertTrue(auth.authenticate(cottage.request(auth.issueChallenge()), "Cottage-WiFi") is AuthResult.Accepted)
        assertFalse(auth.authenticate(cottage.request(auth.issueChallenge()), "Home") is AuthResult.Accepted)
    }

    @Test
    fun removingPairingRevokesIt() {
        auth.pair(gateway, "Home")
        val alias = auth.pairings.value.single().keyAlias
        assertNotNull(auth.removePairing(gateway.gatewayId))
        assertFalse(keyStore.keys.containsKey(alias))
        assertFalse(auth.isServiceSsid("Home"))
        assertEquals("unknown gateway or pairing", rejectionOf(auth.authenticate(gateway.request(auth.issueChallenge()), "Home")))
    }

    // --- Persistence ---

    @Test
    fun pairingsSurviveRestart() {
        auth.pair(gateway, "Home")
        val restarted = GatewayAuthenticator(PairingRepository(prefs, keyStore)) { now }
        assertTrue(restarted.authenticate(gateway.request(restarted.issueChallenge()), "Home") is AuthResult.Accepted)
    }

    @Test
    fun pairingWithoutItsSecretIsDropped() {
        // e.g. app data restored onto another phone: the Keystore secret did not come along
        auth.pair(gateway, "Home")
        keyStore.keys.clear()
        val restarted = GatewayAuthenticator(PairingRepository(prefs, keyStore)) { now }
        assertTrue(restarted.pairings.value.isEmpty())
        assertFalse(restarted.isServiceSsid("Home"))
    }

    // --- Discovery ---

    @Test
    fun discoveryCarriesOneSignaturePerGatewayOnThisWifi() {
        val other = TestGateway(name = "Neighbour-free")
        auth.pair(gateway, "Home")
        auth.pair(other, "Elsewhere")

        val payload = Json.decodeFromString<DiscoveryPayload>(auth.discoveryPayload("Home", "192.168.1.23")!!.toString(Charsets.UTF_8))

        assertEquals("wan-failover", payload.service)
        assertEquals(1, payload.v)
        val entry = payload.pairings.single()
        assertEquals(gateway.gatewayId, entry.gw)
        assertEquals(gateway.phoneId, entry.pid)
        assertTrue(gateway.signer.verify(entry.sig, AuthProtocol.discoveryFields(gateway.gatewayId, gateway.phoneId, "192.168.1.23")))
    }

    @Test
    fun discoveryIncludesPendingPairing() {
        auth.beginPairing(gateway.invite, "Home")
        val payload = Json.decodeFromString<DiscoveryPayload>(auth.discoveryPayload("Home", "10.0.0.5")!!.toString(Charsets.UTF_8))
        assertEquals(gateway.phoneId, payload.pairings.single().pid)
    }

    @Test
    fun noDiscoveryWithoutPairingHere() {
        assertNull(auth.discoveryPayload("Home", "10.0.0.5"))
        auth.pair(gateway, "Home")
        assertNull(auth.discoveryPayload("Elsewhere", "10.0.0.5"))
        assertNull(auth.discoveryPayload(null, "10.0.0.5"))
        assertNull(auth.discoveryPayload("Home", null))
    }

    @Test
    fun discoveryMatchesGatewayVector() {
        // Same vector as gateway/test_pairing.py: the gateway accepts exactly this signature
        val vectorGateway = TestGateway(gatewayId = Vectors.GATEWAY, phoneId = Vectors.PHONE, key = Vectors.KEY)
        auth.pair(vectorGateway, "Home")
        val payload = Json.decodeFromString<DiscoveryPayload>(auth.discoveryPayload("Home", "192.168.1.23")!!.toString(Charsets.UTF_8))
        assertEquals("_BT36DkjoiviLRKqYY75UWrC-xtB5Z4ITgCPfnrqLCA", payload.pairings.single().sig)
    }
}
