package com.yourapp.webrtcapp.api

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * API Client for backend communication
 * Handles authentication, call history, contacts, and TURN credentials
 */
class ApiClient(private val baseUrl: String) {
    
    companion object {
        private const val TAG = "ApiClient"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    private var authToken: String? = null
    
    fun setAuthToken(token: String?) {
        authToken = token
    }
    
    fun getAuthToken(): String? = authToken
    
    // ==================== AUTH ENDPOINTS ====================
    
    /**
     * Request OTP for phone number
     */
    fun sendOtp(
        phoneNumber: String,
        onSuccess: (SendOtpResponse) -> Unit,
        onError: (String) -> Unit
    ) {
        val json = gson.toJson(mapOf("phoneNumber" to phoneNumber))
        val request = Request.Builder()
            .url("$baseUrl/auth/send-otp")
            .post(json.toRequestBody(JSON))
            .build()
        
        executeAsync(request, SendOtpResponse::class.java, onSuccess, onError)
    }
    
    /**
     * Verify OTP and get auth token
     */
    fun verifyOtp(
        phoneNumber: String,
        otp: String,
        deviceInfo: Map<String, String> = emptyMap(),
        onSuccess: (VerifyOtpResponse) -> Unit,
        onError: (String) -> Unit
    ) {
        val json = gson.toJson(mapOf(
            "phoneNumber" to phoneNumber,
            "otp" to otp,
            "deviceInfo" to deviceInfo
        ))
        val request = Request.Builder()
            .url("$baseUrl/auth/verify-otp")
            .post(json.toRequestBody(JSON))
            .build()
        
        executeAsync(request, VerifyOtpResponse::class.java, { response ->
            authToken = response.authToken
            onSuccess(response)
        }, onError)
    }
    
    // ==================== USER ENDPOINTS ====================
    
    /**
     * Get user profile
     */
    fun getUserProfile(
        onSuccess: (UserProfile) -> Unit,
        onError: (String) -> Unit
    ) {
        val request = authenticatedRequest("$baseUrl/user/profile")
            .get()
            .build()
        
        executeAsync(request, UserProfile::class.java, onSuccess, onError)
    }
    
    /**
     * Update display name
     */
    fun updateProfile(
        displayName: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val json = gson.toJson(mapOf("displayName" to displayName))
        val request = authenticatedRequest("$baseUrl/user/profile")
            .put(json.toRequestBody(JSON))
            .build()
        
        executeAsync(request, SuccessResponse::class.java, { onSuccess() }, onError)
    }
    
    // ==================== CALL HISTORY ENDPOINTS ====================
    
    /**
     * Get call history
     */
    fun getCallHistory(
        limit: Int = 50,
        onSuccess: (List<CallHistoryItem>) -> Unit,
        onError: (String) -> Unit
    ) {
        val request = authenticatedRequest("$baseUrl/calls/history?limit=$limit")
            .get()
            .build()
        
        executeAsync(request, CallHistoryResponse::class.java, { response ->
            onSuccess(response.calls)
        }, onError)
    }
    
    /**
     * Get detailed stats for a specific call
     */
    fun getCallStats(
        callId: String,
        onSuccess: (CallStatsItem) -> Unit,
        onError: (String) -> Unit
    ) {
        val request = authenticatedRequest("$baseUrl/calls/stats/$callId")
            .get()
            .build()
        
        executeAsync(request, CallStatsResponse::class.java, { response ->
            onSuccess(response.stats)
        }, onError)
    }
    
    // ==================== CONTACTS ENDPOINTS ====================
    
    /**
     * Get all contacts
     */
    fun getContacts(
        onSuccess: (List<Contact>) -> Unit,
        onError: (String) -> Unit
    ) {
        val request = authenticatedRequest("$baseUrl/contacts")
            .get()
            .build()
        
        executeAsync(request, ContactsResponse::class.java, { response ->
            onSuccess(response.contacts)
        }, onError)
    }
    
    /**
     * Add a contact
     */
    fun addContact(
        phoneNumber: String,
        name: String,
        onSuccess: (Boolean) -> Unit,  // Returns isRegistered
        onError: (String) -> Unit
    ) {
        val json = gson.toJson(mapOf(
            "phoneNumber" to phoneNumber,
            "name" to name
        ))
        val request = authenticatedRequest("$baseUrl/contacts")
            .post(json.toRequestBody(JSON))
            .build()
        
        executeAsync(request, AddContactResponse::class.java, { response ->
            onSuccess(response.isRegistered)
        }, onError)
    }
    
    /**
     * Delete a contact
     */
    fun deleteContact(
        phoneNumber: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val request = authenticatedRequest("$baseUrl/contacts/$phoneNumber")
            .delete()
            .build()
        
        executeAsync(request, SuccessResponse::class.java, { onSuccess() }, onError)
    }
    
    // ==================== TURN CREDENTIALS ====================
    
    /**
     * Get TURN credentials
     */
    fun getTurnCredentials(
        onSuccess: (TurnCredentials) -> Unit,
        onError: (String) -> Unit
    ) {
        val request = authenticatedRequest("$baseUrl/turn-credentials")
            .get()
            .build()
        
        executeAsync(request, TurnCredentials::class.java, onSuccess, onError)
    }
    
    // ==================== HELPER METHODS ====================
    
    private fun authenticatedRequest(url: String): Request.Builder {
        return Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${authToken ?: ""}")
    }
    
    private fun <T> executeAsync(
        request: Request,
        responseClass: Class<T>,
        onSuccess: (T) -> Unit,
        onError: (String) -> Unit
    ) {
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Request failed: ${e.message}")
                onError(e.message ?: "Network error")
            }
            
            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.body?.string()
                    
                    if (!response.isSuccessful) {
                        val error = try {
                            gson.fromJson(body, ErrorResponse::class.java).error
                        } catch (e: Exception) {
                            "Request failed: ${response.code}"
                        }
                        onError(error)
                        return
                    }
                    
                    val result = gson.fromJson(body, responseClass)
                    onSuccess(result)
                } catch (e: Exception) {
                    Log.e(TAG, "Parse error: ${e.message}")
                    onError("Parse error: ${e.message}")
                }
            }
        })
    }
}

// ==================== DATA CLASSES ====================

data class SendOtpResponse(
    val success: Boolean,
    val message: String,
    val devOtp: String?  // Only in dev mode
)

data class VerifyOtpResponse(
    val success: Boolean,
    val user: UserInfo,
    val authToken: String
)

data class UserInfo(
    val id: String,
    val phoneNumber: String,
    val displayName: String
)

data class UserProfile(
    val id: String,
    val phoneNumber: String,
    val displayName: String,
    val createdAt: String
)

data class CallHistoryItem(
    @SerializedName("_id") val id: String?,
    val callId: String,
    val caller: String,
    val callee: String,
    val isVideo: Boolean,
    val duration: Int,
    val status: String,  // "completed", "missed", "rejected"
    val startTime: String,
    val endTime: String,
    val hasStats: Boolean? = false,  // Whether detailed stats are available
    val avgBitrateKbps: Double? = null,
    val avgPacketLossPercent: Double? = null,
    val avgRttMs: Double? = null,
    val totalDataUsedMB: String? = null
)

data class CallHistoryResponse(
    val calls: List<CallHistoryItem>
)

// Stats data classes
data class CallStatsItem(
    @SerializedName("_id") val id: String?,
    val callId: String,
    val caller: String,
    val callee: String,
    val isVideo: Boolean,
    val duration: Int,
    val totalSamples: Int,
    val avgSendBitrateKbps: Double,
    val avgReceiveBitrateKbps: Double,
    val avgPacketLossPercent: Double,
    val avgRttMs: Double,
    val totalDataUsedBytes: Long,
    val qualityDistribution: QualityDistribution?,
    val samples: List<StatsSample>?,
    val timestamp: String
)

data class QualityDistribution(
    val good: Int,
    val moderate: Int,
    val poor: Int
)

data class StatsSample(
    val timestamp: Long,
    val sendBitrateKbps: Long,
    val receiveBitrateKbps: Long,
    val packetLossPercent: Double,
    val rttMs: Int,
    val networkQuality: String,
    val dataUsedBytes: Long
)

data class CallStatsResponse(
    val stats: CallStatsItem
)

data class Contact(
    @SerializedName("_id") val id: String?,
    val phoneNumber: String,
    val name: String,
    val isRegistered: Boolean
)

data class ContactsResponse(
    val contacts: List<Contact>
)

data class AddContactResponse(
    val success: Boolean,
    val isRegistered: Boolean
)

data class TurnCredentials(
    val username: String,
    val password: String,
    val ttl: Int,
    val uris: List<String>
)

data class SuccessResponse(
    val success: Boolean
)

data class ErrorResponse(
    val error: String
)
