package com.example.gudumap.navigation

import com.example.gudumap.sensor.ImuSample
import com.example.gudumap.sensor.OrientationSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UnintendedMovementDemoTest {

    private lateinit var deadReckoningEngine: DeadReckoningEngine

    @Before
    fun setUp() {
        deadReckoningEngine = DeadReckoningEngine()
        deadReckoningEngine.initialize(
            latitude = 11.0168,
            longitude = 76.9558,
            speedMps = 0f,
            bearingDeg = 0f
        )
        deadReckoningEngine.setBlackoutMode(enabled = true, isPedestrianDemo = true)
    }

    @Test
    fun testStationaryDemoMode_ProducesZeroDisplacement() {
        val initialLat = deadReckoningEngine.getState().latitude
        val initialLon = deadReckoningEngine.getState().longitude

        // Feed 50 IMU samples simulating stationary phone noise (no heel-strike steps)
        var timestampNs = System.nanoTime()
        for (i in 1..50) {
            timestampNs += 100_000_000L // 100 ms interval (10 Hz)
            val noiseAx = ((i % 3) - 1) * 0.05f
            val noiseAy = ((i % 2) - 1) * 0.05f
            val sample = ImuSample(
                timestampNs = timestampNs,
                ax = noiseAx,
                ay = noiseAy,
                az = 1.0f, // 1g
                gx = 0.01f,
                gy = 0.01f,
                gz = 0.01f
            )
            deadReckoningEngine.addSensorSample(sample)
        }

        val stateAfterStationary = deadReckoningEngine.getState()
        assertEquals(initialLat, stateAfterStationary.latitude, 1e-7)
        assertEquals(initialLon, stateAfterStationary.longitude, 1e-7)
        assertEquals(0.0, stateAfterStationary.distanceTravelled, 1e-3)
    }

    @Test
    fun testPhoneRotationStationary_ProducesZeroDisplacement() {
        val initialLat = deadReckoningEngine.getState().latitude
        val initialLon = deadReckoningEngine.getState().longitude

        var timestampNs = System.nanoTime()
        // Simulate phone rotation from 0 deg to 180 deg while standing still
        for (heading in 0..180 step 10) {
            timestampNs += 100_000_000L
            val azimuthRad = Math.toRadians(heading.toDouble()).toFloat()
            val orientSample = OrientationSample(
                timestampNs = timestampNs,
                rotationMatrix = floatArrayOf(
                    1f, 0f, 0f,
                    0f, 1f, 0f,
                    0f, 0f, 1f
                ),
                quaternion = floatArrayOf(1f, 0f, 0f, 0f),
                azimuthRad = azimuthRad,
                pitchRad = 0f,
                rollRad = 0f
            )
            deadReckoningEngine.updateOrientation(orientSample)

            // Stationary noise IMU sample
            val sample = ImuSample(
                timestampNs = timestampNs,
                ax = 0.02f,
                ay = -0.02f,
                az = 1.0f,
                gx = 0.05f,
                gy = 0.0f,
                gz = 0.0f
            )
            deadReckoningEngine.addSensorSample(sample)
        }

        val finalState = deadReckoningEngine.getState()
        assertEquals(initialLat, finalState.latitude, 1e-7)
        assertEquals(initialLon, finalState.longitude, 1e-7)
        assertEquals(180.0, finalState.heading.toDouble(), 1e-3)
    }

    @Test
    fun testResetVelocitiesAndPdr_ClearsStateOnStopDemo() {
        deadReckoningEngine.resetVelocitiesAndPdr()
        val state = deadReckoningEngine.getState()
        assertEquals(0, state.stepCount)
        assertEquals(0.0, state.speed.toDouble(), 1e-3)
    }
}
