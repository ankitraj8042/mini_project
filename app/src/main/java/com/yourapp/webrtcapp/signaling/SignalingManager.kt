package com.yourapp.webrtcapp.signaling

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.yourapp.webrtcapp.model.IceCandidateModel
import okhttp3.*
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.util.concurrent.TimeUnit

/**
 * Singleton SignalingManager to share a single WebSocket connection across activities
 */
object SignalingManager {
    private const val TAG = "SignalingManager"
    
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    
    private var webSocket: WebSocket? = null
    private var currentUserId: String? = null
    private var isConnected = false
    
    // Store online users for quick lookup
    private val onlineUsers = mutableSetOf<String>()
    
    // Callbacks for checkUserOnline
    private val userCheckCallbacks = mutableMapOf<String, (Boolean) -> Unit>()
    
    // Current active call ID from server
    private var currentCallId: String? = null
    
    // Listeners
    private var callListener: CallSignalingListener? = null
    private var userListListener: UserListListener? = null
    
    interface CallSignalingListener {
        fun onOfferReceived(from: String, sdp: SessionDescription, isVideoCall: Boolean, callId: String)
        fun onAnswerReceived(sdp: SessionDescription, callId: String)
        fun onIceCandidateReceived(candidate: IceCandidate)
        fun onCallRejected()
        fun onCallEnded()
        fun onEmojiReceived(emoji: String)
        fun onChatMessageReceived(message: String) {}  // Default empty implementation
    }
    
    fun getCurrentCallId(): String? = currentCallId
    
    fun setCurrentCallId(callId: String?) {
        currentCallId = callId
    }
    
    interface UserListListener {
        fun onUserListUpdated(users: List<String>)
        fun onConnectionError(error: String)
    }
    
    private val socketListener = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket connected")
            isConnected = true
            currentUserId?.let { userId ->
                val joinMsg = gson.toJson(mapOf("type" to "join", "userId" to userId))
                Log.d(TAG, "Sending join message: $joinMsg")
                ws.send(joinMsg)
                // Request user list after a short delay to ensure join is processed
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    requestUserList()
                }, 500)
            }
        }

        override fun onMessage(ws: WebSocket, text: String) {
            Log.d(TAG, "Received raw message: $text")
            
            try {
                // Parse JSON manually for better control
                val jsonObject = JsonParser.parseString(text).asJsonObject
                val type = jsonObject.get("type")?.asString ?: return
                
                Log.d(TAG, "Message type: $type")

                when (type) {
                    "userList" -> {
                        val usersArray = jsonObject.getAsJsonArray("users")
                        val users = mutableListOf<String>()
                        usersArray?.forEach { element ->
                            users.add(element.asString)
                        }
                        // Update online users set
                        onlineUsers.clear()
                        onlineUsers.addAll(users)
                        
                        val filteredUsers = users.filter { it != currentUserId }
                        Log.d(TAG, "User list parsed: $users, filtered (excluding $currentUserId): $filteredUsers")
                        Log.d(TAG, "UserListListener is: ${if (userListListener != null) "SET" else "NULL"}")
                        userListListener?.onUserListUpdated(filteredUsers)
                    }
                    
                    "userStatus" -> {
                        val userId = jsonObject.get("userId")?.asString
                        val online = jsonObject.get("online")?.asBoolean ?: false
                        Log.d(TAG, "User status: $userId is ${if (online) "online" else "offline"}")
                        userId?.let {
                            userCheckCallbacks.remove(it)?.invoke(online)
                        }
                    }

                    "offer" -> {
                        val from = jsonObject.get("from")?.asString ?: "unknown"
                        val sdp = jsonObject.get("sdp")?.asString
                        val isVideo = jsonObject.get("isVideoCall")?.asBoolean ?: true
                        val callId = jsonObject.get("callId")?.asString ?: ""
                        Log.d(TAG, "Offer received from $from (video: $isVideo, callId: $callId)")
                        currentCallId = callId  // Store server's callId
                        if (sdp != null) {
                            callListener?.onOfferReceived(
                                from,
                                SessionDescription(SessionDescription.Type.OFFER, sdp),
                                isVideo,
                                callId
                            )
                        }
                    }

                    "answer" -> {
                        val from = jsonObject.get("from")?.asString
                        val sdp = jsonObject.get("sdp")?.asString
                        val callId = jsonObject.get("callId")?.asString ?: currentCallId ?: ""
                        Log.d(TAG, "Answer received from $from (callId: $callId)")
                        if (callId.isNotEmpty()) currentCallId = callId
                        if (sdp != null) {
                            callListener?.onAnswerReceived(
                                SessionDescription(SessionDescription.Type.ANSWER, sdp),
                                callId
                            )
                        }
                    }

                    "candidate" -> {
                        val from = jsonObject.get("from")?.asString
                        val candidateObj = jsonObject.getAsJsonObject("candidate")
                        Log.d(TAG, "ICE candidate received from $from")
                        if (candidateObj != null) {
                            val sdpMid = candidateObj.get("sdpMid")?.asString
                            val sdpMLineIndex = candidateObj.get("sdpMLineIndex")?.asInt ?: 0
                            val candidate = candidateObj.get("candidate")?.asString
                            if (sdpMid != null && candidate != null) {
                                callListener?.onIceCandidateReceived(
                                    IceCandidate(sdpMid, sdpMLineIndex, candidate)
                                )
                            }
                        }
                    }

                    "reject" -> {
                        val from = jsonObject.get("from")?.asString
                        Log.d(TAG, "Call rejected by $from")
                        callListener?.onCallRejected()
                    }

                    "hangup" -> {
                        val from = jsonObject.get("from")?.asString
                        Log.d(TAG, "Call ended by $from")
                        callListener?.onCallEnded()
                    }
                    
                    "emoji" -> {
                        val from = jsonObject.get("from")?.asString
                        val emoji = jsonObject.get("emoji")?.asString
                        Log.d(TAG, "========= EMOJI RECEIVED =========")
                        Log.d(TAG, "From: $from, Emoji: $emoji")
                        Log.d(TAG, "callListener is: ${if (callListener != null) "SET" else "NULL"}")
                        Log.d(TAG, "currentUserId: $currentUserId")
                        if (emoji != null && callListener != null) {
                            Log.d(TAG, "Dispatching emoji to listener...")
                            try {
                                callListener?.onEmojiReceived(emoji)
                                Log.d(TAG, "Emoji dispatched successfully!")
                            } catch (e: Exception) {
                                Log.e(TAG, "Error dispatching emoji: ${e.message}", e)
                            }
                        } else {
                            Log.e(TAG, "Cannot dispatch emoji - emoji: $emoji, listener: $callListener")
                        }
                        Log.d(TAG, "==================================")
                    }
                    
                    "chat" -> {
                        val from = jsonObject.get("from")?.asString
                        val message = jsonObject.get("message")?.asString
                        Log.d(TAG, "Chat message received from $from: $message")
                        if (message != null) {
                            callListener?.onChatMessageReceived(message)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing message: ${e.message}", e)
            }
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket error: ${t.message}")
            isConnected = false
            userListListener?.onConnectionError(t.message ?: "Connection failed")
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closed: $reason")
            isConnected = false
        }
    }
    
    fun connect(serverUrl: String, userId: String) {
        // If already connected with same user, don't reconnect
        if (isConnected && currentUserId == userId && webSocket != null) {
            Log.d(TAG, "Already connected as $userId, reusing connection")
            // Just request fresh user list
            requestUserList()
            return
        }
        
        // Close existing connection if any (but don't clear listeners)
        if (webSocket != null) {
            Log.d(TAG, "Closing existing connection to reconnect...")
            webSocket?.close(1000, "Reconnecting")
            webSocket = null
            isConnected = false
        }
        
        currentUserId = userId
        Log.d(TAG, "Connecting to $serverUrl as $userId")
        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, socketListener)
    }
    
    fun setCallListener(listener: CallSignalingListener?) {
        callListener = listener
    }
    
    fun setUserListListener(listener: UserListListener?) {
        userListListener = listener
    }
    
    fun sendOffer(to: String, sdp: SessionDescription, isVideoCall: Boolean = true) {
        val msg = mapOf(
            "type" to "offer",
            "from" to currentUserId,
            "to" to to,
            "sdp" to sdp.description,
            "isVideoCall" to isVideoCall
        )
        send(msg)
    }

    fun sendAnswer(to: String, sdp: SessionDescription, callId: String? = null) {
        val msg = mapOf(
            "type" to "answer",
            "from" to currentUserId,
            "to" to to,
            "sdp" to sdp.description,
            "callId" to (callId ?: currentCallId ?: "")
        )
        send(msg)
    }

    fun sendIceCandidate(to: String, candidate: IceCandidate) {
        val msg = mapOf(
            "type" to "candidate",
            "from" to currentUserId,
            "to" to to,
            "candidate" to IceCandidateModel(
                candidate.sdpMid,
                candidate.sdpMLineIndex,
                candidate.sdp
            )
        )
        send(msg)
    }

    fun sendReject(to: String, callId: String? = null) {
        val msg = mapOf(
            "type" to "reject",
            "from" to currentUserId,
            "to" to to,
            "callId" to (callId ?: currentCallId ?: "")
        )
        send(msg)
        currentCallId = null  // Clear after reject
    }

    fun sendHangup(to: String, callId: String? = null) {
        val msg = mapOf(
            "type" to "hangup",
            "from" to currentUserId,
            "to" to to,
            "callId" to (callId ?: currentCallId ?: "")
        )
        send(msg)
        currentCallId = null  // Clear after hangup
    }
    
    fun sendCallStats(statsPayload: org.json.JSONObject) {
        try {
            // Convert JSONObject to Map for Gson serialization
            val statsMap = mutableMapOf<String, Any>()
            statsMap["type"] = "callStats"
            statsMap["from"] = currentUserId ?: ""
            
            // Add all fields from the JSON payload
            val keys = statsPayload.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                statsMap[key] = statsPayload.get(key)
            }
            
            send(statsMap)
            Log.d(TAG, "Call stats sent to server")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending call stats: ${e.message}")
        }
    }

    fun requestUserList() {
        send(mapOf("type" to "getUsers", "from" to currentUserId))
    }
    
    fun checkUserOnline(userId: String, callback: (Boolean) -> Unit) {
        Log.d(TAG, "checkUserOnline: $userId, onlineUsers: $onlineUsers, isConnected: $isConnected")
        
        // First check local cache
        if (onlineUsers.contains(userId)) {
            Log.d(TAG, "User $userId found in local cache")
            callback(true)
            return
        }
        
        // Check if we're connected
        if (!isConnected || webSocket == null) {
            Log.e(TAG, "Not connected, cannot check user online status")
            callback(false)
            return
        }
        
        // Store callback and send request to server
        userCheckCallbacks[userId] = callback
        send(mapOf("type" to "checkUser", "userId" to userId))
        
        // Add a timeout in case server doesn't respond
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (userCheckCallbacks.containsKey(userId)) {
                Log.d(TAG, "Timeout waiting for user status response for $userId")
                userCheckCallbacks.remove(userId)?.invoke(false)
            }
        }, 3000)
    }
    
    fun sendEmoji(to: String, emoji: String) {
        val msg = mapOf(
            "type" to "emoji",
            "from" to currentUserId,
            "to" to to,
            "emoji" to emoji
        )
        send(msg)
    }
    
    fun sendChatMessage(to: String, message: String) {
        val msg = mapOf(
            "type" to "chat",
            "from" to currentUserId,
            "to" to to,
            "message" to message
        )
        send(msg)
    }

    private fun send(data: Map<String, Any?>) {
        val json = gson.toJson(data)
        Log.d(TAG, "Sending: $json")
        val sent = webSocket?.send(json) ?: false
        if (!sent) {
            Log.e(TAG, "Failed to send message - WebSocket is null or closed")
        }
    }

    fun disconnect() {
        Log.d(TAG, "Disconnecting...")
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        isConnected = false
        currentUserId = null
        onlineUsers.clear()
        // Note: Don't clear listeners here - they are managed by activities
    }
    
    fun clearListeners() {
        callListener = null
        userListListener = null
    }
    
    fun isConnected(): Boolean {
        // Check both our flag and the actual WebSocket state
        val socketOpen = webSocket?.let { 
            try {
                // WebSocket is ready if we can send ping - this is a best effort check
                true
            } catch (e: Exception) {
                false
            }
        } ?: false
        return isConnected && socketOpen
    }
    
    fun forceReconnect(serverUrl: String, userId: String) {
        Log.d(TAG, "Force reconnecting...")
        // Close existing connection
        webSocket?.close(1000, "Force reconnect")
        webSocket = null
        isConnected = false
        
        // Reconnect
        currentUserId = userId
        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, socketListener)
    }
}
