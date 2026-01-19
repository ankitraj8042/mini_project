package com.yourapp.webrtcapp.ai

import android.util.Log
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * AI-Driven Adaptive Quality Predictor
 * 
 * Uses a lightweight on-device ML model (logistic regression) to predict
 * network quality and recommend optimal profiles for low-speed networks.
 * 
 * Features used for prediction:
 * - Current bitrate (kbps)
 * - Packet loss percentage
 * - Round-trip time (ms)
 * - Jitter (ms)
 * - Recent trend (improving/degrading)
 * 
 * This enables PROACTIVE adaptation instead of reactive threshold-based switching.
 */
class AdaptiveQualityPredictor {

    companion object {
        private const val TAG = "AIQualityPredictor"
        
        // Sliding window size for stats history
        private const val WINDOW_SIZE = 10
        
        // Stability thresholds for profile changes
        private const val STABLE_SAMPLES_FOR_UPGRADE = 5
        private const val DEGRADED_SAMPLES_FOR_DOWNGRADE = 2
        
        // Prediction confidence threshold
        private const val CONFIDENCE_THRESHOLD = 0.7f
    }

    // Stats history for time-series analysis
    private val bitrateHistory = mutableListOf<Float>()
    private val lossHistory = mutableListOf<Float>()
    private val rttHistory = mutableListOf<Float>()
    private val jitterHistory = mutableListOf<Float>()
    
    // Prediction state
    private var currentProfile: NetworkProfile = NetworkProfile.MEDIUM
    private var stableSamplesCount = 0
    private var degradedSamplesCount = 0
    private var lastPrediction: QualityPrediction? = null
    
    // Pre-trained model weights (logistic regression)
    // These weights are derived from simulated network conditions
    // In production, train on real user data
    private val modelWeights = ModelWeights(
        bitrateWeight = 0.003f,      // Higher bitrate = better
        lossWeight = -0.15f,          // Higher loss = worse
        rttWeight = -0.004f,          // Higher RTT = worse
        jitterWeight = -0.02f,        // Higher jitter = worse
        trendWeight = 0.5f,           // Positive trend = better
        bias = 0.2f
    )

    /**
     * Main prediction method - call this with current stats
     * Returns recommended action (upgrade/downgrade/maintain)
     */
    fun predict(stats: NetworkStats): QualityPrediction {
        // Add to history
        addToHistory(stats)
        
        // Calculate features
        val features = extractFeatures(stats)
        
        // Run model prediction
        val qualityScore = runModel(features)
        
        // Calculate confidence based on history variance
        val confidence = calculateConfidence()
        
        // Determine recommended action
        val action = determineAction(qualityScore, confidence)
        
        // Update state
        updateState(action)
        
        val prediction = QualityPrediction(
            qualityScore = qualityScore,
            confidence = confidence,
            recommendedAction = action,
            currentProfile = currentProfile,
            suggestedProfile = getSuggestedProfile(action),
            features = features,
            reasoning = generateReasoning(features, qualityScore, action)
        )
        
        lastPrediction = prediction
        Log.d(TAG, "Prediction: $prediction")
        
        return prediction
    }

    /**
     * Force set current profile (e.g., when user changes mode)
     */
    fun setCurrentProfile(profile: NetworkProfile) {
        currentProfile = profile
        stableSamplesCount = 0
        degradedSamplesCount = 0
    }

    /**
     * Reset predictor state
     */
    fun reset() {
        bitrateHistory.clear()
        lossHistory.clear()
        rttHistory.clear()
        jitterHistory.clear()
        stableSamplesCount = 0
        degradedSamplesCount = 0
        lastPrediction = null
    }

    /**
     * Get current stats summary for display
     */
    fun getStatsSummary(): StatsSummary {
        return StatsSummary(
            avgBitrate = bitrateHistory.averageOrNull() ?: 0f,
            avgLoss = lossHistory.averageOrNull() ?: 0f,
            avgRtt = rttHistory.averageOrNull() ?: 0f,
            avgJitter = jitterHistory.averageOrNull() ?: 0f,
            trend = calculateTrend(),
            samplesCount = bitrateHistory.size
        )
    }

    // ==================== Private Methods ====================

    private fun addToHistory(stats: NetworkStats) {
        bitrateHistory.addAndLimit(stats.bitrateKbps.toFloat())
        lossHistory.addAndLimit(stats.packetLossPercent)
        rttHistory.addAndLimit(stats.rttMs.toFloat())
        jitterHistory.addAndLimit(stats.jitterMs.toFloat())
    }

    private fun MutableList<Float>.addAndLimit(value: Float) {
        add(value)
        if (size > WINDOW_SIZE) removeAt(0)
    }

    private fun extractFeatures(stats: NetworkStats): PredictionFeatures {
        val trend = calculateTrend()
        val bitrateVariance = calculateVariance(bitrateHistory)
        val lossVariance = calculateVariance(lossHistory)
        
        return PredictionFeatures(
            currentBitrate = stats.bitrateKbps.toFloat(),
            avgBitrate = bitrateHistory.averageOrNull() ?: stats.bitrateKbps.toFloat(),
            currentLoss = stats.packetLossPercent,
            avgLoss = lossHistory.averageOrNull() ?: stats.packetLossPercent,
            currentRtt = stats.rttMs.toFloat(),
            avgRtt = rttHistory.averageOrNull() ?: stats.rttMs.toFloat(),
            currentJitter = stats.jitterMs.toFloat(),
            avgJitter = jitterHistory.averageOrNull() ?: stats.jitterMs.toFloat(),
            trend = trend,
            bitrateVariance = bitrateVariance,
            lossVariance = lossVariance
        )
    }

    private fun calculateTrend(): Float {
        if (bitrateHistory.size < 3) return 0f
        
        // Simple linear regression slope
        val n = bitrateHistory.size
        val recentHalf = bitrateHistory.takeLast(n / 2)
        val olderHalf = bitrateHistory.take(n / 2)
        
        val recentAvg = recentHalf.average().toFloat()
        val olderAvg = olderHalf.average().toFloat()
        
        // Normalize trend to [-1, 1]
        val maxBitrate = bitrateHistory.maxOrNull() ?: 1f
        return ((recentAvg - olderAvg) / maxBitrate).coerceIn(-1f, 1f)
    }

    private fun calculateVariance(list: List<Float>): Float {
        if (list.size < 2) return 0f
        val mean = list.average().toFloat()
        return list.map { (it - mean) * (it - mean) }.average().toFloat()
    }

    private fun List<Float>.averageOrNull(): Float? {
        return if (isEmpty()) null else average().toFloat()
    }

    /**
     * Run the logistic regression model
     * Output: quality score in [0, 1] where higher = better
     */
    private fun runModel(features: PredictionFeatures): Float {
        // Normalize features
        val normBitrate = features.avgBitrate / 2000f  // Normalize to ~1 at 2 Mbps
        val normLoss = features.avgLoss / 20f          // Normalize to ~1 at 20% loss
        val normRtt = features.avgRtt / 500f           // Normalize to ~1 at 500ms
        val normJitter = features.avgJitter / 100f     // Normalize to ~1 at 100ms
        
        // Linear combination
        val z = modelWeights.bias +
                modelWeights.bitrateWeight * normBitrate * 100 +
                modelWeights.lossWeight * normLoss * 10 +
                modelWeights.rttWeight * normRtt * 100 +
                modelWeights.jitterWeight * normJitter * 10 +
                modelWeights.trendWeight * features.trend
        
        // Sigmoid activation
        return sigmoid(z)
    }

    private fun sigmoid(x: Float): Float {
        return (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()
    }

    private fun calculateConfidence(): Float {
        if (bitrateHistory.size < 3) return 0.5f  // Low confidence initially
        
        // Higher confidence with more samples and lower variance
        val sampleFactor = min(bitrateHistory.size / WINDOW_SIZE.toFloat(), 1f)
        val varianceFactor = 1f - min(calculateVariance(bitrateHistory) / 100000f, 0.5f)
        
        return (sampleFactor * 0.5f + varianceFactor * 0.5f).coerceIn(0f, 1f)
    }

    private fun determineAction(qualityScore: Float, confidence: Float): RecommendedAction {
        // Thresholds for action
        val upgradeThreshold = 0.7f
        val downgradeThreshold = 0.35f
        
        return when {
            // High quality and confident - consider upgrade
            qualityScore > upgradeThreshold && confidence > CONFIDENCE_THRESHOLD -> {
                if (stableSamplesCount >= STABLE_SAMPLES_FOR_UPGRADE) {
                    RecommendedAction.UPGRADE
                } else {
                    RecommendedAction.MAINTAIN
                }
            }
            // Poor quality - downgrade quickly
            qualityScore < downgradeThreshold -> {
                if (degradedSamplesCount >= DEGRADED_SAMPLES_FOR_DOWNGRADE) {
                    RecommendedAction.DOWNGRADE
                } else {
                    RecommendedAction.MAINTAIN
                }
            }
            // Medium quality - maintain
            else -> RecommendedAction.MAINTAIN
        }
    }

    private fun updateState(action: RecommendedAction) {
        when (action) {
            RecommendedAction.UPGRADE -> {
                currentProfile = NetworkProfile.getNextHigherProfile(currentProfile)
                stableSamplesCount = 0
                degradedSamplesCount = 0
            }
            RecommendedAction.DOWNGRADE -> {
                currentProfile = NetworkProfile.getNextLowerProfile(currentProfile)
                stableSamplesCount = 0
                degradedSamplesCount = 0
            }
            RecommendedAction.MAINTAIN -> {
                // Track stability
                val lastScore = lastPrediction?.qualityScore ?: 0.5f
                if (lastScore > 0.6f) {
                    stableSamplesCount++
                    degradedSamplesCount = max(0, degradedSamplesCount - 1)
                } else if (lastScore < 0.4f) {
                    degradedSamplesCount++
                    stableSamplesCount = max(0, stableSamplesCount - 1)
                }
            }
        }
    }

    private fun getSuggestedProfile(action: RecommendedAction): NetworkProfile {
        return when (action) {
            RecommendedAction.UPGRADE -> NetworkProfile.getNextHigherProfile(currentProfile)
            RecommendedAction.DOWNGRADE -> NetworkProfile.getNextLowerProfile(currentProfile)
            RecommendedAction.MAINTAIN -> currentProfile
        }
    }

    private fun generateReasoning(
        features: PredictionFeatures,
        score: Float,
        action: RecommendedAction
    ): String {
        val reasons = mutableListOf<String>()
        
        if (features.avgLoss > 10) reasons.add("High packet loss (${features.avgLoss.toInt()}%)")
        if (features.avgRtt > 300) reasons.add("High latency (${features.avgRtt.toInt()}ms)")
        if (features.avgJitter > 50) reasons.add("High jitter (${features.avgJitter.toInt()}ms)")
        if (features.trend < -0.2) reasons.add("Degrading connection")
        if (features.trend > 0.2) reasons.add("Improving connection")
        if (features.avgBitrate < 100) reasons.add("Very low bandwidth")
        
        val actionText = when (action) {
            RecommendedAction.UPGRADE -> "Recommending quality upgrade"
            RecommendedAction.DOWNGRADE -> "Recommending quality reduction"
            RecommendedAction.MAINTAIN -> "Maintaining current quality"
        }
        
        return if (reasons.isEmpty()) {
            "$actionText (Score: ${(score * 100).toInt()}%)"
        } else {
            "$actionText: ${reasons.joinToString(", ")}"
        }
    }

    // ==================== Data Classes ====================

    data class ModelWeights(
        val bitrateWeight: Float,
        val lossWeight: Float,
        val rttWeight: Float,
        val jitterWeight: Float,
        val trendWeight: Float,
        val bias: Float
    )
}

/**
 * Input stats for prediction
 */
data class NetworkStats(
    val bitrateKbps: Int,
    val packetLossPercent: Float,
    val rttMs: Int,
    val jitterMs: Int,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Features extracted for ML model
 */
data class PredictionFeatures(
    val currentBitrate: Float,
    val avgBitrate: Float,
    val currentLoss: Float,
    val avgLoss: Float,
    val currentRtt: Float,
    val avgRtt: Float,
    val currentJitter: Float,
    val avgJitter: Float,
    val trend: Float,
    val bitrateVariance: Float,
    val lossVariance: Float
)

/**
 * Prediction result
 */
data class QualityPrediction(
    val qualityScore: Float,        // 0-1, higher = better
    val confidence: Float,          // 0-1, higher = more confident
    val recommendedAction: RecommendedAction,
    val currentProfile: NetworkProfile,
    val suggestedProfile: NetworkProfile,
    val features: PredictionFeatures,
    val reasoning: String
)

/**
 * Stats summary for display
 */
data class StatsSummary(
    val avgBitrate: Float,
    val avgLoss: Float,
    val avgRtt: Float,
    val avgJitter: Float,
    val trend: Float,
    val samplesCount: Int
)

/**
 * Recommended action from AI
 */
enum class RecommendedAction {
    UPGRADE,    // Increase quality (bandwidth allows)
    MAINTAIN,   // Keep current quality
    DOWNGRADE   // Reduce quality (bandwidth constrained)
}
