package com.yourapp.webrtcapp.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.yourapp.webrtcapp.R
import com.yourapp.webrtcapp.ai.NetworkDetector
import com.yourapp.webrtcapp.api.ApiClient
import com.yourapp.webrtcapp.api.CallHistoryItem
import com.yourapp.webrtcapp.api.Contact
import com.yourapp.webrtcapp.auth.AuthManager
import com.yourapp.webrtcapp.signaling.SignalingManager
import com.yourapp.webrtcapp.ui.adapters.ContactsAdapter
import com.yourapp.webrtcapp.ui.adapters.RecentsAdapter
import com.yourapp.webrtcapp.utils.Constants
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

class DialActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DialActivity"
    }

    // Views
    private lateinit var myPhoneText: TextView
    private lateinit var connectionStatus: TextView
    private lateinit var connectionDot: View
    private lateinit var phoneDisplay: TextView
    private lateinit var videoCallBtn: FloatingActionButton
    private lateinit var audioCallBtn: FloatingActionButton
    private lateinit var backspaceBtn: ImageButton
    private lateinit var logoutBtn: ImageButton
    private lateinit var tabLayout: TabLayout
    private lateinit var networkInfoText: TextView
    
    // Tab content views - use View type for flexibility
    private lateinit var keypadTab: View
    private lateinit var recentsTab: View
    private lateinit var contactsTab: View
    
    // RecyclerViews
    private lateinit var recentsRecyclerView: RecyclerView
    private lateinit var contactsRecyclerView: RecyclerView
    
    // Adapters
    private lateinit var recentsAdapter: RecentsAdapter
    private lateinit var contactsAdapter: ContactsAdapter
    
    // Dialpad keys
    private lateinit var dialpadKeys: List<TextView>
    
    private lateinit var myPhone: String
    
    // API Client
    private lateinit var apiClient: ApiClient
    
    // Current dialed number
    private var dialedNumber = StringBuilder()

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
        setupDialpad()
        setupTabs()
        setupSignalingListeners()
        setupNetworkInfo()
        connectToServer()
    }
    
    private fun setupNetworkInfo() {
        // Show current network info and AI recommendation
        val networkDesc = NetworkDetector.getNetworkDescription(this)
        val isLowSpeed = NetworkDetector.isLowSpeedNetwork(this)
        
        networkInfoText.text = "📶 $networkDesc"
        
        if (isLowSpeed) {
            // Auto-enable rural mode for low-speed networks
            Constants.setRuralModeEnabled(this, true)
            networkInfoText.text = "📡 Low-Speed Network Detected - Rural Mode Enabled"
            Toast.makeText(this, "📡 AI detected low-speed network. Rural mode auto-enabled.", Toast.LENGTH_LONG).show()
        }
    }

    private fun initViews() {
        myPhoneText = findViewById(R.id.myPhoneText)
        connectionStatus = findViewById(R.id.connectionStatus)
        connectionDot = findViewById(R.id.connectionDot)
        phoneDisplay = findViewById(R.id.phoneDisplay)
        videoCallBtn = findViewById(R.id.videoCallBtn)
        audioCallBtn = findViewById(R.id.audioCallBtn)
        backspaceBtn = findViewById(R.id.backspaceBtn)
        logoutBtn = findViewById(R.id.logoutBtn)
        tabLayout = findViewById(R.id.tabLayout)
        networkInfoText = findViewById(R.id.networkInfoText)
        
        // Tab contents
        keypadTab = findViewById(R.id.keypadTab)
        recentsTab = findViewById(R.id.recentsTab)
        contactsTab = findViewById(R.id.contactsTab)
        
        // RecyclerViews
        recentsRecyclerView = findViewById(R.id.recentsRecyclerView)
        contactsRecyclerView = findViewById(R.id.contactsRecyclerView)
        
        // Initialize API client using AuthManager
        apiClient = AuthManager.getInstance(this).getApiClient()
        
        // Setup adapters
        setupRecentsAdapter()
        setupContactsAdapter()

        myPhoneText.text = "Your number: $myPhone"

        // Call buttons
        videoCallBtn.setOnClickListener { initiateCall(isVideoCall = true) }
        audioCallBtn.setOnClickListener { initiateCall(isVideoCall = false) }
        
        // Backspace button
        backspaceBtn.setOnClickListener {
            if (dialedNumber.isNotEmpty()) {
                dialedNumber.deleteCharAt(dialedNumber.length - 1)
                updatePhoneDisplay()
            }
        }
        
        // Long press backspace to clear all
        backspaceBtn.setOnLongClickListener {
            dialedNumber.clear()
            updatePhoneDisplay()
            true
        }
        
        // Logout button - also shows settings
        logoutBtn.setOnClickListener {
            showSettingsDialog()
        }
    }
    
    private fun setupRecentsAdapter() {
        recentsAdapter = RecentsAdapter(
            onItemClick = { item ->
                // Show call details dialog
                val caller = if (item.caller == myPhone) item.callee else item.caller
                dialedNumber.clear()
                dialedNumber.append(caller)
                updatePhoneDisplay()
                tabLayout.getTabAt(0)?.select()
            },
            onVideoCall = { phone -> initiateCallTo(phone, true) },
            onAudioCall = { phone -> initiateCallTo(phone, false) },
            onStatsClick = { item ->
                // Open StatsActivity with this call's data
                val intent = Intent(this, StatsActivity::class.java)
                intent.putExtra("callId", item.callId)
                intent.putExtra("caller", item.caller)
                intent.putExtra("callee", item.callee)
                startActivity(intent)
            }
        )
        recentsAdapter.setCurrentPhone(myPhone)
        
        recentsRecyclerView.layoutManager = LinearLayoutManager(this)
        recentsRecyclerView.adapter = recentsAdapter
    }
    
    private fun setupContactsAdapter() {
        contactsAdapter = ContactsAdapter(
            onVideoCall = { phone -> initiateCallTo(phone, true) },
            onAudioCall = { phone -> initiateCallTo(phone, false) },
            onDelete = { contact -> showDeleteContactDialog(contact) }
        )
        
        contactsRecyclerView.layoutManager = LinearLayoutManager(this)
        contactsRecyclerView.adapter = contactsAdapter
        
        // Setup add contact button
        findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.addContactBtn)?.setOnClickListener {
            showAddContactDialog()
        }
    }
    
    private fun loadRecents() {
        if (!AuthManager.getInstance(this).isLoggedIn()) {
            Log.w(TAG, "Not logged in, skipping recents load")
            return
        }
        
        apiClient.getCallHistory(50, { history ->
            runOnUiThread {
                recentsAdapter.submitList(history)
                Log.d(TAG, "Loaded ${history.size} recent calls")
            }
        }, { error ->
            runOnUiThread {
                Log.e(TAG, "Failed to load recents: $error")
            }
        })
    }
    
    private fun loadContacts() {
        if (!AuthManager.getInstance(this).isLoggedIn()) {
            Log.w(TAG, "Not logged in, skipping contacts load")
            return
        }
        
        apiClient.getContacts({ contacts ->
            runOnUiThread {
                contactsAdapter.submitList(contacts)
                Log.d(TAG, "Loaded ${contacts.size} contacts")
            }
        }, { error ->
            runOnUiThread {
                Log.e(TAG, "Failed to load contacts: $error")
            }
        })
    }
    
    private fun showAddContactDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_contact, null)
        val nameInput = dialogView.findViewById<EditText>(R.id.nameInput)
        val phoneInput = dialogView.findViewById<EditText>(R.id.phoneInput)
        
        AlertDialog.Builder(this)
            .setTitle("Add Contact")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString().trim()
                val phone = phoneInput.text.toString().trim()
                
                if (phone.length == 10 && phone.all { it.isDigit() }) {
                    addContact(name, phone)
                } else {
                    Toast.makeText(this, "Please enter a valid 10-digit phone number", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun addContact(name: String, phone: String) {
        apiClient.addContact(phone, name, { isRegistered ->
            runOnUiThread {
                // Create a local contact object since API returns boolean
                val newContact = Contact(null, phone, name, isRegistered)
                contactsAdapter.addContact(newContact)
                val status = if (isRegistered) " (registered)" else ""
                Toast.makeText(this, "Contact added$status", Toast.LENGTH_SHORT).show()
            }
        }, { error ->
            runOnUiThread {
                Toast.makeText(this, "Failed to add contact: $error", Toast.LENGTH_SHORT).show()
            }
        })
    }
    
    private fun showDeleteContactDialog(contact: Contact) {
        AlertDialog.Builder(this)
            .setTitle("Delete Contact")
            .setMessage("Delete ${contact.name.ifEmpty { contact.phoneNumber }}?")
            .setPositiveButton("Delete") { _, _ ->
                deleteContact(contact)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun deleteContact(contact: Contact) {
        apiClient.deleteContact(contact.phoneNumber, {
            runOnUiThread {
                contactsAdapter.removeContact(contact.phoneNumber)
                Toast.makeText(this, "Contact deleted", Toast.LENGTH_SHORT).show()
            }
        }, { error ->
            runOnUiThread {
                Toast.makeText(this, "Failed to delete: $error", Toast.LENGTH_SHORT).show()
            }
        })
    }
    
    private fun initiateCallTo(phone: String, isVideo: Boolean) {
        dialedNumber.clear()
        dialedNumber.append(phone)
        initiateCall(isVideo)
    }
    
    private fun showSettingsDialog() {
        val ruralModeEnabled = Constants.getRuralModeEnabled(this)
        
        AlertDialog.Builder(this)
            .setTitle("⚙️ Settings")
            .setItems(arrayOf(
                if (ruralModeEnabled) "📡 Disable Rural Mode" else "📡 Enable Rural Mode",
                "🌐 Server Settings",
                "📊 Network Info",
                "� View Call Statistics",
                "🚪 Logout"
            )) { _, which ->
                when (which) {
                    0 -> {
                        Constants.setRuralModeEnabled(this, !ruralModeEnabled)
                        val newState = if (!ruralModeEnabled) "enabled" else "disabled"
                        Toast.makeText(this, "Rural Mode $newState", Toast.LENGTH_SHORT).show()
                        setupNetworkInfo()
                    }
                    1 -> showServerSettingsDialog()
                    2 -> {
                        val info = """
                            Network: ${NetworkDetector.getNetworkDescription(this)}
                            Low-Speed: ${NetworkDetector.isLowSpeedNetwork(this)}
                            Rural Mode: ${Constants.getRuralModeEnabled(this)}
                            Server: ${Constants.getSignalingUrl(this)}
                        """.trimIndent()
                        AlertDialog.Builder(this)
                            .setTitle("📊 Network Info")
                            .setMessage(info)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                    3 -> {
                        // Open Stats Activity with graphs
                        startActivity(Intent(this, StatsActivity::class.java))
                    }
                    4 -> {
                        AuthManager.getInstance(this).logout()
                        SignalingManager.disconnect()
                        startActivity(Intent(this, LoginActivity::class.java))
                        finish()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showServerSettingsDialog() {
        val currentIp = Constants.getCustomServerIp(this) ?: Constants.DEFAULT_SERVER_IP
        
        val dialogView = layoutInflater.inflate(R.layout.dialog_server_settings, null)
        val ipInput = dialogView.findViewById<EditText>(R.id.serverIpInput)
        ipInput.setText(currentIp)
        
        AlertDialog.Builder(this)
            .setTitle("🌐 Server Settings")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val newIp = ipInput.text.toString().trim()
                if (newIp.isNotEmpty()) {
                    Constants.setCustomServerIp(this, newIp)
                    Toast.makeText(this, "Server IP updated. Reconnecting...", Toast.LENGTH_SHORT).show()
                    forceReconnect()
                }
            }
            .setNeutralButton("Reset to Default") { _, _ ->
                Constants.setCustomServerIp(this, "")
                Toast.makeText(this, "Using default server", Toast.LENGTH_SHORT).show()
                forceReconnect()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun setupDialpad() {
        val keyIds = listOf(
            R.id.key0, R.id.key1, R.id.key2, R.id.key3, R.id.key4,
            R.id.key5, R.id.key6, R.id.key7, R.id.key8, R.id.key9,
            R.id.keyStar, R.id.keyHash
        )
        
        dialpadKeys = keyIds.map { findViewById<TextView>(it) }
        
        dialpadKeys.forEach { key ->
            key.setOnClickListener {
                val digit = key.text.toString()
                if (dialedNumber.length < 10) {
                    dialedNumber.append(digit)
                    updatePhoneDisplay()
                }
            }
        }
    }
    
    private fun updatePhoneDisplay() {
        phoneDisplay.text = dialedNumber.toString()
    }
    
    private fun setupTabs() {
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> showTab(keypadTab)
                    1 -> {
                        showTab(recentsTab)
                        loadRecents()
                    }
                    2 -> {
                        showTab(contactsTab)
                        loadContacts()
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }
    
    private fun showTab(tabToShow: View) {
        keypadTab.visibility = View.GONE
        recentsTab.visibility = View.GONE
        contactsTab.visibility = View.GONE
        tabToShow.visibility = View.VISIBLE
    }

    private fun initiateCall(isVideoCall: Boolean) {
        val targetPhone = dialedNumber.toString().trim()
        
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
                    Toast.makeText(this, "Reconnecting...", Toast.LENGTH_SHORT).show()
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
        Toast.makeText(this, "Checking if user is online...", Toast.LENGTH_SHORT).show()
        
        SignalingManager.checkUserOnline(targetPhone) { isOnline ->
            runOnUiThread {
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
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun setupSignalingListeners() {
        SignalingManager.setUserListListener(object : SignalingManager.UserListListener {
            override fun onUserListUpdated(users: List<String>) {
                Log.d(TAG, "Users online: ${users.size}")
                runOnUiThread {
                    connectionStatus.text = "Online"
                    connectionDot.setBackgroundResource(R.drawable.status_dot_online)
                }
            }

            override fun onConnectionError(error: String) {
                Log.e(TAG, "Connection error: $error")
                runOnUiThread {
                    connectionStatus.text = "Offline"
                    connectionDot.setBackgroundResource(R.drawable.status_dot_offline)
                    showError("Connection error: $error")
                }
            }
        })

        // Handle incoming calls
        SignalingManager.setCallListener(object : SignalingManager.CallSignalingListener {
            override fun onOfferReceived(from: String, sdp: SessionDescription, isVideoCall: Boolean, callId: String) {
                Log.d(TAG, "Incoming call from $from (video: $isVideoCall, callId: $callId)")
                // Store the callId from server
                SignalingManager.setCurrentCallId(callId)
                runOnUiThread {
                    val intent = Intent(this@DialActivity, CallActivity::class.java)
                    intent.putExtra("MY_ID", myPhone)
                    intent.putExtra("PEER_ID", from)
                    intent.putExtra("IS_CALLER", false)
                    intent.putExtra("IS_VIDEO_CALL", isVideoCall)
                    intent.putExtra("INCOMING_SDP", sdp.description)
                    intent.putExtra("CALL_ID", callId)
                    startActivity(intent)
                }
            }

            override fun onAnswerReceived(sdp: SessionDescription, callId: String) {}
            override fun onIceCandidateReceived(candidate: IceCandidate) {}
            override fun onCallRejected() {}
            override fun onCallEnded() {}
            override fun onEmojiReceived(emoji: String) {}
        })
    }

    private fun connectToServer() {
        val serverUrl = getSignalingUrl()
        Log.d(TAG, "Connecting to $serverUrl as $myPhone")
        connectionStatus.text = "Connecting..."
        
        SignalingManager.connect(serverUrl, myPhone)
        // Re-setup listeners after connect to ensure they're active
        setupSignalingListeners()
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
        setupSignalingListeners()
        
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
        connectionStatus.text = "Reconnecting..."
        
        val serverUrl = getSignalingUrl()
        SignalingManager.forceReconnect(serverUrl, myPhone)
        setupSignalingListeners()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Don't disconnect - keep connection alive for incoming calls
    }
}
