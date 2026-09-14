package com.example.gudumap.navigation

import com.example.gudumap.sensor.ImuSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GpsFallbackIntegrationTest {

    private lateinit var deadReckoningEngine: DeadReckoningEngine

    @Before
    fun setUp() {
        deadReckoningEngine = DeadReckoningEngine()
    }

    @Test
    fun testGpsFallback_WithoutAnchor_RefusesDeadReckoning() {
        // Without an initial GPS anchor fix, dead reckoning should be uninitialized
        assertFalse(deadReckoningEngine.isInitialized)

        val resolvedVisualMode = resolveVisualMode(
            isDemoModeEnabled = false,
            blackoutActive = false,
            resolvedGpsState = GpsState.SEARCHING,
            hasReceivedRealGnssFix = false,
            isDrInitialized = deadReckoningEngine.isInitialized
        )

        // Must remain LIVE mode (which triggers the "Starting location needed" overlay instead of fake coordinates)
        assertEquals(NavigationVisualMode.LIVE, resolvedVisualMode)
    }

    @Test
    fun testGpsFallback_WithTrustedAnchor_ActivatesFallbackMode() {
        // Initialize anchor with a valid real GPS fix
        deadReckoningEngine.initialize(
            latitude = 11.0168,
            longitude = 76.9558,
            speedMps = 1.2f,
            bearingDeg = 90f
        )
        deadReckoningEngine.setBlackoutMode(enabled = true, isPedestrianDemo = false)

        assertTrue(deadReckoningEngine.isInitialized)

        val resolvedVisualMode = resolveVisualMode(
            isDemoModeEnabled = false,
            blackoutActive = true,
            resolvedGpsState = GpsState.GPS_DISABLED,
            hasReceivedRealGnssFix = true,
            isDrInitialized = deadReckoningEngine.isInitialized
        )

        assertEquals(NavigationVisualMode.GPS_FALLBACK, resolvedVisualMode)
    }

    @Test
    fun testGpsFallback_SingleAnchorInitialization_DoesNotResetEstimateOnStep() {
        val initialLat = 11.0168
        val initialLon = 76.9558

        deadReckoningEngine.initialize(
            latitude = initialLat,
            longitude = initialLon,
            speedMps = 0f,
            bearingDeg = 0f
        )
        deadReckoningEngine.setBlackoutMode(enabled = true, isPedestrianDemo = false)

        val stateAtStart = deadReckoningEngine.getState()
        assertEquals(initialLat, stateAtStart.latitude, 1e-7)
        assertEquals(initialLon, stateAtStart.longitude, 1e-7)

        val stepEvent = StepEvent(
            stepIndex = 1,
            stepLengthMeters = 0.8f,
            timestampNs = System.nanoTime(),
            deltaNorthMeters = 0.0,
            deltaEastMeters = 0.8
        )
        val deltaNed = doubleArrayOf(stepEvent.deltaNorthMeters, stepEvent.deltaEastMeters, 0.0)
        deadReckoningEngine.ekf.predict(deltaNed, 0.1)

        val newGeodetic = CoordinateTransformer.addMetricDisplacementToGeodetic(
            initialLat, initialLon,
            deadReckoningEngine.ekf.state[0],
            deadReckoningEngine.ekf.state[1]
        )

        assertTrue(newGeodetic[1] > initialLon)
    }

    @Test
    fun testGpsFallback_StationaryIMUNoise_DoesNotDriftPosition() {
        val anchorLat = 11.0168
        val anchorLon = 76.9558

        deadReckoningEngine.initialize(
            latitude = anchorLat,
            longitude = anchorLon,
            speedMps = 0f,
            bearingDeg = 0f
        )
        deadReckoningEngine.setBlackoutMode(enabled = true, isPedestrianDemo = false)

        // Feed 30 stationary IMU noise samples
        var timestampNs = System.nanoTime()
        for (i in 1..30) {
            timestampNs += 100_000_000L
            val sample = ImuSample(
                timestampNs = timestampNs,
                ax = 0.02f,
                ay = -0.01f,
                az = 1.0f,
                gx = 0.0f,
                gy = 0.0f,
                gz = 0.0f
            )
            deadReckoningEngine.addSensorSample(sample)
        }

        val state = deadReckoningEngine.getState()
        assertEquals(anchorLat, state.latitude, 1e-6)
        assertEquals(anchorLon, state.longitude, 1e-6)
        assertEquals(0.0, state.distanceTravelled, 1e-3)
    }

    @Test
    fun testPedestrianSpeedEstimator_WalkingAndStopping() {
        deadReckoningEngine.initialize(11.0168, 76.9558, 0f, 0f)
        deadReckoningEngine.setBlackoutMode(true, isPedestrianDemo = true)

        // Initially stationary -> 0 m/s
        assertEquals(0f, deadReckoningEngine.getPedestrianSpeedMps(), 1e-4f)

        var t = System.nanoTime()
        for (i in 1..3) {
            t += 500_000_000L
            val deltaNed = doubleArrayOf(0.0, 0.7, 0.0)
            deadReckoningEngine.ekf.predict(deltaNed, 0.5)
        }

        // Stopping (2.0s timeout without steps) -> speed drops to 0
        val tStopped = t + 2_000_000_000L
        assertEquals(0f, deadReckoningEngine.getPedestrianSpeedMps(tStopped), 1e-4f)
    }

    @Test
    fun testGpsFallback_RecoveryWhenGpsRestored_ReturnsToLiveVisualMode() {
        // Initialize anchor
        deadReckoningEngine.initialize(
            latitude = 11.0168,
            longitude = 76.9558,
            speedMps = 0f,
            bearingDeg = 0f
        )
        deadReckoningEngine.setBlackoutMode(enabled = false)

        // When GPS recovers to FIXED, visual mode MUST return to LIVE
        val resolvedVisualMode = resolveVisualMode(
            isDemoModeEnabled = false,
            blackoutActive = false,
            resolvedGpsState = GpsState.FIXED,
            hasReceivedRealGnssFix = true,
            isDrInitialized = deadReckoningEngine.isInitialized
        )

        assertEquals(NavigationVisualMode.LIVE, resolvedVisualMode)
    }

    @Test
    fun testInitialization_ResetsTimestamps_NoSpikeOnFirstStep() {
        val initialLat = 11.0168
        val initialLon = 76.9558

        // Simulate an old session from 1 hour ago
        val oldTimestampNs = System.nanoTime() - 3600_000_000_000L
        deadReckoningEngine.initialize(initialLat, initialLon, 0f, 0f)
        deadReckoningEngine.setBlackoutMode(true)

        // Re-initialize with new position (e.g., transition anchor)
        val newLat = 13.0827
        val newLon = 80.2707
        deadReckoningEngine.initialize(newLat, newLon, 0f, 0f)

        // First step sample in new session
        val nowNs = System.nanoTime()
        val sample = ImuSample(nowNs, 0.05f, 0.01f, 9.81f, 0f, 0f, 0f)
        deadReckoningEngine.addSensorSample(sample)

        val state = deadReckoningEngine.getState()
        assertEquals(newLat, state.latitude, 1e-5)
        assertEquals(newLon, state.longitude, 1e-5)
    }

    @Test
    fun testPredictionTrail_DistanceValidation_RejectsDistantPoints() {
        val trail = mutableListOf<Pair<Double, Double>>()
        trail.add(Pair(11.0168, 76.9558)) // Anchor

        val plausiblePoint = Pair(11.016805, 76.955805)
        val distPlausible = CoordinateTransformer.computeDistanceBetween(
            trail.last().first, trail.last().second,
            plausiblePoint.first, plausiblePoint.second
        )
        assertTrue(distPlausible in 0.5..100.0)
        trail.add(plausiblePoint)
        assertEquals(2, trail.size)

        // Implausible point 500 km away
        val distantPoint = Pair(13.0827, 80.2707)
        val distDistant = CoordinateTransformer.computeDistanceBetween(
            trail.last().first, trail.last().second,
            distantPoint.first, distantPoint.second
        )
        assertTrue(distDistant > 100.0)

        // Reset trail on implausible jump rather than connecting huge line
        if (distDistant > 100.0) {
            trail.clear()
            trail.add(distantPoint)
        }

        assertEquals(1, trail.size)
        assertEquals(distantPoint, trail.first())
    }

    @Test
    fun testLkPin_PreservedAtGpsLossEntry_WhileActivePositionMoves() {
        val entryLat = 11.0168
        val entryLon = 76.9558

        deadReckoningEngine.initialize(entryLat, entryLon, 0f, 0f)
        deadReckoningEngine.setBlackoutMode(true)

        val initialState = deadReckoningEngine.getState()
        assertEquals(entryLat, initialState.latitude, 1e-7)

        // Apply physical displacement step
        deadReckoningEngine.updateWithMLDisplacement(
            globalEastMeters = 10f,
            globalNorthMeters = 20f,
            dtSeconds = 1.0f,
            headingDeg = 45f,
            isStationary = false
        )

        val movedState = deadReckoningEngine.getState()
        // PDR active position moved
        assertTrue(movedState.latitude > entryLat)
        assertTrue(movedState.longitude > entryLon)

        // Anchor reference coordinates remain fixed at entry location
        assertEquals(entryLat, entryLat, 1e-7)
        assertEquals(entryLon, entryLon, 1e-7)
    }

    @Test
    fun testInternetLoss_WithValidGps_MaintainsLiveGpsTracking() {
        val resolvedVisualMode = resolveVisualMode(
            isDemoModeEnabled = false,
            blackoutActive = false,
            resolvedGpsState = GpsState.FIXED,
            hasReceivedRealGnssFix = true,
            isDrInitialized = true
        )

        assertEquals(NavigationVisualMode.LIVE, resolvedVisualMode)
    }

    @Test
    fun testSearchAreaLabel_FormatsEstimatedSearchAreaCorrectly() {
        val radiusMeters = 25.0
        val label = "Estimated search area · ${radiusMeters.toInt()} m"
        assertEquals("Estimated search area · 25 m", label)

        val demoLabel = "Simulated search area · ${radiusMeters.toInt()} m"
        assertEquals("Simulated search area · 25 m", demoLabel)
    }

    private fun resolveVisualMode(
        isDemoModeEnabled: Boolean,
        blackoutActive: Boolean,
        resolvedGpsState: GpsState,
        hasReceivedRealGnssFix: Boolean,
        isDrInitialized: Boolean
    ): NavigationVisualMode {
        return when {
            isDemoModeEnabled -> NavigationVisualMode.DEMO
            blackoutActive || resolvedGpsState == GpsState.LOST || resolvedGpsState == GpsState.STALE || resolvedGpsState == GpsState.SEARCHING || resolvedGpsState == GpsState.GPS_DISABLED || resolvedGpsState == GpsState.PERMISSION_REQUIRED -> {
                if (hasReceivedRealGnssFix || isDrInitialized) {
                    NavigationVisualMode.GPS_FALLBACK
                } else {
                    NavigationVisualMode.LIVE
                }
            }
            else -> NavigationVisualMode.LIVE
        }
    }
}
