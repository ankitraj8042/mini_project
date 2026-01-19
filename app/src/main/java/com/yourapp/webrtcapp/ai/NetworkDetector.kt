package com.yourapp.webrtcapp.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Utility to detect current network type for initial profile selection.
 * Critical for "AI-Driven Adaptive Communication for Low-Speed Networks"
 */
object NetworkDetector {
    
    private const val TAG = "NetworkDetector"

    /**
     * Detect current network type
     */
    fun detectNetworkType(context: Context): NetworkType {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        val network = connectivityManager.activeNetwork
        if (network == null) {
            Log.d(TAG, "No active network")
            return NetworkType.NETWORK_NONE
        }
        
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        if (capabilities == null) {
            Log.d(TAG, "No network capabilities")
            return NetworkType.NETWORK_UNKNOWN
        }
        
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                Log.d(TAG, "Network type: WiFi")
                NetworkType.NETWORK_WIFI
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                detectCellularType(context)
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                Log.d(TAG, "Network type: Ethernet")
                NetworkType.NETWORK_WIFI  // Treat as WiFi (usually fast)
            }
            else -> {
                Log.d(TAG, "Network type: Unknown")
                NetworkType.NETWORK_UNKNOWN
            }
        }
    }

    /**
     * Detect specific cellular network type (2G/3G/4G/5G)
     */
    private fun detectCellularType(context: Context): NetworkType {
        try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            
            // Try to get data network type
            val networkType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                telephonyManager.dataNetworkType
            } else {
                @Suppress("DEPRECATION")
                telephonyManager.networkType
            }
            
            val type = when (networkType) {
                // 2G Networks
                TelephonyManager.NETWORK_TYPE_GPRS,
                TelephonyManager.NETWORK_TYPE_EDGE,
                TelephonyManager.NETWORK_TYPE_CDMA,
                TelephonyManager.NETWORK_TYPE_1xRTT,
                TelephonyManager.NETWORK_TYPE_IDEN -> {
                    Log.d(TAG, "Network type: 2G (type=$networkType)")
                    NetworkType.NETWORK_2G
                }
                
                // 3G Networks
                TelephonyManager.NETWORK_TYPE_UMTS,
                TelephonyManager.NETWORK_TYPE_EVDO_0,
                TelephonyManager.NETWORK_TYPE_EVDO_A,
                TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSUPA,
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_EVDO_B,
                TelephonyManager.NETWORK_TYPE_EHRPD,
                TelephonyManager.NETWORK_TYPE_HSPAP -> {
                    Log.d(TAG, "Network type: 3G (type=$networkType)")
                    NetworkType.NETWORK_3G
                }
                
                // 4G Networks
                TelephonyManager.NETWORK_TYPE_LTE -> {
                    Log.d(TAG, "Network type: 4G LTE")
                    NetworkType.NETWORK_4G
                }
                
                // 5G Networks
                TelephonyManager.NETWORK_TYPE_NR -> {
                    Log.d(TAG, "Network type: 5G NR")
                    NetworkType.NETWORK_5G
                }
                
                else -> {
                    Log.d(TAG, "Network type: Unknown cellular (type=$networkType)")
                    NetworkType.NETWORK_UNKNOWN
                }
            }
            
            return type
        } catch (e: SecurityException) {
            // READ_PHONE_STATE permission not granted
            Log.w(TAG, "Cannot detect cellular type: ${e.message}")
            return NetworkType.NETWORK_UNKNOWN
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting cellular type: ${e.message}")
            return NetworkType.NETWORK_UNKNOWN
        }
    }

    /**
     * Get a human-readable description of current network
     */
    fun getNetworkDescription(context: Context): String {
        val type = detectNetworkType(context)
        val profile = NetworkProfile.getInitialProfile(type)
        return "${type.name.replace("NETWORK_", "")} - Recommended: ${profile.displayName}"
    }

    /**
     * Check if we're on a low-speed network (2G or 3G)
     */
    fun isLowSpeedNetwork(context: Context): Boolean {
        return when (detectNetworkType(context)) {
            NetworkType.NETWORK_2G,
            NetworkType.NETWORK_3G,
            NetworkType.NETWORK_NONE -> true
            else -> false
        }
    }

    /**
     * Estimate initial bandwidth based on network type
     * These are conservative estimates for profile selection
     */
    fun estimateInitialBandwidth(networkType: NetworkType): Int {
        return when (networkType) {
            NetworkType.NETWORK_NONE -> 0
            NetworkType.NETWORK_2G -> 30      // 30 kbps
            NetworkType.NETWORK_3G -> 200     // 200 kbps
            NetworkType.NETWORK_4G -> 1000    // 1 Mbps
            NetworkType.NETWORK_5G -> 5000    // 5 Mbps
            NetworkType.NETWORK_WIFI -> 2000  // 2 Mbps (conservative)
            NetworkType.NETWORK_UNKNOWN -> 300 // 300 kbps (conservative)
        }
    }

    /**
     * Get recommended initial profile for current network
     */
    fun getRecommendedProfile(context: Context): NetworkProfile {
        val networkType = detectNetworkType(context)
        return NetworkProfile.getInitialProfile(networkType)
    }
}
