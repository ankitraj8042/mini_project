package com.yourapp.webrtcapp.signaling

import com.yourapp.webrtcapp.model.IceCandidateModel

data class SignalingMessage(
    val type: String,
    val from: String? = null,
    val to: String? = null,
    val sdp: String? = null,
    val candidate: IceCandidateModel? = null,
    val users: List<String>? = null  // For userList messages
)
