package com.example.gudumap.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GpsLocationFilterTest {

    private lateinit var filter: GpsLocationFilter

    @Before
    fun setUp() {
        filter = GpsLocationFilter()
    }

    @Test
    fun testIsValidLocation_validCoordinatesAndAccuracy() {
        val now = System.currentTimeMillis()
        assertTrue(filter.isValidLocation(11.0168, 76.9558, 5.0f, now, true, now))
    }

    @Test
    fun testIsValidLocation_invalidCoordinates() {
        val now = System.currentTimeMillis()
        assertFalse(filter.isValidLocation(0.0, 0.0, 5.0f, now, true, now))
        assertFalse(filter.isValidLocation(95.0, 76.9558, 5.0f, now, true, now))
    }

    @Test
    fun testIsValidLocation_zeroOrNegativeAccuracy() {
        val now = System.currentTimeMillis()
        assertFalse(filter.isValidLocation(11.0168, 76.9558, 0.0f, now, true, now))
        assertFalse(filter.isValidLocation(11.0168, 76.9558, -1.0f, now, true, now))
    }

    @Test
    fun testIsValidLocation_staleTimestamp() {
        val now = System.currentTimeMillis()
        val oldTime = now - 40000L // 40s ago
        assertFalse(filter.isValidLocation(11.0168, 76.9558, 5.0f, oldTime, true, now))
    }

    @Test
    fun testStationaryJitterSuppression() {
        val now = System.currentTimeMillis()
        val firstResult = filter.processLocation(
            rawLat = 11.016800,
            rawLon = 76.955800,
            accuracy = 5.0f,
            locationTimeMs = now,
            speedMps = 1.5f,
            hasSpeed = true,
            hasAccuracy = true,
            currentTimeMs = now
        )
        assertEquals(11.016800, firstResult.filteredLatitude, 1e-6)
        assertEquals(76.955800, firstResult.filteredLongitude, 1e-6)

        // Micro movement of ~0.2m (well inside accuracy-aware noise radius ~2.0m)
        val secondResult = filter.processLocation(
            rawLat = 11.0168001,
            rawLon = 76.9558001,
            accuracy = 5.0f,
            locationTimeMs = now + 1000,
            speedMps = 0.1f, // Low speed below stationary threshold
            hasSpeed = true,
            hasAccuracy = true,
            currentTimeMs = now + 1000
        )
        
        // Output coordinates should remain locked to initial position to prevent stationary jitter
        assertEquals(11.016800, secondResult.filteredLatitude, 1e-6)
        assertEquals(76.955800, secondResult.filteredLongitude, 1e-6)
        assertTrue("Result should be marked stationary", secondResult.isStationary)
    }

    @Test
    fun testShortestAngleHeadingInterpolation() {
        val now = System.currentTimeMillis()
        val firstResult = filter.processLocation(
            rawLat = 11.0168,
            rawLon = 76.9558,
            accuracy = 5.0f,
            locationTimeMs = now,
            bearingDeg = 359.0f,
            speedMps = 3.0f,
            hasBearing = true,
            hasAccuracy = true,
            hasSpeed = true,
            currentTimeMs = now
        )
        assertEquals(359.0, firstResult.filteredHeadingDeg.toDouble(), 1e-3)

        // Transition from 359 deg to 1 deg across north boundary
        val secondResult = filter.processLocation(
            rawLat = 11.0200,
            rawLon = 76.9600,
            accuracy = 5.0f,
            locationTimeMs = now + 1000,
            bearingDeg = 1.0f,
            speedMps = 3.0f,
            hasBearing = true,
            hasAccuracy = true,
            hasSpeed = true,
            currentTimeMs = now + 1000
        )
        
        // Shortest angle delta is +2 deg. Low-pass filter should interpolate smoothly around 0/360 deg
        val filteredHeading = secondResult.filteredHeadingDeg.toDouble()
        assertTrue("Heading should stay near 0/360 boundary, was $filteredHeading", filteredHeading > 350.0 || filteredHeading < 10.0)
    }

    @Test
    fun testGpsAccuracyLevelClassification() {
        assertEquals(GpsAccuracyLevel.EXCELLENT, GpsAccuracyLevel.fromAccuracy(3.0f))
        assertEquals(GpsAccuracyLevel.GOOD, GpsAccuracyLevel.fromAccuracy(8.0f))
        assertEquals(GpsAccuracyLevel.FAIR, GpsAccuracyLevel.fromAccuracy(18.0f))
        assertEquals(GpsAccuracyLevel.POOR, GpsAccuracyLevel.fromAccuracy(40.0f))
        assertEquals(GpsAccuracyLevel.VERY_POOR, GpsAccuracyLevel.fromAccuracy(75.0f))
        assertEquals(GpsAccuracyLevel.VERY_POOR, GpsAccuracyLevel.fromAccuracy(0.0f))
    }
}
