package com.example.cellularwanfailover

import com.example.cellularwanfailover.http.ControlHttpServer
import com.example.cellularwanfailover.http.FailoverControlDelegate
import com.example.cellularwanfailover.model.FailoverActionResponse
import com.example.cellularwanfailover.model.StatusResponse
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
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
        override fun getWireguardConfigString(): String = "[Interface]\nAddress = 10.100.0.2/24\n"

        override suspend fun startFailover(): Boolean {
            startFailoverCalled = true
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
        server?.start()
        Thread.sleep(500) // allow server to bind
    }

    @After
    fun tearDown() {
        server?.stop()
        Thread.sleep(300)
    }

    private fun httpGet(path: String): Pair<Int, String> {
        val url = URL("http://127.0.0.1:$testPort$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        val code = conn.responseCode
        val text = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        conn.disconnect()
        return Pair(code, text)
    }

    private fun httpPost(path: String): Pair<Int, String> {
        val url = URL("http://127.0.0.1:$testPort$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        conn.doOutput = true
        conn.outputStream.use { it.write(ByteArray(0)) }
        val code = conn.responseCode
        val text = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
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
    fun testGetWireguardConfigEndpoint() {
        val (code, body) = httpGet("/v1/wireguard/config")
        assertEquals(200, code)
        assertTrue(body.contains("[Interface]"))
        assertTrue(body.contains("Address = 10.100.0.2/24"))
    }

    @Test
    fun testPostFailoverStartEndpoint() {
        val (code, body) = httpPost("/v1/failover/start")
        assertEquals(200, code)

        val actionRes = json.decodeFromString<FailoverActionResponse>(body)
        assertEquals("active", actionRes.result)
        assertTrue(startFailoverCalled)
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
