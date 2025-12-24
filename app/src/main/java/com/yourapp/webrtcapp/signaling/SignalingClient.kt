package com.yourapp.webrtcapp.signaling

import android.util.Log
import com.google.gson.Gson
import com.yourapp.webrtcapp.model.IceCandidateModel
import okhttp3.*
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.util.concurrent.TimeUnit

class SignalingClient(
    serverUrl: String,
    private val userId: String,
    private val listener: SignalingListener
) {
    companion object {
        private const val TAG = "SignalingClient"
    }

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    private var webSocket: WebSocket? = null
    private var isConnected = false

    private val socketListener = object : WebSocketListener() {

        override fun onOpen(ws: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket connected")
            isConnected = true
            ws.send(gson.toJson(mapOf("type" to "join", "userId" to userId)))
        }

        override fun onMessage(ws: WebSocket, text: String) {
            Log.d(TAG, "Received: $text")
            
            try {
                val msg = gson.fromJson(text, SignalingMessage::class.java)

                when (msg.type) {
                    "userList" -> {
                        val users = msg.users ?: emptyList()
                        listener.onUserListUpdated(users.filter { it != userId })
                    }

                    "offer" -> {
                        Log.d(TAG, "Offer received from ${msg.from}")
                        listener.onOfferReceived(
                            msg.from ?: "unknown",
                            SessionDescription(SessionDescription.Type.OFFER, msg.sdp!!)
                        )
                    }

                    "answer" -> {
                        Log.d(TAG, "Answer received from ${msg.from}")
                        listener.onAnswerReceived(
                            SessionDescription(SessionDescription.Type.ANSWER, msg.sdp!!)
                        )
                    }

                    "candidate" -> {
                        val c = msg.candidate!!
                        listener.onIceCandidateReceived(
                            IceCandidate(c.sdpMid, c.sdpMLineIndex, c.candidate)
                        )
                    }

                    "reject" -> {
                        Log.d(TAG, "Call rejected by ${msg.from}")
                        listener.onCallRejected()
                    }

                    "hangup" -> {
                        Log.d(TAG, "Call ended by ${msg.from}")
                        listener.onCallEnded()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing message: ${e.message}")
            }
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket error: ${t.message}")
            isConnected = false
            listener.onConnectionError(t.message ?: "Connection failed")
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closed: $reason")
            isConnected = false
        }
    }

    init {
        Log.d(TAG, "Connecting to $serverUrl as $userId")
        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, socketListener)
    }

    fun sendOffer(to: String, sdp: SessionDescription) {
        val msg = mapOf(
            "type" to "offer",
            "from" to userId,
            "to" to to,
            "sdp" to sdp.description
        )
        send(msg)
    }

    fun sendAnswer(to: String, sdp: SessionDescription) {
        val msg = mapOf(
            "type" to "answer",
            "from" to userId,
            "to" to to,
            "sdp" to sdp.description
        )
        send(msg)
    }

    fun sendIceCandidate(to: String, candidate: IceCandidate) {
        val msg = mapOf(
            "type" to "candidate",
            "from" to userId,
            "to" to to,
            "candidate" to IceCandidateModel(
                candidate.sdpMid,
                candidate.sdpMLineIndex,
                candidate.sdp
            )
        )
        send(msg)
    }

    fun sendReject(to: String) {
        val msg = mapOf(
            "type" to "reject",
            "from" to userId,
            "to" to to
        )
        send(msg)
    }

    fun sendHangup(to: String) {
        val msg = mapOf(
            "type" to "hangup",
            "from" to userId,
            "to" to to
        )
        send(msg)
    }

    fun requestUserList() {
        send(mapOf("type" to "getUsers", "from" to userId))
    }

    private fun send(data: Map<String, Any?>) {
        val json = gson.toJson(data)
        Log.d(TAG, "Sending: $json")
        webSocket?.send(json)
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        isConnected = false
    }
}
