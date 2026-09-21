package com.example.gudumap.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationEngineStateTest {

    @Test
    fun testNavigationStateMatrixDefaults() {
        val state = NavigationState()
        assertEquals(0.0, state.latitude, 1e-4)
        assertEquals(0.0, state.longitude, 1e-4)
        assertFalse(state.hasGpsFix)
        assertFalse(state.blackoutMode)
        assertEquals("", state.statusMessage)
    }

    @Test
    fun testNavigationStateMatrixCombinations() {
        // Combination 1: Internet Available, GPS Available -> GNSS Live
        val stateGnssLive = NavigationState(
            isInternetAvailable = true,
            hasGpsFix = true,
            navigationMode = "GNSS",
            gnssStatus = "AVAILABLE",
            blackoutMode = false,
            statusMessage = "GNSS Live"
        )
        assertEquals("GNSS", stateGnssLive.navigationMode)
        assertFalse(stateGnssLive.blackoutMode)
        assertEquals("GNSS Live", stateGnssLive.statusMessage)

        // Combination 2: Internet Unavailable, GPS Available -> GNSS Live (Offline Map)
        val stateOfflineMap = NavigationState(
            isInternetAvailable = false,
            hasGpsFix = true,
            navigationMode = "GNSS",
            gnssStatus = "AVAILABLE",
            blackoutMode = false,
            statusMessage = "GNSS Live — Offline map active"
        )
        assertEquals("GNSS", stateOfflineMap.navigationMode)
        assertFalse(stateOfflineMap.blackoutMode)
        assertEquals("GNSS Live — Offline map active", stateOfflineMap.statusMessage)

        // Combination 3: Internet Available, GPS Unavailable -> WAITING FOR GPS
        val stateWaitingGps = NavigationState(
            isInternetAvailable = true,
            hasGpsFix = false,
            navigationMode = "WAITING FOR GPS",
            gnssStatus = "UNAVAILABLE",
            blackoutMode = false,
            statusMessage = "Waiting for GPS — Internet available"
        )
        assertEquals("WAITING FOR GPS", stateWaitingGps.navigationMode)
        assertFalse(stateWaitingGps.blackoutMode)
        assertEquals("Waiting for GPS — Internet available", stateWaitingGps.statusMessage)

        // Combination 4: Internet Unavailable, GPS Unavailable WITH Anchor -> DEAD RECKONING
        val stateDeadReckoning = NavigationState(
            isInternetAvailable = false,
            hasGpsFix = false,
            navigationMode = "DEAD RECKONING",
            gnssStatus = "UNAVAILABLE",
            blackoutMode = true,
            statusMessage = "Dead Reckoning Active — GNSS unavailable"
        )
        assertEquals("DEAD RECKONING", stateDeadReckoning.navigationMode)
        assertTrue(stateDeadReckoning.blackoutMode)
        assertEquals("Dead Reckoning Active — GNSS unavailable", stateDeadReckoning.statusMessage)

        // Combination 5: Internet Unavailable, GPS Unavailable WITHOUT Anchor -> POSITION UNAVAILABLE
        val statePositionUnavailable = NavigationState(
            isInternetAvailable = false,
            hasGpsFix = false,
            navigationMode = "POSITION UNAVAILABLE",
            gnssStatus = "UNAVAILABLE",
            blackoutMode = false,
            statusMessage = "Position unavailable — A GPS fix is needed to start"
        )
        assertEquals("POSITION UNAVAILABLE", statePositionUnavailable.navigationMode)
        assertFalse(statePositionUnavailable.blackoutMode)
        assertEquals("Position unavailable — A GPS fix is needed to start", statePositionUnavailable.statusMessage)
    }

    @Test
    fun testManualDemoModeIsolationState() {
        val demoState = NavigationState(
            blackoutMode = true,
            isSimulatedBlackout = true,
            navigationMode = "DEAD RECKONING",
            statusMessage = "Simulated GNSS Blackout Demo"
        )
        assertTrue(demoState.blackoutMode)
        assertTrue(demoState.isSimulatedBlackout)
        assertEquals("DEAD RECKONING", demoState.navigationMode)
        assertEquals("Simulated GNSS Blackout Demo", demoState.statusMessage)
    }

    @Test
    fun testDeadReckoningEngineInitialization() {
        val engine = DeadReckoningEngine()
        assertFalse(engine.isInitialized)

        // Anchor with real fix
        engine.initialize(11.0185, 76.9572)
        assertTrue(engine.isInitialized)

        // Now blackout can activate
        engine.setBlackoutMode(true)
        assertTrue(engine.blackoutMode)
    }

    @Test
    fun testDemoModeOffClearsDemoTrailState() {
        val stateDemoOff = NavigationState(
            isDemoModeEnabled = false,
            demoTrailPoints = emptyList()
        )
        assertFalse(stateDemoOff.isDemoModeEnabled)
        assertTrue(stateDemoOff.demoTrailPoints.isEmpty())
    }

    @Test
    fun testMovementStateEnum() {
        val defaultState = NavigationState()
        assertEquals(MovementState.STATIONARY, defaultState.movementState)

        val updatedState = defaultState.copy(
            movementState = MovementState.WALKING
        )
        assertEquals(MovementState.WALKING, updatedState.movementState)
    }

    @Test
    fun testLiveOrientationAndSpeedAvailability() {
        val state = NavigationState(
            deviceHeadingDeg = 245.5f,
            isSpeedAvailable = true,
            speedKmh = 14.2f
        )
        assertEquals(245.5f, state.deviceHeadingDeg, 1e-4f)
        assertTrue(state.isSpeedAvailable)
        assertEquals(14.2f, state.speedKmh, 1e-4f)

        val noFixState = NavigationState(
            hasGpsFix = false,
            isSpeedAvailable = false
        )
        assertFalse(noFixState.isSpeedAvailable)
    }

    @Test
    fun testPredictedCoordinatesLabelByMode() {
        val softwareDemoState = NavigationState(
            isDemoModeEnabled = true,
            currentRoadName = "Predicted Coordinates (Simulated)"
        )
        assertEquals("Predicted Coordinates (Simulated)", softwareDemoState.currentRoadName)

        val physicalDemoState = NavigationState(
            isDemoModeEnabled = true,
            currentRoadName = "Predicted Coordinates"
        )
        assertEquals("Predicted Coordinates", physicalDemoState.currentRoadName)

        val blackoutState = NavigationState(
            blackoutMode = true,
            currentRoadName = "Predicted Coordinates"
        )
        assertEquals("Predicted Coordinates", blackoutState.currentRoadName)
    }

    @Test
    fun testPendingGpsLossStateAndSustainedBlackoutTransition() {
        // Short interruption (6s fix age) -> STALE (Pending loss), blackoutMode remains false, no LK marker created
        val pendingLossState = NavigationState(
            hasGpsFix = true,
            gpsState = GpsState.STALE,
            blackoutMode = false,
            historicalLkMarkers = emptyList()
        )
        assertFalse("Pending loss (short delay) must not trigger full blackout mode", pendingLossState.blackoutMode)
        assertEquals(GpsState.STALE, pendingLossState.gpsState)
        assertTrue("Pending loss must not generate an LK marker", pendingLossState.historicalLkMarkers.isEmpty())

        // Sustained interruption (10s+ fix age) -> Confirmed blackout mode active, LK marker generated
        val sustainedLossState = NavigationState(
            hasGpsFix = true,
            gpsState = GpsState.LOST,
            blackoutMode = true,
            historicalLkMarkers = listOf(LkMarkerData("lk_1", 11.0168, 76.9558, System.currentTimeMillis(), 5f))
        )
        assertTrue("Sustained 10s loss must enter confirmed blackout mode", sustainedLossState.blackoutMode)
        assertEquals(GpsState.LOST, sustainedLossState.gpsState)
        assertEquals(1, sustainedLossState.historicalLkMarkers.size)
    }
}
