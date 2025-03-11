package com.arcamerastreamer.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
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

                // Skip USB tethering interfaces (usually named rndis)
                if (networkInterface.name.contains("rndis") ||
                    networkInterface.displayName.contains("rndis")) {
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

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10+ approach using ConnectivityManager
                    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    val activeNetwork = connectivityManager.activeNetwork

                    val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
                    if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                        // For Android 10+, we need to get the IP another way
                        return getIPFromInterfaces(NetworkCapabilities.TRANSPORT_WIFI)
                    }
                } else {
                    // Legacy approach for older devices
                    if (wifiInfo != null && wifiInfo.ipAddress != 0) {
                        // Convert int IP to dotted format
                        return formatIPAddress(wifiInfo.ipAddress)
                    }
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

    /**
     * Get IP address from network interfaces by transport type
     */
    private fun getIPFromInterfaces(transportType: Int): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp) continue

                // Try to find WiFi interface - common names for WiFi interfaces
                val isWifi = when (transportType) {
                    NetworkCapabilities.TRANSPORT_WIFI -> {
                        networkInterface.name.startsWith("wlan") ||
                                networkInterface.displayName.contains("wlan", ignoreCase = true) ||
                                networkInterface.name == "ap0"
                    }
                    NetworkCapabilities.TRANSPORT_CELLULAR -> {
                        networkInterface.name.startsWith("rmnet") ||
                                networkInterface.name.startsWith("data") ||
                                networkInterface.displayName.contains("mobile", ignoreCase = true)
                    }
                    else -> false
                }

                if (isWifi) {
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        if (!address.isLoopbackAddress && address is Inet4Address) {
                            return address.hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding interface IP: ${e.message}", e)
        }

        return null
    }

    /**
     * Get the SSID of the connected WiFi network
     */
    fun getWifiSSID(context: Context): String? {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?

            if (wifiManager?.isWifiEnabled == true) {
                val wifiInfo = wifiManager.connectionInfo
                if (wifiInfo != null) {
                    var ssid = wifiInfo.ssid

                    // Remove quotes if present
                    if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                        ssid = ssid.substring(1, ssid.length - 1)
                    }

                    // Check for unknown or unavailable SSID
                    if (ssid == "<unknown ssid>" || ssid == "0x" || ssid.isEmpty()) {
                        return null
                    }

                    return ssid
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting WiFi SSID: ${e.message}", e)
        }

        return null
    }

    /**
     * Check if device is connected to a network
     */
    fun isNetworkConnected(context: Context): Boolean {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)

                return capabilities != null && (
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            } else {
                @Suppress("DEPRECATION")
                val networkInfo = connectivityManager.activeNetworkInfo
                @Suppress("DEPRECATION")
                return networkInfo != null && networkInfo.isConnected
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking network connection: ${e.message}", e)
        }

        return false
    }

    /**
     * Check if a specific port is already in use
     */
    fun isPortInUse(port: Int): Boolean {
        return try {
            val socket = java.net.ServerSocket(port)
            socket.close()
            false // Port is available
        } catch (e: Exception) {
            true // Port is in use
        }
    }
}