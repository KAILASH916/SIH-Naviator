package com.example.gudumap.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundTruthEvaluationTest {

    @Test
    fun testBlackoutMetricsErrorAndDriftCalculation() {
        val startLat = 11.0168
        val startLon = 76.9558

        // Move 100 meters north
        val drCoords = CoordinateTransformer.addMetricDisplacementToGeodetic(
            startLat, startLon, 100.0, 0.0
        )
        val drLat = drCoords[0]
        val drLon = drCoords[1]

        // Actual ground truth position moved 98 meters north (2 meters drift error)
        val gtCoords = CoordinateTransformer.addMetricDisplacementToGeodetic(
            startLat, startLon, 98.0, 0.0
        )
        val gtLat = gtCoords[0]
        val gtLon = gtCoords[1]

        val drDistance = 100.0
        val referenceDistance = 98.0
        val positionError = CoordinateTransformer.computeDistanceBetween(
            drLat, drLon, gtLat, gtLon
        )
        val driftPercentage = (positionError / drDistance) * 100.0

        val metrics = BlackoutMetrics(
            blackoutStartTime = System.currentTimeMillis() - 10000L,
            blackoutEndTime = System.currentTimeMillis(),
            blackoutDurationSeconds = 10.0,
            blackoutStartLatitude = startLat,
            blackoutStartLongitude = startLon,
            blackoutEndGnssLatitude = gtLat,
            blackoutEndGnssLongitude = gtLon,
            drLatitude = drLat,
            drLongitude = drLon,
            gnssReferenceDistance = referenceDistance,
            drDistance = drDistance,
            positionErrorMeters = positionError,
            distanceErrorMeters = kotlin.math.abs(drDistance - referenceDistance),
            driftPercentage = driftPercentage
        )

        assertEquals(10.0, metrics.blackoutDurationSeconds, 1e-4)
        assertEquals(2.0, metrics.positionErrorMeters, 0.5) // ~2m error
        assertEquals(2.0, metrics.driftPercentage, 0.5) // ~2% drift over 100m
    }

    @Test
    fun testNavigationStateEvaluationDefaults() {
        val state = NavigationState()
        assertFalse(state.isEvaluationActive)
        assertEquals(0, state.evaluationTimeRemainingSec)
        assertEquals(0f, state.rawSpeedKmh, 1e-4f)
        assertEquals(0f, state.filteredSpeedKmh, 1e-4f)
        assertEquals(0f, state.displayedSpeedKmh, 1e-4f)
    }
}
