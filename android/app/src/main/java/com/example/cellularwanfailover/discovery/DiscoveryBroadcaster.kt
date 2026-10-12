package com.example.cellularwanfailover.discovery

import android.net.Network
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Announces the phone to its paired gateways on the Wi-Fi network: one UDP broadcast every
 * [intervalMillis] carrying a signature per gateway paired on that network (see PROTOCOL.md).
 */
class DiscoveryBroadcaster(
    private val broadcastPort: Int = 8990,
    private val intervalMillis: Long = 5000L,
    /** Wi-Fi network to send on; without it broadcasts follow the default network (cellular during an outage) */
    private val wifiNetworkProvider: () -> Network? = { null },
    /** Signed payload for the current network, or null when no gateway is paired on it */
    private val payloadProvider: () -> ByteArray? = { null }
) {
    companion object {
        private const val TAG = "DiscoveryBroadcaster"
    }

    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private val _isRunning = AtomicBoolean(false)
    val isRunning: Boolean get() = _isRunning.get()

    var onLog: ((message: String, isError: Boolean) -> Unit)? = null

    // Wakes the loop up to broadcast right away (e.g. a pairing just started)
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)

    fun broadcastNow() {
        wakeUp.trySend(Unit)
    }

    @Synchronized
    fun start() {
        if (_isRunning.getAndSet(true)) return

        val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = newScope

        job = newScope.launch {
            log("UDP Discovery broadcaster started on port $broadcastPort (every ${intervalMillis / 1000}s)")

            while (isActive) {
                var socket: DatagramSocket? = null
                try {
                    val jsonPayload = payloadProvider()
                    if (jsonPayload == null) {
                        withTimeoutOrNull(intervalMillis) { wakeUp.receive() }
                        continue
                    }
                    socket = DatagramSocket()
                    socket.broadcast = true
                    wifiNetworkProvider()?.bindSocket(socket)

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
                } catch (e: Exception) {
                    if (isActive) {
                        Log.w(TAG, "Discovery broadcast failed: ${e.message}")
                    }
                } finally {
                    try { socket?.close() } catch (_: Exception) {}
                }

                withTimeoutOrNull(intervalMillis) { wakeUp.receive() }
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
