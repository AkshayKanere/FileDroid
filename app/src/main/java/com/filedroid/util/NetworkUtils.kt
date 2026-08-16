package com.filedroid.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    fun getWifiIpAddress(context: Context): String? {
        // First try ConnectivityManager (modern approach)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null

        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return null
        }

        val linkProperties = cm.getLinkProperties(network) ?: return null
        val ipv4 = linkProperties.linkAddresses
            .firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
            ?.address?.hostAddress

        if (ipv4 != null) return ipv4

        // Fallback: iterate network interfaces
        return getIpFromInterfaces()
    }

    private fun getIpFromInterfaces(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces.toList()) {
                if (!intf.isUp || intf.isLoopback) continue
                // Prefer wlan interfaces
                val name = intf.name.lowercase()
                if (!name.startsWith("wlan") && !name.startsWith("eth")) continue

                for (addr in intf.inetAddresses.toList()) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
