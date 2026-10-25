package com.example.cellularwanfailover.http

import android.util.Log
import com.example.cellularwanfailover.auth.AuthContext
import com.example.cellularwanfailover.auth.AuthProtocol
import com.example.cellularwanfailover.auth.AuthResult
import com.example.cellularwanfailover.auth.GatewayAuthenticator
import com.example.cellularwanfailover.auth.SignedRequest
import com.example.cellularwanfailover.model.ChallengeResponse
import com.example.cellularwanfailover.model.FailoverActionResponse
import com.example.cellularwanfailover.model.StartFailoverRequest
import com.example.cellularwanfailover.model.StatusResponse
import com.example.cellularwanfailover.model.WireGuardInfo
import com.example.cellularwanfailover.power.BatterySettings
import com.wireguard.crypto.Key
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import kotlinx.serialization.encodeToString
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean

sealed interface FailoverStartResult {
    data object Started : FailoverStartResult
    /** Another gateway is using the relay */
    data object Busy : FailoverStartResult
    /** The phone's settings don't allow it to carry an outage now (battery below its minimum) */
    data class Declined(val message: String) : FailoverStartResult
    data class Failed(val message: String) : FailoverStartResult
}

interface FailoverControlDelegate {
    val failoverStatusString: String
    val currentWifiIp: String?
    /** SSID of the Wi-Fi network the phone is on, or null if unknown */
    val currentSsid: String?
    /** Name sent to a gateway when pairing */
    val deviceName: String
    val isCellularReady: Boolean
    val currentBytesTransmitted: Long
    val wireguardPort: Int
    val wireguardPublicKey: String
    val wireguardTunnelIp: String
    val wireguardPeerIp: String
    /** "hot" or "cold" (AuthProtocol.STANDBY_*) */
    val standby: String
    /** Battery state of charge (%) */
    val batteryLevel: Int
    val isPluggedIn: Boolean
    val batterySettings: BatterySettings
    suspend fun startFailover(gatewayPublicKey: Key, gatewayId: String): FailoverStartResult
    /** Returns false (and keeps the relay) when another gateway owns the active failover. */
    suspend fun stopFailover(gatewayId: String): Boolean
    fun logEvent(tag: String, message: String, isError: Boolean = false)
}

/**
 * The phone's control API, used by paired gateways only. Apart from fetching a challenge,
 * every request must be signed by a paired gateway (on the Wi-Fi it was paired on), and
 * every answer to it is signed in return (see PROTOCOL.md).
 */
class ControlHttpServer(
    val port: Int = 8989,
    private val delegate: FailoverControlDelegate,
    private val authenticator: GatewayAuthenticator
) {
    companion object {
        private const val TAG = "ControlHttpServer"
        private const val MAX_BODY_BYTES = 16 * 1024
    }

    private class Reply(val status: HttpStatusCode, val body: String)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private inline fun <reified T> reply(status: HttpStatusCode, value: T) = Reply(status, json.encodeToString(value))

    private var server: EmbeddedServer<*, *>? = null
    private val _isRunning = AtomicBoolean(false)
    val isRunning: Boolean get() = _isRunning.get()

    @Volatile
    var bindAddress: String? = null
        private set

    /** Listens on [bindAddress] only (the phone's Wi-Fi IP), never on all interfaces. */
    @Synchronized
    fun start(bindAddress: String) {
        if (_isRunning.getAndSet(true)) {
            Log.d(TAG, "HTTP server already running on ${this.bindAddress}:$port")
            return
        }

        try {
            val serverEngine = embeddedServer(CIO, port = port, host = bindAddress) {
                routing {
                    get("/v1/challenge") {
                        val body = json.encodeToString(ChallengeResponse(authenticator.issueChallenge(call.request.local.remoteHost)))
                        call.respondBytes(body.toByteArray(Charsets.UTF_8), ContentType.Application.Json, HttpStatusCode.OK)
                    }

                    signed(HttpMethod.Get, "/v1/status") { _, _ ->
                        reply(
                            HttpStatusCode.OK,
                            StatusResponse(
                                status = delegate.failoverStatusString,
                                mode = "wireguard",
                                wifi_ip = delegate.currentWifiIp,
                                cellular_ready = delegate.isCellularReady,
                                bytes_transmitted = delegate.currentBytesTransmitted,
                                wireguard = WireGuardInfo(
                                    port = delegate.wireguardPort,
                                    public_key = delegate.wireguardPublicKey,
                                    tunnel_ip = delegate.wireguardTunnelIp,
                                    peer_ip = delegate.wireguardPeerIp
                                ),
                                standby = delegate.standby,
                                battery = delegate.batteryLevel,
                                plugged = delegate.isPluggedIn,
                                min_battery = delegate.batterySettings.minBattery,
                                last_resort = delegate.batterySettings.lastResort
                            )
                        )
                    }

                    signed(HttpMethod.Post, "/v1/failover/start") { auth, body ->
                        val gatewayKey = try {
                            Key.fromBase64(json.decodeFromString<StartFailoverRequest>(body.toString(Charsets.UTF_8)).gateway_public_key)
                        } catch (e: Exception) {
                            delegate.logEvent("KtorServer", "Rejected failover start: missing or invalid gateway_public_key", true)
                            return@signed reply(
                                HttpStatusCode.BadRequest,
                                FailoverActionResponse(result = "error", message = "Body must be JSON with a valid base64 gateway_public_key")
                            )
                        }
                        when (val result = delegate.startFailover(gatewayKey, auth.gatewayId)) {
                            FailoverStartResult.Started ->
                                reply(HttpStatusCode.OK, FailoverActionResponse(result = "active"))
                            FailoverStartResult.Busy ->
                                reply(HttpStatusCode.Conflict, FailoverActionResponse(result = "busy", message = "Relay in use by another gateway"))
                            is FailoverStartResult.Declined ->
                                reply(HttpStatusCode.Conflict, FailoverActionResponse(result = "declined", message = result.message))
                            is FailoverStartResult.Failed ->
                                reply(HttpStatusCode.InternalServerError, FailoverActionResponse(result = "error", message = result.message))
                        }
                    }

                    signed(HttpMethod.Post, "/v1/failover/stop") { auth, _ ->
                        if (delegate.stopFailover(auth.gatewayId)) {
                            reply(HttpStatusCode.OK, FailoverActionResponse(result = "idle"))
                        } else {
                            reply(HttpStatusCode.Conflict, FailoverActionResponse(result = "busy", message = "Relay in use by another gateway"))
                        }
                    }

                    signed(HttpMethod.Post, "/v1/pair/complete", allowPending = true) { auth, _ ->
                        val response = authenticator.completePairing(auth, delegate.deviceName)
                            ?: return@signed reply(
                                HttpStatusCode.Conflict,
                                FailoverActionResponse(result = "error", message = "Pairing cancelled or expired on the phone")
                            )
                        delegate.logEvent("Pairing", "Paired with gateway '${auth.gatewayName}' on Wi-Fi '${response.ssid}'")
                        reply(HttpStatusCode.OK, response)
                    }
                }
            }

            server = serverEngine
            serverEngine.start(wait = false)
            this.bindAddress = bindAddress
            delegate.logEvent("KtorServer", "Control HTTP server started on $bindAddress:$port")
        } catch (e: Exception) {
            _isRunning.set(false)
            delegate.logEvent("KtorServer", "Failed to start HTTP server: ${e.message}", true)
            throw e
        }
    }

    /**
     * A route that only answers requests signed by a paired gateway, and signs its answer.
     * The signature covers [path] as declared here (not the raw URI) and the exact body bytes.
     */
    private fun Route.signed(
        method: HttpMethod,
        path: String,
        allowPending: Boolean = false,
        handler: suspend (AuthContext, ByteArray) -> Reply
    ) {
        route(path, method) {
            handle {
                val clientIp = call.request.local.remoteHost
                // Bounded read: a chunked body has no Content-Length to check up front
                val body = call.receiveChannel().readRemaining(MAX_BODY_BYTES + 1L).readByteArray()
                if (body.size > MAX_BODY_BYTES) {
                    call.respondUnsigned(HttpStatusCode.PayloadTooLarge, "Body too large")
                    return@handle
                }
                val headers = call.request.headers
                val request = SignedRequest(
                    gatewayId = headers[AuthProtocol.HEADER_GATEWAY],
                    phoneId = headers[AuthProtocol.HEADER_PHONE],
                    challenge = headers[AuthProtocol.HEADER_CHALLENGE],
                    nonce = headers[AuthProtocol.HEADER_NONCE],
                    signature = headers[AuthProtocol.HEADER_SIGNATURE],
                    method = method.value,
                    path = path,
                    body = body
                )
                when (val result = authenticator.authenticate(request, delegate.currentSsid, allowPending)) {
                    is AuthResult.Rejected -> {
                        delegate.logEvent("KtorServer", "Rejected ${method.value} $path from $clientIp: ${result.reason}", true)
                        call.respondUnsigned(HttpStatusCode.Unauthorized, "Unauthorized")
                    }
                    is AuthResult.Accepted -> {
                        val auth = result.context
                        delegate.logEvent("KtorServer", "${method.value} $path from gateway '${auth.gatewayName}' ($clientIp)")
                        val answer = try {
                            handler(auth, body)
                        } catch (e: Exception) {
                            delegate.logEvent("KtorServer", "${method.value} $path error: ${e.message}", true)
                            reply(HttpStatusCode.InternalServerError, FailoverActionResponse(result = "error", message = e.message))
                        }
                        val bytes = answer.body.toByteArray(Charsets.UTF_8)
                        call.response.header(AuthProtocol.HEADER_SIGNATURE, authenticator.signResponse(auth, answer.status.value, bytes))
                        call.respondBytes(bytes, ContentType.Application.Json, answer.status)
                    }
                }
            }
        }
    }

    private suspend fun ApplicationCall.respondUnsigned(status: HttpStatusCode, message: String) {
        val body = json.encodeToString(FailoverActionResponse(result = "error", message = message))
        respondBytes(body.toByteArray(Charsets.UTF_8), ContentType.Application.Json, status)
    }

    @Synchronized
    fun stop() {
        if (!_isRunning.getAndSet(false)) return

        try {
            server?.stop(gracePeriodMillis = 500, timeoutMillis = 1000)
            server = null
            bindAddress = null
            delegate.logEvent("KtorServer", "Control HTTP server stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping HTTP server: ${e.message}")
        }
    }
}
