package com.example.cellularwanfailover.wireguard

object WgRelay {
    init {
        System.loadLibrary("wgrelay")
    }

    external fun startRelay(
        port: Int,
        privKeyBase64: String,
        peerPubKeyBase64: String,
        bindAddress: String,
        wifiNetHandle: Long,
        netHandle: Long
    ): Int

    external fun stopRelay(): Int

    /** Switches outbound connections to another cellular network (0: none) without restarting the relay. */
    external fun setCellularNetwork(netHandle: Long): Boolean

    /** Unix time of the gateway's last WireGuard handshake, 0 if none. */
    external fun getLastHandshakeEpochSeconds(): Long

    external fun getBytesTransmitted(): Long

    external fun isRunning(): Boolean
}
