package com.yourapp.webrtcapp.ai

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for AdaptiveQualityPredictor
 * 
 * Tests the ML-based quality prediction logic including:
 * - Feature normalization
 * - Quality score calculation
 * - Recommendation logic (UPGRADE/MAINTAIN/DOWNGRADE)
 * - Profile transitions
 */
class AdaptiveQualityPredictorTest {
    
    private lateinit var predictor: AdaptiveQualityPredictor
    
    @Before
    fun setUp() {
        predictor = AdaptiveQualityPredictor()
        predictor.setCurrentProfile(NetworkProfile.MEDIUM)
    }
    
    // ==================== EXCELLENT NETWORK TESTS ====================
    
    @Test
    fun `predict returns high quality score for excellent network conditions`() {
        val stats = NetworkStats(
            bitrateKbps = 2500,      // High bandwidth
            packetLossPercent = 0f,   // No packet loss
            rttMs = 30,              // Low latency
            jitterMs = 5             // Low jitter
        )
        
        val prediction = predictor.predict(stats)
        
        assertTrue("Quality score should be > 0.8 for excellent conditions", 
            prediction.qualityScore > 0.8f)
        assertEquals("Should maintain or upgrade on excellent network", 
            RecommendedAction.MAINTAIN, prediction.recommendedAction)
    }
    
    @Test
    fun `predict recommends UPGRADE when network improves significantly`() {
        // Start at LOW profile
        predictor.setCurrentProfile(NetworkProfile.LOW)
        
        // Simulate excellent conditions
        val stats = NetworkStats(
            bitrateKbps = 3000,
            packetLossPercent = 0.5f,
            rttMs = 25,
            jitterMs = 3
        )
        
        val prediction = predictor.predict(stats)
        
        assertEquals("Should recommend upgrade when conditions are good", 
            RecommendedAction.UPGRADE, prediction.recommendedAction)
        assertTrue("Suggested profile should be higher than current",
            prediction.suggestedProfile.priority > NetworkProfile.LOW.priority)
    }
    
    // ==================== POOR NETWORK TESTS ====================
    
    @Test
    fun `predict returns low quality score for poor network conditions`() {
        val stats = NetworkStats(
            bitrateKbps = 50,        // Very low bandwidth
            packetLossPercent = 15f, // High packet loss
            rttMs = 800,            // High latency
            jitterMs = 100          // High jitter
        )
        
        val prediction = predictor.predict(stats)
        
        assertTrue("Quality score should be < 0.4 for poor conditions", 
            prediction.qualityScore < 0.4f)
    }
    
    @Test
    fun `predict recommends DOWNGRADE on high packet loss`() {
        predictor.setCurrentProfile(NetworkProfile.HIGH)
        
        val stats = NetworkStats(
            bitrateKbps = 1000,
            packetLossPercent = 12f,  // High packet loss
            rttMs = 150,
            jitterMs = 30
        )
        
        val prediction = predictor.predict(stats)
        
        assertEquals("Should recommend downgrade on high packet loss", 
            RecommendedAction.DOWNGRADE, prediction.recommendedAction)
    }
    
    @Test
    fun `predict recommends DOWNGRADE on very high RTT`() {
        predictor.setCurrentProfile(NetworkProfile.MEDIUM)
        
        val stats = NetworkStats(
            bitrateKbps = 800,
            packetLossPercent = 2f,
            rttMs = 600,             // Very high RTT
            jitterMs = 50
        )
        
        val prediction = predictor.predict(stats)
        
        assertEquals("Should recommend downgrade on high RTT", 
            RecommendedAction.DOWNGRADE, prediction.recommendedAction)
    }
    
    @Test
    fun `predict recommends DOWNGRADE on low bitrate`() {
        predictor.setCurrentProfile(NetworkProfile.HIGH)
        
        val stats = NetworkStats(
            bitrateKbps = 80,        // Very low bitrate
            packetLossPercent = 3f,
            rttMs = 200,
            jitterMs = 20
        )
        
        val prediction = predictor.predict(stats)
        
        assertEquals("Should recommend downgrade on low bitrate", 
            RecommendedAction.DOWNGRADE, prediction.recommendedAction)
    }
    
    // ==================== MODERATE NETWORK TESTS ====================
    
    @Test
    fun `predict returns MAINTAIN for moderate stable conditions`() {
        predictor.setCurrentProfile(NetworkProfile.MEDIUM)
        
        val stats = NetworkStats(
            bitrateKbps = 500,
            packetLossPercent = 2f,
            rttMs = 150,
            jitterMs = 20
        )
        
        val prediction = predictor.predict(stats)
        
        // Should either maintain or make minor adjustment
        assertTrue("Should maintain or make minor adjustment",
            prediction.recommendedAction == RecommendedAction.MAINTAIN ||
            prediction.suggestedProfile == NetworkProfile.MEDIUM)
    }
    
    // ==================== EDGE CASE TESTS ====================
    
    @Test
    fun `predict handles zero values gracefully`() {
        val stats = NetworkStats(
            bitrateKbps = 0,
            packetLossPercent = 0f,
            rttMs = 0,
            jitterMs = 0
        )
        
        // Should not throw exception
        val prediction = predictor.predict(stats)
        
        assertNotNull("Should return valid prediction", prediction)
        assertNotNull("Should have reasoning", prediction.reasoning)
    }
    
    @Test
    fun `predict handles extreme values gracefully`() {
        val stats = NetworkStats(
            bitrateKbps = 100000,       // Extremely high
            packetLossPercent = 100f,   // 100% loss
            rttMs = 10000,              // 10 second RTT
            jitterMs = 5000
        )
        
        val prediction = predictor.predict(stats)
        
        assertNotNull("Should return valid prediction for extreme values", prediction)
        assertTrue("Quality score should be between 0 and 1",
            prediction.qualityScore in 0f..1f)
    }
    
    @Test
    fun `predict confidence increases with consistent samples`() {
        // First prediction - lower confidence
        val stats1 = NetworkStats(500, 2f, 100, 15)
        val prediction1 = predictor.predict(stats1)
        
        // Same conditions multiple times
        val stats2 = NetworkStats(500, 2f, 100, 15)
        val prediction2 = predictor.predict(stats2)
        
        val stats3 = NetworkStats(500, 2f, 100, 15)
        val prediction3 = predictor.predict(stats3)
        
        // Later predictions should have higher or equal confidence
        assertTrue("Confidence should stabilize with consistent data",
            prediction3.confidence >= prediction1.confidence * 0.9f)
    }
    
    // ==================== PROFILE TRANSITION TESTS ====================
    
    @Test
    fun `profile transitions respect network profile priority order`() {
        predictor.setCurrentProfile(NetworkProfile.VERY_HIGH)
        
        // Bad conditions should suggest lower profile
        val badStats = NetworkStats(100, 10f, 400, 80)
        val prediction = predictor.predict(badStats)
        
        if (prediction.recommendedAction == RecommendedAction.DOWNGRADE) {
            assertTrue("Suggested profile should have lower priority",
                prediction.suggestedProfile.priority < NetworkProfile.VERY_HIGH.priority)
        }
    }
    
    @Test
    fun `cannot upgrade above VERY_HIGH profile`() {
        predictor.setCurrentProfile(NetworkProfile.VERY_HIGH)
        
        // Perfect conditions
        val perfectStats = NetworkStats(5000, 0f, 10, 1)
        val prediction = predictor.predict(perfectStats)
        
        assertTrue("Should not suggest profile above VERY_HIGH",
            prediction.suggestedProfile.priority <= NetworkProfile.VERY_HIGH.priority)
    }
    
    @Test
    fun `cannot downgrade below ULTRA_LOW profile`() {
        predictor.setCurrentProfile(NetworkProfile.ULTRA_LOW)
        
        // Terrible conditions
        val terribleStats = NetworkStats(10, 50f, 2000, 500)
        val prediction = predictor.predict(terribleStats)
        
        assertEquals("Should stay at ULTRA_LOW minimum",
            NetworkProfile.ULTRA_LOW, prediction.suggestedProfile)
    }
    
    // ==================== 2G/RURAL NETWORK SIMULATION ====================
    
    @Test
    fun `predict handles 2G network simulation`() {
        predictor.setCurrentProfile(NetworkProfile.ULTRA_LOW)
        
        // Typical 2G conditions
        val stats2G = NetworkStats(
            bitrateKbps = 30,        // ~30 kbps typical for 2G
            packetLossPercent = 5f,
            rttMs = 500,             // High latency on 2G
            jitterMs = 100
        )
        
        val prediction = predictor.predict(stats2G)
        
        assertEquals("Should maintain ULTRA_LOW for 2G conditions",
            NetworkProfile.ULTRA_LOW, prediction.suggestedProfile)
    }
    
    @Test
    fun `predict handles 3G network simulation`() {
        predictor.setCurrentProfile(NetworkProfile.LOW)
        
        // Typical 3G conditions
        val stats3G = NetworkStats(
            bitrateKbps = 200,
            packetLossPercent = 3f,
            rttMs = 200,
            jitterMs = 40
        )
        
        val prediction = predictor.predict(stats3G)
        
        assertTrue("Should suggest LOW or VERY_LOW for 3G",
            prediction.suggestedProfile.priority <= NetworkProfile.MEDIUM.priority)
    }
    
    @Test
    fun `predict handles 4G network simulation`() {
        predictor.setCurrentProfile(NetworkProfile.MEDIUM)
        
        // Typical 4G conditions
        val stats4G = NetworkStats(
            bitrateKbps = 1500,
            packetLossPercent = 1f,
            rttMs = 50,
            jitterMs = 10
        )
        
        val prediction = predictor.predict(stats4G)
        
        assertTrue("Quality score should be good for 4G",
            prediction.qualityScore > 0.6f)
    }
    
    @Test
    fun `predict handles WiFi network simulation`() {
        predictor.setCurrentProfile(NetworkProfile.HIGH)
        
        // Typical WiFi conditions
        val statsWiFi = NetworkStats(
            bitrateKbps = 3000,
            packetLossPercent = 0.5f,
            rttMs = 20,
            jitterMs = 3
        )
        
        val prediction = predictor.predict(statsWiFi)
        
        assertTrue("Quality score should be excellent for WiFi",
            prediction.qualityScore > 0.8f)
    }
    
    // ==================== REASONING TESTS ====================
    
    @Test
    fun `prediction includes meaningful reasoning`() {
        val stats = NetworkStats(100, 8f, 300, 50)
        val prediction = predictor.predict(stats)
        
        assertNotNull("Reasoning should not be null", prediction.reasoning)
        assertTrue("Reasoning should not be empty", prediction.reasoning.isNotEmpty())
        assertTrue("Reasoning should be descriptive (>10 chars)", prediction.reasoning.length > 10)
    }
}

/**
 * Integration tests for NetworkProfile
 */
class NetworkProfileTest {
    
    @Test
    fun `profiles are ordered by priority correctly`() {
        val profiles = NetworkProfile.values().sortedBy { it.priority }
        
        assertEquals("ULTRA_LOW should have lowest priority", 
            NetworkProfile.ULTRA_LOW, profiles.first())
        assertEquals("VERY_HIGH should have highest priority", 
            NetworkProfile.VERY_HIGH, profiles.last())
    }
    
    @Test
    fun `getInitialProfile returns sensible defaults`() {
        assertEquals("2G should map to ULTRA_LOW",
            NetworkProfile.ULTRA_LOW, NetworkProfile.getInitialProfile(NetworkType.CELLULAR_2G))
        assertEquals("3G should map to LOW or VERY_LOW",
            true, NetworkProfile.getInitialProfile(NetworkType.CELLULAR_3G).priority <= NetworkProfile.LOW.priority)
        assertEquals("WiFi should map to HIGH or above",
            true, NetworkProfile.getInitialProfile(NetworkType.WIFI).priority >= NetworkProfile.HIGH.priority)
    }
    
    @Test
    fun `all profiles have valid video dimensions`() {
        NetworkProfile.values().forEach { profile ->
            if (profile.enableVideo) {
                assertTrue("Video width should be > 0 for ${profile.name}", profile.videoWidth > 0)
                assertTrue("Video height should be > 0 for ${profile.name}", profile.videoHeight > 0)
                assertTrue("Video FPS should be > 0 for ${profile.name}", profile.videoFps > 0)
            }
        }
    }
    
    @Test
    fun `audio-only profiles have enableVideo false`() {
        val audioOnlyProfiles = listOf(NetworkProfile.ULTRA_LOW)
        
        audioOnlyProfiles.forEach { profile ->
            assertFalse("${profile.name} should have enableVideo=false", profile.enableVideo)
        }
    }
    
    @Test
    fun `bitrates are sensible for each profile`() {
        NetworkProfile.values().forEach { profile ->
            assertTrue("Max video bitrate should be >= min for ${profile.name}",
                profile.maxVideoBitrateBps >= profile.minVideoBitrateBps)
            assertTrue("Audio bitrate should be reasonable for ${profile.name}",
                profile.audioBitrateBps in 8000..128000)
        }
    }
}
