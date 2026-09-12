package com.example.cellularwanfailover.discovery

import android.os.Build
import android.util.Log
import com.example.cellularwanfailover.model.DiscoveryMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean

class DiscoveryBroadcaster(
    private val httpPort: Int = 8989,
    private val broadcastPort: Int = 8990,
    private val intervalMillis: Long = 5000L
) {
    companion object {
        private const val TAG = "DiscoveryBroadcaster"
    }

    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private val _isRunning = AtomicBoolean(false)
    val isRunning: Boolean get() = _isRunning.get()

    var onLog: ((message: String, isError: Boolean) -> Unit)? = null

    @Synchronized
    fun start() {
        if (_isRunning.getAndSet(true)) return

        val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = newScope

        job = newScope.launch {
            log("UDP Discovery broadcaster started on port $broadcastPort (every ${intervalMillis / 1000}s)")
            val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            val msg = DiscoveryMessage(
                device = if (deviceModel.isNotBlank()) deviceModel else "Android Device",
                service = "wan-failover",
                port = httpPort
            )
            val jsonSerializer = Json { encodeDefaults = true }
            val jsonPayload = jsonSerializer.encodeToString(msg).toByteArray(Charsets.UTF_8)

            while (isActive) {
                var socket: DatagramSocket? = null
                try {
                    socket = DatagramSocket()
                    socket.broadcast = true

                    val targets = mutableSetOf<InetAddress>()
                    targets.add(InetAddress.getByName("255.255.255.255"))

                    try {
                        val interfaces = NetworkInterface.getNetworkInterfaces()
                        if (interfaces != null) {
                            for (intf in interfaces.asSequence()) {
                                if (intf.isLoopback || !intf.isUp) continue
                                for (ifaceAddr in intf.interfaceAddresses) {
                                    val bcast = ifaceAddr.broadcast
                                    if (bcast != null) {
                                        targets.add(bcast)
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}

                    for (target in targets) {
                        try {
                            val packet = DatagramPacket(
                                jsonPayload,
                                jsonPayload.size,
                                target,
                                broadcastPort
                            )
                            socket.send(packet)
                        } catch (_: Exception) {}
                    }
                    Log.d(TAG, "Sent discovery broadcast to $targets: ${String(jsonPayload)}")
                } catch (e: Exception) {
                    if (isActive) {
                        Log.w(TAG, "Discovery broadcast failed: ${e.message}")
                    }
                } finally {
                    try { socket?.close() } catch (_: Exception) {}
                }

                delay(intervalMillis)
            }
        }
    }

    @Synchronized
    fun stop() {
        if (!_isRunning.getAndSet(false)) return

        job?.cancel()
        job = null
        scope?.cancel()
        scope = null
        log("UDP Discovery broadcaster stopped")
    }

    private fun log(msg: String, isError: Boolean = false) {
        if (isError) Log.e(TAG, msg) else Log.i(TAG, msg)
        onLog?.invoke(msg, isError)
    }
}
