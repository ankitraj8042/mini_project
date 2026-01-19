package com.yourapp.webrtcapp.turn

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.*
import org.webrtc.PeerConnection
import java.util.concurrent.TimeUnit

/**
 * TURN Credential Manager
 * 
 * Fetches time-limited TURN credentials from the signaling server.
 * This provides better security than hardcoded credentials.
 * 
 * Features:
 * - Short-lived credentials (1 hour TTL)
 * - Automatic credential refresh before expiry
 * - Fallback to static credentials if fetch fails
 */
class TurnCredentialManager(
    private val signalingUrl: String
) {
    companion object {
        private const val TAG = "TurnCredentialManager"
        
        // Fallback static credentials (used when dynamic fetch fails)
        private const val FALLBACK_USERNAME = "openrelayproject"
        private const val FALLBACK_PASSWORD = "openrelayproject"
        private val FALLBACK_URIS = listOf(
            "turn:openrelay.metered.ca:80",
            "turn:openrelay.metered.ca:443",
            "turn:openrelay.metered.ca:443?transport=tcp"
        )
        
        // Refresh credentials 5 minutes before expiry
        private const val REFRESH_BUFFER_SECONDS = 300
    }
    
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
    
    private var cachedCredentials: TurnCredentials? = null
    private var credentialsFetchTime: Long = 0
    
    /**
     * Callback interface for async credential fetching
     */
    interface CredentialsCallback {
        fun onCredentialsFetched(iceServers: List<PeerConnection.IceServer>)
        fun onError(error: String)
    }
    
    /**
     * Get ICE servers with fresh TURN credentials
     * Uses cache if credentials are still valid
     */
    fun getIceServers(callback: CredentialsCallback) {
        // Check if we have valid cached credentials
        val cached = cachedCredentials
        if (cached != null && !isExpired(cached)) {
            Log.d(TAG, "Using cached TURN credentials")
            callback.onCredentialsFetched(buildIceServers(cached))
            return
        }
        
        // Fetch new credentials
        fetchCredentials(object : CredentialsCallback {
            override fun onCredentialsFetched(iceServers: List<PeerConnection.IceServer>) {
                callback.onCredentialsFetched(iceServers)
            }
            
            override fun onError(error: String) {
                Log.w(TAG, "Failed to fetch TURN credentials: $error, using fallback")
                callback.onCredentialsFetched(buildFallbackIceServers())
            }
        })
    }
    
    /**
     * Get ICE servers synchronously (blocking)
     * Use with caution - should not be called on main thread
     */
    fun getIceServersSync(): List<PeerConnection.IceServer> {
        // Check cache first
        val cached = cachedCredentials
        if (cached != null && !isExpired(cached)) {
            return buildIceServers(cached)
        }
        
        // Try to fetch, fallback on error
        return try {
            fetchCredentialsSync()?.let { buildIceServers(it) } ?: buildFallbackIceServers()
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching credentials: ${e.message}")
            buildFallbackIceServers()
        }
    }
    
    /**
     * Fetch credentials from signaling server
     */
    private fun fetchCredentials(callback: CredentialsCallback) {
        // Convert ws:// to http:// for REST request
        val httpUrl = signalingUrl
            .replace("ws://", "http://")
            .replace("wss://", "https://")
            .trimEnd('/') + "/turn-credentials"
        
        val request = Request.Builder()
            .url(httpUrl)
            .get()
            .build()
        
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                Log.e(TAG, "HTTP request failed: ${e.message}")
                callback.onError(e.message ?: "Request failed")
            }
            
            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    callback.onError("HTTP ${response.code}")
                    return
                }
                
                try {
                    val body = response.body?.string()
                    val credentials = gson.fromJson(body, TurnCredentials::class.java)
                    cachedCredentials = credentials
                    credentialsFetchTime = System.currentTimeMillis()
                    
                    Log.d(TAG, "Fetched TURN credentials: username=${credentials.username}, ttl=${credentials.ttl}s")
                    callback.onCredentialsFetched(buildIceServers(credentials))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse credentials: ${e.message}")
                    callback.onError(e.message ?: "Parse error")
                }
            }
        })
    }
    
    /**
     * Fetch credentials synchronously
     */
    private fun fetchCredentialsSync(): TurnCredentials? {
        val httpUrl = signalingUrl
            .replace("ws://", "http://")
            .replace("wss://", "https://")
            .trimEnd('/') + "/turn-credentials"
        
        val request = Request.Builder()
            .url(httpUrl)
            .get()
            .build()
        
        return try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                val credentials = gson.fromJson(body, TurnCredentials::class.java)
                cachedCredentials = credentials
                credentialsFetchTime = System.currentTimeMillis()
                credentials
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Sync fetch failed: ${e.message}")
            null
        }
    }
    
    /**
     * Check if credentials are expired or about to expire
     */
    private fun isExpired(credentials: TurnCredentials): Boolean {
        val elapsedSeconds = (System.currentTimeMillis() - credentialsFetchTime) / 1000
        return elapsedSeconds >= (credentials.ttl - REFRESH_BUFFER_SECONDS)
    }
    
    /**
     * Build ICE servers from dynamic credentials
     */
    private fun buildIceServers(credentials: TurnCredentials): List<PeerConnection.IceServer> {
        val servers = mutableListOf<PeerConnection.IceServer>()
        
        // Add STUN servers (no auth needed)
        servers.add(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                .createIceServer()
        )
        servers.add(
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302")
                .createIceServer()
        )
        
        // Add TURN servers with dynamic credentials
        credentials.uris.forEach { uri ->
            servers.add(
                PeerConnection.IceServer.builder(uri)
                    .setUsername(credentials.username)
                    .setPassword(credentials.password)
                    .createIceServer()
            )
        }
        
        return servers
    }
    
    /**
     * Build fallback ICE servers with static credentials
     */
    private fun buildFallbackIceServers(): List<PeerConnection.IceServer> {
        val servers = mutableListOf<PeerConnection.IceServer>()
        
        // STUN servers
        servers.add(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                .createIceServer()
        )
        servers.add(
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302")
                .createIceServer()
        )
        
        // Fallback TURN servers
        FALLBACK_URIS.forEach { uri ->
            servers.add(
                PeerConnection.IceServer.builder(uri)
                    .setUsername(FALLBACK_USERNAME)
                    .setPassword(FALLBACK_PASSWORD)
                    .createIceServer()
            )
        }
        
        return servers
    }
    
    /**
     * Clear cached credentials
     */
    fun clearCache() {
        cachedCredentials = null
        credentialsFetchTime = 0
    }
}

/**
 * TURN credentials response from server
 */
data class TurnCredentials(
    @SerializedName("username")
    val username: String,
    
    @SerializedName("password")
    val password: String,
    
    @SerializedName("ttl")
    val ttl: Int,
    
    @SerializedName("uris")
    val uris: List<String>
)
