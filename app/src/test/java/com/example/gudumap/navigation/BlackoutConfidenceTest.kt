package com.example.gudumap.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlackoutConfidenceTest {

    @Test
    fun testMathematicalUncertaintyDerivation() {
        val ekf = EKF()
        ekf.initialize(pNorth = 0.0, pEast = 0.0, pDown = 0.0)

        // Initial P00 = gnssPosNoise^2 = 3.0^2 = 9.0, P11 = 9.0
        // Variance sum = 18.0 -> sqrt(18.0) ~ 4.2426m
        val expected = kotlin.math.sqrt(ekf.P[0][0] + ekf.P[1][1])
        val derived = ekf.getHorizontalUncertainty()

        assertEquals(expected, derived, 1e-5)
        assertTrue("Initial horizontal uncertainty must be positive and non-zero", derived > 0.0)
    }

    @Test
    fun testCovarianceGrowthDuringBlackoutPrediction() {
        val ekf = EKF()
        ekf.initialize(pNorth = 0.0, pEast = 0.0, pDown = 0.0)
        val initialUncertainty = ekf.getHorizontalUncertainty()

        // Simulate 5 seconds of prediction steps during blackout
        for (step in 1..5) {
            ekf.predict(deltaPNed = doubleArrayOf(1.0, 0.5, 0.0), dt = 1.0)
        }

        val blackoutUncertainty = ekf.getHorizontalUncertainty()
        assertTrue(
            "Covariance and uncertainty must increase during blackout prediction",
            blackoutUncertainty > initialUncertainty
        )
    }

    @Test
    fun testGnssUpdateReducesUncertainty() {
        val ekf = EKF()
        ekf.initialize(pNorth = 0.0, pEast = 0.0, pDown = 0.0)

        for (step in 1..5) {
            ekf.predict(deltaPNed = doubleArrayOf(1.0, 0.5, 0.0), dt = 1.0)
        }
        val highUncertainty = ekf.getHorizontalUncertainty()

        // GNSS fix with high accuracy (1.0m)
        ekf.updateGnssPosition(pNorth = 5.0, pEast = 2.5, pDown = 0.0, noiseMeters = 1.0)
        val updatedUncertainty = ekf.getHorizontalUncertainty()

        assertTrue(
            "Trusted GNSS update must reduce position uncertainty",
            updatedUncertainty < highUncertainty
        )
    }
}
