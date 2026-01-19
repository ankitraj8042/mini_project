package com.yourapp.webrtcapp.ai

/**
 * Network profiles optimized for different connection types.
 * This is the core of "AI-Driven Adaptive Communication for Low-Speed Networks"
 */
enum class NetworkProfile(
    val displayName: String,
    val videoWidth: Int,
    val videoHeight: Int,
    val videoFps: Int,
    val maxVideoBitrateBps: Int,
    val maxAudioBitrateBps: Int,
    val enableVideo: Boolean,
    val priority: Int // Lower = more conservative
) {
    /**
     * Ultra Low - For 2G/EDGE networks (< 50 kbps)
     * Audio-only with minimal bitrate
     */
    ULTRA_LOW(
        displayName = "Ultra Low (2G)",
        videoWidth = 0,
        videoHeight = 0,
        videoFps = 0,
        maxVideoBitrateBps = 0,
        maxAudioBitrateBps = 16_000,  // 16 kbps Opus - still intelligible
        enableVideo = false,
        priority = 1
    ),

    /**
     * Very Low - For 2G/slow 3G (50-100 kbps)
     * Audio-only with better quality
     */
    VERY_LOW(
        displayName = "Very Low (2G+)",
        videoWidth = 0,
        videoHeight = 0,
        videoFps = 0,
        maxVideoBitrateBps = 0,
        maxAudioBitrateBps = 24_000,  // 24 kbps Opus
        enableVideo = false,
        priority = 2
    ),

    /**
     * Low - For 3G networks (100-300 kbps)
     * Minimal video + good audio
     */
    LOW(
        displayName = "Low (3G)",
        videoWidth = 160,
        videoHeight = 120,
        videoFps = 10,
        maxVideoBitrateBps = 100_000,   // 100 kbps video
        maxAudioBitrateBps = 32_000,    // 32 kbps Opus
        enableVideo = true,
        priority = 3
    ),

    /**
     * Medium Low - For decent 3G (300-500 kbps)
     * Small video + good audio
     */
    MEDIUM_LOW(
        displayName = "Medium Low (3G+)",
        videoWidth = 320,
        videoHeight = 240,
        videoFps = 15,
        maxVideoBitrateBps = 250_000,   // 250 kbps video
        maxAudioBitrateBps = 32_000,
        enableVideo = true,
        priority = 4
    ),

    /**
     * Medium - For 4G/decent WiFi (500 kbps - 1 Mbps)
     * Standard quality video
     */
    MEDIUM(
        displayName = "Medium (4G)",
        videoWidth = 480,
        videoHeight = 360,
        videoFps = 24,
        maxVideoBitrateBps = 600_000,   // 600 kbps video
        maxAudioBitrateBps = 48_000,
        enableVideo = true,
        priority = 5
    ),

    /**
     * High - For good 4G/WiFi (1-2 Mbps)
     * HD-ready video
     */
    HIGH(
        displayName = "High (4G+/WiFi)",
        videoWidth = 640,
        videoHeight = 480,
        videoFps = 30,
        maxVideoBitrateBps = 1_200_000,  // 1.2 Mbps video
        maxAudioBitrateBps = 64_000,
        enableVideo = true,
        priority = 6
    ),

    /**
     * Very High - For excellent connections (> 2 Mbps)
     * HD video
     */
    VERY_HIGH(
        displayName = "Very High (WiFi)",
        videoWidth = 1280,
        videoHeight = 720,
        videoFps = 30,
        maxVideoBitrateBps = 2_500_000,  // 2.5 Mbps video
        maxAudioBitrateBps = 64_000,
        enableVideo = true,
        priority = 7
    );

    companion object {
        /**
         * Get initial profile based on network type
         */
        fun getInitialProfile(networkType: NetworkType): NetworkProfile {
            return when (networkType) {
                NetworkType.NETWORK_2G -> ULTRA_LOW
                NetworkType.NETWORK_3G -> LOW
                NetworkType.NETWORK_4G -> MEDIUM
                NetworkType.NETWORK_WIFI -> HIGH
                NetworkType.NETWORK_5G -> VERY_HIGH
                NetworkType.NETWORK_UNKNOWN -> MEDIUM_LOW // Conservative default
                NetworkType.NETWORK_NONE -> ULTRA_LOW
            }
        }

        /**
         * Get profile that's one step lower (more conservative)
         */
        fun getNextLowerProfile(current: NetworkProfile): NetworkProfile {
            return values().filter { it.priority < current.priority }
                .maxByOrNull { it.priority } ?: ULTRA_LOW
        }

        /**
         * Get profile that's one step higher (better quality)
         */
        fun getNextHigherProfile(current: NetworkProfile): NetworkProfile {
            return values().filter { it.priority > current.priority }
                .minByOrNull { it.priority } ?: VERY_HIGH
        }

        /**
         * Get profile by estimated bandwidth
         */
        fun getProfileForBandwidth(bandwidthKbps: Int): NetworkProfile {
            return when {
                bandwidthKbps < 50 -> ULTRA_LOW
                bandwidthKbps < 100 -> VERY_LOW
                bandwidthKbps < 300 -> LOW
                bandwidthKbps < 500 -> MEDIUM_LOW
                bandwidthKbps < 1000 -> MEDIUM
                bandwidthKbps < 2000 -> HIGH
                else -> VERY_HIGH
            }
        }
    }
}

/**
 * Network type classification
 */
enum class NetworkType {
    NETWORK_NONE,
    NETWORK_2G,
    NETWORK_3G,
    NETWORK_4G,
    NETWORK_5G,
    NETWORK_WIFI,
    NETWORK_UNKNOWN
}
