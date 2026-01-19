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
import androidx.cardview.widget.CardView
import com.yourapp.webrtcapp.R
import com.yourapp.webrtcapp.api.ApiClient
import com.yourapp.webrtcapp.api.CallStatsItem
import com.yourapp.webrtcapp.auth.AuthManager

/**
 * Statistics Activity - Shows call quality graphs
 * Loads real recorded data from MongoDB
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
    private lateinit var qualityCard: CardView
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
        qualityCard = findViewById(R.id.qualityCard)
        statsContainer = findViewById(R.id.statsContainer)
        progressBar = findViewById(R.id.progressBar)

        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }
    }

    private fun loadStats() {
        val callId = intent.getStringExtra("callId")
        val caller = intent.getStringExtra("caller") ?: ""
        val callee = intent.getStringExtra("callee") ?: ""
        
        Log.d(TAG, "Loading stats for callId: $callId, caller: $caller, callee: $callee")
        
        if (callId != null && callId.isNotEmpty()) {
            loadCallStatsFromApi(callId, caller, callee)
        } else {
            displayNoStats("No call ID provided")
        }
    }

    private fun loadCallStatsFromApi(callId: String, caller: String, callee: String) {
        showLoading(true)
        titleText.text = "📊 Loading..."
        
        Log.d(TAG, "Fetching stats from API for callId: $callId")
        
        apiClient.getCallStats(callId, { stats ->
            runOnUiThread {
                showLoading(false)
                Log.d(TAG, "Stats received: totalSamples=${stats.totalSamples}, samples=${stats.samples?.size ?: "null"}")
                Log.d(TAG, "Stats details: callId=${stats.callId}, duration=${stats.duration}, avgBitrate=${stats.avgSendBitrateKbps}")
                if (stats.samples != null && stats.samples.isNotEmpty()) {
                    Log.d(TAG, "First sample: ${stats.samples[0]}")
                }
                displayRealStats(stats, caller, callee)
            }
        }, { error ->
            runOnUiThread {
                showLoading(false)
                Log.e(TAG, "Error loading stats: $error")
                displayNoStats(error)
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
        
        // Extract sample data for graphs from the recorded samples
        val samples = stats.samples
        
        if (samples.isNullOrEmpty()) {
            Log.w(TAG, "No sample data in stats, using averages only")
            // No detailed samples - show message
            summaryText.text = "Duration: ${formatDuration(stats.duration)} | ${if (stats.isVideo) "Video" else "Audio"} Call"
            summaryText.visibility = View.VISIBLE
            
            // Show simple 2-point graphs with averages
            bitrateGraph.setData(
                listOf(stats.avgSendBitrateKbps.toFloat(), stats.avgReceiveBitrateKbps.toFloat()),
                "Avg Bitrate (kbps)",
                Color.parseColor("#4CAF50")
            )
            packetLossGraph.setData(
                listOf(stats.avgPacketLossPercent.toFloat()),
                "Avg Packet Loss (%)",
                Color.parseColor("#F44336")
            )
            rttGraph.setData(
                listOf(stats.avgRttMs.toFloat()),
                "Avg Latency (ms)",
                Color.parseColor("#2196F3")
            )
            qualityCard.visibility = View.GONE
            return
        }
        
        Log.d(TAG, "Displaying ${samples.size} recorded samples")
        
        // Build graph data from actual recorded samples
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
        
        // Brief summary line only
        summaryText.text = "Duration: ${formatDuration(stats.duration)} | ${samples.size} data points | ${if (stats.isVideo) "Video" else "Audio"}"
        summaryText.visibility = View.VISIBLE

        // Display graphs with real recorded data
        bitrateGraph.visibility = View.VISIBLE
        packetLossGraph.visibility = View.VISIBLE
        rttGraph.visibility = View.VISIBLE
        qualityCard.visibility = View.VISIBLE
        
        bitrateGraph.setData(bitrateData, "Bitrate (kbps)", Color.parseColor("#4CAF50"))
        packetLossGraph.setData(packetLossData, "Packet Loss (%)", Color.parseColor("#F44336"))
        rttGraph.setData(rttData, "Latency (ms)", Color.parseColor("#2196F3"))
        qualityGraph.setData(qualityData, "Network Quality", Color.parseColor("#FF9800"))
        
        Log.d(TAG, "Graphs displayed with ${samples.size} samples")
    }

    private fun displayNoStats(reason: String) {
        titleText.text = "📊 No Statistics"
        
        summaryText.text = "Could not load call statistics.\n\nReason: $reason\n\nStats are recorded during video calls and saved when the call ends properly."
        summaryText.visibility = View.VISIBLE

        // Hide all graphs
        bitrateGraph.visibility = View.GONE
        packetLossGraph.visibility = View.GONE
        rttGraph.visibility = View.GONE
        qualityCard.visibility = View.GONE
    }

    private fun formatDuration(seconds: Int): String {
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }
}
