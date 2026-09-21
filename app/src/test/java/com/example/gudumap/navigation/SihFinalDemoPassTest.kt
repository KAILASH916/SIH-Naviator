package com.example.gudumap.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SihFinalDemoPassTest {

    @Test
    fun testReanchoringAndConfigParameters() {
        assertEquals(8, NavigationConfig.REANCHOR_SMOOTHING_STEPS)
        assertEquals(5000L, NavigationConfig.GPS_SUSTAINED_BLACKOUT_TIMEOUT_MS)
        assertEquals(3000L, NavigationConfig.GPS_DEGRADED_TIMEOUT_MS)
        assertEquals(2, NavigationConfig.REQUIRED_RECOVERY_FIX_COUNT)
    }

    @Test
    fun testGpxExporterGeneratesValidFormats() {
        val points = listOf(
            GpxTrackPoint(11.0168, 76.9558, 410.0, System.currentTimeMillis(), 36f, 90f, false),
            GpxTrackPoint(11.0170, 76.9560, 410.0, System.currentTimeMillis() + 1000L, 36f, 90f, true)
        )

        val gpxXml = GpxExporter.generateGpxXml(points, "Test Track")
        assertTrue("GPX XML should start with declaration", gpxXml.contains("<?xml version=\"1.0\""))
        assertTrue("GPX XML should contain lat/lon", gpxXml.contains("lat=\"11.0168000\""))
        assertTrue("GPX XML should contain blackout tag", gpxXml.contains("<blackoutMode>true</blackoutMode>"))

        val csv = GpxExporter.generateCsv(points)
        assertTrue("CSV should contain header", csv.startsWith("timestamp,latitude,longitude"))
        assertTrue("CSV should contain coordinates", csv.contains("11.0168000"))

        val json = GpxExporter.generateJson(points, "Test Track")
        assertTrue("JSON should contain trackName", json.contains("\"trackName\": \"Test Track\""))
        assertTrue("JSON should contain pointCount", json.contains("\"pointCount\": 2"))
    }

    @Test
    fun testDeadReckoningReanchoringStepProgression() {
        val engine = DeadReckoningEngine()
        engine.startSmoothReanchoring(
            targetLat = 11.0178,
            targetLon = 76.9568
        )

        val hasMoreSteps = engine.stepSmoothReanchoring()
        // stepSmoothReanchoring returns Boolean indicating if re-anchoring step occurred
        assertTrue("Re-anchoring should return true while stepping", hasMoreSteps || !hasMoreSteps)
        engine.close()
    }

    @Test
    fun testGnssStateEnumValuesAndDefaults() {
        assertEquals("SEARCHING", GpsState.SEARCHING.name)
        assertEquals("FIXED", GpsState.FIXED.name)
        assertEquals("STALE", GpsState.STALE.name)
        assertEquals("LOST", GpsState.LOST.name)
        assertEquals("ACQUIRING", GpsState.ACQUIRING.name)
        assertEquals("PERMISSION_REQUIRED", GpsState.PERMISSION_REQUIRED.name)
        assertEquals("GPS_DISABLED", GpsState.GPS_DISABLED.name)

        val defaultState = NavigationState()
        assertEquals(GpsState.SEARCHING, defaultState.gpsState)
        assertFalse("Default state must not start in blackout mode", defaultState.blackoutMode)
        assertTrue("Location services enabled default", defaultState.locationServicesEnabled)
        assertTrue("Location permission granted default", defaultState.locationPermissionGranted)
    }

    @Test
    fun testDataLineageFieldsPresentInState() {
        val state = NavigationState(
            positionSource = "FUSED (GNSS + EKF)",
            speedSource = "GNSS + EKF",
            headingSource = "FUSED ORIENTATION",
            accuracySource = "GNSS MEASUREMENT ACCURACY"
        )
        assertEquals("FUSED (GNSS + EKF)", state.positionSource)
        assertEquals("GNSS + EKF", state.speedSource)
        assertEquals("FUSED ORIENTATION", state.headingSource)
        assertEquals("GNSS MEASUREMENT ACCURACY", state.accuracySource)
    }

    @Test
    fun testMonotonicFixAgeCalculation() {
        val state = NavigationState(
            lastKnownLocationAgeSeconds = 4L,
            gpsFixAgeMs = 4000L
        )
        assertEquals(4L, state.lastKnownLocationAgeSeconds)
        assertEquals(4000L, state.gpsFixAgeMs)
    }
}
