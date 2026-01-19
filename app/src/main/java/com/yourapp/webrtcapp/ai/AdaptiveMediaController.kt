package com.yourapp.webrtcapp.ai

import android.content.Context
import android.util.Log
import org.webrtc.*

/**
 * Adaptive Media Controller
 * 
 * Central controller for AI-driven media adaptation.
 * Coordinates between:
 * - NetworkDetector (initial network assessment)
 * - AdaptiveQualityPredictor (ML-based quality prediction)
 * - WebRTC components (capturer, sender parameters)
 * 
 * This is the brain of "AI-Driven Adaptive Voice and Data Communication"
 */
class AdaptiveMediaController(
    private val context: Context,
    private val listener: AdaptationListener
) {
    companion object {
        private const val TAG = "AdaptiveMediaController"
        
        // Adaptation intervals
        private const val STATS_INTERVAL_MS = 1000L
        private const val ADAPTATION_COOLDOWN_MS = 3000L  // Min time between adaptations
    }
    
    // AI Components
    private val predictor = AdaptiveQualityPredictor()
    
    // Current state
    private var currentProfile: NetworkProfile = NetworkProfile.MEDIUM
    private var isRuralModeEnabled = false
    private var isAudioOnlyMode = false
    private var lastAdaptationTime = 0L
    private var lastPrediction: QualityPrediction? = null
    
    // WebRTC components (set externally)
    private var videoCapturer: CameraVideoCapturer? = null
    private var videoSender: RtpSender? = null
    private var audioSender: RtpSender? = null
    
    /**
     * Initialize controller with initial network assessment
     */
    fun initialize() {
        val networkType = NetworkDetector.detectNetworkType(context)
        val initialProfile = if (isRuralModeEnabled) {
            // Rural mode: Always start conservative
            NetworkProfile.ULTRA_LOW
        } else {
            NetworkProfile.getInitialProfile(networkType)
        }
        
        Log.d(TAG, "Initializing with network=$networkType, profile=${initialProfile.displayName}")
        
        currentProfile = initialProfile
        predictor.setCurrentProfile(currentProfile)
        
        listener.onProfileChanged(currentProfile, "Initial: ${networkType.name}")
    }
    
    /**
     * Set WebRTC components for adaptation
     */
    fun setWebRtcComponents(
        capturer: CameraVideoCapturer?,
        videoSender: RtpSender?,
        audioSender: RtpSender?
    ) {
        this.videoCapturer = capturer
        this.videoSender = videoSender
        this.audioSender = audioSender
    }
    
    /**
     * Enable/disable Rural Mode (forces ultra-conservative settings)
     */
    fun setRuralMode(enabled: Boolean) {
        isRuralModeEnabled = enabled
        Log.d(TAG, "Rural mode: $enabled")
        
        if (enabled) {
            // Immediately switch to ultra-low profile
            applyProfile(NetworkProfile.ULTRA_LOW, "Rural mode enabled")
        } else {
            // Re-assess network and pick appropriate profile
            initialize()
        }
        
        listener.onRuralModeChanged(enabled)
    }
    
    /**
     * Enable/disable audio-only mode
     */
    fun setAudioOnlyMode(enabled: Boolean) {
        isAudioOnlyMode = enabled
        Log.d(TAG, "Audio-only mode: $enabled")
        
        if (enabled) {
            // Find best audio-only profile
            val audioProfile = if (isRuralModeEnabled) {
                NetworkProfile.ULTRA_LOW
            } else {
                NetworkProfile.VERY_LOW
            }
            applyProfile(audioProfile, "Audio-only mode")
        }
        
        listener.onAudioOnlyModeChanged(enabled)
    }
    
    /**
     * Process new stats and run AI prediction
     * Call this every STATS_INTERVAL_MS with current WebRTC stats
     */
    fun onStatsUpdate(
        bitrateKbps: Int,
        packetLossPercent: Float,
        rttMs: Int,
        jitterMs: Int
    ) {
        // Skip if in audio-only or rural mode (already at minimum)
        if (isRuralModeEnabled || (isAudioOnlyMode && currentProfile.priority <= 2)) {
            return
        }
        
        // Create stats object
        val stats = NetworkStats(
            bitrateKbps = bitrateKbps,
            packetLossPercent = packetLossPercent,
            rttMs = rttMs,
            jitterMs = jitterMs
        )
        
        // Run AI prediction
        val prediction = predictor.predict(stats)
        lastPrediction = prediction
        
        // Notify listener of prediction
        listener.onPredictionUpdate(prediction)
        
        // Check if we should adapt
        val now = System.currentTimeMillis()
        if (now - lastAdaptationTime < ADAPTATION_COOLDOWN_MS) {
            Log.v(TAG, "Adaptation on cooldown")
            return
        }
        
        // Apply recommendation if not MAINTAIN
        when (prediction.recommendedAction) {
            RecommendedAction.UPGRADE -> {
                if (!isAudioOnlyMode) {
                    applyProfile(prediction.suggestedProfile, prediction.reasoning)
                }
            }
            RecommendedAction.DOWNGRADE -> {
                applyProfile(prediction.suggestedProfile, prediction.reasoning)
                
                // If downgrading to audio-only level, suggest to user
                if (prediction.suggestedProfile.priority <= 2) {
                    listener.onSuggestAudioOnly(prediction.reasoning)
                }
            }
            RecommendedAction.MAINTAIN -> {
                // Do nothing
            }
        }
    }
    
    /**
     * Apply a new profile
     */
    private fun applyProfile(profile: NetworkProfile, reason: String) {
        if (profile == currentProfile) return
        
        Log.d(TAG, "Applying profile: ${profile.displayName} (was: ${currentProfile.displayName})")
        Log.d(TAG, "Reason: $reason")
        
        val oldProfile = currentProfile
        currentProfile = profile
        predictor.setCurrentProfile(profile)
        lastAdaptationTime = System.currentTimeMillis()
        
        // Apply video settings
        applyVideoSettings(profile)
        
        // Apply bitrate constraints
        applyBitrateConstraints(profile)
        
        // Notify listener
        listener.onProfileChanged(profile, reason)
        
        // Log adaptation
        Log.i(TAG, "Adapted: ${oldProfile.displayName} → ${profile.displayName}")
    }
    
    /**
     * Apply video capture settings (resolution/fps)
     */
    private fun applyVideoSettings(profile: NetworkProfile) {
        val capturer = videoCapturer ?: return
        
        if (!profile.enableVideo) {
            // Disable video completely
            Log.d(TAG, "Disabling video capture")
            // Note: Actually stopping capturer should be handled by CallActivity
            // as it affects UI. We just signal the change.
            return
        }
        
        try {
            // Change capture format
            Log.d(TAG, "Changing capture: ${profile.videoWidth}x${profile.videoHeight}@${profile.videoFps}")
            capturer.changeCaptureFormat(
                profile.videoWidth,
                profile.videoHeight,
                profile.videoFps
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error changing capture format: ${e.message}")
        }
    }
    
    /**
     * Apply bitrate constraints to senders
     */
    private fun applyBitrateConstraints(profile: NetworkProfile) {
        // Apply video bitrate
        videoSender?.let { sender ->
            if (sender.track()?.kind() == "video") {
                val params = sender.parameters
                if (params.encodings.isNotEmpty()) {
                    params.encodings[0].maxBitrateBps = profile.maxVideoBitrateBps
                    sender.parameters = params
                    Log.d(TAG, "Set video bitrate: ${profile.maxVideoBitrateBps / 1000} kbps")
                }
            }
        }
        
        // Apply audio bitrate (if supported by codec)
        audioSender?.let { sender ->
            if (sender.track()?.kind() == "audio") {
                val params = sender.parameters
                if (params.encodings.isNotEmpty()) {
                    params.encodings[0].maxBitrateBps = profile.maxAudioBitrateBps
                    sender.parameters = params
                    Log.d(TAG, "Set audio bitrate: ${profile.maxAudioBitrateBps / 1000} kbps")
                }
            }
        }
    }
    
    /**
     * Get current profile
     */
    fun getCurrentProfile(): NetworkProfile = currentProfile
    
    /**
     * Get predictor stats summary
     */
    fun getStatsSummary(): StatsSummary = predictor.getStatsSummary()
    
    /**
     * Check if on low-speed network
     */
    fun isLowSpeedNetwork(): Boolean = NetworkDetector.isLowSpeedNetwork(context)
    
    /**
     * Reset controller state
     */
    fun reset() {
        predictor.reset()
        lastAdaptationTime = 0
        isAudioOnlyMode = false
        // Note: Don't reset rural mode - it's a user preference
    }
    
    /**
     * Get capture settings for initial setup
     */
    fun getInitialCaptureSettings(): CaptureSettings {
        return CaptureSettings(
            width = currentProfile.videoWidth.takeIf { it > 0 } ?: 320,
            height = currentProfile.videoHeight.takeIf { it > 0 } ?: 240,
            fps = currentProfile.videoFps.takeIf { it > 0 } ?: 15,
            enableVideo = currentProfile.enableVideo && !isAudioOnlyMode
        )
    }
    
    /**
     * Get the last prediction result (for telemetry)
     */
    fun getLastPrediction(): QualityPrediction? = lastPrediction
    
    /**
     * Listener interface for adaptation events
     */
    interface AdaptationListener {
        fun onProfileChanged(profile: NetworkProfile, reason: String)
        fun onPredictionUpdate(prediction: QualityPrediction)
        fun onSuggestAudioOnly(reason: String)
        fun onRuralModeChanged(enabled: Boolean)
        fun onAudioOnlyModeChanged(enabled: Boolean)
    }
    
    /**
     * Capture settings for initial setup
     */
    data class CaptureSettings(
        val width: Int,
        val height: Int,
        val fps: Int,
        val enableVideo: Boolean
    )
}
