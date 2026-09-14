package com.example.gudumap.navigation

import com.example.gudumap.sensor.ImuSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

/**
 * Production integration test verifying that physical walking sensor samples produce validated
 * step displacement and update the published latitude/longitude from an initial GPS anchor,
 * while stationary samples produce zero artificial travel.
 */
class PhysicalWalkingIntegrationTest {

    private lateinit var deadReckoningEngine: DeadReckoningEngine

    @Before
    fun setUp() {
        deadReckoningEngine = DeadReckoningEngine()
    }

    private fun feedWalkingStepSamples(
        numSteps: Int,
        startNs: Long = System.nanoTime()
    ): Long {
        var currentNs = startNs
        val dtNs = 20_000_000L // 50 Hz sensor rate (20ms)

        for (step in 0 until numSteps) {
            // 1. Quiescent phase (200ms)
            for (i in 0 until 10) {
                deadReckoningEngine.addSensorSample(
                    ImuSample(
                        timestampNs = currentNs,
                        ax = 0.05f,
                        ay = 0.02f,
                        az = 0.05f,
                        gx = 0.01f,
                        gy = 0.01f,
                        gz = 0.01f
                    )
                )
                currentNs += dtNs
            }

            // 2. Heel strike peak (linear acceleration peak > 1.2 m/s^2)
            for (i in 0 until 4) {
                val accPeak = 2.4f - (i * 0.4f)
                deadReckoningEngine.addSensorSample(
                    ImuSample(
                        timestampNs = currentNs,
                        ax = 0.1f,
                        ay = accPeak,
                        az = 0.5f,
                        gx = 0.05f,
                        gy = 0.02f,
                        gz = 0.02f
                    )
                )
                currentNs += dtNs
            }

            // 3. Post-strike trough (120ms)
            for (i in 0 until 6) {
                deadReckoningEngine.addSensorSample(
                    ImuSample(
                        timestampNs = currentNs,
                        ax = 0.02f,
                        ay = 0.05f,
                        az = 0.02f,
                        gx = 0.01f,
                        gy = 0.01f,
                        gz = 0.01f
                    )
                )
                currentNs += dtNs
            }
        }
        return currentNs
    }

    private fun feedStationarySamples(
        numSamples: Int,
        startNs: Long = System.nanoTime()
    ): Long {
        var currentNs = startNs
        val dtNs = 20_000_000L

        for (i in 0 until numSamples) {
            deadReckoningEngine.addSensorSample(
                ImuSample(
                    timestampNs = currentNs,
                    ax = 0.01f,
                    ay = 0.01f,
                    az = 0.02f,
                    gx = 0.001f,
                    gy = 0.001f,
                    gz = 0.001f
                )
            )
            currentNs += dtNs
        }
        return currentNs
    }

    @Test
    fun testNormalGpsFallback_PhysicalWalking_UpdatesPublishedPosition() {
        val anchorLat = 11.0168
        val anchorLon = 76.9558

        // Initialize DR Engine from valid real anchor position
        deadReckoningEngine.initialize(latitude = anchorLat, longitude = anchorLon, speedMps = 0f, bearingDeg = 0f)
        deadReckoningEngine.setBlackoutMode(true)

        val initialState = deadReckoningEngine.getState()
        assertEquals(anchorLat, initialState.latitude, 0.00001)
        assertEquals(anchorLon, initialState.longitude, 0.00001)

        // Feed physical walking steps
        feedWalkingStepSamples(numSteps = 10)

        val updatedState = deadReckoningEngine.getState()

        // Assert step detector registered physical steps
        assertTrue("Step count should increase during physical walking", updatedState.stepCount >= 5)

        // Assert published position has changed from initial anchor
        val finalLat = updatedState.latitude
        val finalLon = updatedState.longitude

        val latDiff = abs(finalLat - anchorLat)
        assertTrue("Latitude should change after physical walking steps", latDiff > 0.000001)
        assertNotEquals("Published latitude must not remain frozen at starting anchor", anchorLat, finalLat)
        assertTrue("Distance travelled should be non-zero", updatedState.distanceTravelled > 0.0)
    }

    @Test
    fun testPhysicalDemoMode_PhysicalWalking_UpdatesPublishedPosition() {
        val startLat = 11.0168
        val startLon = 76.9558

        deadReckoningEngine.initialize(latitude = startLat, longitude = startLon, speedMps = 0f, bearingDeg = 0f)
        deadReckoningEngine.resetVelocitiesAndPdr()
        deadReckoningEngine.setBlackoutMode(true, isPedestrianDemo = true)

        val initialState = deadReckoningEngine.getState()
        assertEquals(startLat, initialState.latitude, 0.00001)
        assertEquals(startLon, initialState.longitude, 0.00001)

        // Feed physical walking steps with software simulation stopped
        feedWalkingStepSamples(numSteps = 10)

        val updatedState = deadReckoningEngine.getState()

        val finalLat = updatedState.latitude
        val finalLon = updatedState.longitude

        val latDiff = abs(finalLat - startLat)
        assertTrue("Physical Demo Mode position must update dynamically when walking", latDiff > 0.000001)
        assertNotEquals("Position must not stay frozen in Physical Demo Mode", startLat, finalLat)
        assertTrue("Distance travelled should be non-zero", updatedState.distanceTravelled > 0.0)
    }

    @Test
    fun testStationaryMode_NoArtificialTravel() {
        val anchorLat = 11.0168
        val anchorLon = 76.9558

        deadReckoningEngine.initialize(latitude = anchorLat, longitude = anchorLon, speedMps = 0f, bearingDeg = 0f)
        deadReckoningEngine.setBlackoutMode(true)

        val initialLat = deadReckoningEngine.getState().latitude
        val initialLon = deadReckoningEngine.getState().longitude

        // Feed 60 stationary IMU noise samples
        feedStationarySamples(numSamples = 60)

        val finalState = deadReckoningEngine.getState()
        val finalLat = finalState.latitude
        val finalLon = finalState.longitude
        val dist = finalState.distanceTravelled

        assertEquals("Stationary IMU noise must produce zero latitude displacement", initialLat, finalLat, 0.0000001)
        assertEquals("Stationary IMU noise must produce zero longitude displacement", initialLon, finalLon, 0.0000001)
        assertEquals("Stationary IMU noise must produce 0.0m distance", 0.0, dist, 0.00001)
    }
}
