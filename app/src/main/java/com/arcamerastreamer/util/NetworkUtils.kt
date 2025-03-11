package com.arcamerastreamer.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.*

/**
 * Utility class for network operations
 */
object NetworkUtils {

    private const val TAG = "NetworkUtils"

    /**
     * Get the device's IP address on the network
     */
    fun getIPAddress(context: Context): String? {
        try {
            // Try to get WiFi IP first (most likely for local network)
            val wifiIP = getWifiIPAddress(context)
            if (!wifiIP.isNullOrEmpty() && wifiIP != "0.0.0.0") {
                return wifiIP
            }

            // If no WiFi IP, try to get from network interfaces
            val networkInterfaces = NetworkInterface.getNetworkInterfaces()

            while (networkInterfaces.hasMoreElements()) {
                val networkInterface = networkInterfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback ||
                    networkInterface.isVirtual || networkInterface.name.contains("dummy")) {
                    continue
                }

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()

                    // Filter for IPv4 addresses that are not loopback
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val hostAddress = address.hostAddress
                        if (!hostAddress.isNullOrEmpty() && hostAddress != "0.0.0.0") {
                            return hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP address: ${e.message}", e)
        }

        return null
    }

    /**
     * Get WiFi network IP address
     */
    private fun getWifiIPAddress(context: Context): String? {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?
            wifiManager?.let {
                val wifiInfo = it.connectionInfo

                if (wifiInfo != null && wifiInfo.ipAddress != 0) {
                    // Convert int IP to dotted format
                    return formatIPAddress(wifiInfo.ipAddress)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting WiFi IP address: ${e.message}", e)
        }

        return null
    }

    /**
     * Format integer IP address to dotted decimal string
     */
    private fun formatIPAddress(ipAddress: Int): String {
        return String.format(
            Locale.US,
            "%d.%d.%d.%d",
            ipAddress and 0xff,
            ipAddress shr 8 and 0xff,
            ipAddress shr 16 and 0xff,
            ipAddress shr 24 and 0xff
        )
    }
}