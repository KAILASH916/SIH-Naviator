package com.example.gudumap.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SensorFusionManagerTest {

    private lateinit var fusionManager: SensorFusionManager

    @Before
    fun setUp() {
        fusionManager = SensorFusionManager()
    }

    @Test
    fun testDeadbandFilterSuppressesStaticDrift() {
        val t0 = 1_000_000_000L
        val t1 = 2_000_000_000L

        // Initial measurement
        fusionManager.updateGyroscope(0f, 0f, 0f, t0)

        // Small noise input (0.003 rad/s) below 0.005 deadband
        fusionManager.updateGyroscope(0.003f, -0.002f, 0.004f, t1)

        val orientation = fusionManager.getOrientation()
        assertEquals(0f, orientation.heading, 1e-4f)
        assertEquals(0f, orientation.pitch, 1e-4f)
        assertEquals(0f, orientation.roll, 1e-4f)
    }

    @Test
    fun testOnlineBiasEstimationAndSubtraction() {
        val t0 = 1_000_000_000L
        fusionManager.updateGyroscope(0f, 0f, 0f, t0)

        // Feed constant small static bias (0.005 rad/s) over multiple samples (rawMag = 0.0086 rad/s < 0.015)
        var time = t0
        for (i in 1..20) {
            time += 100_000_000L // 100ms step
            fusionManager.updateGyroscope(0.005f, 0.005f, 0.005f, time)
        }

        // Check that bias was learned
        assertTrue("X bias should adapt", fusionManager.gyroBiasX > 0f)
        assertTrue("Y bias should adapt", fusionManager.gyroBiasY > 0f)
        assertTrue("Z bias should adapt", fusionManager.gyroBiasZ > 0f)
    }

    @Test
    fun test3DRodriguesRotationIntegrationAccuracy() {
        val t0 = 1_000_000_000L

        // Initialize gyro at t0
        fusionManager.updateGyroscope(0f, 0f, 0f, t0)

        // Rotate clockwise toward East (negative Z rate in Android NED/ENU azimuth convention)
        // 90 deg/sec = Math.PI / 2 rad/s. Step in 50ms increments for 1 second total.
        val rateZ = -(Math.PI / 2.0).toFloat()
        var time = t0
        for (i in 1..20) {
            time += 50_000_000L // 50ms step
            fusionManager.updateGyroscope(0f, 0f, rateZ, time)
        }

        val orientation = fusionManager.getOrientation()
        // 90 deg clockwise rotation around Z produces ~90 deg azimuth
        val headingDelta = orientation.heading
        assertEquals(90f, headingDelta, 1.5f)
    }
}
