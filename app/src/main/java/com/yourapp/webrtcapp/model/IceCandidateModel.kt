package com.yourapp.webrtcapp.model

data class IceCandidateModel(
    val sdpMid: String,
    val sdpMLineIndex: Int,
    val candidate: String
)
