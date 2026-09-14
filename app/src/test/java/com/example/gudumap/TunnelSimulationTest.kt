package com.example.gudumap

import com.example.gudumap.navigation.DeadReckoningEngine
import com.example.gudumap.sensor.ImuSample
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelSimulationTest {

    @Test
    fun testBlackoutStateTransitionsInEngine() {
        val engine = DeadReckoningEngine()
        engine.initialize(11.0168, 76.9558, 12.5f, 45f)

        assertFalse("Initially blackout mode should be false", engine.blackoutMode)

        // Trigger blackout mode
        engine.setBlackoutMode(true)
        assertTrue("Blackout mode should be active", engine.blackoutMode)

        // Feed IMU sample
        val sample = ImuSample(
            timestampNs = System.nanoTime(),
            ax = 0.1f, ay = 0.0f, az = 9.81f,
            gx = 0.0f, gy = 0.0f, gz = 0.0f
        )
        engine.addSensorSample(sample)

        val state = engine.getState()
        assertTrue("State should reflect blackout mode", state.isBlackout)

        // Disable blackout mode
        engine.setBlackoutMode(false)
        assertFalse("Blackout mode should be disabled", engine.blackoutMode)
    }
}
