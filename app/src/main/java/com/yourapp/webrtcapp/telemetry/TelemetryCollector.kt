package com.yourapp.webrtcapp.telemetry

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.yourapp.webrtcapp.ai.NetworkStats
import com.yourapp.webrtcapp.ai.NetworkProfile
import com.yourapp.webrtcapp.ai.NetworkType
import com.yourapp.webrtcapp.ai.QualityPrediction
import com.yourapp.webrtcapp.ai.RecommendedAction
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Telemetry Collector for ML Model Training
 * 
 * Collects anonymized network statistics during calls (opt-in only).
 * Data is stored locally and can be exported for model training.
 * 
 * Privacy-first design:
 * - No PII (phone numbers, IPs are hashed)
 * - User must explicitly opt-in
 * - Data stored locally until export
 * - User can delete all data anytime
 */
class TelemetryCollector private constructor(private val context: Context) {

    companion object {
        private const val TAG = "TelemetryCollector"
        private const val PREFS_NAME = "telemetry_prefs"
        private const val KEY_OPTED_IN = "telemetry_opted_in"
        private const val KEY_SESSION_COUNT = "session_count"
        private const val MAX_SAMPLES_PER_SESSION = 300  // ~5 min at 1 sample/sec
        private const val MAX_SESSIONS_STORED = 50
        
        @Volatile
        private var instance: TelemetryCollector? = null
        
        fun getInstance(context: Context): TelemetryCollector {
            return instance ?: synchronized(this) {
                instance ?: TelemetryCollector(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // Current session data
    private var currentSession: TelemetrySession? = null
    private val sessionSamples = mutableListOf<TelemetrySample>()
    
    /**
     * Check if user has opted into telemetry
     */
    fun isOptedIn(): Boolean = prefs.getBoolean(KEY_OPTED_IN, false)
    
    /**
     * Set opt-in status
     */
    fun setOptIn(optIn: Boolean) {
        prefs.edit().putBoolean(KEY_OPTED_IN, optIn).apply()
        Log.d(TAG, "Telemetry opt-in set to: $optIn")
        
        if (!optIn) {
            // Clear all data when user opts out
            deleteAllData()
        }
    }
    
    /**
     * Start a new telemetry session (call this when call starts)
     */
    fun startSession(
        networkType: NetworkType,
        initialProfile: NetworkProfile,
        isVideoCall: Boolean
    ) {
        if (!isOptedIn()) return
        
        val sessionId = UUID.randomUUID().toString().take(8)
        currentSession = TelemetrySession(
            sessionId = sessionId,
            startTime = System.currentTimeMillis(),
            networkType = networkType.name,
            initialProfile = initialProfile.name,
            isVideoCall = isVideoCall,
            deviceModel = android.os.Build.MODEL.hashCode().toString(),  // Anonymized
            androidVersion = android.os.Build.VERSION.SDK_INT
        )
        sessionSamples.clear()
        
        Log.d(TAG, "Started telemetry session: $sessionId")
    }
    
    /**
     * Record a telemetry sample (call this with each stats update)
     */
    fun recordSample(
        stats: NetworkStats,
        prediction: QualityPrediction?,
        currentProfile: NetworkProfile,
        userAction: UserAction? = null
    ) {
        if (!isOptedIn() || currentSession == null) return
        if (sessionSamples.size >= MAX_SAMPLES_PER_SESSION) return
        
        val sample = TelemetrySample(
            timestamp = System.currentTimeMillis(),
            bitrateKbps = stats.bitrateKbps,
            packetLossPercent = stats.packetLossPercent,
            rttMs = stats.rttMs,
            jitterMs = stats.jitterMs,
            qualityScore = prediction?.qualityScore ?: 0f,
            confidence = prediction?.confidence ?: 0f,
            recommendedAction = prediction?.recommendedAction?.name ?: "NONE",
            currentProfile = currentProfile.name,
            userAction = userAction?.name
        )
        
        sessionSamples.add(sample)
    }
    
    /**
     * Record when user takes an action (for supervised learning)
     */
    fun recordUserAction(action: UserAction, currentProfile: NetworkProfile) {
        if (!isOptedIn() || currentSession == null) return
        
        // Mark the last sample with this action
        if (sessionSamples.isNotEmpty()) {
            val lastSample = sessionSamples.last()
            sessionSamples[sessionSamples.lastIndex] = lastSample.copy(
                userAction = action.name
            )
        }
        
        Log.d(TAG, "Recorded user action: $action")
    }
    
    /**
     * End the current session and save data
     */
    fun endSession(callQualityRating: Int? = null) {
        if (!isOptedIn() || currentSession == null) return
        
        val session = currentSession!!.copy(
            endTime = System.currentTimeMillis(),
            sampleCount = sessionSamples.size,
            userRating = callQualityRating
        )
        
        // Save session to file
        saveSession(session, sessionSamples.toList())
        
        Log.d(TAG, "Ended telemetry session: ${session.sessionId}, samples: ${session.sampleCount}")
        
        currentSession = null
        sessionSamples.clear()
        
        // Increment session count
        val count = prefs.getInt(KEY_SESSION_COUNT, 0) + 1
        prefs.edit().putInt(KEY_SESSION_COUNT, count).apply()
        
        // Cleanup old sessions if needed
        cleanupOldSessions()
    }
    
    /**
     * Export all telemetry data as JSON (for ML training)
     */
    fun exportData(): String {
        val sessions = JSONArray()
        
        getTelemetryDir().listFiles()?.forEach { file ->
            if (file.name.endsWith(".json")) {
                try {
                    val content = file.readText()
                    sessions.put(JSONObject(content))
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading telemetry file: ${e.message}")
                }
            }
        }
        
        val export = JSONObject().apply {
            put("version", 1)
            put("exportTime", System.currentTimeMillis())
            put("sessionCount", sessions.length())
            put("sessions", sessions)
        }
        
        return export.toString(2)
    }
    
    /**
     * Get telemetry statistics
     */
    fun getStats(): TelemetryStats {
        val dir = getTelemetryDir()
        val files = dir.listFiles()?.filter { it.name.endsWith(".json") } ?: emptyList()
        
        var totalSamples = 0
        files.forEach { file ->
            try {
                val json = JSONObject(file.readText())
                totalSamples += json.optInt("sampleCount", 0)
            } catch (e: Exception) {
                // Ignore corrupt files
            }
        }
        
        return TelemetryStats(
            isOptedIn = isOptedIn(),
            sessionCount = files.size,
            totalSamples = totalSamples,
            storageUsedBytes = files.sumOf { it.length() }
        )
    }
    
    /**
     * Delete all telemetry data
     */
    fun deleteAllData() {
        getTelemetryDir().listFiles()?.forEach { it.delete() }
        prefs.edit().putInt(KEY_SESSION_COUNT, 0).apply()
        Log.d(TAG, "All telemetry data deleted")
    }
    
    // ==================== Private Methods ====================
    
    private fun getTelemetryDir(): File {
        val dir = File(context.filesDir, "telemetry")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
    
    private fun saveSession(session: TelemetrySession, samples: List<TelemetrySample>) {
        try {
            val json = JSONObject().apply {
                put("sessionId", session.sessionId)
                put("startTime", session.startTime)
                put("endTime", session.endTime)
                put("networkType", session.networkType)
                put("initialProfile", session.initialProfile)
                put("isVideoCall", session.isVideoCall)
                put("deviceModel", session.deviceModel)
                put("androidVersion", session.androidVersion)
                put("sampleCount", session.sampleCount)
                put("userRating", session.userRating)
                
                val samplesArray = JSONArray()
                samples.forEach { sample ->
                    samplesArray.put(JSONObject().apply {
                        put("ts", sample.timestamp)
                        put("br", sample.bitrateKbps)
                        put("loss", sample.packetLossPercent)
                        put("rtt", sample.rttMs)
                        put("jitter", sample.jitterMs)
                        put("score", sample.qualityScore)
                        put("conf", sample.confidence)
                        put("action", sample.recommendedAction)
                        put("profile", sample.currentProfile)
                        sample.userAction?.let { put("userAction", it) }
                    })
                }
                put("samples", samplesArray)
            }
            
            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val fileName = "session_${dateFormat.format(Date())}_${session.sessionId}.json"
            val file = File(getTelemetryDir(), fileName)
            file.writeText(json.toString())
            
            Log.d(TAG, "Saved telemetry to: ${file.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving telemetry: ${e.message}")
        }
    }
    
    private fun cleanupOldSessions() {
        val files = getTelemetryDir().listFiles()
            ?.filter { it.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        
        if (files.size > MAX_SESSIONS_STORED) {
            files.drop(MAX_SESSIONS_STORED).forEach { file ->
                file.delete()
                Log.d(TAG, "Deleted old telemetry: ${file.name}")
            }
        }
    }
}

/**
 * User actions for supervised learning labels
 */
enum class UserAction {
    ENABLED_AUDIO_ONLY,      // User switched to audio-only
    DISABLED_AUDIO_ONLY,     // User re-enabled video
    ENABLED_RURAL_MODE,      // User enabled rural mode
    DISABLED_RURAL_MODE,     // User disabled rural mode
    ENDED_CALL_EARLY,        // User ended call (possibly due to quality)
    RATED_GOOD,              // User rated call as good
    RATED_BAD                // User rated call as bad
}

/**
 * Telemetry session metadata
 */
data class TelemetrySession(
    val sessionId: String,
    val startTime: Long,
    val endTime: Long? = null,
    val networkType: String,
    val initialProfile: String,
    val isVideoCall: Boolean,
    val deviceModel: String,
    val androidVersion: Int,
    val sampleCount: Int = 0,
    val userRating: Int? = null
)

/**
 * Single telemetry sample
 */
data class TelemetrySample(
    val timestamp: Long,
    val bitrateKbps: Int,
    val packetLossPercent: Float,
    val rttMs: Int,
    val jitterMs: Int,
    val qualityScore: Float,
    val confidence: Float,
    val recommendedAction: String,
    val currentProfile: String,
    val userAction: String? = null
)

/**
 * Telemetry statistics
 */
data class TelemetryStats(
    val isOptedIn: Boolean,
    val sessionCount: Int,
    val totalSamples: Int,
    val storageUsedBytes: Long
)
