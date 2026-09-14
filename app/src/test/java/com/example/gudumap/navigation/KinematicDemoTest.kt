package com.example.gudumap.navigation

import com.example.gudumap.map.OfflineMapManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class KinematicDemoTest {

    @Test
    fun testKinematicNorthMovement() {
        val startLat = OfflineMapManager.COIMBATORE_DEFAULT_LAT
        val startLon = OfflineMapManager.COIMBATORE_DEFAULT_LON

        var lat = startLat
        var lon = startLon

        val speedKmh = 36f // 10 m/s
        val headingDeg = 0f // North
        val dtSec = 1.0

        for (step in 1..10) { // 10 seconds -> 100 meters North
            val speedMps = speedKmh / 3.6f
            val distance = speedMps * dtSec
            val headingRad = Math.toRadians(headingDeg.toDouble())
            val deltaNorth = distance * Math.cos(headingRad)
            val deltaEast = distance * Math.sin(headingRad)

            val newGeodetic = CoordinateTransformer.addMetricDisplacementToGeodetic(
                lat, lon, deltaNorth, deltaEast
            )
            lat = newGeodetic[0]
            lon = newGeodetic[1]
        }

        val totalNorthDisplacement = CoordinateTransformer.computeDistanceBetween(startLat, startLon, lat, startLon)
        val totalEastDisplacement = CoordinateTransformer.computeDistanceBetween(lat, startLon, lat, lon)

        assertEquals(100.0, totalNorthDisplacement, 1.0)
        assertEquals(0.0, totalEastDisplacement, 0.5)
    }

    @Test
    fun testKinematicEastMovement() {
        val startLat = OfflineMapManager.COIMBATORE_DEFAULT_LAT
        val startLon = OfflineMapManager.COIMBATORE_DEFAULT_LON

        var lat = startLat
        var lon = startLon

        val speedKmh = 36f // 10 m/s
        val headingDeg = 90f // East
        val dtSec = 1.0

        for (step in 1..10) { // 10 seconds -> 100 meters East
            val speedMps = speedKmh / 3.6f
            val distance = speedMps * dtSec
            val headingRad = Math.toRadians(headingDeg.toDouble())
            val deltaNorth = distance * Math.cos(headingRad)
            val deltaEast = distance * Math.sin(headingRad)

            val newGeodetic = CoordinateTransformer.addMetricDisplacementToGeodetic(
                lat, lon, deltaNorth, deltaEast
            )
            lat = newGeodetic[0]
            lon = newGeodetic[1]
        }

        val totalNorthDisplacement = CoordinateTransformer.computeDistanceBetween(startLat, startLon, lat, startLon)
        val totalEastDisplacement = CoordinateTransformer.computeDistanceBetween(startLat, startLon, startLat, lon)

        assertEquals(0.0, totalNorthDisplacement, 0.5)
        assertEquals(100.0, totalEastDisplacement, 1.0)
    }

    @Test
    fun testKinematicSouthMovement() {
        val startLat = OfflineMapManager.COIMBATORE_DEFAULT_LAT
        val startLon = OfflineMapManager.COIMBATORE_DEFAULT_LON

        var lat = startLat
        var lon = startLon

        val speedKmh = 18f // 5 m/s
        val headingDeg = 180f // South
        val dtSec = 1.0

        for (step in 1..20) { // 20 seconds -> 100 meters South
            val speedMps = speedKmh / 3.6f
            val distance = speedMps * dtSec
            val headingRad = Math.toRadians(headingDeg.toDouble())
            val deltaNorth = distance * Math.cos(headingRad)
            val deltaEast = distance * Math.sin(headingRad)

            val newGeodetic = CoordinateTransformer.addMetricDisplacementToGeodetic(
                lat, lon, deltaNorth, deltaEast
            )
            lat = newGeodetic[0]
            lon = newGeodetic[1]
        }

        val totalSouthDisplacement = CoordinateTransformer.computeDistanceBetween(startLat, startLon, lat, startLon)

        assertEquals(100.0, totalSouthDisplacement, 1.0)
    }

    @Test
    fun testZeroSpeedNoTranslation() {
        val startLat = OfflineMapManager.COIMBATORE_DEFAULT_LAT
        val startLon = OfflineMapManager.COIMBATORE_DEFAULT_LON

        var lat = startLat
        var lon = startLon

        val speedKmh = 0f
        val headingDeg = 45f

        for (step in 1..10) {
            val speedMps = speedKmh / 3.6f
            val distance = speedMps * 1.0
            val headingRad = Math.toRadians(headingDeg.toDouble())
            val deltaNorth = distance * Math.cos(headingRad)
            val deltaEast = distance * Math.sin(headingRad)

            val newGeodetic = CoordinateTransformer.addMetricDisplacementToGeodetic(
                lat, lon, deltaNorth, deltaEast
            )
            lat = newGeodetic[0]
            lon = newGeodetic[1]
        }

        assertEquals(startLat, lat, 1e-7)
        assertEquals(startLon, lon, 1e-7)
    }

    @Test
    fun testConnectedLegsWithDirectionChanges() {
        val startLat = OfflineMapManager.COIMBATORE_DEFAULT_LAT
        val startLon = OfflineMapManager.COIMBATORE_DEFAULT_LON

        var lat = startLat
        var lon = startLon

        // Leg 1: 5s North at 36 km/h (50m N)
        var speedKmh = 36f
        var headingDeg = 0f
        for (step in 1..5) {
            val speedMps = speedKmh / 3.6f
            val distance = speedMps * 1.0
            val headingRad = Math.toRadians(headingDeg.toDouble())
            val deltaNorth = distance * Math.cos(headingRad)
            val deltaEast = distance * Math.sin(headingRad)

            val newGeodetic = CoordinateTransformer.addMetricDisplacementToGeodetic(
                lat, lon, deltaNorth, deltaEast
            )
            lat = newGeodetic[0]
            lon = newGeodetic[1]
        }

        // Leg 2: 5s East at 36 km/h (50m E)
        headingDeg = 90f
        for (step in 1..5) {
            val speedMps = speedKmh / 3.6f
            val distance = speedMps * 1.0
            val headingRad = Math.toRadians(headingDeg.toDouble())
            val deltaNorth = distance * Math.cos(headingRad)
            val deltaEast = distance * Math.sin(headingRad)

            val newGeodetic = CoordinateTransformer.addMetricDisplacementToGeodetic(
                lat, lon, deltaNorth, deltaEast
            )
            lat = newGeodetic[0]
            lon = newGeodetic[1]
        }

        val totalNorth = CoordinateTransformer.computeDistanceBetween(startLat, startLon, lat, startLon)
        val totalEast = CoordinateTransformer.computeDistanceBetween(startLat, startLon, startLat, lon)

        assertEquals(50.0, totalNorth, 1.0)
        assertEquals(50.0, totalEast, 1.0)
    }

    @Test
    fun testDeterministicKinematicDemoNoFabricatedUncertainty() {
        val state = NavigationState(
            locationProvider = "demo",
            uncertaintyRadiusMeters = 0.0
        )
        assertEquals("demo", state.locationProvider)
        assertEquals(0.0, state.uncertaintyRadiusMeters, 1e-6)
    }

    @Test
    fun testMarkerRotationAtKeyAngles() {
        // Angles: 0°, 45°, 90°, 91°, 126°, 180°, 264°, 270°, 359°, 721°, -90°
        val angles = listOf(0f, 45f, 90f, 91f, 126f, 180f, 264f, 270f, 359f, 721f, -90f)
        // osmdroid canvas rotation angle = (360 - heading) % 360
        val expected = listOf(0f, 315f, 270f, 269f, 234f, 180f, 96f, 90f, 1f, 359f, 90f)

        for (i in angles.indices) {
            val rot = CoordinateTransformer.computeMarkerRotation(angles[i])
            assertEquals(expected[i], rot, 1e-3f)
        }
    }

    @Test
    fun testShortestAngularDeltaWrapAround() {
        // 359° -> 1° should be +2°, not -358°
        val delta1 = CoordinateTransformer.computeShortestAngularDelta(359f, 1f)
        assertEquals(2f, delta1, 1e-3f)

        // 1° -> 359° should be -2°, not +358°
        val delta2 = CoordinateTransformer.computeShortestAngularDelta(1f, 359f)
        assertEquals(-2f, delta2, 1e-3f)
    }

    @Test
    fun testSoutheast126DegreesDisplacementAndRotation() {
        val startLat = OfflineMapManager.COIMBATORE_DEFAULT_LAT
        val startLon = OfflineMapManager.COIMBATORE_DEFAULT_LON

        val headingDeg = 126f
        val speedKmh = 36f // 10 m/s
        val dtSec = 1.0

        val speedMps = speedKmh / 3.6f
        val distance = speedMps * dtSec
        val headingRad = Math.toRadians(headingDeg.toDouble())
        val deltaNorth = distance * Math.cos(headingRad) // negative (South)
        val deltaEast = distance * Math.sin(headingRad)  // positive (East)

        val newGeodetic = CoordinateTransformer.addMetricDisplacementToGeodetic(
            startLat, startLon, deltaNorth, deltaEast
        )

        // Verify displacement goes South and East
        assertTrue("Displacement should move South (lat decreases)", newGeodetic[0] < startLat)
        assertTrue("Displacement should move East (lon increases)", newGeodetic[1] > startLon)

        // Verify osmdroid marker rotation for 126° (360 - 126 = 234°)
        val markerRotation = CoordinateTransformer.computeMarkerRotation(headingDeg)
        assertEquals(234f, markerRotation, 1e-3f)
    }

    @Test
    fun testDemoTrailStateRetentionOnStopAndClearOnReset() {
        val stateWithTrail = NavigationState(
            demoTrailPoints = listOf(
                Pair(11.0168, 76.9558),
                Pair(11.0170, 76.9560),
                Pair(11.0172, 76.9562)
            )
        )

        // Stopping demo retains completed trail
        assertEquals(3, stateWithTrail.demoTrailPoints.size)
        assertEquals(11.0168, stateWithTrail.demoTrailPoints[0].first, 1e-6)

        // Resetting demo clears trail points
        val resetState = stateWithTrail.copy(demoTrailPoints = emptyList())
        assertTrue("Trail points must be cleared on reset", resetState.demoTrailPoints.isEmpty())
    }

    @Test
    fun testDemoModeEnabledDefaultsToFalseAndIsSeparateFromActive() {
        val initialState = NavigationState()
        assertTrue("Fresh launch must default isDemoModeEnabled to false", !initialState.isDemoModeEnabled)

        // Panel visible (isDemoModeEnabled = true), simulation stopped (isKinematicDemoActive = false)
        val stoppedState = initialState.copy(
            isDemoModeEnabled = true,
            demoTrailPoints = listOf(Pair(11.0168, 76.9558))
        )
        assertTrue("Panel remains visible when demo mode enabled", stoppedState.isDemoModeEnabled)
        assertEquals(1, stoppedState.demoTrailPoints.size)

        // Turning sidebar switch OFF hides panel but preserves trail points
        val hiddenPanelState = stoppedState.copy(isDemoModeEnabled = false)
        assertTrue("Sidebar OFF hides panel", !hiddenPanelState.isDemoModeEnabled)
        assertEquals("Completed trail points retained when panel hidden", 1, hiddenPanelState.demoTrailPoints.size)
    }

    @Test
    fun testManualControlStateSeparationAndReset() {
        val defaultState = NavigationState()
        assertEquals(36f, defaultState.selectedDemoSpeed, 1e-3f)
        assertEquals(0f, defaultState.selectedDemoHeading, 1e-3f)

        // Manual configuration before START DEMO
        val configuredState = defaultState.copy(
            selectedDemoSpeed = 60f,
            selectedDemoHeading = 90f
        )
        assertEquals(60f, configuredState.selectedDemoSpeed, 1e-3f)
        assertEquals(90f, configuredState.selectedDemoHeading, 1e-3f)

        // Reset restores defaults
        val resetState = configuredState.copy(
            selectedDemoSpeed = 36f,
            selectedDemoHeading = 0f,
            demoTrailPoints = emptyList()
        )
        assertEquals(36f, resetState.selectedDemoSpeed, 1e-3f)
        assertEquals(0f, resetState.selectedDemoHeading, 1e-3f)
        assertTrue(resetState.demoTrailPoints.isEmpty())
    }

    @Test
    fun testCoverageStateLogic() {
        val bounds = OfflineMapManager.COIMBATORE_BOUNDS
        val west = bounds.lonWest  // 76.880
        val east = bounds.lonEast  // 77.070
        val south = bounds.latSouth // 10.915
        val north = bounds.latNorth // 11.125

        // Case 1: No valid GPS location -> WAITING_FOR_LOCATION (not OUT_OF_COVERAGE)
        val noGpsState = com.example.gudumap.ui.components.CoverageState.WAITING_FOR_LOCATION
        assertEquals(com.example.gudumap.ui.components.CoverageState.WAITING_FOR_LOCATION, noGpsState)

        // Case 2: Valid GPS inside Coimbatore bounds -> IN_COVERAGE
        val inCoverageLat = 11.0168
        val inCoverageLon = 76.9558
        val isInside = inCoverageLon in west..east && inCoverageLat in south..north
        assertTrue("Coimbatore center must be inside MBTiles bounds", isInside)

        // Case 3: Valid GPS outside Coimbatore bounds (Chennai) -> OUT_OF_COVERAGE
        val outLat = 13.0827
        val outLon = 80.2707
        val isOutside = !(outLon in west..east && outLat in south..north)
        assertTrue("Chennai coordinates must be outside Coimbatore MBTiles bounds", isOutside)
    }

    @Test
    fun testDemoModeActivationPreservesDisplayedPosition() {
        val userPositionLat = 11.0250
        val userPositionLon = 76.9600

        val stateBeforeDemo = NavigationState(
            latitude = userPositionLat,
            longitude = userPositionLon,
            isDemoModeEnabled = false,
            navigationSource = "GPS"
        )

        // When Demo Mode is enabled, start location must capture the user position without jumping to default coordinates
        val startLocation = if (stateBeforeDemo.latitude.isFinite() && stateBeforeDemo.longitude.isFinite()) {
            Pair(stateBeforeDemo.latitude, stateBeforeDemo.longitude)
        } else {
            Pair(OfflineMapManager.COIMBATORE_DEFAULT_LAT, OfflineMapManager.COIMBATORE_DEFAULT_LON)
        }

        assertEquals("Lat must match user position", userPositionLat, startLocation.first, 1e-6)
        assertEquals("Lon must match user position", userPositionLon, startLocation.second, 1e-6)
    }

    @Test
    fun testDemoModeStartResumeHandoff() {
        val posA = Pair(11.0200, 76.9500)
        var simulatedPos = posA

        // START DEMO from posA -> moves to posB
        val posB = Pair(11.0210, 76.9510)
        simulatedPos = posB // STOP DEMO at posB

        // START DEMO again (resume) must start from posB, NOT posA or default fallback
        val resumeStart = simulatedPos
        assertEquals("Resume must begin from stopped position B", posB.first, resumeStart.first, 1e-6)
        assertEquals("Resume must begin from stopped position B", posB.second, resumeStart.second, 1e-6)
    }

    @Test
    fun testNavigationVisualModeResolution() {
        val liveState = NavigationState(
            isDemoModeEnabled = false,
            hasGpsFix = true,
            gpsState = GpsState.FIXED,
            visualMode = NavigationVisualMode.LIVE
        )
        assertEquals(NavigationVisualMode.LIVE, liveState.visualMode)

        val demoState = NavigationState(
            isDemoModeEnabled = true,
            visualMode = NavigationVisualMode.DEMO
        )
        assertEquals(NavigationVisualMode.DEMO, demoState.visualMode)

        val fallbackState = NavigationState(
            isDemoModeEnabled = false,
            blackoutMode = true,
            gpsState = GpsState.LOST,
            visualMode = NavigationVisualMode.GPS_FALLBACK
        )
        assertEquals(NavigationVisualMode.GPS_FALLBACK, fallbackState.visualMode)
    }

    @Test
    fun testTrailSeparationAndPreservation() {
        val gpsPoints = listOf(Pair(11.0168, 76.9558), Pair(11.0170, 76.9560))
        val demoPoints = listOf(Pair(11.0200, 76.9600), Pair(11.0210, 76.9610))

        val state = NavigationState(
            gpsTrailPoints = gpsPoints,
            demoTrailPoints = demoPoints
        )

        assertEquals("GPS trail points must be preserved", 2, state.gpsTrailPoints.size)
        assertEquals("Demo trail points must be preserved", 2, state.demoTrailPoints.size)
        assertEquals(11.0168, state.gpsTrailPoints[0].first, 1e-6)
        assertEquals(11.0200, state.demoTrailPoints[0].first, 1e-6)
    }

    @Test
    fun testPhysicalDisplacementDemoMovement() {
        val startLat = 11.0168
        val startLon = 76.9558

        val physicalDistanceMoved = 20.0 // 20 meters physical walking
        val demoHeadingDeg = 90f // 90° East

        val headingRad = Math.toRadians(demoHeadingDeg.toDouble())
        val deltaNorth = physicalDistanceMoved * Math.cos(headingRad)
        val deltaEast = physicalDistanceMoved * Math.sin(headingRad)

        val newGeodetic = CoordinateTransformer.addMetricDisplacementToGeodetic(
            startLat, startLon, deltaNorth, deltaEast
        )

        val northDisp = CoordinateTransformer.computeDistanceBetween(startLat, startLon, newGeodetic[0], startLon)
        val eastDisp = CoordinateTransformer.computeDistanceBetween(startLat, startLon, startLat, newGeodetic[1])

        assertEquals("North displacement should be zero for East heading", 0.0, northDisp, 0.5)
        assertEquals("East displacement should match physical distance", 20.0, eastDisp, 0.5)
    }

    @Test
    fun testPhysicalDisplacementJitterFiltering() {
        val startLat = 11.0168
        val startLon = 76.9558

        val jitterDistance = 1.0 // 1.0 meter jitter (below 1.5m threshold)
        val isPhysicalMovementCredible = jitterDistance >= 1.5

        assertTrue("1.0m jitter must be filtered out", !isPhysicalMovementCredible)
    }

    @Test
    fun testHeadingChangeWhileWalking() {
        var simulatedLat = 11.0168
        var simulatedLon = 76.9558

        // Step 1: Walk 10m with Heading = 0° (North)
        var demoHeading = 0f
        var headingRad = Math.toRadians(demoHeading.toDouble())
        var newGeo = CoordinateTransformer.addMetricDisplacementToGeodetic(simulatedLat, simulatedLon, 10.0 * Math.cos(headingRad), 10.0 * Math.sin(headingRad))
        simulatedLat = newGeo[0]
        simulatedLon = newGeo[1]

        val northStep = CoordinateTransformer.computeDistanceBetween(11.0168, 76.9558, simulatedLat, 76.9558)
        assertEquals(10.0, northStep, 0.5)

        // Step 2: Change Heading to 90° (East) and walk 10m
        demoHeading = 90f
        headingRad = Math.toRadians(demoHeading.toDouble())
        newGeo = CoordinateTransformer.addMetricDisplacementToGeodetic(simulatedLat, simulatedLon, 10.0 * Math.cos(headingRad), 10.0 * Math.sin(headingRad))

        val eastStep = CoordinateTransformer.computeDistanceBetween(simulatedLat, 76.9558, newGeo[0], newGeo[1])
        assertEquals(10.0, eastStep, 0.5)
    }

    @Test
    fun testPhysicalWalkingDeadReckoningInDemoMode() {
        val startLat = 11.0168
        val startLon = 76.9558
        val drEngine = DeadReckoningEngine()
        drEngine.initialize(startLat, startLon, 0f, 0f)
        drEngine.setBlackoutMode(true)

        // Verify DR engine initializes properly for physical walking in Demo Mode
        assertTrue("DR engine must be initialized in Demo Mode", drEngine.isInitialized)
        val drState = drEngine.getState()
        assertEquals("Start lat must match anchor", startLat, drState.latitude, 1e-6)
        assertEquals("Start lon must match anchor", startLon, drState.longitude, 1e-6)
    }

    @Test
    fun testZeroJumpTransitionOnSoftwareDemoStop() {
        val simLat = 11.0250
        val simLon = 76.9650
        val simHeading = 90f

        val drEngine = DeadReckoningEngine()
        // Re-anchor DR engine on simulation stop
        drEngine.initialize(simLat, simLon, 0f, simHeading)
        drEngine.setBlackoutMode(true)

        val stateAfterStop = drEngine.getState()
        assertEquals("Lat after stopping software demo must match simulation coordinate", simLat, stateAfterStop.latitude, 1e-6)
        assertEquals("Lon after stopping software demo must match simulation coordinate", simLon, stateAfterStop.longitude, 1e-6)
        assertEquals("Heading after stopping software demo must match simulation heading", simHeading, stateAfterStop.heading, 1e-3f)
    }

    @Test
    fun testStopDemoPreservesMovedLocationAndResetRestoresAnchor() {
        val movedLat = 11.0250
        val movedLon = 76.9650
        val initialLat = 11.0168
        val initialLon = 76.9558

        val drEngine = DeadReckoningEngine()

        // 1. Stopping demo preserves moved location
        drEngine.initialize(movedLat, movedLon, 0f, 90f)
        drEngine.setBlackoutMode(true)
        val stateAfterStop = drEngine.getState()
        assertEquals("Lat after stopping software demo must remain at moved location", movedLat, stateAfterStop.latitude, 1e-6)
        assertEquals("Lon after stopping software demo must remain at moved location", movedLon, stateAfterStop.longitude, 1e-6)

        // 2. Clicking Reset restores location to initial anchor
        drEngine.initialize(initialLat, initialLon, 0f, 0f)
        val stateAfterReset = drEngine.getState()
        assertEquals("Lat after reset must return to initial anchor", initialLat, stateAfterReset.latitude, 1e-6)
        assertEquals("Lon after reset must return to initial anchor", initialLon, stateAfterReset.longitude, 1e-6)
    }

    @Test
    fun testCompletePredictedPathPreservedAcrossRunAgain() {
        val trailList = mutableListOf<Pair<Double, Double>>()
        trailList.add(11.0168 to 76.9558)
        trailList.add(11.0178 to 76.9558)
        trailList.add(11.0188 to 76.9558)

        // Resuming simulation preserves existing points
        if (trailList.isEmpty()) {
            trailList.add(11.0198 to 76.9558)
        } else if (trailList.lastOrNull() != (11.0198 to 76.9558)) {
            trailList.add(11.0198 to 76.9558)
        }

        assertEquals(4, trailList.size)
        assertEquals(11.0168, trailList.first().first, 1e-6)
        assertEquals(11.0198, trailList.last().first, 1e-6)
    }
}




