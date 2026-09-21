package com.example.gudumap.navigation

import android.location.Location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupFirstFixUxTest {

    @Test
    fun testInitialGnssQualityIsLost() {
        val evaluator = GnssQualityEvaluator()
        assertEquals(GnssQualityScore.LOST, evaluator.currentQualityScore)
    }

    @Test
    fun testEvaluatesExcellentGnssFix() {
        val evaluator = GnssQualityEvaluator()

        // Hysteresis requires 2 confirmations
        evaluator.evaluate(accuracyMeters = 3.0f, fixAgeMs = 500L, speedAccuracyMps = 0.2f)
        val result = evaluator.evaluate(accuracyMeters = 3.0f, fixAgeMs = 500L, speedAccuracyMps = 0.2f)

        assertEquals(GnssQualityScore.EXCELLENT, result.qualityScore)
        assertTrue(result.isTrusted)
    }

    @Test
    fun testEvaluatesPoorGnssFixNotTrusted() {
        val evaluator = GnssQualityEvaluator()

        evaluator.evaluate(accuracyMeters = 45.0f, fixAgeMs = 6000L, speedAccuracyMps = 2.0f)
        val result = evaluator.evaluate(accuracyMeters = 45.0f, fixAgeMs = 6000L, speedAccuracyMps = 2.0f)

        assertEquals(GnssQualityScore.POOR, result.qualityScore)
        assertFalse("Poor fix must not be marked trusted", result.isTrusted)
    }

    @Test
    fun testMarkerVisibilityCondition() {
        val validLat = 11.0168
        val validLon = 76.9558
        val invalidLat = Double.NaN
        val invalidLon = Double.NaN
        val zeroLat = 0.0
        val zeroLon = 0.0

        assertTrue("Valid coordinates must enable marker visibility", com.example.gudumap.ui.components.isValidMapCoordinate(validLat, validLon))
        assertFalse("NaN coordinates must hide marker", com.example.gudumap.ui.components.isValidMapCoordinate(invalidLat, invalidLon))
        assertFalse("Sentinel 0,0 coordinates must hide marker", com.example.gudumap.ui.components.isValidMapCoordinate(zeroLat, zeroLon))
    }
}

