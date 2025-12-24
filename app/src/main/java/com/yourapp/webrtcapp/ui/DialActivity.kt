package com.yourapp.webrtcapp.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.yourapp.webrtcapp.R
import com.yourapp.webrtcapp.signaling.SignalingManager
import com.yourapp.webrtcapp.utils.Constants
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

class DialActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DialActivity"
    }

    private lateinit var myPhoneText: TextView
    private lateinit var connectionStatus: TextView
    private lateinit var dialPhoneInput: TextInputEditText
    private lateinit var videoCallBtn: Button
    private lateinit var audioCallBtn: Button
    private lateinit var statusMessage: TextView
    
    private lateinit var myPhone: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dial)

        myPhone = intent.getStringExtra("MY_PHONE") ?: ""
        if (myPhone.isEmpty()) {
            Toast.makeText(this, "Error: Phone number not provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initViews()
        setupListeners()
        connectToServer()
    }

    private fun initViews() {
        myPhoneText = findViewById(R.id.myPhoneText)
        connectionStatus = findViewById(R.id.connectionStatus)
        dialPhoneInput = findViewById(R.id.dialPhoneInput)
        videoCallBtn = findViewById(R.id.videoCallBtn)
        audioCallBtn = findViewById(R.id.audioCallBtn)
        statusMessage = findViewById(R.id.statusMessage)

        myPhoneText.text = "Your number: $myPhone"

        videoCallBtn.setOnClickListener { initiateCall(isVideoCall = true) }
        audioCallBtn.setOnClickListener { initiateCall(isVideoCall = false) }
    }

    private fun initiateCall(isVideoCall: Boolean) {
        val targetPhone = dialPhoneInput.text.toString().trim()
        
        Log.d(TAG, "initiateCall: target=$targetPhone, isVideo=$isVideoCall")
        Log.d(TAG, "SignalingManager.isConnected: ${SignalingManager.isConnected()}")
        
        when {
            targetPhone.isEmpty() -> {
                showError("Please enter a phone number to call")
            }
            targetPhone.length != 10 -> {
                showError("Please enter a valid 10-digit phone number")
            }
            !targetPhone.all { it.isDigit() } -> {
                showError("Phone number should contain only digits")
            }
            targetPhone == myPhone -> {
                showError("You cannot call yourself!")
            }
            else -> {
                // Check connection first
                if (!SignalingManager.isConnected()) {
                    Log.d(TAG, "Not connected, reconnecting first...")
                    statusMessage.text = "Reconnecting..."
                    statusMessage.visibility = View.VISIBLE
                    connectToServer()
                    // Wait a bit and try again
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (SignalingManager.isConnected()) {
                            checkAndCall(targetPhone, isVideoCall)
                        } else {
                            showError("Cannot connect to server. Please try again.")
                        }
                    }, 2000)
                    return
                }
                
                checkAndCall(targetPhone, isVideoCall)
            }
        }
    }
    
    private fun checkAndCall(targetPhone: String, isVideoCall: Boolean) {
        statusMessage.text = "Checking if user is online..."
        statusMessage.visibility = View.VISIBLE
        
        SignalingManager.checkUserOnline(targetPhone) { isOnline ->
            runOnUiThread {
                statusMessage.visibility = View.GONE
                Log.d(TAG, "User $targetPhone online status: $isOnline")
                if (isOnline) {
                    // Start call
                    val intent = Intent(this, CallActivity::class.java)
                    intent.putExtra("MY_ID", myPhone)
                    intent.putExtra("PEER_ID", targetPhone)
                    intent.putExtra("IS_CALLER", true)
                    intent.putExtra("IS_VIDEO_CALL", isVideoCall)
                    startActivity(intent)
                } else {
                    showUserNotOnlineDialog(targetPhone)
                }
            }
        }
    }

    private fun showUserNotOnlineDialog(phone: String) {
        AlertDialog.Builder(this)
            .setTitle("User Not Available")
            .setMessage("The user with phone number $phone is not currently online.\n\nThey need to open the app and register with the same phone number to receive calls.")
            .setPositiveButton("OK", null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }

    private fun showError(message: String) {
        statusMessage.text = message
        statusMessage.visibility = View.VISIBLE
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun setupListeners() {
        SignalingManager.setUserListListener(object : SignalingManager.UserListListener {
            override fun onUserListUpdated(users: List<String>) {
                Log.d(TAG, "Users online: ${users.size}")
                runOnUiThread {
                    connectionStatus.text = "● Online"
                    connectionStatus.setTextColor(resources.getColor(android.R.color.holo_green_dark, null))
                }
            }

            override fun onConnectionError(error: String) {
                Log.e(TAG, "Connection error: $error")
                runOnUiThread {
                    connectionStatus.text = "● Disconnected"
                    connectionStatus.setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
                    showError("Connection error: $error")
                }
            }
        })

        // Handle incoming calls
        SignalingManager.setCallListener(object : SignalingManager.CallSignalingListener {
            override fun onOfferReceived(from: String, sdp: SessionDescription, isVideoCall: Boolean) {
                Log.d(TAG, "Incoming call from $from (video: $isVideoCall)")
                runOnUiThread {
                    val intent = Intent(this@DialActivity, CallActivity::class.java)
                    intent.putExtra("MY_ID", myPhone)
                    intent.putExtra("PEER_ID", from)
                    intent.putExtra("IS_CALLER", false)
                    intent.putExtra("IS_VIDEO_CALL", isVideoCall)
                    intent.putExtra("INCOMING_SDP", sdp.description)
                    startActivity(intent)
                }
            }

            override fun onAnswerReceived(sdp: SessionDescription) {}
            override fun onIceCandidateReceived(candidate: IceCandidate) {}
            override fun onCallRejected() {}
            override fun onCallEnded() {}
            override fun onEmojiReceived(emoji: String) {}
        })
    }

    private fun connectToServer() {
        val serverUrl = getSignalingUrl()
        Log.d(TAG, "Connecting to $serverUrl as $myPhone")
        connectionStatus.text = "● Connecting..."
        connectionStatus.setTextColor(resources.getColor(android.R.color.holo_orange_dark, null))
        
        SignalingManager.connect(serverUrl, myPhone)
        // Re-setup listeners after connect to ensure they're active
        setupListeners()
    }

    private fun getSignalingUrl(): String {
        return if (isEmulator()) {
            Constants.EMULATOR_SERVER_URL
        } else {
            Constants.DEVICE_SERVER_URL
        }
    }

    private fun isEmulator(): Boolean {
        return (android.os.Build.FINGERPRINT.startsWith("generic")
                || android.os.Build.FINGERPRINT.startsWith("unknown")
                || android.os.Build.MODEL.contains("google_sdk")
                || android.os.Build.MODEL.contains("Emulator")
                || android.os.Build.MODEL.contains("Android SDK built for x86"))
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume - setting up listeners and checking connection")
        setupListeners()
        
        // Always try to request user list first
        if (SignalingManager.isConnected()) {
            Log.d(TAG, "SignalingManager reports connected, requesting user list")
            SignalingManager.requestUserList()
            
            // Set a timeout to check if we got a response
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                // If still showing connecting after 2 seconds, force reconnect
                if (connectionStatus.text.toString().contains("Connecting")) {
                    Log.d(TAG, "No response after 2s, forcing reconnect")
                    forceReconnect()
                }
            }, 2000)
        } else {
            Log.d(TAG, "SignalingManager not connected, connecting...")
            connectToServer()
        }
    }
    
    private fun forceReconnect() {
        Log.d(TAG, "Force reconnecting to server")
        connectionStatus.text = "● Reconnecting..."
        connectionStatus.setTextColor(resources.getColor(android.R.color.holo_orange_dark, null))
        
        val serverUrl = getSignalingUrl()
        SignalingManager.forceReconnect(serverUrl, myPhone)
        setupListeners()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Don't disconnect - keep connection alive for incoming calls
    }
}
