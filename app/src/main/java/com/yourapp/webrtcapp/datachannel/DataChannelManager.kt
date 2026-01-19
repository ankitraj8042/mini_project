package com.yourapp.webrtcapp.datachannel

import android.util.Log
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.PeerConnection
import java.nio.ByteBuffer
import java.nio.charset.Charset

/**
 * DataChannel Manager for low-bandwidth data communication
 * 
 * Enables peer-to-peer messaging, telemetry, and control signals
 * independent of audio/video streams. Critical for:
 * - Text messaging during calls (works even when video is disabled)
 * - AI policy synchronization between peers
 * - Network telemetry sharing
 * - Quick reactions/emojis with minimal bandwidth
 */
class DataChannelManager(
    private val peerConnection: PeerConnection,
    private val listener: DataChannelListener
) {
    companion object {
        private const val TAG = "DataChannelManager"
        private const val CHANNEL_NAME = "adaptive-data"
        
        // Message types
        const val MSG_TYPE_TEXT = "text"
        const val MSG_TYPE_EMOJI = "emoji"
        const val MSG_TYPE_TELEMETRY = "telemetry"
        const val MSG_TYPE_CONTROL = "control"
        const val MSG_TYPE_PROFILE_SYNC = "profile_sync"
    }
    
    private var dataChannel: DataChannel? = null
    private var isOpen = false
    
    /**
     * Create and initialize the data channel (caller side)
     */
    fun createDataChannel() {
        Log.d(TAG, "Creating data channel: $CHANNEL_NAME")
        
        val config = DataChannel.Init().apply {
            ordered = true          // Guarantee order for control messages
            maxRetransmits = 3      // Retry up to 3 times (low bandwidth friendly)
            negotiated = false
        }
        
        dataChannel = peerConnection.createDataChannel(CHANNEL_NAME, config)
        dataChannel?.registerObserver(createObserver())
        
        Log.d(TAG, "Data channel created: ${dataChannel?.label()}")
    }
    
    /**
     * Accept incoming data channel (callee side)
     */
    fun acceptDataChannel(channel: DataChannel) {
        Log.d(TAG, "Accepting data channel: ${channel.label()}")
        dataChannel = channel
        dataChannel?.registerObserver(createObserver())
    }
    
    /**
     * Send a text message
     */
    fun sendTextMessage(text: String): Boolean {
        return sendMessage(MSG_TYPE_TEXT, JSONObject().apply {
            put("text", text)
            put("timestamp", System.currentTimeMillis())
        })
    }
    
    /**
     * Send an emoji reaction
     */
    fun sendEmoji(emoji: String): Boolean {
        return sendMessage(MSG_TYPE_EMOJI, JSONObject().apply {
            put("emoji", emoji)
            put("timestamp", System.currentTimeMillis())
        })
    }
    
    /**
     * Send network telemetry (for AI sync between peers)
     */
    fun sendTelemetry(
        bitrateKbps: Int,
        packetLoss: Float,
        rttMs: Int,
        qualityScore: Float
    ): Boolean {
        return sendMessage(MSG_TYPE_TELEMETRY, JSONObject().apply {
            put("bitrate", bitrateKbps)
            put("loss", packetLoss)
            put("rtt", rttMs)
            put("quality", qualityScore)
            put("timestamp", System.currentTimeMillis())
        })
    }
    
    /**
     * Send a control message
     */
    fun sendControl(action: String, params: Map<String, Any> = emptyMap()): Boolean {
        return sendMessage(MSG_TYPE_CONTROL, JSONObject().apply {
            put("action", action)
            params.forEach { (key, value) -> put(key, value) }
            put("timestamp", System.currentTimeMillis())
        })
    }
    
    /**
     * Send current AI profile selection (for peer synchronization)
     */
    fun sendProfileSync(profileName: String, qualityScore: Float): Boolean {
        return sendMessage(MSG_TYPE_PROFILE_SYNC, JSONObject().apply {
            put("profile", profileName)
            put("quality", qualityScore)
            put("timestamp", System.currentTimeMillis())
        })
    }
    
    /**
     * Generic send message
     */
    private fun sendMessage(type: String, payload: JSONObject): Boolean {
        if (!isOpen || dataChannel == null) {
            Log.w(TAG, "Cannot send message: channel not open")
            return false
        }
        
        try {
            val message = JSONObject().apply {
                put("type", type)
                put("payload", payload)
            }
            
            val data = message.toString().toByteArray(Charset.forName("UTF-8"))
            val buffer = DataChannel.Buffer(ByteBuffer.wrap(data), false)
            
            val sent = dataChannel?.send(buffer) == true
            if (sent) {
                Log.d(TAG, "Sent $type message: ${data.size} bytes")
            } else {
                Log.w(TAG, "Failed to send $type message")
            }
            return sent
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message: ${e.message}")
            return false
        }
    }
    
    /**
     * Create data channel observer
     */
    private fun createObserver(): DataChannel.Observer {
        return object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {
                Log.v(TAG, "Buffered amount: $previousAmount -> ${dataChannel?.bufferedAmount()}")
            }
            
            override fun onStateChange() {
                val state = dataChannel?.state()
                Log.d(TAG, "Data channel state: $state")
                
                isOpen = state == DataChannel.State.OPEN
                
                when (state) {
                    DataChannel.State.OPEN -> listener.onDataChannelOpen()
                    DataChannel.State.CLOSED -> listener.onDataChannelClosed()
                    else -> {}
                }
            }
            
            override fun onMessage(buffer: DataChannel.Buffer) {
                try {
                    val data = ByteArray(buffer.data.remaining())
                    buffer.data.get(data)
                    val messageStr = String(data, Charset.forName("UTF-8"))
                    
                    Log.d(TAG, "Received message: ${data.size} bytes")
                    
                    val json = JSONObject(messageStr)
                    val type = json.getString("type")
                    val payload = json.getJSONObject("payload")
                    
                    when (type) {
                        MSG_TYPE_TEXT -> {
                            val text = payload.getString("text")
                            listener.onTextMessageReceived(text)
                        }
                        MSG_TYPE_EMOJI -> {
                            val emoji = payload.getString("emoji")
                            listener.onEmojiReceived(emoji)
                        }
                        MSG_TYPE_TELEMETRY -> {
                            listener.onTelemetryReceived(
                                bitrateKbps = payload.getInt("bitrate"),
                                packetLoss = payload.getDouble("loss").toFloat(),
                                rttMs = payload.getInt("rtt"),
                                qualityScore = payload.getDouble("quality").toFloat()
                            )
                        }
                        MSG_TYPE_CONTROL -> {
                            val action = payload.getString("action")
                            listener.onControlReceived(action, payload)
                        }
                        MSG_TYPE_PROFILE_SYNC -> {
                            val profile = payload.getString("profile")
                            val quality = payload.getDouble("quality").toFloat()
                            listener.onProfileSyncReceived(profile, quality)
                        }
                        else -> {
                            Log.w(TAG, "Unknown message type: $type")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing message: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Check if channel is ready
     */
    fun isReady(): Boolean = isOpen
    
    /**
     * Close the data channel
     */
    fun close() {
        Log.d(TAG, "Closing data channel")
        dataChannel?.close()
        dataChannel = null
        isOpen = false
    }
    
    /**
     * Listener interface for data channel events
     */
    interface DataChannelListener {
        fun onDataChannelOpen()
        fun onDataChannelClosed()
        fun onTextMessageReceived(text: String)
        fun onEmojiReceived(emoji: String)
        fun onTelemetryReceived(bitrateKbps: Int, packetLoss: Float, rttMs: Int, qualityScore: Float)
        fun onControlReceived(action: String, params: JSONObject)
        fun onProfileSyncReceived(profile: String, qualityScore: Float)
    }
}
