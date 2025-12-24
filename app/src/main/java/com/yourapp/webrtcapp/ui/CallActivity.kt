package com.yourapp.webrtcapp.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
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
    private lateinit var muteBtn: ImageButton
    private lateinit var endCallBtn: ImageButton
    private lateinit var videoToggleBtn: ImageButton
    
    // Media tracks
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null
    
    // State management
    private var callState = CallState.IDLE
    private var isMuted = false
    private var isVideoEnabled = true
    private var networkQuality = NetworkQuality.GOOD
    private var isCaller = false
    private var incomingSdp: String? = null
    
    // ICE candidates queue (for candidates received before remote description is set)
    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    private var isRemoteDescriptionSet = false
    
    // Stats monitoring
    private val statsHandler = Handler(Looper.getMainLooper())
    private var lastBytesSent = 0L
    private var lastBytesReceived = 0L
    private var lastTimestamp = 0L
    private var lastPacketsSent = 0L
    private var lastPacketsLost = 0L

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
        incomingSdp = intent.getStringExtra("INCOMING_SDP")

        // Log network information for debugging
        Log.d(TAG, "===========================================")
        Log.d(TAG, "Starting call: myId=$myId, peerId=$peerId, isCaller=$isCaller")
        Log.d(TAG, "Device IP Address: ${getDeviceIpAddress()}")
        Log.d(TAG, "Network Type: ${getNetworkType()}")
        Log.d(TAG, "===========================================")

        localRenderer = findViewById(R.id.localView)
        remoteRenderer = findViewById(R.id.remoteView)
        callStateText = findViewById(R.id.callStateText)
        networkStatsText = findViewById(R.id.networkStatsText)
        networkQualityText = findViewById(R.id.networkQualityText)
        muteBtn = findViewById(R.id.muteBtn)
        endCallBtn = findViewById(R.id.endCallBtn)
        videoToggleBtn = findViewById(R.id.videoToggleBtn)

        // Set initial call state text
        callStateText.text = if (isCaller) "Calling $peerId..." else "Incoming call..."
        networkStatsText.text = ""
        networkQualityText.text = ""

        eglBase = EglBase.create()
        
        localRenderer.init(eglBase.eglBaseContext, null)
        localRenderer.setMirror(true)
        localRenderer.setEnableHardwareScaler(true)
        localRenderer.setZOrderMediaOverlay(true)
        
        remoteRenderer.init(eglBase.eglBaseContext, null)
        remoteRenderer.setEnableHardwareScaler(true)
        remoteRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)

        setupUIControls()
        initWebRTC()
        initSignaling()
        
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
        
        // Log all available network interfaces for debugging
        logNetworkInterfaces()
        
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

            override fun onOfferReceived(from: String, sdp: SessionDescription) {
                Log.d(TAG, "Offer received from $from (while in call)")
                // We're already in a call, ignore new offers
            }

            override fun onAnswerReceived(sdp: SessionDescription) {
                Log.d(TAG, "Answer received")
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
                        SignalingManager.sendOffer(peerId, offer)
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
            IncomingCallDialog(
                this,
                peerId,
                onAccept = {
                    acceptIncomingCall(sessionDescription)
                },
                onReject = {
                    SignalingManager.sendReject(peerId)
                    updateCallState(CallState.ENDED)
                    finish()
                }
            ).show()
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
                        SignalingManager.sendAnswer(peerId, answer)
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
        videoCapturer?.startCapture(640, 480, 30)

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
    }

    private fun endCall() {
        SignalingManager.sendHangup(peerId)
        updateCallState(CallState.ENDED)
        finish()
    }

    // ===================== CALL STATE =====================

    private fun updateCallState(newState: CallState) {
        callState = newState
        Log.d(TAG, "Call state updated to: $newState")
        runOnUiThread {
            callStateText.text = when (callState) {
                CallState.IDLE -> "Connecting..."
                CallState.CALLING -> "Calling $peerId..."
                CallState.RINGING -> "Incoming call from $peerId"
                CallState.CONNECTED -> "Connected to $peerId"
                CallState.ENDED -> "Call Ended"
            }
        }
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
            var hasVideoStats = false

            for (stats in report.statsMap.values) {
                when (stats.type) {
                    // Outbound RTP stats for bitrate calculation
                    "outbound-rtp" -> {
                        val mediaType = stats.members["mediaType"] as? String
                        if (mediaType == "video") {
                            hasVideoStats = true
                            val bytesSent = (stats.members["bytesSent"] as? Number)?.toLong() ?: 0L
                            val packetsSent = (stats.members["packetsSent"] as? Number)?.toLong() ?: 0L
                            totalBytesSent += bytesSent
                            totalPacketsSent += packetsSent
                        }
                    }
                    
                    // Inbound RTP stats
                    "inbound-rtp" -> {
                        val mediaType = stats.members["mediaType"] as? String
                        if (mediaType == "video") {
                            val bytesReceived = (stats.members["bytesReceived"] as? Number)?.toLong() ?: 0L
                            totalBytesReceived += bytesReceived
                        }
                    }

                    // Remote inbound stats for packet loss
                    "remote-inbound-rtp" -> {
                        val packetsLost = (stats.members["packetsLost"] as? Number)?.toLong() ?: 0L
                        val roundTripTime = (stats.members["roundTripTime"] as? Number)?.toDouble()
                        if (roundTripTime != null && roundTripTime > 0) {
                            rtt = roundTripTime
                        }
                        totalPacketsLost += packetsLost
                    }

                    // Candidate pair for RTT
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

            // Convert RTT to milliseconds
            val rttMs = (rtt * 1000).toInt()

            updateNetworkQuality(sendBitrateKbps, packetLossPercent, rtt)
            updateStatsUI(sendBitrateKbps, receiveBitrateKbps, packetLossPercent, rttMs)
        }
    }

    private fun updateNetworkQuality(bitrate: Long, packetLoss: Double, rtt: Double) {
        val newQuality = when {
            packetLoss > 5.0 || rtt > 0.3 -> NetworkQuality.POOR
            packetLoss > 2.0 || rtt > 0.15 -> NetworkQuality.MODERATE
            else -> NetworkQuality.GOOD
        }

        if (newQuality != networkQuality) {
            networkQuality = newQuality
            adjustVideoQuality(newQuality)
        }
    }

    private fun updateStatsUI(sendBitrate: Long, receiveBitrate: Long, packetLoss: Double, rttMs: Int) {
        runOnUiThread {
            networkStatsText.text = buildString {
                append("↑ ${sendBitrate} kbps | ↓ ${receiveBitrate} kbps\n")
                append("Loss: ${"%.2f".format(packetLoss)}% | RTT: ${rttMs} ms")
            }
            
            networkQualityText.text = when (networkQuality) {
                NetworkQuality.GOOD -> "Quality: ✅ Good"
                NetworkQuality.MODERATE -> "Quality: ⚠️ Moderate"
                NetworkQuality.POOR -> "Quality: ❌ Poor"
            }
        }
    }

    // ===================== ADAPTIVE QUALITY =====================

    private fun adjustVideoQuality(quality: NetworkQuality) {
        Log.d(TAG, "Adjusting video quality to: $quality")
        peerConnection?.senders?.forEach { sender ->
            if (sender.track()?.kind() == "video") {
                val parameters = sender.parameters
                if (parameters.encodings.isNotEmpty()) {
                    parameters.encodings[0].maxBitrateBps = when (quality) {
                        NetworkQuality.GOOD -> 1_500_000
                        NetworkQuality.MODERATE -> 800_000
                        NetworkQuality.POOR -> 300_000
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
    
    private fun logNetworkInterfaces() {
        Log.d(TAG, "=== NETWORK INTERFACES ===")
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address) {
                        Log.d(TAG, "  Interface: ${networkInterface.displayName}")
                        Log.d(TAG, "    IP: ${address.hostAddress}")
                        Log.d(TAG, "    isLoopback: ${address.isLoopbackAddress}")
                        Log.d(TAG, "    isUp: ${networkInterface.isUp}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enumerating network interfaces: ${e.message}")
        }
        Log.d(TAG, "=========================")
    }
    
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
