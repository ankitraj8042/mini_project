package com.yourapp.webrtcapp.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.yourapp.webrtcapp.R
import com.yourapp.webrtcapp.api.CallHistoryItem
import java.text.SimpleDateFormat
import java.util.*

/**
 * Adapter for displaying call history (recents)
 */
class RecentsAdapter(
    private val onItemClick: (CallHistoryItem) -> Unit,
    private val onVideoCall: (String) -> Unit,
    private val onAudioCall: (String) -> Unit,
    private val onStatsClick: (CallHistoryItem) -> Unit = {}  // New callback for stats
) : RecyclerView.Adapter<RecentsAdapter.ViewHolder>() {

    private val items = mutableListOf<CallHistoryItem>()
    private var currentPhone: String = ""

    fun setCurrentPhone(phone: String) {
        currentPhone = phone
    }

    fun submitList(newItems: List<CallHistoryItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recent_call, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val callIcon: ImageView = itemView.findViewById(R.id.callIcon)
        private val phoneText: TextView = itemView.findViewById(R.id.phoneText)
        private val callTypeText: TextView = itemView.findViewById(R.id.callTypeText)
        private val timeText: TextView = itemView.findViewById(R.id.timeText)
        private val durationText: TextView = itemView.findViewById(R.id.durationText)
        private val videoCallBtn: ImageButton = itemView.findViewById(R.id.videoCallBtn)
        private val audioCallBtn: ImageButton = itemView.findViewById(R.id.audioCallBtn)
        private val statsBtn: ImageButton = itemView.findViewById(R.id.statsBtn)

        fun bind(item: CallHistoryItem) {
            // Determine if this was incoming or outgoing
            val isOutgoing = item.caller == currentPhone
            val otherParty = if (isOutgoing) item.callee else item.caller
            
            phoneText.text = otherParty
            
            // Set call type icon and text
            val (iconRes, typeText, color) = when {
                item.status == "missed" -> Triple(
                    R.drawable.ic_call_missed,
                    "Missed ${if (item.isVideo) "video" else "audio"} call",
                    android.graphics.Color.RED
                )
                isOutgoing -> Triple(
                    R.drawable.ic_call_made,
                    "Outgoing ${if (item.isVideo) "video" else "audio"} call",
                    android.graphics.Color.parseColor("#4CAF50")
                )
                else -> Triple(
                    R.drawable.ic_call_received,
                    "Incoming ${if (item.isVideo) "video" else "audio"} call",
                    android.graphics.Color.parseColor("#2196F3")
                )
            }
            
            callIcon.setImageResource(iconRes)
            callIcon.setColorFilter(color)
            callTypeText.text = typeText
            
            // Format time - parse ISO date string
            val timestamp = try {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()).parse(item.startTime)?.time ?: 0L
            } catch (e: Exception) { 0L }
            timeText.text = formatTime(timestamp)
            
            // Format duration
            durationText.text = formatDuration(item.duration)
            
            // Show stats button for all video calls (completed or missed)
            // For completed calls: shows real stats from database
            // For missed/short calls: shows demo stats for demonstration
            if (item.isVideo) {
                statsBtn.visibility = View.VISIBLE
                statsBtn.setOnClickListener { onStatsClick(item) }
            } else {
                statsBtn.visibility = View.GONE
            }
            
            // Click handlers
            videoCallBtn.setOnClickListener { onVideoCall(otherParty) }
            audioCallBtn.setOnClickListener { onAudioCall(otherParty) }
            itemView.setOnClickListener { onItemClick(item) }
        }
        
        private fun formatTime(timestamp: Long): String {
            val now = System.currentTimeMillis()
            val diff = now - timestamp
            
            return when {
                diff < 60_000 -> "Just now"
                diff < 3600_000 -> "${diff / 60_000} min ago"
                diff < 86400_000 -> SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp))
                diff < 604800_000 -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(timestamp))
                else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
            }
        }
        
        private fun formatDuration(seconds: Int): String {
            return when {
                seconds < 60 -> "${seconds}s"
                seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
                else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
            }
        }
    }
}
