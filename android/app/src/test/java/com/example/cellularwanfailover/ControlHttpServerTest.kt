package com.example.cellularwanfailover

import com.example.cellularwanfailover.auth.AuthProtocol
import com.example.cellularwanfailover.auth.GatewayAuthenticator
import com.example.cellularwanfailover.auth.MessageSigner
import com.example.cellularwanfailover.auth.PairCompleteResponse
import com.example.cellularwanfailover.http.ControlHttpServer
import com.example.cellularwanfailover.http.FailoverControlDelegate
import com.example.cellularwanfailover.http.FailoverStartResult
import com.example.cellularwanfailover.model.ChallengeResponse
import com.example.cellularwanfailover.model.FailoverActionResponse
import com.example.cellularwanfailover.model.StatusResponse
import com.example.cellularwanfailover.power.BatterySettings
import com.wireguard.crypto.Key
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class ControlHttpServerTest {

    private val testPort = 18989
    private var server: ControlHttpServer? = null
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var auth: GatewayAuthenticator
    private val gateway = TestGateway()

    private var mockSsid: String? = "Home"
    private var mockStatus = "idle"
    private var startResult: FailoverStartResult = FailoverStartResult.Started
    private var stopAllowed = true
    private var startedBy: String? = null
    private var receivedGatewayKey: Key? = null
    private var stoppedBy: String? = null
    private val validGatewayKey = "YNqHbfBQKaGvzefSSMvi/A7nLC4sQ+iAo56KNNQAo2E="

    private val mockDelegate = object : FailoverControlDelegate {
        override val failoverStatusString: String get() = mockStatus
        override val currentWifiIp: String? get() = "10.20.0.50"
        override val currentSsid: String? get() = mockSsid
        override val deviceName: String get() = "Mom's Pixel"
        override val isCellularReady: Boolean get() = false
        override val currentBytesTransmitted: Long get() = 12345L
        override val wireguardPort: Int get() = 51820
        override val wireguardPublicKey: String get() = "mockWireguardPublicKey=="
        override val wireguardTunnelIp: String get() = "10.100.0.1"
        override val wireguardPeerIp: String get() = "10.100.0.2"
        override val standby: String get() = "hot"
        override val batteryLevel: Int get() = 64
        override val isPluggedIn: Boolean get() = true
        override val batterySettings: BatterySettings get() = BatterySettings(minBattery = 30, lastResort = false)
        override suspend fun startFailover(gatewayPublicKey: Key, gatewayId: String): FailoverStartResult {
            startedBy = gatewayId
            receivedGatewayKey = gatewayPublicKey
            return startResult
        }

        override suspend fun stopFailover(gatewayId: String): Boolean {
            stoppedBy = gatewayId
            return stopAllowed
        }

        override fun logEvent(tag: String, message: String, isError: Boolean) {
            println("[$tag] $message")
        }
    }

    @Before
    fun setUp() {
        auth = newAuthenticator()
        auth.pair(gateway, "Home")
        server = ControlHttpServer(port = testPort, delegate = mockDelegate, authenticator = auth)
        server?.start("127.0.0.1")
        Thread.sleep(500) // allow server to bind
    }

    @After
    fun tearDown() {
        server?.stop()
        Thread.sleep(300)
    }

    private class Answer(val code: Int, val body: String, val signature: String?, val nonce: String)

    private fun open(path: String, method: String): HttpURLConnection =
        (URL("http://127.0.0.1:$testPort$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 3000
            readTimeout = 3000
        }

    private fun HttpURLConnection.readAnswer(nonce: String): Answer {
        val code = responseCode
        val stream = if (code in 200..299) inputStream else errorStream
        val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        return Answer(code, body, getHeaderField(AuthProtocol.HEADER_SIGNATURE), nonce).also { disconnect() }
    }

    private fun challenge(): String {
        val conn = open("/v1/challenge", "GET")
        return json.decodeFromString<ChallengeResponse>(conn.readAnswer("").body).challenge
    }

    /** What the gateway does: fetch a challenge, then send the signed request. */
    private fun signedCall(
        method: String,
        path: String,
        body: String = "",
        from: TestGateway = gateway,
        challenge: String = challenge(),
        signWith: MessageSigner = from.signer,
        chunked: Boolean = false
    ): Answer {
        val bytes = body.toByteArray()
        val request = from.request(challenge, method, path, bytes, signWith = signWith)
        val conn = open(path, method)
        conn.setRequestProperty(AuthProtocol.HEADER_GATEWAY, request.gatewayId)
        conn.setRequestProperty(AuthProtocol.HEADER_PHONE, request.phoneId)
        conn.setRequestProperty(AuthProtocol.HEADER_CHALLENGE, request.challenge)
        conn.setRequestProperty(AuthProtocol.HEADER_NONCE, request.nonce)
        conn.setRequestProperty(AuthProtocol.HEADER_SIGNATURE, request.signature)
        if (method == "POST") {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (chunked) conn.setChunkedStreamingMode(1024)
            conn.outputStream.use { it.write(bytes) }
        }
        return conn.readAnswer(request.nonce!!)
    }

    /** The gateway only trusts answers signed by the phone for its own nonce. */
    private fun assertSignedByPhone(answer: Answer, by: TestGateway = gateway) {
        val fields = AuthProtocol.responseFields(by.gatewayId, by.phoneId, answer.nonce, answer.code, answer.body.toByteArray())
        assertTrue("answer must be signed by the phone", by.signer.verify(answer.signature, fields))
    }

    @Test
    fun testServerBindsOnlyToGivenAddress() {
        assertEquals("127.0.0.1", server?.bindAddress)
    }

    @Test
    fun testChallengesAreFreshEachTime() {
        assertFalse(challenge() == challenge())
    }

    @Test
    fun testSignedStatus() {
        val answer = signedCall("GET", "/v1/status")
        assertEquals(200, answer.code)
        assertSignedByPhone(answer)

        val status = json.decodeFromString<StatusResponse>(answer.body)
        assertEquals("idle", status.status)
        assertEquals("10.20.0.50", status.wifi_ip)
        assertEquals(12345L, status.bytes_transmitted)
        assertEquals("mockWireguardPublicKey==", status.wireguard?.public_key)
        assertEquals("hot", status.standby)
        assertEquals(64, status.battery)
        assertTrue(status.plugged)
        assertEquals(30, status.min_battery)
        assertFalse(status.last_resort)
    }

    @Test
    fun testUnsignedRequestsAreRejected() {
        for ((method, path) in listOf("GET" to "/v1/status", "POST" to "/v1/failover/start", "POST" to "/v1/failover/stop", "POST" to "/v1/pair/complete")) {
            val conn = open(path, method)
            if (method == "POST") {
                conn.doOutput = true
                conn.outputStream.use { it.write("{}".toByteArray()) }
            }
            val answer = conn.readAnswer("")
            assertEquals("$method $path", 401, answer.code)
            assertNull(answer.signature)
        }
        assertNull(startedBy)
        assertNull(stoppedBy)
    }

    @Test
    fun testWrongKeyIsRejected() {
        val answer = signedCall("POST", "/v1/failover/start", """{"gateway_public_key": "$validGatewayKey"}""", signWith = TestGateway().signer)
        assertEquals(401, answer.code)
        assertNull(startedBy)
    }

    @Test
    fun testReplayedChallengeIsRejected() {
        val challenge = challenge()
        assertEquals(200, signedCall("GET", "/v1/status", challenge = challenge).code)
        assertEquals(401, signedCall("GET", "/v1/status", challenge = challenge).code)
    }

    @Test
    fun testRejectedOnAnotherWifi() {
        mockSsid = "Coffee-Shop"
        assertEquals(401, signedCall("GET", "/v1/status").code)
    }

    @Test
    fun testUnknownGatewayIsRejected() {
        assertEquals(401, signedCall("GET", "/v1/status", from = TestGateway()).code)
    }

    @Test
    fun testWireguardConfigEndpointRemoved() {
        assertEquals(404, open("/v1/wireguard/config", "GET").readAnswer("").code)
    }

    @Test
    fun testStartFailover() {
        val answer = signedCall("POST", "/v1/failover/start", """{"gateway_public_key": "$validGatewayKey"}""")
        assertEquals(200, answer.code)
        assertSignedByPhone(answer)
        assertEquals("active", json.decodeFromString<FailoverActionResponse>(answer.body).result)
        assertEquals(gateway.gatewayId, startedBy)
        assertEquals(validGatewayKey, receivedGatewayKey?.toBase64())
    }

    @Test
    fun testStartFailoverBusyIsSignedConflict() {
        startResult = FailoverStartResult.Busy
        val answer = signedCall("POST", "/v1/failover/start", """{"gateway_public_key": "$validGatewayKey"}""")
        assertEquals(409, answer.code)
        assertSignedByPhone(answer)
        assertEquals("busy", json.decodeFromString<FailoverActionResponse>(answer.body).result)
    }

    @Test
    fun testStartFailoverDeclinedOnLowBatteryIsSignedConflict() {
        startResult = FailoverStartResult.Declined("Battery below the phone's minimum")
        val answer = signedCall("POST", "/v1/failover/start", """{"gateway_public_key": "$validGatewayKey"}""")
        assertEquals(409, answer.code)
        assertSignedByPhone(answer)
        assertEquals("declined", json.decodeFromString<FailoverActionResponse>(answer.body).result)
    }

    @Test
    fun testStartFailoverFailure() {
        startResult = FailoverStartResult.Failed("Cellular connection failed")
        val answer = signedCall("POST", "/v1/failover/start", """{"gateway_public_key": "$validGatewayKey"}""")
        assertEquals(500, answer.code)
        assertSignedByPhone(answer)
    }

    @Test
    fun testPostFailoverStartWithoutBodyIsRejected() {
        val answer = signedCall("POST", "/v1/failover/start")
        assertEquals(400, answer.code)
        assertSignedByPhone(answer)
        assertTrue(answer.body.contains("gateway_public_key"))
        assertNull(startedBy)
    }

    @Test
    fun testPostFailoverStartWithInvalidKeyIsRejected() {
        for (badKey in listOf("junk", "AAAA", "$validGatewayKey\\nPostUp = id")) {
            val answer = signedCall("POST", "/v1/failover/start", """{"gateway_public_key": "$badKey"}""")
            assertEquals("key '$badKey' should be rejected", 400, answer.code)
        }
        assertNull(startedBy)
        assertNull(receivedGatewayKey)
    }

    @Test
    fun testStopFailover() {
        val answer = signedCall("POST", "/v1/failover/stop", "{}")
        assertEquals(200, answer.code)
        assertSignedByPhone(answer)
        assertEquals("idle", json.decodeFromString<FailoverActionResponse>(answer.body).result)
        assertEquals(gateway.gatewayId, stoppedBy)
    }

    @Test
    fun testStopByAnotherGatewayIsConflict() {
        stopAllowed = false
        val answer = signedCall("POST", "/v1/failover/stop", "{}")
        assertEquals(409, answer.code)
        assertSignedByPhone(answer)
    }

    @Test
    fun testPairingCompletion() {
        val newcomer = TestGateway(name = "Cottage")
        auth.beginPairing(newcomer.invite, "Home")

        // Not usable before the pairing completes
        assertEquals(401, signedCall("GET", "/v1/status", from = newcomer).code)

        val answer = signedCall("POST", "/v1/pair/complete", "{}", from = newcomer)
        assertEquals(200, answer.code)
        assertSignedByPhone(answer, by = newcomer)
        val response = json.decodeFromString<PairCompleteResponse>(answer.body)
        assertEquals("Mom's Pixel", response.name)
        assertEquals("Home", response.ssid)

        // Now it is a regular paired gateway
        assertEquals(200, signedCall("GET", "/v1/status", from = newcomer).code)
    }

    @Test
    fun testPairingCompletionFromAnotherWifiIsRejected() {
        val newcomer = TestGateway()
        auth.beginPairing(newcomer.invite, "Home")
        mockSsid = "Elsewhere"
        assertEquals(401, signedCall("POST", "/v1/pair/complete", "{}", from = newcomer).code)
        assertEquals(1, auth.pairings.value.size)
    }

    @Test
    fun testOversizedBodyIsRejected() {
        val answer = signedCall("POST", "/v1/failover/start", "x".repeat(20_000))
        assertEquals(413, answer.code)
        assertNull(startedBy)
    }

    @Test
    fun testOversizedChunkedBodyIsRejected() {
        // No Content-Length to check up front: the body must still be read with a bound
        val answer = signedCall("POST", "/v1/failover/start", "x".repeat(20_000), chunked = true)
        assertEquals(413, answer.code)
        assertNull(startedBy)
    }

    @Test
    fun testChunkedBodyWithinLimitIsAccepted() {
        val answer = signedCall("POST", "/v1/failover/start", "{\"gateway_public_key\":\"$validGatewayKey\"}", chunked = true)
        assertEquals(200, answer.code)
        assertSignedByPhone(answer)
    }

    @Test
    fun testCompletingADroppedPairingIsRefused() {
        val newcomer = TestGateway(name = "Cottage")
        auth.beginPairing(newcomer.invite, "Home")
        val context = (auth.authenticate(
            newcomer.request(auth.issueChallenge(), "POST", "/v1/pair/complete", "{}".toByteArray()), "Home", allowPending = true
        ) as com.example.cellularwanfailover.auth.AuthResult.Accepted).context
        // Timed out (or left the Wi-Fi) between the signature check and the completion
        auth.cancelPairing("Timed out.")
        assertNull(auth.completePairing(context, "Mom's Pixel"))
        assertEquals(1, auth.pairings.value.size)
    }
}
