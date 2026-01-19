package com.yourapp.webrtcapp.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.yourapp.webrtcapp.R
import com.yourapp.webrtcapp.model.CallState
import com.yourapp.webrtcapp.model.NetworkQuality
import com.yourapp.webrtcapp.signaling.SignalingManager
import com.yourapp.webrtcapp.webrtc.SimpleSdpObserver
import org.webrtc.*
import java.net.Inet4Address
import java.net.NetworkInterface

class CallActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CallActivity"
    }

    private lateinit var localRenderer: SurfaceViewRenderer
    private lateinit var remoteRenderer: SurfaceViewRenderer
    private lateinit var eglBase: EglBase

    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null

    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private lateinit var myId: String
    private lateinit var peerId: String
    
    // UI Controls
    private lateinit var callStateText: TextView
    private lateinit var networkStatsText: TextView
    private lateinit var networkQualityText: TextView
    private lateinit var networkTypeText: TextView
    private lateinit var dataUsageText: TextView
    private lateinit var audioOnlyIndicator: TextView
    private lateinit var emojiOverlay: TextView
    private lateinit var emojiList: LinearLayout
    private lateinit var emojiToggle: TextView
    private lateinit var statsHeader: LinearLayout
    private lateinit var statsDetails: LinearLayout
    private lateinit var statsExpandArrow: android.widget.ImageView
    private lateinit var callDurationText: TextView
    private lateinit var callQualityText: TextView
    private lateinit var muteBtn: ImageButton
    private lateinit var endCallBtn: ImageButton
    private lateinit var videoToggleBtn: ImageButton
    private lateinit var speakerBtn: ImageButton
    private lateinit var audioOnlyBtn: ImageButton
    private lateinit var switchCameraBtn: ImageButton
    private lateinit var qualityBtn: ImageButton
    
    // UI State
    private var isStatsExpanded = false
    private var isEmojiListVisible = false
    
    // Media tracks
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null
    
    // State management
    private var callState = CallState.IDLE
    private var isMuted = false
    private var isVideoEnabled = true
    private var isSpeakerOn = true
    private var isAudioOnlyMode = false
    private var isVideoCall = true  // Initially video call
    private var networkQuality = NetworkQuality.GOOD
    private var isCaller = false
    private var isAutoQuality = true  // Default to auto quality
    private var currentVideoResolution = "720p"  // Track current resolution
    private var incomingSdp: String? = null
    private var isFrontCamera = true
    
    // Audio Manager
    private lateinit var audioManager: AudioManager
    
    // ICE candidates queue (for candidates received before remote description is set)
    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    private var isRemoteDescriptionSet = false
    
    // Stats monitoring
    private val statsHandler = Handler(Looper.getMainLooper())
    private val timerHandler = Handler(Looper.getMainLooper())
    private var lastBytesSent = 0L
    private var lastBytesReceived = 0L
    private var lastTimestamp = 0L
    private var lastPacketsSent = 0L
    private var lastPacketsLost = 0L
    private var totalDataUsedBytes = 0L  // Track cumulative data usage
    private var lastStatsSaveTime = 0L  // Track when we last saved stats
    
    // Poor quality dialog tracking
    private var poorQualityStartTime = 0L  // When poor quality started
    private var dontShowPoorQualityDialog = false  // User chose "Don't show again"
    private var isPoorQualityDialogShowing = false  // Prevent multiple dialogs
    private val POOR_QUALITY_THRESHOLD_MS = 20_000L  // 20 seconds threshold
    private val STATS_SAVE_INTERVAL_MS = 3_000L  // Save stats every 3 seconds
    
    // Real-time stats collection for saving to database
    private val statsSamples = mutableListOf<StatsSample>()
    private var callStartTime = 0L
    private var currentCallId: String? = null
    
    // Data class for stats samples
    data class StatsSample(
        val timestamp: Long,
        val sendBitrateKbps: Long,
        val receiveBitrateKbps: Long,
        val packetLossPercent: Double,
        val rttMs: Int,
        val networkQuality: String,
        val dataUsedBytes: Long
    )

    // ===================== PERMISSIONS =====================

    private fun hasPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
                ),
                100
            )
            return
        }

        startCall()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startCall()
        } else {
            Toast.makeText(this, "Permissions required for video call", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // ===================== CALL START =====================

    private fun startCall() {
        setContentView(R.layout.activity_call)

        myId = intent.getStringExtra("MY_ID") ?: "user1"
        peerId = intent.getStringExtra("PEER_ID") ?: "user2"
        isCaller = intent.getBooleanExtra("IS_CALLER", true)
        isVideoCall = intent.getBooleanExtra("IS_VIDEO_CALL", true)
        incomingSdp = intent.getStringExtra("INCOMING_SDP")
        
        // Get callId from intent (for incoming calls) or SignalingManager
        currentCallId = intent.getStringExtra("CALL_ID") ?: SignalingManager.getCurrentCallId()

        // Log network information for debugging
        Log.d(TAG, "===========================================")
        Log.d(TAG, "Starting call: myId=$myId, peerId=$peerId, isCaller=$isCaller, isVideoCall=$isVideoCall")
        Log.d(TAG, "Device IP Address: ${getDeviceIpAddress()}")
        Log.d(TAG, "Network Type: ${getNetworkType()}")
        Log.d(TAG, "===========================================")
        
        // Initialize audio manager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        localRenderer = findViewById(R.id.localView)
        remoteRenderer = findViewById(R.id.remoteView)
        callStateText = findViewById(R.id.callStateText)
        networkStatsText = findViewById(R.id.networkStatsText)
        networkQualityText = findViewById(R.id.networkQualityText)
        networkTypeText = findViewById(R.id.networkTypeText)
        dataUsageText = findViewById(R.id.dataUsageText)
        audioOnlyIndicator = findViewById(R.id.audioOnlyIndicator)
        emojiOverlay = findViewById(R.id.emojiOverlay)
        emojiList = findViewById(R.id.emojiList)
        emojiToggle = findViewById(R.id.emojiToggle)
        statsHeader = findViewById(R.id.statsHeader)
        statsDetails = findViewById(R.id.statsDetails)
        statsExpandArrow = findViewById(R.id.statsExpandArrow)
        callDurationText = findViewById(R.id.callDurationText)
        callQualityText = findViewById(R.id.callQualityText)
        muteBtn = findViewById(R.id.muteBtn)
        endCallBtn = findViewById(R.id.endCallBtn)
        videoToggleBtn = findViewById(R.id.videoToggleBtn)
        speakerBtn = findViewById(R.id.speakerBtn)
        audioOnlyBtn = findViewById(R.id.audioOnlyBtn)
        switchCameraBtn = findViewById(R.id.switchCameraBtn)
        qualityBtn = findViewById(R.id.qualityBtn)

        // Set initial call state text
        callStateText.text = if (isCaller) "Calling $peerId..." else "Incoming call..."
        networkStatsText.text = ""
        networkQualityText.text = ""
        callDurationText.text = " • 00:00"
        callQualityText.text = "📶 Connecting..."
        
        // If audio-only call, show indicator and hide video views
        if (!isVideoCall) {
            enableAudioOnlyMode()
        }

        eglBase = EglBase.create()
        
        localRenderer.init(eglBase.eglBaseContext, null)
        localRenderer.setMirror(true)
        localRenderer.setEnableHardwareScaler(true)
        localRenderer.setZOrderMediaOverlay(true)
        
        remoteRenderer.init(eglBase.eglBaseContext, null)
        remoteRenderer.setEnableHardwareScaler(true)
        remoteRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)

        setupUIControls()
        setupEmojiBar()
        initWebRTC()
        initSignaling()
        
        // Set speaker on by default for video calls
        setSpeakerphone(true)
        
        // Show network info to user
        val deviceIp = getDeviceIpAddress()
        val networkType = getNetworkType()
        Toast.makeText(this, "Network: $networkType\nIP: $deviceIp", Toast.LENGTH_LONG).show()
        
        // Check if we have a valid IP (not localhost)
        if (deviceIp == "Unknown" || deviceIp.startsWith("127.")) {
            Log.w(TAG, "WARNING: Device has no valid network IP address!")
            Toast.makeText(this, "⚠️ No WiFi connection! Connect both phones to same WiFi", Toast.LENGTH_LONG).show()
        }
        
        if (isCaller) {
            startCallAsCaller()
        } else {
            // Handle incoming call
            handleIncomingCall()
        }
        
        startStatsMonitoring()
    }

    // ===================== WEBRTC =====================

    private fun initWebRTC() {
        Log.d(TAG, "Initializing WebRTC")
        
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this)
                .setEnableInternalTracer(false)
                .setFieldTrials("WebRTC-BindUsingInterfaceName/Enabled/")
                .createInitializationOptions()
        )

        val options = PeerConnectionFactory.Options().apply {
            // Disable network monitor to prevent interface issues on some devices
            disableNetworkMonitor = false
        }
        
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
            )
            .setVideoDecoderFactory(
                DefaultVideoDecoderFactory(eglBase.eglBaseContext)
            )
            .createPeerConnectionFactory()

        // Use a single reliable TURN server - Metered.ca free tier
        // Both devices MUST use the same TURN server for relay to work
        val iceServers = mutableListOf<PeerConnection.IceServer>()
        
        // Google STUN servers (for discovering public IP)
        iceServers.add(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                .createIceServer()
        )
        iceServers.add(
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302")
                .createIceServer()
        )
        iceServers.add(
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302")
                .createIceServer()
        )
        iceServers.add(
            PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302")
                .createIceServer()
        )
        iceServers.add(
            PeerConnection.IceServer.builder("stun:stun4.l.google.com:19302")
                .createIceServer()
        )
        
        // TURN servers from multiple providers for redundancy
        // OpenRelay (free public TURN)
        iceServers.add(
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer()
        )
        iceServers.add(
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer()
        )
        iceServers.add(
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer()
        )
        
        // Metered.ca TURN servers 
        val turnUsername = "83eebabf8b4cce9d5dbcb649"
        val turnPassword = "2D7JvfkOQtBdYW3R"
        
        iceServers.add(
            PeerConnection.IceServer.builder("turn:a.relay.metered.ca:80")
                .setUsername(turnUsername)
                .setPassword(turnPassword)
                .createIceServer()
        )
        iceServers.add(
            PeerConnection.IceServer.builder("turn:a.relay.metered.ca:80?transport=tcp")
                .setUsername(turnUsername)
                .setPassword(turnPassword)
                .createIceServer()
        )
        iceServers.add(
            PeerConnection.IceServer.builder("turn:a.relay.metered.ca:443")
                .setUsername(turnUsername)
                .setPassword(turnPassword)
                .createIceServer()
        )
        iceServers.add(
            PeerConnection.IceServer.builder("turn:a.relay.metered.ca:443?transport=tcp")
                .setUsername(turnUsername)
                .setPassword(turnPassword)
                .createIceServer()
        )
        iceServers.add(
            PeerConnection.IceServer.builder("turns:a.relay.metered.ca:443?transport=tcp")
                .setUsername(turnUsername)
                .setPassword(turnPassword)
                .createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            // Use ALL transport types - let ICE figure out best path
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL
            keyType = PeerConnection.KeyType.ECDSA
            // Increase ICE timeout for slow TURN connections
            iceConnectionReceivingTimeout = 10000
            iceBackupCandidatePairPingInterval = 2000
        }
        
        Log.d(TAG, "ICE Configuration: ${iceServers.size} servers configured")
        iceServers.forEachIndexed { index, server ->
            Log.d(TAG, "  Server $index: ${server.urls}")
        }

        peerConnection = peerConnectionFactory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {

                override fun onIceCandidate(candidate: IceCandidate) {
                    // Extract IP from candidate string (format: "... <ip> <port> typ <type> ...")
                    val candidateType = when {
                        candidate.sdp.contains("typ relay") -> "RELAY (TURN)"
                        candidate.sdp.contains("typ srflx") -> "SRFLX (STUN)"
                        candidate.sdp.contains("typ prflx") -> "PRFLX"
                        candidate.sdp.contains("typ host") -> "HOST"
                        else -> "UNKNOWN"
                    }
                    
                    // Extract IP address from candidate
                    val ipPattern = Regex("""(\d+\.\d+\.\d+\.\d+)""")
                    val ipMatch = ipPattern.find(candidate.sdp)
                    val candidateIp = ipMatch?.value ?: "unknown"
                    
                    Log.d(TAG, "=== LOCAL ICE CANDIDATE ===")
                    Log.d(TAG, "  Type: $candidateType")
                    Log.d(TAG, "  IP: $candidateIp")
                    Log.d(TAG, "  Full: ${candidate.sdp.take(100)}...")
                    
                    // Warn if only generating localhost candidates
                    if (candidateIp.startsWith("127.") || candidateIp == "::1") {
                        Log.w(TAG, "  WARNING: Localhost candidate - won't work between devices!")
                    }
                    
                    SignalingManager.sendIceCandidate(peerId, candidate)
                }

                override fun onTrack(transceiver: RtpTransceiver) {
                    Log.d(TAG, "onTrack called: ${transceiver.receiver.track()?.kind()}")
                    val track = transceiver.receiver.track()
                    if (track is VideoTrack) {
                        Log.d(TAG, "Adding remote video track to renderer")
                        runOnUiThread {
                            track.setEnabled(true)
                            track.addSink(remoteRenderer)
                            // Ensure remote renderer is visible when we receive video
                            remoteRenderer.visibility = View.VISIBLE
                            Log.d(TAG, "Remote video track added and renderer visible")
                        }
                    } else if (track is AudioTrack) {
                        Log.d(TAG, "Remote audio track received")
                        runOnUiThread {
                            track.setEnabled(true)
                        }
                    }
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                    Log.d(TAG, "ICE connection state: $state")
                    runOnUiThread {
                        when (state) {
                            PeerConnection.IceConnectionState.CHECKING -> {
                                Log.d(TAG, "ICE is checking connectivity...")
                            }
                            PeerConnection.IceConnectionState.CONNECTED -> {
                                Log.d(TAG, "ICE connected! Peer-to-peer connection established")
                                updateCallState(CallState.CONNECTED)
                            }
                            PeerConnection.IceConnectionState.COMPLETED -> {
                                Log.d(TAG, "ICE completed! All candidates processed")
                                updateCallState(CallState.CONNECTED)
                            }
                            PeerConnection.IceConnectionState.DISCONNECTED -> {
                                Log.w(TAG, "ICE disconnected temporarily")
                                Toast.makeText(this@CallActivity, "Connection lost, reconnecting...", Toast.LENGTH_SHORT).show()
                            }
                            PeerConnection.IceConnectionState.FAILED -> {
                                Log.e(TAG, "ICE connection failed! Unable to establish peer connection")
                                Toast.makeText(this@CallActivity, "Connection failed - Network issue", Toast.LENGTH_LONG).show()
                                updateCallState(CallState.ENDED)
                            }
                            PeerConnection.IceConnectionState.CLOSED -> {
                                Log.d(TAG, "ICE connection closed")
                            }
                            else -> {
                                Log.d(TAG, "ICE state: $state")
                            }
                        }
                    }
                }

                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                    Log.d(TAG, "Peer connection state: $newState")
                    runOnUiThread {
                        when (newState) {
                            PeerConnection.PeerConnectionState.CONNECTED -> {
                                Log.d(TAG, "Peer connection CONNECTED!")
                                updateCallState(CallState.CONNECTED)
                            }
                            PeerConnection.PeerConnectionState.FAILED -> {
                                Log.e(TAG, "Peer connection FAILED!")
                            }
                            PeerConnection.PeerConnectionState.DISCONNECTED -> {
                                Log.w(TAG, "Peer connection DISCONNECTED")
                            }
                            else -> {
                                Log.d(TAG, "Peer connection state: $newState")
                            }
                        }
                    }
                }

                override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
                
                override fun onAddStream(stream: MediaStream) {
                    Log.d(TAG, "onAddStream: videoTracks=${stream.videoTracks.size}, audioTracks=${stream.audioTracks.size}")
                    runOnUiThread {
                        if (stream.videoTracks.isNotEmpty()) {
                            val videoTrack = stream.videoTracks[0]
                            Log.d(TAG, "Adding video track from stream to renderer")
                            videoTrack.setEnabled(true)
                            videoTrack.addSink(remoteRenderer)
                            // Ensure remote renderer is visible
                            remoteRenderer.visibility = View.VISIBLE
                            Log.d(TAG, "Remote video from stream added, renderer visible")
                        }
                        if (stream.audioTracks.isNotEmpty()) {
                            val audioTrack = stream.audioTracks[0]
                            audioTrack.setEnabled(true)
                        }
                    }
                }
                
                override fun onSignalingChange(state: PeerConnection.SignalingState) {
                    Log.d(TAG, "Signaling state: $state")
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                    Log.d(TAG, "ICE gathering state: $state")
                }
                override fun onRemoveStream(stream: MediaStream) {}
                override fun onDataChannel(channel: DataChannel) {}
                override fun onRenegotiationNeeded() {
                    Log.d(TAG, "Renegotiation needed")
                }
            }
        )

        startLocalMedia()
    }

    // ===================== SIGNALING =====================

    private fun initSignaling() {
        Log.d(TAG, "Setting up signaling listener")
        
        // Use the shared SignalingManager instead of creating a new connection
        SignalingManager.setCallListener(object : SignalingManager.CallSignalingListener {

            override fun onOfferReceived(from: String, sdp: SessionDescription, isVideoCall: Boolean, callId: String) {
                Log.d(TAG, "Offer received from $from (while in call, callId: $callId)")
                // We're already in a call, ignore new offers
            }

            override fun onAnswerReceived(sdp: SessionDescription, callId: String) {
                Log.d(TAG, "Answer received (callId: $callId)")
                // Store the server's callId
                currentCallId = callId
                runOnUiThread {
                    updateCallState(CallState.CONNECTED)
                }
                peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        Log.d(TAG, "Remote description (answer) set successfully")
                        isRemoteDescriptionSet = true
                        drainPendingIceCandidates()
                    }
                    override fun onSetFailure(error: String) {
                        Log.e(TAG, "Failed to set remote description: $error")
                    }
                }, sdp)
            }

            override fun onIceCandidateReceived(candidate: IceCandidate) {
                Log.d(TAG, "Remote ICE candidate received: ${candidate.sdp}")
                Log.d(TAG, "  Type: ${if (candidate.sdp.contains("typ relay")) "RELAY (TURN)" else if (candidate.sdp.contains("typ srflx")) "SRFLX (STUN)" else if (candidate.sdp.contains("typ host")) "HOST" else "UNKNOWN"}")
                if (isRemoteDescriptionSet) {
                    peerConnection?.addIceCandidate(candidate)
                    Log.d(TAG, "  Added to peer connection")
                } else {
                    Log.d(TAG, "  Queued (remote description not set)")
                    pendingIceCandidates.add(candidate)
                }
            }
            
            override fun onCallRejected() {
                runOnUiThread {
                    Toast.makeText(this@CallActivity, "Call rejected", Toast.LENGTH_SHORT).show()
                    updateCallState(CallState.ENDED)
                    finish()
                }
            }

            override fun onCallEnded() {
                runOnUiThread {
                    Toast.makeText(this@CallActivity, "Call ended by peer", Toast.LENGTH_SHORT).show()
                    updateCallState(CallState.ENDED)
                    finish()
                }
            }
            
            override fun onEmojiReceived(emoji: String) {
                Log.d(TAG, "========= CallActivity received emoji =========")
                Log.d(TAG, "Emoji: $emoji")
                Log.d(TAG, "Current thread: ${Thread.currentThread().name}")
                Log.d(TAG, "Activity state: isFinishing=$isFinishing, isDestroyed=${if (android.os.Build.VERSION.SDK_INT >= 17) isDestroyed else "N/A"}")
                runOnUiThread {
                    Log.d(TAG, "Running on UI thread, showing animation...")
                    try {
                        showEmojiAnimation(emoji)
                        Log.d(TAG, "Animation started successfully")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error showing emoji animation: ${e.message}", e)
                    }
                }
            }
        })
    }

    private fun drainPendingIceCandidates() {
        Log.d(TAG, "Draining ${pendingIceCandidates.size} pending ICE candidates")
        for (candidate in pendingIceCandidates) {
            peerConnection?.addIceCandidate(candidate)
        }
        pendingIceCandidates.clear()
    }

    // ===================== CALL FLOW =====================

    private fun startCallAsCaller() {
        Log.d(TAG, "Creating offer as caller")
        updateCallState(CallState.CALLING)
        
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        
        peerConnection?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(offer: SessionDescription) {
                Log.d(TAG, "Offer created successfully")
                peerConnection?.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        Log.d(TAG, "Local description (offer) set successfully")
                        SignalingManager.sendOffer(peerId, offer, isVideoCall)
                    }
                    override fun onSetFailure(error: String) {
                        Log.e(TAG, "Failed to set local description: $error")
                    }
                }, offer)
            }
            override fun onCreateFailure(error: String) {
                Log.e(TAG, "Failed to create offer: $error")
            }
        }, constraints)
    }

    private fun handleIncomingCall() {
        // If we have incoming SDP from UserListActivity, handle it
        incomingSdp?.let { sdp ->
            Log.d(TAG, "Handling incoming call from $peerId")
            updateCallState(CallState.RINGING)
            val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, sdp)
            
            AlertDialog.Builder(this)
                .setTitle("Incoming Call")
                .setMessage("$peerId is calling you...")
                .setCancelable(false)
                .setPositiveButton("Accept") { _, _ ->
                    acceptIncomingCall(sessionDescription)
                }
                .setNegativeButton("Reject") { _, _ ->
                    SignalingManager.sendReject(peerId)
                    updateCallState(CallState.ENDED)
                    finish()
                }
                .show()
        }
    }

    private fun acceptIncomingCall(sdp: SessionDescription) {
        Log.d(TAG, "Accepting incoming call")
        updateCallState(CallState.CONNECTED)
        
        peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                Log.d(TAG, "Remote description (offer) set successfully")
                isRemoteDescriptionSet = true
                drainPendingIceCandidates()
                createAnswer()
            }
            override fun onSetFailure(error: String) {
                Log.e(TAG, "Failed to set remote description: $error")
            }
        }, sdp)
    }

    private fun createAnswer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        
        peerConnection?.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(answer: SessionDescription) {
                Log.d(TAG, "Answer created successfully")
                peerConnection?.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        Log.d(TAG, "Local description (answer) set successfully")
                        SignalingManager.sendAnswer(peerId, answer, currentCallId)
                    }
                    override fun onSetFailure(error: String) {
                        Log.e(TAG, "Failed to set local description: $error")
                    }
                }, answer)
            }
            override fun onCreateFailure(error: String) {
                Log.e(TAG, "Failed to create answer: $error")
            }
        }, constraints)
    }

    // ===================== MEDIA =====================

    private fun startLocalMedia() {
        Log.d(TAG, "Starting local media")
        
        val videoSource = peerConnectionFactory.createVideoSource(false)

        surfaceTextureHelper =
            SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)

        videoCapturer = createCameraCapturer()
        videoCapturer?.initialize(
            surfaceTextureHelper,
            this,
            videoSource.capturerObserver
        )
        // Higher resolution for better quality (1280x720 @ 30fps)
        videoCapturer?.startCapture(1280, 720, 30)

        localVideoTrack =
            peerConnectionFactory.createVideoTrack("video_track", videoSource)
        localVideoTrack?.setEnabled(true)
        localVideoTrack?.addSink(localRenderer)

        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        
        val audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory.createAudioTrack("audio_track", audioSource)
        localAudioTrack?.setEnabled(true)

        // Create a media stream and add tracks to it
        val streamIds = listOf("local_stream")
        
        // Use addTrack for UNIFIED_PLAN SDP semantics
        localVideoTrack?.let { 
            peerConnection?.addTrack(it, streamIds)
            Log.d(TAG, "Added local video track to peer connection")
        }
        localAudioTrack?.let { 
            peerConnection?.addTrack(it, streamIds)
            Log.d(TAG, "Added local audio track to peer connection")
        }
        
        Log.d(TAG, "Local media started, tracks added to peer connection")
    }

    private fun createCameraCapturer(): VideoCapturer? {
        // Try Camera2 first
        val camera2 = Camera2Enumerator(this)
        for (device in camera2.deviceNames) {
            if (camera2.isFrontFacing(device)) {
                Log.d(TAG, "Using front camera (Camera2): $device")
                return camera2.createCapturer(device, null)
            }
        }

        // Fallback: ANY camera (emulator case)
        for (device in camera2.deviceNames) {
            Log.d(TAG, "Using camera (Camera2): $device")
            return camera2.createCapturer(device, null)
        }

        // Camera1 fallback (older devices / emulator)
        val camera1 = Camera1Enumerator(false)
        for (device in camera1.deviceNames) {
            if (camera1.isFrontFacing(device)) {
                Log.d(TAG, "Using front camera (Camera1): $device")
                return camera1.createCapturer(device, null)
            }
        }

        // Last fallback: ANY camera
        for (device in camera1.deviceNames) {
            Log.d(TAG, "Using camera (Camera1): $device")
            return camera1.createCapturer(device, null)
        }

        Log.e(TAG, "No camera available on this device")
        return null
    }


    // ===================== URL SELECTION =====================

    private fun getSignalingUrl(): String {
        return if (isEmulator()) {
            com.yourapp.webrtcapp.utils.Constants.EMULATOR_SERVER_URL
        } else {
            com.yourapp.webrtcapp.utils.Constants.DEVICE_SERVER_URL
        }
    }

    private fun isEmulator(): Boolean {
        return android.os.Build.FINGERPRINT.contains("generic")
    }

    // ===================== UI CONTROLS =====================

    private fun setupUIControls() {
        muteBtn.setOnClickListener {
            toggleMute()
        }

        endCallBtn.setOnClickListener {
            endCall()
        }

        videoToggleBtn.setOnClickListener {
            toggleVideo()
        }
        
        speakerBtn.setOnClickListener {
            toggleSpeaker()
        }
        
        audioOnlyBtn.setOnClickListener {
            toggleAudioOnlyMode()
        }
        
        switchCameraBtn.setOnClickListener {
            switchCamera()
        }
        
        qualityBtn.setOnClickListener {
            showQualityPicker()
        }
        
        statsHeader.setOnClickListener {
            toggleStatsExpanded()
        }
        
        emojiToggle.setOnClickListener {
            toggleEmojiList()
        }
    }
    
    private fun toggleStatsExpanded() {
        isStatsExpanded = !isStatsExpanded
        if (isStatsExpanded) {
            statsDetails.visibility = View.VISIBLE
            statsExpandArrow.rotation = 180f
        } else {
            statsDetails.visibility = View.GONE
            statsExpandArrow.rotation = 0f
        }
    }
    
    private fun toggleEmojiList() {
        isEmojiListVisible = !isEmojiListVisible
        emojiList.visibility = if (isEmojiListVisible) View.VISIBLE else View.GONE
    }
    
    private fun showQualityPicker() {
        val qualities = arrayOf(
            "Low (240p) - Save Data",
            "Medium (480p)",
            "High (720p) - Best Quality",
            "Auto (Recommended)"
        )
        
        // Default to Auto (index 3) if auto quality is enabled
        var selectedIndex = if (isAutoQuality) 3 else when (networkQuality) {
            NetworkQuality.POOR -> 0
            NetworkQuality.MODERATE -> 1
            NetworkQuality.GOOD -> 2
        }
        
        AlertDialog.Builder(this)
            .setTitle("Video Quality")
            .setSingleChoiceItems(qualities, selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton("Apply") { _, _ ->
                when (selectedIndex) {
                    0 -> {
                        isAutoQuality = false
                        applyQualityLevel(NetworkQuality.POOR, "Low (240p)")
                    }
                    1 -> {
                        isAutoQuality = false
                        applyQualityLevel(NetworkQuality.MODERATE, "Medium (480p)")
                    }
                    2 -> {
                        isAutoQuality = false
                        applyQualityLevel(NetworkQuality.GOOD, "High (720p)")
                    }
                    3 -> {
                        isAutoQuality = true
                        Toast.makeText(this, "Auto quality enabled - adapts to network", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun applyQualityLevel(quality: NetworkQuality, name: String) {
        networkQuality = quality
        val (width, height, fps, bitrate) = when (quality) {
            NetworkQuality.POOR -> listOf(320, 240, 15, 300_000)
            NetworkQuality.MODERATE -> listOf(640, 480, 24, 800_000)
            NetworkQuality.GOOD -> listOf(1280, 720, 30, 2_000_000)
        }
        
        // Update current resolution tracking
        currentVideoResolution = "${height}p"
        
        // Apply video encoding parameters
        try {
            videoCapturer?.changeCaptureFormat(width, height, fps)
            Log.d(TAG, "Applied quality: $name (${width}x${height}@${fps}fps)")
            Toast.makeText(this, "Quality: $name", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply quality: ${e.message}")
        }
    }
    
    private fun setupEmojiBar() {
        val emojis = listOf(
            R.id.emoji1 to "👍",
            R.id.emoji2 to "❤️",
            R.id.emoji3 to "😂",
            R.id.emoji4 to "😮",
            R.id.emoji5 to "👏"
        )
        
        emojis.forEach { (viewId, emoji) ->
            findViewById<TextView>(viewId).setOnClickListener {
                sendEmoji(emoji)
            }
        }
    }
    
    private fun sendEmoji(emoji: String) {
        SignalingManager.sendEmoji(peerId, emoji)
        // Don't show locally - emoji only shows on receiver side
        Toast.makeText(this, "Sent $emoji", Toast.LENGTH_SHORT).show()
    }
    
    private fun showEmojiAnimation(emoji: String) {
        // Create a new TextView for the bubble animation
        val emojiView = TextView(this).apply {
            text = emoji
            textSize = 80f
            alpha = 0f
            scaleX = 0.3f
            scaleY = 0.3f
        }
        
        // Add to the root view
        val rootView = findViewById<ViewGroup>(android.R.id.content)
        rootView.addView(emojiView)
        
        // Position at bottom center
        emojiView.post {
            val screenHeight = rootView.height
            val screenWidth = rootView.width
            
            emojiView.x = (screenWidth / 2f) - (emojiView.width / 2f)
            emojiView.y = screenHeight.toFloat() - 100f  // Start from bottom
            
            // Target Y - middle of screen
            val targetY = screenHeight / 2f - (emojiView.height / 2f)
            
            // Create animations
            val fadeIn = ObjectAnimator.ofFloat(emojiView, "alpha", 0f, 1f).apply {
                duration = 300
            }
            
            val scaleUpX = ObjectAnimator.ofFloat(emojiView, "scaleX", 0.3f, 1.2f, 1f).apply {
                duration = 500
            }
            
            val scaleUpY = ObjectAnimator.ofFloat(emojiView, "scaleY", 0.3f, 1.2f, 1f).apply {
                duration = 500
            }
            
            val floatUp = ObjectAnimator.ofFloat(emojiView, "y", emojiView.y, targetY).apply {
                duration = 1500
                interpolator = AccelerateDecelerateInterpolator()
            }
            
            val fadeOut = ObjectAnimator.ofFloat(emojiView, "alpha", 1f, 0f).apply {
                duration = 500
                startDelay = 1500
            }
            
            // Play animations
            AnimatorSet().apply {
                playTogether(fadeIn, scaleUpX, scaleUpY, floatUp)
                start()
            }
            
            // Start fade out after float up
            Handler(Looper.getMainLooper()).postDelayed({
                fadeOut.start()
                fadeOut.addListener(object : android.animation.Animator.AnimatorListener {
                    override fun onAnimationStart(animation: android.animation.Animator) {}
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        rootView.removeView(emojiView)
                    }
                    override fun onAnimationCancel(animation: android.animation.Animator) {}
                    override fun onAnimationRepeat(animation: android.animation.Animator) {}
                })
            }, 1500)
        }
    }

    private fun toggleMute() {
        isMuted = !isMuted
        localAudioTrack?.setEnabled(!isMuted)
        muteBtn.setImageResource(if (isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic)
    }

    private fun toggleVideo() {
        isVideoEnabled = !isVideoEnabled
        localVideoTrack?.setEnabled(isVideoEnabled)
        videoToggleBtn.setImageResource(if (isVideoEnabled) R.drawable.ic_videocam else R.drawable.ic_videocam_off)
        
        // Show/hide local preview
        localRenderer.visibility = if (isVideoEnabled) View.VISIBLE else View.GONE
    }
    
    private fun toggleSpeaker() {
        isSpeakerOn = !isSpeakerOn
        setSpeakerphone(isSpeakerOn)
        speakerBtn.setImageResource(if (isSpeakerOn) R.drawable.ic_speaker else R.drawable.ic_speaker_off)
        Toast.makeText(this, if (isSpeakerOn) "Speaker ON" else "Speaker OFF", Toast.LENGTH_SHORT).show()
    }
    
    private fun setSpeakerphone(enabled: Boolean) {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = enabled
    }
    
    private fun toggleAudioOnlyMode() {
        if (isAudioOnlyMode) {
            disableAudioOnlyMode()
        } else {
            // Ask for confirmation before switching to audio-only
            AlertDialog.Builder(this)
                .setTitle("Switch to Audio Only?")
                .setMessage("This will disable video to save bandwidth. You can turn it back on later.")
                .setPositiveButton("Yes") { _, _ -> enableAudioOnlyMode() }
                .setNegativeButton("No", null)
                .show()
        }
    }
    
    private fun enableAudioOnlyMode() {
        isAudioOnlyMode = true
        isVideoEnabled = false
        
        // Disable video
        localVideoTrack?.setEnabled(false)
        
        // Hide video views
        localRenderer.visibility = View.GONE
        remoteRenderer.visibility = View.GONE
        
        // Show audio-only indicator
        audioOnlyIndicator.visibility = View.VISIBLE
        
        // Update buttons
        audioOnlyBtn.setBackgroundResource(R.drawable.btn_circle_green)
        videoToggleBtn.isEnabled = false
        videoToggleBtn.alpha = 0.5f
        switchCameraBtn.isEnabled = false
        switchCameraBtn.alpha = 0.5f
        
        // Adjust video quality to minimum
        adjustVideoQuality(NetworkQuality.POOR)
        
        Toast.makeText(this, "Audio Only Mode - Video disabled", Toast.LENGTH_SHORT).show()
    }
    
    private fun disableAudioOnlyMode() {
        isAudioOnlyMode = false
        isVideoEnabled = true
        
        // Enable video
        localVideoTrack?.setEnabled(true)
        
        // Show video views
        localRenderer.visibility = View.VISIBLE
        remoteRenderer.visibility = View.VISIBLE
        
        // Hide audio-only indicator
        audioOnlyIndicator.visibility = View.GONE
        
        // Update buttons
        audioOnlyBtn.setBackgroundResource(R.drawable.btn_circle_background)
        videoToggleBtn.isEnabled = true
        videoToggleBtn.alpha = 1f
        switchCameraBtn.isEnabled = true
        switchCameraBtn.alpha = 1f
        
        // Restore video quality
        adjustVideoQuality(networkQuality)
        
        Toast.makeText(this, "Video Enabled", Toast.LENGTH_SHORT).show()
    }
    
    private fun switchCamera() {
        if (videoCapturer is CameraVideoCapturer) {
            isFrontCamera = !isFrontCamera
            (videoCapturer as CameraVideoCapturer).switchCamera(null)
            localRenderer.setMirror(isFrontCamera)
        }
    }

    private fun endCall() {
        // Save call stats before ending
        saveCallStatsToServer()
        
        SignalingManager.sendHangup(peerId, currentCallId)
        updateCallState(CallState.ENDED)
        finish()
    }
    
    private fun saveCallStatsToServer() {
        if (statsSamples.isEmpty() || currentCallId == null) {
            Log.d(TAG, "No stats to save or call not connected")
            return
        }
        
        val duration = if (callStartTime > 0) (System.currentTimeMillis() - callStartTime) / 1000 else 0
        
        // Calculate summary stats
        val avgSendBitrate = statsSamples.map { it.sendBitrateKbps }.average()
        val avgReceiveBitrate = statsSamples.map { it.receiveBitrateKbps }.average()
        val avgPacketLoss = statsSamples.map { it.packetLossPercent }.average()
        val avgRtt = statsSamples.map { it.rttMs }.average()
        val totalDataUsed = statsSamples.lastOrNull()?.dataUsedBytes ?: 0L
        
        // Calculate quality distribution
        val goodCount = statsSamples.count { it.networkQuality == "GOOD" }
        val moderateCount = statsSamples.count { it.networkQuality == "MODERATE" }
        val poorCount = statsSamples.count { it.networkQuality == "POOR" }
        
        // Build JSON for stats samples
        val samplesJson = org.json.JSONArray()
        statsSamples.forEach { sample ->
            samplesJson.put(org.json.JSONObject().apply {
                put("timestamp", sample.timestamp)
                put("sendBitrateKbps", sample.sendBitrateKbps)
                put("receiveBitrateKbps", sample.receiveBitrateKbps)
                put("packetLossPercent", sample.packetLossPercent)
                put("rttMs", sample.rttMs)
                put("networkQuality", sample.networkQuality)
                put("dataUsedBytes", sample.dataUsedBytes)
            })
        }
        
        val statsPayload = org.json.JSONObject().apply {
            put("callId", currentCallId)
            put("caller", myId)
            put("callee", peerId)
            put("isVideo", isVideoCall)
            put("duration", duration)
            put("totalSamples", statsSamples.size)
            put("avgSendBitrateKbps", avgSendBitrate)
            put("avgReceiveBitrateKbps", avgReceiveBitrate)
            put("avgPacketLossPercent", avgPacketLoss)
            put("avgRttMs", avgRtt)
            put("totalDataUsedBytes", totalDataUsed)
            put("qualityDistribution", org.json.JSONObject().apply {
                put("good", goodCount)
                put("moderate", moderateCount)
                put("poor", poorCount)
            })
            put("samples", samplesJson)
        }
        
        Log.d(TAG, "Saving call stats: ${statsSamples.size} samples, duration: ${duration}s")
        
        // Send via WebSocket
        SignalingManager.sendCallStats(statsPayload)
    }

    // ===================== CALL STATE =====================

    private fun updateCallState(newState: CallState) {
        callState = newState
        Log.d(TAG, "Call state updated to: $newState")
        
        // Track call start time when connected
        if (newState == CallState.CONNECTED && callStartTime == 0L) {
            callStartTime = System.currentTimeMillis()
            lastStatsSaveTime = callStartTime
            // Use server's callId if available, otherwise generate our own
            if (currentCallId == null) {
                currentCallId = SignalingManager.getCurrentCallId() ?: "${myId}_${peerId}_${callStartTime}"
            }
            statsSamples.clear()  // Clear any previous samples
            Log.d(TAG, "Call started, callId: $currentCallId")
            
            // Start the call duration timer
            startCallTimer()
        }
        
        // Stop timer when call ends
        if (newState == CallState.ENDED) {
            stopCallTimer()
        }
        
        runOnUiThread {
            callStateText.text = when (callState) {
                CallState.IDLE -> "Connecting..."
                CallState.CALLING -> "Calling $peerId..."
                CallState.RINGING -> "Incoming call from $peerId"
                CallState.CONNECTED -> "Connected to $peerId"
                CallState.ENDED -> "Call Ended"
            }
            
            // Update quality indicator
            if (callState == CallState.CONNECTED) {
                updateCallQualityText()
            }
        }
    }
    
    private fun startCallTimer() {
        timerHandler.post(object : Runnable {
            override fun run() {
                if (callState == CallState.CONNECTED && callStartTime > 0) {
                    val elapsed = (System.currentTimeMillis() - callStartTime) / 1000
                    val minutes = elapsed / 60
                    val seconds = elapsed % 60
                    runOnUiThread {
                        callDurationText.text = " • %02d:%02d".format(minutes, seconds)
                    }
                    timerHandler.postDelayed(this, 1000)
                }
            }
        })
    }
    
    private fun stopCallTimer() {
        timerHandler.removeCallbacksAndMessages(null)
    }
    
    private fun updateCallQualityText() {
        val qualityText = when (networkQuality) {
            NetworkQuality.GOOD -> "📶 Excellent"
            NetworkQuality.MODERATE -> "📶 Good"
            NetworkQuality.POOR -> "📶 Poor"
        }
        val qualityColor = when (networkQuality) {
            NetworkQuality.GOOD -> android.graphics.Color.parseColor("#4CAF50")
            NetworkQuality.MODERATE -> android.graphics.Color.parseColor("#FFC107")
            NetworkQuality.POOR -> android.graphics.Color.parseColor("#F44336")
        }
        callQualityText.text = qualityText
        callQualityText.setTextColor(qualityColor)
    }

    // ===================== NETWORK STATS =====================

    private fun startStatsMonitoring() {
        statsHandler.postDelayed(object : Runnable {
            override fun run() {
                if (callState == CallState.CONNECTED && peerConnection != null) {
                    getNetworkStats()
                }
                statsHandler.postDelayed(this, 1000) // Every 1 second for better accuracy
            }
        }, 2000)  // Start after 2 seconds to allow connection to establish
    }

    private fun getNetworkStats() {
        peerConnection?.getStats { report ->
            var totalBytesSent = 0L
            var totalBytesReceived = 0L
            var totalPacketsSent = 0L
            var totalPacketsLost = 0L
            var rtt = 0.0
            var currentTimestamp = System.currentTimeMillis()

            for (stats in report.statsMap.values) {
                when (stats.type) {
                    // Outbound RTP stats for bitrate calculation
                    "outbound-rtp" -> {
                        // Use "kind" (modern) or fallback to "mediaType" (legacy)
                        val kind = (stats.members["kind"] as? String) 
                            ?: (stats.members["mediaType"] as? String)
                        // Collect both video and audio stats
                        val bytesSent = (stats.members["bytesSent"] as? Number)?.toLong() ?: 0L
                        val packetsSent = (stats.members["packetsSent"] as? Number)?.toLong() ?: 0L
                        totalBytesSent += bytesSent
                        totalPacketsSent += packetsSent
                    }
                    
                    // Inbound RTP stats
                    "inbound-rtp" -> {
                        val kind = (stats.members["kind"] as? String) 
                            ?: (stats.members["mediaType"] as? String)
                        // Collect both video and audio stats
                        val bytesReceived = (stats.members["bytesReceived"] as? Number)?.toLong() ?: 0L
                        val packetsLost = (stats.members["packetsLost"] as? Number)?.toLong() ?: 0L
                        totalBytesReceived += bytesReceived
                        totalPacketsLost += packetsLost
                    }

                    // Remote inbound stats for RTT
                    "remote-inbound-rtp" -> {
                        val roundTripTime = (stats.members["roundTripTime"] as? Number)?.toDouble()
                        if (roundTripTime != null && roundTripTime > 0) {
                            rtt = roundTripTime
                        }
                    }

                    // Candidate pair for RTT (more reliable source)
                    "candidate-pair" -> {
                        val state = stats.members["state"] as? String
                        if (state == "succeeded") {
                            val currentRtt = (stats.members["currentRoundTripTime"] as? Number)?.toDouble()
                            if (currentRtt != null && currentRtt > 0) {
                                rtt = currentRtt
                            }
                        }
                    }
                }
            }

            // Calculate bitrate
            var sendBitrateKbps = 0L
            var receiveBitrateKbps = 0L
            
            if (lastTimestamp > 0 && currentTimestamp > lastTimestamp) {
                val timeDiffSeconds = (currentTimestamp - lastTimestamp) / 1000.0
                if (timeDiffSeconds > 0) {
                    val bytesSentDiff = totalBytesSent - lastBytesSent
                    val bytesReceivedDiff = totalBytesReceived - lastBytesReceived
                    
                    sendBitrateKbps = ((bytesSentDiff * 8) / timeDiffSeconds / 1000).toLong()
                    receiveBitrateKbps = ((bytesReceivedDiff * 8) / timeDiffSeconds / 1000).toLong()
                }
            }

            // Calculate packet loss percentage
            var packetLossPercent = 0.0
            if (totalPacketsSent > lastPacketsSent) {
                val packetsSentDiff = totalPacketsSent - lastPacketsSent
                val packetsLostDiff = totalPacketsLost - lastPacketsLost
                if (packetsSentDiff > 0) {
                    packetLossPercent = (packetsLostDiff.toDouble() / packetsSentDiff.toDouble()) * 100.0
                    if (packetLossPercent < 0) packetLossPercent = 0.0
                    if (packetLossPercent > 100) packetLossPercent = 100.0
                }
            }

            // Update last values
            lastBytesSent = totalBytesSent
            lastBytesReceived = totalBytesReceived
            lastTimestamp = currentTimestamp
            lastPacketsSent = totalPacketsSent
            lastPacketsLost = totalPacketsLost
            
            // Update cumulative data usage
            totalDataUsedBytes = totalBytesSent + totalBytesReceived

            // Convert RTT to milliseconds
            val rttMs = (rtt * 1000).toInt()

            updateNetworkQuality(sendBitrateKbps, packetLossPercent, rtt)
            updateStatsUI(sendBitrateKbps, receiveBitrateKbps, packetLossPercent, rttMs, totalDataUsedBytes)
        }
    }

    private fun updateNetworkQuality(bitrate: Long, packetLoss: Double, rtt: Double) {
        // Adjusted thresholds for TURN-relay connections (very forgiving)
        // 500ms RTT is normal for relay, only consider poor if very high loss or RTT > 1s
        val newQuality = when {
            packetLoss > 15.0 || rtt > 1.0 -> NetworkQuality.POOR
            packetLoss > 5.0 || rtt > 0.6 -> NetworkQuality.MODERATE
            else -> NetworkQuality.GOOD
        }

        if (newQuality != networkQuality) {
            networkQuality = newQuality
            
            // Track when poor quality started
            if (newQuality == NetworkQuality.POOR) {
                if (poorQualityStartTime == 0L) {
                    poorQualityStartTime = System.currentTimeMillis()
                }
            } else {
                // Quality improved - reset the timer
                poorQualityStartTime = 0L
            }
            
            if (!isAudioOnlyMode) {
                adjustVideoQuality(newQuality)
            }
        }
        
        // Check if we should show the poor quality dialog (after 20 seconds of continuous poor quality)
        if (networkQuality == NetworkQuality.POOR && 
            !isAudioOnlyMode && 
            isVideoCall && 
            !dontShowPoorQualityDialog &&
            !isPoorQualityDialogShowing &&
            poorQualityStartTime > 0 &&
            (System.currentTimeMillis() - poorQualityStartTime) >= POOR_QUALITY_THRESHOLD_MS) {
            
            runOnUiThread {
                suggestAudioOnlyMode()
            }
        }
    }
    
    private fun suggestAudioOnlyMode() {
        if (isPoorQualityDialogShowing || dontShowPoorQualityDialog) return
        isPoorQualityDialogShowing = true
        
        // Create checkbox view
        val checkBox = android.widget.CheckBox(this).apply {
            text = "Don't show this again"
            setPadding(48, 24, 48, 8)
        }
        
        AlertDialog.Builder(this)
            .setTitle("Poor Network Quality")
            .setMessage("Your network connection has been unstable for a while. Would you like to switch to Audio Only mode for better call quality?")
            .setView(checkBox)
            .setPositiveButton("Switch to Audio Only") { _, _ -> 
                if (checkBox.isChecked) {
                    dontShowPoorQualityDialog = true
                }
                enableAudioOnlyMode()
                isPoorQualityDialogShowing = false
            }
            .setNegativeButton("Keep Video") { _, _ ->
                if (checkBox.isChecked) {
                    dontShowPoorQualityDialog = true
                }
                isPoorQualityDialogShowing = false
                // Reset timer so it doesn't show again immediately
                poorQualityStartTime = System.currentTimeMillis()
            }
            .setOnCancelListener {
                isPoorQualityDialogShowing = false
                // Reset timer on cancel too
                poorQualityStartTime = System.currentTimeMillis()
            }
            .show()
    }

    private fun updateStatsUI(sendBitrate: Long, receiveBitrate: Long, packetLoss: Double, rttMs: Int, dataUsedBytes: Long) {
        // Collect stats sample every 3 seconds for database storage
        val currentTime = System.currentTimeMillis()
        if (callStartTime > 0 && (currentTime - lastStatsSaveTime) >= STATS_SAVE_INTERVAL_MS) {
            lastStatsSaveTime = currentTime
            val sample = StatsSample(
                timestamp = currentTime,
                sendBitrateKbps = sendBitrate,
                receiveBitrateKbps = receiveBitrate,
                packetLossPercent = packetLoss,
                rttMs = rttMs,
                networkQuality = networkQuality.name,
                dataUsedBytes = dataUsedBytes
            )
            statsSamples.add(sample)
            Log.d(TAG, "Stats sample collected (every 3s): bitrate=${sendBitrate}/${receiveBitrate}, loss=${"%.2f".format(packetLoss)}%, rtt=${rttMs}ms, samples count=${statsSamples.size}")
        }
        
        // Update quality indicator text
        runOnUiThread {
            updateCallQualityText()
        }
        
        runOnUiThread {
            networkStatsText.text = buildString {
                append("↑ ${sendBitrate} kbps | ↓ ${receiveBitrate} kbps\n")
                append("Loss: ${"%.2f".format(packetLoss)}% | RTT: ${rttMs} ms")
            }
            
            // Show quality with auto indicator and current resolution
            val qualityIcon = when (networkQuality) {
                NetworkQuality.GOOD -> "✅"
                NetworkQuality.MODERATE -> "⚠️"
                NetworkQuality.POOR -> "❌"
            }
            val autoIndicator = if (isAutoQuality) " (Auto)" else ""
            networkQualityText.text = "$qualityIcon ${networkQuality.name}$autoIndicator | 📹 $currentVideoResolution"
            
            // Update network type
            networkTypeText.text = "Network: ${getNetworkType()}"
            
            // Update data usage (convert to MB)
            val dataUsedMB = dataUsedBytes / (1024.0 * 1024.0)
            dataUsageText.text = "Data: ${"%.2f".format(dataUsedMB)} MB"
        }
    }

    // ===================== ADAPTIVE QUALITY =====================

    private fun adjustVideoQuality(quality: NetworkQuality) {
        Log.d(TAG, "Adjusting video quality to: $quality")
        
        // Update resolution tracking based on quality level
        currentVideoResolution = when (quality) {
            NetworkQuality.GOOD -> "720p"
            NetworkQuality.MODERATE -> "480p"
            NetworkQuality.POOR -> "240p"
        }
        
        peerConnection?.senders?.forEach { sender ->
            if (sender.track()?.kind() == "video") {
                val parameters = sender.parameters
                if (parameters.encodings.isNotEmpty()) {
                    // Bitrates for video (higher for better quality)
                    parameters.encodings[0].maxBitrateBps = when (quality) {
                        NetworkQuality.GOOD -> 2_500_000     // 2.5 Mbps for good networks
                        NetworkQuality.MODERATE -> 1_200_000 // 1.2 Mbps for moderate
                        NetworkQuality.POOR -> 500_000       // 500 Kbps for poor
                    }
                    sender.parameters = parameters
                }
            }
        }
    }

    // ===================== CLEANUP =====================

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "CallActivity destroyed, cleaning up")

        statsHandler.removeCallbacksAndMessages(null)
        timerHandler.removeCallbacksAndMessages(null)

        try {
            videoCapturer?.stopCapture()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping video capturer: ${e.message}")
        }

        videoCapturer?.dispose()
        surfaceTextureHelper?.dispose()

        localVideoTrack?.dispose()
        localAudioTrack?.dispose()

        peerConnection?.close()
        peerConnection?.dispose()
        peerConnectionFactory.dispose()

        localRenderer.release()
        remoteRenderer.release()
        eglBase.release()
        
        // Don't disconnect SignalingManager - it's shared
        SignalingManager.setCallListener(null)
    }

    // ===================== NETWORK HELPERS =====================
    
    private fun getDeviceIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                // Skip loopback and inactive interfaces
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    // Only IPv4 addresses
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        Log.d(TAG, "Found network interface: ${networkInterface.displayName} -> ${address.hostAddress}")
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
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            else -> "Unknown"
        }
    }
}
