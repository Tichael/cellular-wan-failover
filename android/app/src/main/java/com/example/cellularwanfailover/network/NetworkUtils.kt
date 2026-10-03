package com.example.cellularwanfailover.network

import android.content.Context
import android.net.ConnectivityManager
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

    fun getWifiIpAddress(context: Context): String? {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                for (network in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(network) ?: continue
                    if (isPhysicalWifi(caps)) {
                        val lp = cm.getLinkProperties(network) ?: continue
                        for (linkAddr in lp.linkAddresses) {
                            val addr = linkAddr.address
                            if (addr is Inet4Address && !addr.isLoopbackAddress) {
                                return addr.hostAddress
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting Wi-Fi IP via ConnectivityManager", e)
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

