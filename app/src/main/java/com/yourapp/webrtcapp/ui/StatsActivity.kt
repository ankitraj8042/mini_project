package com.yourapp.webrtcapp.ui

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.yourapp.webrtcapp.R
import com.yourapp.webrtcapp.api.ApiClient
import com.yourapp.webrtcapp.api.CallStatsItem
import com.yourapp.webrtcapp.auth.AuthManager

/**
 * Statistics Activity - Shows REAL call quality metrics and graphs
 * Loads data from MongoDB via API for actual call statistics
 */
class StatsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "StatsActivity"
    }

    private lateinit var titleText: TextView
    private lateinit var summaryText: TextView
    private lateinit var bitrateGraph: GraphView
    private lateinit var packetLossGraph: GraphView
    private lateinit var rttGraph: GraphView
    private lateinit var qualityGraph: GraphView
    private lateinit var statsContainer: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var apiClient: ApiClient
    private lateinit var authManager: AuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)

        // Initialize API client
        authManager = AuthManager.getInstance(this)
        apiClient = authManager.getApiClient()

        initViews()
        loadStats()
    }

    private fun initViews() {
        titleText = findViewById(R.id.titleText)
        summaryText = findViewById(R.id.summaryText)
        bitrateGraph = findViewById(R.id.bitrateGraph)
        packetLossGraph = findViewById(R.id.packetLossGraph)
        rttGraph = findViewById(R.id.rttGraph)
        qualityGraph = findViewById(R.id.qualityGraph)
        statsContainer = findViewById(R.id.statsContainer)
        progressBar = findViewById(R.id.progressBar)

        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }
    }

    private fun loadStats() {
        val callId = intent.getStringExtra("callId")
        val caller = intent.getStringExtra("caller") ?: ""
        val callee = intent.getStringExtra("callee") ?: ""
        
        if (callId != null) {
            // Load stats for a specific call from API
            loadCallStatsFromApi(callId, caller, callee)
        } else {
            // No specific call - show no data message
            displayNoStats()
        }
    }

    private fun loadCallStatsFromApi(callId: String, caller: String, callee: String) {
        showLoading(true)
        titleText.text = "📊 Loading Call Statistics..."
        
        Log.d(TAG, "Loading stats for callId: $callId")
        
        apiClient.getCallStats(callId, { stats ->
            runOnUiThread {
                showLoading(false)
                displayRealStats(stats, caller, callee)
            }
        }, { error ->
            runOnUiThread {
                showLoading(false)
                Log.e(TAG, "Error loading stats: $error")
                Toast.makeText(this, "Could not load stats: $error", Toast.LENGTH_SHORT).show()
                // Show no data message
                displayNoStats()
            }
        })
    }

    private fun showLoading(show: Boolean) {
        progressBar.visibility = if (show) View.VISIBLE else View.GONE
        statsContainer.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun displayRealStats(stats: CallStatsItem, caller: String, callee: String) {
        val otherParty = if (caller == authManager.getPhoneNumber()) callee else caller
        
        titleText.text = "📊 Call with $otherParty"
        
        // Extract sample data for graphs
        val samples = stats.samples ?: emptyList()
        
        val bitrateData = samples.map { (it.sendBitrateKbps + it.receiveBitrateKbps).toFloat() }
        val packetLossData = samples.map { it.packetLossPercent.toFloat() }
        val rttData = samples.map { it.rttMs.toFloat() }
        val qualityData = samples.map { 
            when (it.networkQuality) {
                "GOOD" -> 100f
                "MODERATE" -> 60f
                "POOR" -> 30f
                else -> 50f
            }
        }

        // Calculate quality distribution percentages
        val totalSamples = stats.totalSamples.coerceAtLeast(1)
        val goodPercent = ((stats.qualityDistribution?.good ?: 0) * 100.0 / totalSamples)
        val moderatePercent = ((stats.qualityDistribution?.moderate ?: 0) * 100.0 / totalSamples)
        val poorPercent = ((stats.qualityDistribution?.poor ?: 0) * 100.0 / totalSamples)
        
        // Data usage in MB
        val dataUsedMB = stats.totalDataUsedBytes / (1024.0 * 1024.0)

        summaryText.text = """
            📈 REAL Call Statistics (from MongoDB):
            
            📞 Call Details:
            ▸ Duration: ${formatDuration(stats.duration)}
            ▸ Type: ${if (stats.isVideo) "Video" else "Audio"} Call
            ▸ Data Used: ${"%.2f".format(dataUsedMB)} MB
            ▸ Total Samples: ${stats.totalSamples}
            
            📊 Average Metrics:
            ▸ Send Bitrate: ${stats.avgSendBitrateKbps.toInt()} kbps
            ▸ Receive Bitrate: ${stats.avgReceiveBitrateKbps.toInt()} kbps
            ▸ Packet Loss: ${"%.2f".format(stats.avgPacketLossPercent)}%
            ▸ RTT (Latency): ${stats.avgRttMs.toInt()} ms
            
            🎯 Quality Distribution:
            ▸ ✅ Good: ${"%.1f".format(goodPercent)}%
            ▸ ⚠️ Moderate: ${"%.1f".format(moderatePercent)}%
            ▸ ❌ Poor: ${"%.1f".format(poorPercent)}%
        """.trimIndent()

        // Display graphs with real data
        if (bitrateData.isNotEmpty()) {
            bitrateGraph.setData(bitrateData, "Total Bitrate (kbps)", Color.parseColor("#4CAF50"))
            packetLossGraph.setData(packetLossData, "Packet Loss (%)", Color.parseColor("#F44336"))
            rttGraph.setData(rttData, "RTT (ms)", Color.parseColor("#2196F3"))
            qualityGraph.setData(qualityData, "Quality Score", Color.parseColor("#FF9800"))
        } else {
            // No sample data, show summary only
            bitrateGraph.setData(listOf(stats.avgSendBitrateKbps.toFloat(), stats.avgReceiveBitrateKbps.toFloat()), 
                "Bitrate (kbps)", Color.parseColor("#4CAF50"))
        }
        
        Log.d(TAG, "Displayed real stats: ${stats.totalSamples} samples, ${stats.duration}s duration")
    }

    private fun displayNoStats() {
        titleText.text = "📊 No Statistics Available"
        
        summaryText.text = """
            ⚠️ No call statistics found for this call.
            
            This could happen because:
            
            1. The call was not connected (missed/rejected)
            2. The call was too short to collect stats
            3. Stats could not be saved to the server
            
            📞 To see real statistics:
            ▸ Make a video call that lasts at least 10 seconds
            ▸ Make sure both parties are connected
            ▸ End the call properly using the End button
            
            Statistics are collected every second during 
            connected calls and saved when the call ends.
        """.trimIndent()

        // Hide graphs when no data
        bitrateGraph.visibility = View.GONE
        packetLossGraph.visibility = View.GONE
        rttGraph.visibility = View.GONE
        qualityGraph.visibility = View.GONE
    }

    private fun formatDuration(seconds: Int): String {
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }
}
