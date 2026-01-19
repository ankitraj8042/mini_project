package com.yourapp.webrtcapp.auth

import android.content.Context
import android.content.SharedPreferences
import com.yourapp.webrtcapp.api.ApiClient
import com.yourapp.webrtcapp.api.UserInfo
import com.yourapp.webrtcapp.utils.Constants

/**
 * Authentication Manager
 * Handles login state, token storage, and user info
 */
class AuthManager private constructor(context: Context) {
    
    companion object {
        private const val PREFS_NAME = "auth_prefs"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_PHONE_NUMBER = "phone_number"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        
        @Volatile
        private var instance: AuthManager? = null
        
        fun getInstance(context: Context): AuthManager {
            return instance ?: synchronized(this) {
                instance ?: AuthManager(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val prefs: SharedPreferences = 
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    private val _apiClient: ApiClient by lazy {
        val serverUrl = Constants.getHttpServerUrl(context)
        ApiClient(serverUrl).also { client ->
            getAuthToken()?.let { token ->
                client.setAuthToken(token)
            }
        }
    }
    
    // ==================== LOGIN STATE ====================
    
    fun isLoggedIn(): Boolean = prefs.getBoolean(KEY_IS_LOGGED_IN, false)
    
    fun getAuthToken(): String? = prefs.getString(KEY_AUTH_TOKEN, null)
    
    fun getPhoneNumber(): String? = prefs.getString(KEY_PHONE_NUMBER, null)
    
    fun getDisplayName(): String? = prefs.getString(KEY_DISPLAY_NAME, null)
    
    fun getUserId(): String? = prefs.getString(KEY_USER_ID, null)
    
    // ==================== AUTH OPERATIONS ====================
    
    /**
     * Request OTP for phone number
     */
    fun sendOtp(
        phoneNumber: String,
        onSuccess: (devOtp: String?) -> Unit,
        onError: (String) -> Unit
    ) {
        _apiClient.sendOtp(phoneNumber, { response ->
            if (response.success) {
                onSuccess(response.devOtp)
            } else {
                onError(response.message)
            }
        }, onError)
    }
    
    /**
     * Verify OTP and complete login
     */
    fun verifyOtp(
        phoneNumber: String,
        otp: String,
        onSuccess: (UserInfo) -> Unit,
        onError: (String) -> Unit
    ) {
        val deviceInfo = mapOf(
            "model" to android.os.Build.MODEL,
            "manufacturer" to android.os.Build.MANUFACTURER,
            "sdk" to android.os.Build.VERSION.SDK_INT.toString()
        )
        
        _apiClient.verifyOtp(phoneNumber, otp, deviceInfo, { response ->
            if (response.success) {
                // Save login state
                saveLoginState(
                    authToken = response.authToken,
                    phoneNumber = response.user.phoneNumber,
                    displayName = response.user.displayName,
                    userId = response.user.id
                )
                onSuccess(response.user)
            } else {
                onError("Verification failed")
            }
        }, onError)
    }
    
    /**
     * Legacy login (without OTP - for backward compatibility)
     */
    fun legacyLogin(phoneNumber: String) {
        prefs.edit()
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .putString(KEY_PHONE_NUMBER, phoneNumber)
            .putString(KEY_DISPLAY_NAME, phoneNumber)
            .apply()
    }
    
    /**
     * Logout user
     */
    fun logout() {
        prefs.edit()
            .remove(KEY_AUTH_TOKEN)
            .remove(KEY_PHONE_NUMBER)
            .remove(KEY_DISPLAY_NAME)
            .remove(KEY_USER_ID)
            .putBoolean(KEY_IS_LOGGED_IN, false)
            .apply()
        
        _apiClient.setAuthToken(null)
    }
    
    // ==================== API ACCESS ====================
    
    fun getApiClient(): ApiClient = _apiClient
    
    // ==================== PRIVATE METHODS ====================
    
    private fun saveLoginState(
        authToken: String,
        phoneNumber: String,
        displayName: String,
        userId: String
    ) {
        prefs.edit()
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .putString(KEY_AUTH_TOKEN, authToken)
            .putString(KEY_PHONE_NUMBER, phoneNumber)
            .putString(KEY_DISPLAY_NAME, displayName)
            .putString(KEY_USER_ID, userId)
            .apply()
        
        _apiClient.setAuthToken(authToken)
    }
}
