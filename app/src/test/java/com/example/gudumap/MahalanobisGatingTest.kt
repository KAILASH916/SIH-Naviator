package com.example.gudumap

import com.example.gudumap.navigation.EKF
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MahalanobisGatingTest {

    @Test
    fun testMahalanobisAcceptsValidUpdate() {
        val ekf = EKF()
        ekf.initialize(pNorth = 0.0, pEast = 0.0, pDown = 0.0)

        // Small 1-meter position difference (valid update)
        val accepted = ekf.updateGnssPosition(pNorth = 1.0, pEast = 0.0, pDown = 0.0, noiseMeters = 3.0, mahalanobisThreshold = 11.34)
        assertTrue("Valid 1m update should be accepted by Mahalanobis gating", accepted)
    }

    @Test
    fun testMahalanobisRejectsAnomalousJump() {
        val ekf = EKF()
        ekf.initialize(pNorth = 0.0, pEast = 0.0, pDown = 0.0)

        val initialP0 = ekf.P[0][0]

        // Huge 100-meter GPS multipath jump (anomalous update)
        val accepted = ekf.updateGnssPosition(pNorth = 100.0, pEast = 0.0, pDown = 0.0, noiseMeters = 3.0, mahalanobisThreshold = 11.34)
        assertFalse("Anomalous 100m jump should be rejected by Mahalanobis gating", accepted)

        // State and covariance must remain unchanged when rejected
        org.junit.Assert.assertEquals(0.0, ekf.state[0], 1e-6)
        org.junit.Assert.assertEquals(initialP0, ekf.P[0][0], 1e-6)
    }
}
