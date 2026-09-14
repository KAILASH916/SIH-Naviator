package com.example.gudumap.sos

import com.example.gudumap.navigation.GpsState
import com.example.gudumap.navigation.NavigationState
import com.example.gudumap.navigation.NavigationVisualMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SosFeatureTest {

    @Test
    fun testSosLocationResolutionFreshLiveGps() {
        val liveState = NavigationState(
            latitude = 11.0168,
            longitude = 76.9558,
            hasGpsFix = true,
            gpsState = GpsState.FIXED,
            locationAccuracyMeters = 8.5f,
            locationAgeSeconds = 2L,
            isDemoModeEnabled = false
        )

        val resolved = SosLocationResolver.resolve(liveState)
        assertEquals(SosLocationSource.LIVE_GPS, resolved.source)
        assertEquals(11.0168, resolved.latitude, 1e-6)
        assertEquals(76.9558, resolved.longitude, 1e-6)
        assertEquals(8.5f, resolved.accuracyMeters, 1e-3f)
        assertTrue(resolved.isAccuracyValid)
    }

    @Test
    fun testSosLocationResolutionStaleGpsDoesNotClaimLive() {
        // Fix age is 12 seconds (> 5s threshold)
        val staleState = NavigationState(
            latitude = 11.0168,
            longitude = 76.9558,
            hasGpsFix = true,
            gpsState = GpsState.STALE,
            locationAgeSeconds = 12L,
            lastTrustedGpsLat = 11.0168,
            lastTrustedGpsLon = 76.9558,
            gpsFixAgeMs = 12_000L,
            isDemoModeEnabled = false
        )

        val resolved = SosLocationResolver.resolve(staleState)
        // Must NOT claim LIVE_GPS for a stale fix
        assertFalse(resolved.source == SosLocationSource.LIVE_GPS)
        assertEquals(SosLocationSource.LAST_CONFIRMED_GPS, resolved.source)
        assertEquals(12L, resolved.lastFixAgeSeconds)
    }

    @Test
    fun testSosLocationResolutionCombinedPrediction() {
        val realPredictionState = NavigationState(
            latitude = 11.0250,
            longitude = 76.9650,
            visualMode = NavigationVisualMode.GPS_FALLBACK,
            blackoutMode = true,
            isSimulatedBlackout = false,
            isDemoModeEnabled = false,
            predictionUncertaintyMeters = 25.0,
            lastTrustedGpsLat = 11.0185,
            lastTrustedGpsLon = 76.9572,
            gpsFixAgeMs = 180_000L // 3 minutes ago
        )

        val resolved = SosLocationResolver.resolve(realPredictionState)
        assertEquals(SosLocationSource.PREDICTED_FROM_REAL_GPS, resolved.source)
        assertEquals(11.0250, resolved.latitude, 1e-6)
        assertEquals(76.9650, resolved.longitude, 1e-6)
        assertEquals(25.0, resolved.uncertaintyRadiusMeters, 1e-3)
        assertTrue(resolved.hasValidLastTrusted())
        assertEquals(11.0185, resolved.lastTrustedLat!!, 1e-6)
        assertEquals(76.9572, resolved.lastTrustedLon!!, 1e-6)
        assertEquals(180L, resolved.lastTrustedAgeSeconds)
    }

    @Test
    fun testSosLocationResolutionDemoModeExclusion() {
        // User in Demo Mode with simulated coordinates (11.0500, 76.9800)
        val demoState = NavigationState(
            latitude = 11.0500,
            longitude = 76.9800,
            isDemoModeEnabled = true,
            locationProvider = "demo",
            lastTrustedGpsLat = 11.0168,
            lastTrustedGpsLon = 76.9558,
            gpsFixAgeMs = 60_000L
        )

        val resolved = SosLocationResolver.resolve(demoState)
        // Must NEVER return simulated Demo coordinates (11.0500, 76.9800)
        assertFalse("Demo coordinates must be strictly excluded", resolved.latitude == 11.0500 && resolved.longitude == 76.9800)
        assertEquals(SosLocationSource.LAST_CONFIRMED_GPS, resolved.source)
        assertEquals(11.0168, resolved.latitude, 1e-6)
    }

    @Test
    fun testSosLocationResolutionUnavailable() {
        val emptyState = NavigationState(
            latitude = 0.0,
            longitude = 0.0,
            hasGpsFix = false,
            gpsState = GpsState.SEARCHING,
            lastTrustedGpsLat = null,
            lastTrustedGpsLon = null
        )

        val resolved = SosLocationResolver.resolve(emptyState)
        assertEquals(SosLocationSource.UNAVAILABLE, resolved.source)
        assertFalse(resolved.hasValidCoordinates())
    }

    @Test
    fun testSosMessageBuilderLiveGps() {
        val loc = SosLocation(
            latitude = 11.0168,
            longitude = 76.9558,
            source = SosLocationSource.LIVE_GPS,
            accuracyMeters = 5f,
            isAccuracyValid = true
        )
        val msg = SosMessageBuilder.buildMessage(loc)
        assertTrue(msg.contains("🆘 EMERGENCY SOS"))
        assertTrue(msg.contains("Current location (Live GPS):"))
        assertTrue(msg.contains("https://www.google.com/maps/search/?api=1&query=11.016800,76.955800"))
        assertTrue(msg.contains("Accuracy: ±5 m"))
        assertTrue(msg.contains("Sent from NAVIGATOR"))
    }

    @Test
    fun testSosMessageBuilderCombinedPrediction() {
        val loc = SosLocation(
            latitude = 11.0250,
            longitude = 76.9650,
            source = SosLocationSource.PREDICTED_FROM_REAL_GPS,
            uncertaintyRadiusMeters = 30.0,
            lastTrustedLat = 11.0185,
            lastTrustedLon = 76.9572,
            lastTrustedAgeSeconds = 180L,
            lastTrustedAccuracyMeters = 8f,
            lastTrustedAccuracyValid = true
        )
        val msg = SosMessageBuilder.buildMessage(loc)
        assertTrue(msg.contains("GPS signal is unavailable."))
        assertTrue(msg.contains("Last confirmed GPS location:"))
        assertTrue(msg.contains("https://www.google.com/maps/search/?api=1&query=11.018500,76.957200"))
        assertTrue(msg.contains("Fix age: 3 minutes ago (±8 m)"))
        assertTrue(msg.contains("Estimated current location (UNCONFIRMED PREDICTION):"))
        assertTrue(msg.contains("https://www.google.com/maps/search/?api=1&query=11.025000,76.965000"))
        assertTrue(msg.contains("Estimated uncertainty: ±30 m"))
        assertTrue(msg.contains("Note: Estimated position is calculated from real movement sensors"))
    }

    @Test
    fun testSosMessageBuilderUnavailable() {
        val loc = SosLocation(source = SosLocationSource.UNAVAILABLE)
        val msg = SosMessageBuilder.buildMessage(loc)
        assertTrue(msg.contains("Current location could not be determined."))
    }
}
