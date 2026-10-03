package com.example.cellularwanfailover.network

/**
 * Remembers which Wi-Fi network connection was verified as trusted.
 *
 * Android hides the SSID from apps in the background (it reports "<unknown ssid>"),
 * for example when the screen locks or Wi-Fi loses internet access. Treating that as
 * "untrusted" would stop the failover relay in the middle of an outage. Instead, once a
 * connection's SSID has been read and trusted, it stays trusted until that connection
 * is lost. Joining another network creates a new connection (a new network ID), which
 * must be verified again; roaming between access points of the same mesh does not.
 *
 * Network IDs are android.net.Network handles, kept as Long so this stays unit-testable.
 */
class StickyWifiTrust(private val isTrusted: (String) -> Boolean) {

    private var verifiedNetworkId: Long? = null
    private var verifiedSsid: String? = null

    /**
     * Returns the SSID to evaluate for [networkId]: [readableSsid] when Android exposes it,
     * otherwise the SSID verified earlier for this same connection, or null if unknown.
     */
    @Synchronized
    fun resolveSsid(networkId: Long, readableSsid: String?): String? {
        if (readableSsid != null) {
            if (isTrusted(readableSsid)) {
                verifiedNetworkId = networkId
                verifiedSsid = readableSsid
            } else if (verifiedNetworkId == networkId) {
                clear()
            }
            return readableSsid
        }
        return if (verifiedNetworkId == networkId) verifiedSsid else null
    }

    @Synchronized
    fun onNetworkLost(networkId: Long) {
        if (verifiedNetworkId == networkId) clear()
    }

    private fun clear() {
        verifiedNetworkId = null
        verifiedSsid = null
    }
}
