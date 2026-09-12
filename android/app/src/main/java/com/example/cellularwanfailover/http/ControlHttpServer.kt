package com.example.cellularwanfailover.http

import android.util.Log
import com.example.cellularwanfailover.model.CellularState
import com.example.cellularwanfailover.model.FailoverActionResponse
import com.example.cellularwanfailover.model.StatusResponse
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean

import com.example.cellularwanfailover.model.WireGuardInfo
import io.ktor.http.ContentType
import io.ktor.server.response.respondText

interface FailoverControlDelegate {
    val failoverStatusString: String
    val currentWifiIp: String?
    val isCellularReady: Boolean
    val currentBytesTransmitted: Long
    val wireguardPort: Int
    val wireguardPublicKey: String
    val wireguardTunnelIp: String
    val wireguardPeerIp: String
    fun getWireguardConfigString(): String
    suspend fun startFailover(): Boolean
    suspend fun stopFailover()
    fun logEvent(tag: String, message: String, isError: Boolean = false)
}

class ControlHttpServer(
    val port: Int = 8989,
    private val delegate: FailoverControlDelegate
) {
    companion object {
        private const val TAG = "ControlHttpServer"
    }

    private var server: EmbeddedServer<*, *>? = null
    private val _isRunning = AtomicBoolean(false)
    val isRunning: Boolean get() = _isRunning.get()

    @Synchronized
    fun start() {
        if (_isRunning.getAndSet(true)) {
            Log.d(TAG, "HTTP server already running on port $port")
            return
        }

        try {
            val serverEngine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
                install(ContentNegotiation) {
                    json(Json {
                        prettyPrint = true
                        isLenient = true
                        ignoreUnknownKeys = true
                        encodeDefaults = true
                    })
                }

                routing {
                    get("/v1/status") {
                        val clientIp = call.request.local.remoteHost
                        delegate.logEvent("KtorServer", "GET /v1/status from $clientIp")
                        val response = StatusResponse(
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
                            )
                        )
                        call.respond(HttpStatusCode.OK, response)
                    }

                    get("/v1/wireguard/config") {
                        val clientIp = call.request.local.remoteHost
                        delegate.logEvent("KtorServer", "GET /v1/wireguard/config from $clientIp")
                        call.respondText(delegate.getWireguardConfigString(), ContentType.Text.Plain)
                    }

                    post("/v1/failover/start") {
                        val clientIp = call.request.local.remoteHost
                        delegate.logEvent("KtorServer", "POST /v1/failover/start from $clientIp")
                        try {
                            val success = delegate.startFailover()
                            if (success) {
                                call.respond(HttpStatusCode.OK, FailoverActionResponse(result = "active"))
                            } else {
                                call.respond(
                                    HttpStatusCode.InternalServerError,
                                    FailoverActionResponse(result = "error", message = "Cellular connection failed")
                                )
                            }
                        } catch (e: Exception) {
                            delegate.logEvent("KtorServer", "Failover start error: ${e.message}", true)
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                FailoverActionResponse(result = "error", message = e.message)
                            )
                        }
                    }

                    post("/v1/failover/stop") {
                        val clientIp = call.request.local.remoteHost
                        delegate.logEvent("KtorServer", "POST /v1/failover/stop from $clientIp")
                        try {
                            delegate.stopFailover()
                            call.respond(HttpStatusCode.OK, FailoverActionResponse(result = "idle"))
                        } catch (e: Exception) {
                            delegate.logEvent("KtorServer", "Failover stop error: ${e.message}", true)
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                FailoverActionResponse(result = "error", message = e.message)
                            )
                        }
                    }
                }
            }

            server = serverEngine
            serverEngine.start(wait = false)
            delegate.logEvent("KtorServer", "Control HTTP server started on 0.0.0.0:$port")
        } catch (e: Exception) {
            _isRunning.set(false)
            delegate.logEvent("KtorServer", "Failed to start HTTP server: ${e.message}", true)
            throw e
        }
    }

    @Synchronized
    fun stop() {
        if (!_isRunning.getAndSet(false)) return

        try {
            server?.stop(gracePeriodMillis = 500, timeoutMillis = 1000)
            server = null
            delegate.logEvent("KtorServer", "Control HTTP server stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping HTTP server: ${e.message}")
        }
    }
}
