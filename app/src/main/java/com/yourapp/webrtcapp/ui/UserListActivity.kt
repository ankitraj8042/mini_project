package com.yourapp.webrtcapp.ui

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.yourapp.webrtcapp.R
import com.yourapp.webrtcapp.signaling.SignalingManager
import com.yourapp.webrtcapp.utils.Constants
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.net.Inet4Address
import java.net.NetworkInterface

class UserListActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "UserListActivity"
    }

    private lateinit var usernameText: TextView
    private lateinit var userListView: ListView
    private lateinit var refreshBtn: Button
    private lateinit var currentUsername: String
    private lateinit var adapter: ArrayAdapter<String>
    
    private val userList = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_list)

        currentUsername = intent.getStringExtra("USERNAME") ?: "User"
        
        usernameText = findViewById(R.id.usernameText)
        userListView = findViewById(R.id.userListView)
        refreshBtn = findViewById(R.id.refreshBtn)

        usernameText.text = "Logged in as: $currentUsername"
        
        // Show network status
        val deviceIp = getDeviceIpAddress()
        val networkType = getNetworkType()
        Log.d(TAG, "Device IP: $deviceIp, Network Type: $networkType")
        Toast.makeText(this, "Your IP: $deviceIp ($networkType)", Toast.LENGTH_LONG).show()
        
        // Warn if not on WiFi
        if (networkType != "WiFi") {
            Toast.makeText(this, "⚠️ For video calls, both phones should be on the same WiFi network", Toast.LENGTH_LONG).show()
        }

        adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            userList
        )
        userListView.adapter = adapter

        userListView.setOnItemClickListener { _, _, position, _ ->
            val selectedUser = userList[position]
            val intent = Intent(this, CallActivity::class.java)
            intent.putExtra("MY_ID", currentUsername)
            intent.putExtra("PEER_ID", selectedUser)
            intent.putExtra("IS_CALLER", true)
            startActivity(intent)
        }

        refreshBtn.setOnClickListener {
            Log.d(TAG, "Refresh button clicked")
            SignalingManager.requestUserList()
            Toast.makeText(this, "Refreshing user list...", Toast.LENGTH_SHORT).show()
        }

        setupListeners()
        connectToSignalingServer()
    }

    private fun setupListeners() {
        Log.d(TAG, "Setting up listeners")
        
        // Set up user list listener
        SignalingManager.setUserListListener(object : SignalingManager.UserListListener {
            override fun onUserListUpdated(users: List<String>) {
                Log.d(TAG, "onUserListUpdated called with ${users.size} users: $users")
                runOnUiThread {
                    Log.d(TAG, "Updating UI with users: $users")
                    userList.clear()
                    userList.addAll(users)
                    adapter.notifyDataSetChanged()
                    Log.d(TAG, "Adapter notified, userList size: ${userList.size}")
                    
                    if (users.isEmpty()) {
                        Toast.makeText(this@UserListActivity, 
                            "No other users online", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@UserListActivity, 
                            "Found ${users.size} user(s) online", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            override fun onConnectionError(error: String) {
                Log.e(TAG, "Connection error: $error")
                runOnUiThread {
                    Toast.makeText(this@UserListActivity, 
                        "Connection error: $error", Toast.LENGTH_LONG).show()
                }
            }
        })
        
        // Set up call listener
        SignalingManager.setCallListener(object : SignalingManager.CallSignalingListener {
            override fun onOfferReceived(from: String, sdp: SessionDescription, isVideoCall: Boolean) {
                Log.d(TAG, "Incoming call from $from (video: $isVideoCall)")
                runOnUiThread {
                    val intent = Intent(this@UserListActivity, CallActivity::class.java)
                    intent.putExtra("MY_ID", currentUsername)
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

    private fun connectToSignalingServer() {
        val serverUrl = getSignalingUrl()
        Log.d(TAG, "Connecting to server: $serverUrl as $currentUsername")
        
        // Connect using the singleton
        SignalingManager.connect(serverUrl, currentUsername)
    }

    private fun getSignalingUrl(): String {
        return if (isEmulator()) {
            Constants.EMULATOR_SERVER_URL
        } else {
            Constants.DEVICE_SERVER_URL
        }
    }

    private fun isEmulator(): Boolean {
        return android.os.Build.FINGERPRINT.contains("generic")
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume - re-attaching listeners and requesting user list")
        // Re-attach listeners when coming back to this activity
        setupListeners()
        // Request fresh user list
        SignalingManager.requestUserList()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        // Don't disconnect here - let CallActivity use the connection
        // But don't null out listener - it causes issues
    }
    
    private fun getDeviceIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        return address.hostAddress ?: "Unknown"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting device IP: ${e.message}")
        }
        return "Unknown"
    }
    
    private fun getNetworkType(): String {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val capabilities = cm.getNetworkCapabilities(network)
        return when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Cellular"
            else -> "Unknown"
        }
    }
}
