package com.yourapp.webrtcapp.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * Configuration constants for AI-Driven Adaptive Communication app
 * 
 * SECURITY NOTES:
 * - TURN credentials should be fetched from backend with short-lived tokens
 * - For production: Use WSS (TLS) instead of WS
 * - Server IP should be configurable, not hardcoded
 */
object Constants {
    
    // ==================== SIGNALING SERVER ====================
    
    // Default server configuration
    // ⚠️ Change SERVER_IP to your computer's IP (run 'ipconfig')
    const val DEFAULT_SERVER_IP = "10.137.143.46"
    private const val DEFAULT_SERVER_PORT = 3000
    
    // For production: Use secure WebSocket (WSS)
    private const val USE_SECURE_WEBSOCKET = false  // Set true for production
    
    // Protocol prefix
    private val WS_PROTOCOL = if (USE_SECURE_WEBSOCKET) "wss" else "ws"
    
    // For emulator testing (connects to host machine's localhost)
    const val EMULATOR_SERVER_URL = "ws://10.0.2.2:$DEFAULT_SERVER_PORT"
    
    // For real devices
    val DEVICE_SERVER_URL: String
        get() = "$WS_PROTOCOL://$DEFAULT_SERVER_IP:$DEFAULT_SERVER_PORT"
    
    // ==================== ICE/TURN CONFIGURATION ====================
    
    /**
     * Get ICE server configuration
     * 
     * In production:
     * - Fetch TURN credentials from your backend using REST API
     * - Credentials should be short-lived (expire in minutes/hours)
     * - Use TURN REST API pattern: https://tools.ietf.org/html/draft-uberti-behave-turn-rest-00
     */
    object IceConfig {
        // Public STUN servers (free, no auth needed)
        val STUN_SERVERS = listOf(
            "stun:stun.l.google.com:19302",
            "stun:stun1.l.google.com:19302",
            "stun:stun2.l.google.com:19302",
            "stun:stun.stunprotocol.org:3478"
        )
        
        // Public TURN servers (for development only!)
        // ⚠️ WARNING: In production, use your own TURN server with proper auth
        // These are rate-limited and may stop working
        data class TurnServer(
            val url: String,
            val username: String,
            val password: String
        )
        
        // Development TURN servers - DO NOT use in production
        val DEV_TURN_SERVERS = listOf(
            // OpenRelay (community, may be unreliable)
            TurnServer(
                url = "turn:openrelay.metered.ca:80",
                username = "openrelayproject",
                password = "openrelayproject"
            ),
            TurnServer(
                url = "turn:openrelay.metered.ca:443",
                username = "openrelayproject",
                password = "openrelayproject"
            ),
            TurnServer(
                url = "turn:openrelay.metered.ca:443?transport=tcp",
                username = "openrelayproject",
                password = "openrelayproject"
            )
        )
        
        /**
         * In production, implement this to fetch from your backend:
         * 
         * suspend fun fetchTurnCredentials(authToken: String): List<TurnServer> {
         *     val response = yourApi.getTurnCredentials(authToken)
         *     return response.servers.map { TurnServer(it.url, it.username, it.credential) }
         * }
         */
    }
    
    // ==================== AI ADAPTATION SETTINGS ====================
    
    object AdaptiveSettings {
        // Stats polling interval (ms)
        const val STATS_INTERVAL_MS = 1000L
        
        // Minimum time between profile changes (ms)
        const val ADAPTATION_COOLDOWN_MS = 3000L
        
        // Enable AI-driven adaptation
        const val AI_ADAPTATION_ENABLED = true
        
        // Enable telemetry collection (anonymized, for model improvement)
        const val TELEMETRY_ENABLED = false  // Opt-in only
        
        // Rural mode auto-detection
        const val AUTO_RURAL_MODE = true
    }
    
    // ==================== MEDIA DEFAULTS ====================
    
    object MediaDefaults {
        // Default video constraints (conservative for low-speed networks)
        const val DEFAULT_VIDEO_WIDTH = 320
        const val DEFAULT_VIDEO_HEIGHT = 240
        const val DEFAULT_VIDEO_FPS = 15
        
        // Maximum video constraints (for high-quality mode)
        const val MAX_VIDEO_WIDTH = 1280
        const val MAX_VIDEO_HEIGHT = 720
        const val MAX_VIDEO_FPS = 30
        
        // Audio settings
        const val ENABLE_ECHO_CANCELLATION = true
        const val ENABLE_NOISE_SUPPRESSION = true
        const val ENABLE_AUTO_GAIN_CONTROL = true
    }
    
    // ==================== PREFERENCES ====================
    
    private const val PREFS_NAME = "adaptive_call_prefs"
    private const val KEY_RURAL_MODE = "rural_mode_enabled"
    private const val KEY_SERVER_IP = "custom_server_ip"
    
    fun getRuralModeEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_RURAL_MODE, false)
    }
    
    fun setRuralModeEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_RURAL_MODE, enabled).apply()
    }
    
    fun getCustomServerIp(context: Context): String? {
        return getPrefs(context).getString(KEY_SERVER_IP, null)
    }
    
    fun setCustomServerIp(context: Context, ip: String) {
        getPrefs(context).edit().putString(KEY_SERVER_IP, ip).apply()
    }
    
    /**
     * Get the full WebSocket URL for signaling
     */
    fun getSignalingUrl(context: Context): String {
        val customIp = getCustomServerIp(context)
        return if (customIp != null && customIp.isNotBlank()) {
            // Check if custom IP is a full URL (e.g., ngrok)
            if (customIp.startsWith("wss://") || customIp.startsWith("ws://")) {
                customIp
            } else if (customIp.contains(".ngrok")) {
                "wss://$customIp"
            } else {
                "$WS_PROTOCOL://$customIp:$DEFAULT_SERVER_PORT"
            }
        } else {
            DEVICE_SERVER_URL
        }
    }
    
    /**
     * Get HTTP URL for REST API
     * Derives from the same server as WebSocket to ensure consistency
     */
    fun getHttpServerUrl(context: Context): String {
        val customIp = getCustomServerIp(context)
        return if (customIp != null && customIp.isNotBlank()) {
            // Check if custom IP is a full URL (e.g., ngrok domain)
            if (customIp.startsWith("https://") || customIp.startsWith("http://")) {
                customIp  // Already a full URL
            } else if (customIp.contains(".ngrok")) {
                "https://$customIp"  // ngrok uses HTTPS
            } else {
                val protocol = if (USE_SECURE_WEBSOCKET) "https" else "http"
                "$protocol://$customIp:$DEFAULT_SERVER_PORT"
            }
        } else {
            val protocol = if (USE_SECURE_WEBSOCKET) "https" else "http"
            "$protocol://$DEFAULT_SERVER_IP:$DEFAULT_SERVER_PORT"
        }
    }
    
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}

