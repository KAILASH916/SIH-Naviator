package com.example.gudumap

import com.example.gudumap.navigation.CoordinateTransformer
import com.example.gudumap.navigation.EKF
import com.example.gudumap.navigation.ZuptDetector
import com.example.gudumap.sensor.ImuSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccuracyImprovementTest {

    @Test
    fun testStationaryGpsNoiseVsGenuineSlowWalking() {
        val filter = com.example.gudumap.navigation.GpsLocationFilter()
        val now = System.currentTimeMillis()

        // 1. Initial fix
        val first = filter.processLocation(11.016800, 76.955800, 5.0f, now, speedMps = 0.0f, hasSpeed = true, currentTimeMs = now)
        assertTrue(first.isAccepted)

        // 2. Stationary jitter (micro displacement < 1m, low speed 0.1 m/s)
        val jitter = filter.processLocation(11.0168001, 76.9558001, 5.0f, now + 1000, speedMps = 0.1f, hasSpeed = true, currentTimeMs = now + 1000)
        assertTrue(jitter.isStationary)
        assertEquals(11.016800, jitter.filteredLatitude, 1e-6)

        // 3. Genuine slow walking (0.5 m/s, continuous displacement > 2m)
        val walking1 = filter.processLocation(11.016830, 76.955830, 3.0f, now + 3000, speedMps = 0.5f, hasSpeed = true, currentTimeMs = now + 3000)
        val walking2 = filter.processLocation(11.016860, 76.955860, 3.0f, now + 5000, speedMps = 0.5f, hasSpeed = true, currentTimeMs = now + 5000)
        assertFalse(walking2.isStationary)
        assertTrue(walking2.filteredLatitude > 11.016800)
    }

    @Test
    fun testOutOfOrderAndDuplicateFixRejection() {
        val filter = com.example.gudumap.navigation.GpsLocationFilter()
        val now = System.currentTimeMillis()

        val fix1 = filter.processLocation(11.0168, 76.9558, 5.0f, now, currentTimeMs = now)
        assertTrue(fix1.isAccepted)

        // Out-of-order fix from 5 seconds in the past
        val oldFix = filter.processLocation(11.0160, 76.9550, 5.0f, now - 5000L, currentTimeMs = now + 1000L)
        assertFalse("Out of order timestamp fix must be rejected", oldFix.isAccepted)
    }

    @Test
    fun testStationaryPhoneRotationWithoutTranslation() {
        val drEngine = com.example.gudumap.navigation.DeadReckoningEngine()
        drEngine.initialize(11.0168, 76.9558, 0f, 0f)

        val initialLat = drEngine.getState().latitude
        val initialLon = drEngine.getState().longitude

        // Rotate phone 90 degrees while stationary
        drEngine.updateOrientation(com.example.gudumap.sensor.OrientationSample(
            timestampNs = System.nanoTime(),
            rotationMatrix = FloatArray(9) { if (it % 4 == 0) 1f else 0f },
            quaternion = floatArrayOf(0f, 0f, 0f, 1f),
            azimuthRad = Math.toRadians(90.0).toFloat(),
            pitchRad = 0f,
            rollRad = 0f
        ))

        val endState = drEngine.getState()
        assertEquals("Latitude must not change on stationary rotation", initialLat, endState.latitude, 1e-7)
        assertEquals("Longitude must not change on stationary rotation", initialLon, endState.longitude, 1e-7)
    }

    @Test
    fun testHeadingTransitionsAcrossNorth() {
        val delta1 = CoordinateTransformer.computeShortestAngularDelta(359f, 1f)
        assertEquals(2.0f, delta1, 1e-4f)

        val delta2 = CoordinateTransformer.computeShortestAngularDelta(1f, 359f)
        assertEquals(-2.0f, delta2, 1e-4f)
    }

    @Test
    fun testGpsLossPredictionUncertaintyGrowth() {
        val ekf = EKF()
        ekf.initialize(pNorth = 0.0, pEast = 0.0, pDown = 0.0)

        val initialUncertainty = ekf.P[0][0]

        // Predict 10 steps during GPS loss
        for (i in 1..10) {
            ekf.predict(doubleArrayOf(1.0, 0.0, 0.0), dt = 1.0)
        }

        val blackoutUncertainty = ekf.P[0][0]
        assertTrue("Uncertainty covariance P[0][0] must grow during GPS loss", blackoutUncertainty > initialUncertainty)
    }

    @Test
    fun testRecoveryWithPoorVsReliableFixes() {
        val ekf1 = EKF()
        ekf1.initialize(0.0, 0.0, 0.0)
        ekf1.updateGnssPosition(10.0, 0.0, 0.0, noiseMeters = 2.0)

        val ekf2 = EKF()
        ekf2.initialize(0.0, 0.0, 0.0)
        ekf2.updateGnssPosition(10.0, 0.0, 0.0, noiseMeters = 50.0)

        assertTrue("Reliable fix (2m noise) should pull EKF state further than noisy fix (50m noise)",
            ekf1.state[0] > ekf2.state[0])
    }
}
