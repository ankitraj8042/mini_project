package com.yourapp.webrtcapp.ui.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.yourapp.webrtcapp.R
import java.text.SimpleDateFormat
import java.util.*

/**
 * Adapter for in-call chat messages
 */
class ChatAdapter : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    private val messages = mutableListOf<ChatMessage>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun getMessageCount(): Int = messages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        holder.bind(messages[position])
    }

    override fun getItemCount(): Int = messages.size

    inner class ChatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val sentContainer: LinearLayout = itemView.findViewById(R.id.sentContainer)
        private val sentMessage: TextView = itemView.findViewById(R.id.sentMessage)
        private val sentTime: TextView = itemView.findViewById(R.id.sentTime)
        
        private val receivedContainer: LinearLayout = itemView.findViewById(R.id.receivedContainer)
        private val receivedMessage: TextView = itemView.findViewById(R.id.receivedMessage)
        private val receivedTime: TextView = itemView.findViewById(R.id.receivedTime)

        fun bind(message: ChatMessage) {
            val formattedTime = timeFormat.format(Date(message.timestamp))
            
            if (message.isSent) {
                sentContainer.visibility = View.VISIBLE
                receivedContainer.visibility = View.GONE
                sentMessage.text = message.text
                sentTime.text = formattedTime
            } else {
                sentContainer.visibility = View.GONE
                receivedContainer.visibility = View.VISIBLE
                receivedMessage.text = message.text
                receivedTime.text = formattedTime
            }
        }
    }
}

/**
 * Data class for chat messages
 */
data class ChatMessage(
    val text: String,
    val isSent: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)
