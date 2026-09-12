package com.example.cellularwanfailover.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    private const val TAG = "NetworkUtils"

    fun getWifiIpAddress(context: Context): String? {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                for (network in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(network) ?: continue
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
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

            // General non-loopback fallback if name didn't match
            val allInterfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in allInterfaces) {
                if (intf.isLoopback || !intf.isUp || intf.name.startsWith("rmnet") || intf.name.startsWith("dummy")) {
                    continue
                }
                for (addr in intf.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error inspecting NetworkInterfaces for IP", e)
        }

        return null
    }
}
