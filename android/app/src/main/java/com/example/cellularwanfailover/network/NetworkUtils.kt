package com.example.cellularwanfailover.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    private const val TAG = "NetworkUtils"

    /**
     * A real Wi-Fi network, not a VPN. VPNs (including ones running in another
     * profile, e.g. a work profile) report their underlying transport, so they
     * also match TRANSPORT_WIFI.
     */
    private fun isPhysicalWifi(caps: NetworkCapabilities): Boolean =
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

    private fun ipv4Of(cm: ConnectivityManager, network: Network): String? {
        val lp = cm.getLinkProperties(network) ?: return null
        return lp.linkAddresses
            .map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }

    /** The physical Wi-Fi network that has an IPv4 address (never a VPN). */
    fun getWifiNetwork(context: Context): Network? {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
            return cm.allNetworks.firstOrNull { network ->
                val caps = cm.getNetworkCapabilities(network)
                caps != null && isPhysicalWifi(caps) && ipv4Of(cm, network) != null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error looking up Wi-Fi network", e)
            return null
        }
    }

    /** IPv4 address of [network], or null. */
    fun getIpv4Address(context: Context, network: Network): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        return try {
            ipv4Of(cm, network)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading IPv4 address of $network", e)
            null
        }
    }

    fun getWifiIpAddress(context: Context): String? {
        getWifiNetwork(context)?.let { network ->
            getIpv4Address(context, network)?.let { return it }
        }

        // Fallback: inspect network interfaces
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                // Typically Wi-Fi interface is wlan0, but could be ap0, etc.
                val name = intf.name.lowercase()
                if (name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("wifi")) {
                    for (addr in intf.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            return addr.hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error inspecting NetworkInterfaces for IP", e)
        }

        return null
    }

    fun normalizeSsid(rawSsid: String?): String? {
        if (rawSsid == null) return null
        var cleaned = rawSsid.trim()
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length >= 2) {
            cleaned = cleaned.substring(1, cleaned.length - 1).trim()
        }
        if (cleaned.equals("<unknown ssid>", ignoreCase = true) || cleaned.isEmpty()) {
            return null
        }
        return cleaned
    }

    fun getCurrentWifiInfo(context: Context): com.example.cellularwanfailover.model.WifiNetworkInfo? {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                for (network in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(network) ?: continue
                    if (isPhysicalWifi(caps)) {
                        var ssid: String? = null
                        var bssid: String? = null
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            val wifiInfo = caps.transportInfo as? android.net.wifi.WifiInfo
                            if (wifiInfo != null) {
                                ssid = normalizeSsid(wifiInfo.ssid)
                                bssid = wifiInfo.bssid
                            }
                        }
                        if (ssid == null) {
                            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                            val connInfo = wm?.connectionInfo
                            if (connInfo != null) {
                                ssid = normalizeSsid(connInfo.ssid)
                                if (bssid == null) bssid = connInfo.bssid
                            }
                        }
                        val cleanBssid = if (bssid != null && bssid != "02:00:00:00:00:00") bssid else null
                        return if (ssid != null) {
                            com.example.cellularwanfailover.model.WifiNetworkInfo(ssid = ssid, bssid = cleanBssid, isConnected = true)
                        } else {
                            com.example.cellularwanfailover.model.WifiNetworkInfo(ssid = "<unknown ssid>", bssid = cleanBssid, isConnected = true)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving current Wi-Fi info", e)
        }
        return null
    }
}

