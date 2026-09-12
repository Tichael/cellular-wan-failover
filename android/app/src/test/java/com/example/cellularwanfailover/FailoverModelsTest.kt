package com.example.cellularwanfailover

import com.example.cellularwanfailover.model.DiscoveryMessage
import com.example.cellularwanfailover.model.FailoverActionResponse
import com.example.cellularwanfailover.model.StatusResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FailoverModelsTest {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun testStatusResponseSerialization() {
        val status = StatusResponse(
            status = "idle",
            mode = "wireguard",
            wifi_ip = "10.20.0.42",
            cellular_ready = true,
            bytes_transmitted = 10485760L,
            wireguard = com.example.cellularwanfailover.model.WireGuardInfo(
                port = 51820,
                public_key = "abc123publicKeyBase64==",
                tunnel_ip = "10.100.0.1",
                peer_ip = "10.100.0.2"
            )
        )

        val encoded = json.encodeToString(status)
        assertTrue(encoded.contains("\"status\": \"idle\""))
        assertTrue(encoded.contains("\"mode\": \"wireguard\""))
        assertTrue(encoded.contains("\"wifi_ip\": \"10.20.0.42\""))
        assertTrue(encoded.contains("\"cellular_ready\": true"))
        assertTrue(encoded.contains("\"bytes_transmitted\": 10485760"))
        assertTrue(encoded.contains("\"public_key\": \"abc123publicKeyBase64==\""))

        val decoded = json.decodeFromString<StatusResponse>(encoded)
        assertEquals(status, decoded)
    }

    @Test
    fun testFailoverActionResponseSerialization() {
        val actionActive = FailoverActionResponse(result = "active")
        val encodedActive = json.encodeToString(actionActive)
        assertTrue(encodedActive.contains("\"result\": \"active\""))

        val decodedActive = json.decodeFromString<FailoverActionResponse>(encodedActive)
        assertEquals(actionActive, decodedActive)

        val actionIdle = FailoverActionResponse(result = "idle")
        val decodedIdle = json.decodeFromString<FailoverActionResponse>(json.encodeToString(actionIdle))
        assertEquals("idle", decodedIdle.result)
    }

    @Test
    fun testDiscoveryMessageSerialization() {
        val msg = DiscoveryMessage(
            device = "Pixel 8",
            service = "wan-failover",
            port = 8989
        )
        val encoded = json.encodeToString(msg)
        val decoded = json.decodeFromString<DiscoveryMessage>(encoded)
        assertEquals("Pixel 8", decoded.device)
        assertEquals("wan-failover", decoded.service)
        assertEquals(8989, decoded.port)
    }
}
