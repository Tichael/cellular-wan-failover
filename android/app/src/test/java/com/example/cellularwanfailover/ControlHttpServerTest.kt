package com.example.cellularwanfailover

import com.example.cellularwanfailover.http.ControlHttpServer
import com.example.cellularwanfailover.http.FailoverControlDelegate
import com.example.cellularwanfailover.model.FailoverActionResponse
import com.example.cellularwanfailover.model.StatusResponse
import com.wireguard.crypto.Key
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class ControlHttpServerTest {

    private val testPort = 18989
    private var server: ControlHttpServer? = null
    private val json = Json { ignoreUnknownKeys = true }

    private var mockStatus = "idle"
    private var mockWifiIp: String? = "10.20.0.50"
    private var mockCellularReady = false
    private var mockBytesTransmitted = 12345L
    private var startFailoverCalled = false
    private var receivedGatewayKey: Key? = null
    private val validGatewayKey = "YNqHbfBQKaGvzefSSMvi/A7nLC4sQ+iAo56KNNQAo2E="
    private var stopFailoverCalled = false

    private val mockDelegate = object : FailoverControlDelegate {
        override val failoverStatusString: String get() = mockStatus
        override val currentWifiIp: String? get() = mockWifiIp
        override val isCellularReady: Boolean get() = mockCellularReady
        override val currentBytesTransmitted: Long get() = mockBytesTransmitted
        override val wireguardPort: Int get() = 51820
        override val wireguardPublicKey: String get() = "mockWireguardPublicKey=="
        override val wireguardTunnelIp: String get() = "10.100.0.1"
        override val wireguardPeerIp: String get() = "10.100.0.2"
        override suspend fun startFailover(gatewayPublicKey: Key): Boolean {
            startFailoverCalled = true
            receivedGatewayKey = gatewayPublicKey
            mockStatus = "active"
            mockCellularReady = true
            return true
        }

        override suspend fun stopFailover() {
            stopFailoverCalled = true
            mockStatus = "idle"
            mockCellularReady = false
        }

        override fun logEvent(tag: String, message: String, isError: Boolean) {
            println("[$tag] $message")
        }
    }

    @Before
    fun setUp() {
        server = ControlHttpServer(port = testPort, delegate = mockDelegate)
        server?.start("127.0.0.1")
        Thread.sleep(500) // allow server to bind
    }

    @After
    fun tearDown() {
        server?.stop()
        Thread.sleep(300)
    }

    private fun readBody(conn: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return stream?.let { BufferedReader(InputStreamReader(it)).use { r -> r.readText() } } ?: ""
    }

    private fun httpGet(path: String): Pair<Int, String> {
        val url = URL("http://127.0.0.1:$testPort$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        val code = conn.responseCode
        val text = readBody(conn, code)
        conn.disconnect()
        return Pair(code, text)
    }

    private fun httpPost(path: String, body: String? = null): Pair<Int, String> {
        val url = URL("http://127.0.0.1:$testPort$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        conn.doOutput = true
        if (body != null) {
            conn.setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)) }
        val code = conn.responseCode
        val text = readBody(conn, code)
        conn.disconnect()
        return Pair(code, text)
    }

    @Test
    fun testGetStatusEndpoint() {
        val (code, body) = httpGet("/v1/status")
        assertEquals(200, code)

        val status = json.decodeFromString<StatusResponse>(body)
        assertEquals("idle", status.status)
        assertEquals("wireguard", status.mode)
        assertEquals("10.20.0.50", status.wifi_ip)
        assertEquals(false, status.cellular_ready)
        assertEquals(12345L, status.bytes_transmitted)
        assertEquals(51820, status.wireguard?.port)
        assertEquals("mockWireguardPublicKey==", status.wireguard?.public_key)
    }

    @Test
    fun testServerBindsOnlyToGivenAddress() {
        assertEquals("127.0.0.1", server?.bindAddress)
    }

    @Test
    fun testWireguardConfigEndpointRemoved() {
        val (code, _) = httpGet("/v1/wireguard/config")
        assertEquals(404, code)
    }

    @Test
    fun testPostFailoverStartEndpoint() {
        val (code, body) = httpPost("/v1/failover/start", """{"gateway_public_key": "$validGatewayKey"}""")
        assertEquals(200, code)

        val actionRes = json.decodeFromString<FailoverActionResponse>(body)
        assertEquals("active", actionRes.result)
        assertTrue(startFailoverCalled)
        assertEquals(validGatewayKey, receivedGatewayKey?.toBase64())
    }

    @Test
    fun testPostFailoverStartWithoutBodyIsRejected() {
        val (code, body) = httpPost("/v1/failover/start")
        assertEquals(400, code)
        assertTrue(body.contains("gateway_public_key"))
        assertFalse(startFailoverCalled)
    }

    @Test
    fun testPostFailoverStartWithInvalidKeyIsRejected() {
        for (badKey in listOf("junk", "AAAA", "$validGatewayKey\\nPostUp = id")) {
            val (code, _) = httpPost("/v1/failover/start", """{"gateway_public_key": "$badKey"}""")
            assertEquals("key '$badKey' should be rejected", 400, code)
        }
        assertFalse(startFailoverCalled)
        assertNull(receivedGatewayKey)
    }

    @Test
    fun testPostFailoverStopEndpoint() {
        val (code, body) = httpPost("/v1/failover/stop")
        assertEquals(200, code)

        val actionRes = json.decodeFromString<FailoverActionResponse>(body)
        assertEquals("idle", actionRes.result)
        assertTrue(stopFailoverCalled)
    }
}
