package com.example.gudumap.navigation

import com.example.gudumap.sensor.ImuSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PdrDetectorTest {

    private lateinit var pdrDetector: PdrDetector

    @Before
    fun setUp() {
        pdrDetector = PdrDetector()
    }

    @Test
    fun testStepDetectionAndWeinbergCalculation() {
        // Simulate walking acceleration pattern with peak and trough
        val baseNs = 1_000_000_000L
        val sampleDtNs = 20_000_000L // 50 Hz IMU

        var stepDetected: StepEvent? = null

        // Trough phase (acceleration drops)
        for (i in 0..10) {
            val sample = ImuSample(
                timestampNs = baseNs + i * sampleDtNs,
                ax = 0f, ay = 0f, az = 5.0f, // trough around 5 m/s^2
                gx = 0f, gy = 0f, gz = 0f
            )
            val event = pdrDetector.processSample(sample, 0f)
            if (event != null) stepDetected = event
        }

        // Peak phase (acceleration spikes)
        for (i in 11..25) {
            val sample = ImuSample(
                timestampNs = baseNs + i * sampleDtNs,
                ax = 0f, ay = 0f, az = 15.0f, // peak around 15 m/s^2
                gx = 0f, gy = 0f, gz = 0f
            )
            val event = pdrDetector.processSample(sample, 0f)
            if (event != null) stepDetected = event
        }

        // Falling edge after peak (triggers step detection)
        for (i in 26..30) {
            val sample = ImuSample(
                timestampNs = baseNs + i * sampleDtNs,
                ax = 0f, ay = 0f, az = 9.8f,
                gx = 0f, gy = 0f, gz = 0f
            )
            val event = pdrDetector.processSample(sample, 0f)
            if (event != null) stepDetected = event
        }

        assertNotNull("PDR detector should detect a step during simulated walking pattern", stepDetected)
        assertEquals(1, pdrDetector.totalStepCount)
        assertTrue("Weinberg stride length should be within valid human range [0.35m, 1.30m]", pdrDetector.latestStrideMeters in 0.35f..1.30f)
    }
}
