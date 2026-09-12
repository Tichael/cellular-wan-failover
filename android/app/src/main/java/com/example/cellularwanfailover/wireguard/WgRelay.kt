package com.example.cellularwanfailover.wireguard

object WgRelay {
    init {
        System.loadLibrary("wgrelay")
    }

    external fun startRelay(
        port: Int,
        privKeyBase64: String,
        peerPubKeyBase64: String,
        netHandle: Long
    ): Int

    external fun stopRelay(): Int

    external fun getBytesTransmitted(): Long

    external fun isRunning(): Boolean
}
