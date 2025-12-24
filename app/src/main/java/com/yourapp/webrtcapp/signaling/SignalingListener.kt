package com.yourapp.webrtcapp.signaling

import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

interface SignalingListener {
    fun onOfferReceived(from: String, sdp: SessionDescription)
    fun onAnswerReceived(sdp: SessionDescription)
    fun onIceCandidateReceived(candidate: IceCandidate)
    fun onCallRejected()
    fun onCallEnded()
    fun onUserListUpdated(users: List<String>)
    fun onConnectionError(error: String)
}
