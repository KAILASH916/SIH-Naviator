package com.example.gudumap.navigation

import com.example.gudumap.sensor.ImuSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StationaryDetectionTest {

    @Test
    fun testStationaryPhoneOnTable() {
        val detector = ZuptDetector()
        val stillSample = ImuSample(
            timestampNs = 1_000_000_000L,
            ax = 0.01f, ay = 0.01f, az = 0.01f,
            gx = 0.001f, gy = 0.001f, gz = 0.001f
        )

        // Feed still samples to fill window & trigger stationary count
        for (i in 0 until 10) {
            detector.update(stillSample, gnssSpeed = 0f)
        }

        assertTrue("Phone on table must be classified stationary", detector.isStationary)
        assertEquals("HIGH", detector.stationaryConfidence)
    }

    @Test
    fun testVibrationHysteresisSuppression() {
        val detector = ZuptDetector()
        val stillSample = ImuSample(
            timestampNs = 1_000_000_000L,
            ax = 0.01f, ay = 0.01f, az = 0.01f,
            gx = 0.001f, gy = 0.001f, gz = 0.001f
        )

        for (i in 0 until 10) {
            detector.update(stillSample, gnssSpeed = 0f)
        }
        assertTrue(detector.isStationary)

        // Single vibration sample spike
        val vibrationSample = ImuSample(
            timestampNs = 2_000_000_000L,
            ax = 0.8f, ay = 0.2f, az = 0.1f,
            gx = 0.2f, gy = 0.1f, gz = 0.05f
        )

        detector.update(vibrationSample, gnssSpeed = 0f)

        // Due to hysteresis (requires 3 consecutive moving samples), single sample does not trigger MOVING
        assertTrue("Hysteresis must prevent single vibration spike from triggering MOVING immediately", detector.motionState == NavMotionState.UNCERTAIN || detector.isStationary)
    }

    @Test
    fun testTransitionStationaryToMoving() {
        val detector = ZuptDetector()
        val stillSample = ImuSample(
            timestampNs = 1_000_000_000L,
            ax = 0.01f, ay = 0.01f, az = 0.01f,
            gx = 0.001f, gy = 0.001f, gz = 0.001f
        )

        for (i in 0 until 10) {
            detector.update(stillSample, gnssSpeed = 0f)
        }
        assertTrue(detector.isStationary)

        val walkingSample = ImuSample(
            timestampNs = 2_000_000_000L,
            ax = 1.2f, ay = 0.5f, az = 0.3f,
            gx = 0.5f, gy = 0.3f, gz = 0.2f
        )

        // Feed 5 consecutive moving samples
        for (i in 0 until 5) {
            detector.update(walkingSample, gnssSpeed = 1.5f)
        }

        assertEquals(NavMotionState.MOVING, detector.motionState)
        assertFalse(detector.isStationary)
    }
}
