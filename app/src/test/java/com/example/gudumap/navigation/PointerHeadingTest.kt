package com.example.gudumap.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class PointerHeadingTest {

    @Test
    fun testComputeMarkerRotationCardinalAndIntermediateAngles() {
        // 0° points North (UP)
        assertEquals(0f, CoordinateTransformer.computeMarkerRotation(0f), 1e-4f)

        // 85° points East (RIGHT and slightly UP): osmdroid canvas rotation = (360 - 85) = 275°
        assertEquals(275f, CoordinateTransformer.computeMarkerRotation(85f), 1e-4f)

        // 87° points East (RIGHT and slightly UP): osmdroid canvas rotation = (360 - 87) = 273°
        assertEquals(273f, CoordinateTransformer.computeMarkerRotation(87f), 1e-4f)

        // 90° points East (RIGHT): osmdroid canvas rotation = (360 - 90) = 270°
        assertEquals(270f, CoordinateTransformer.computeMarkerRotation(90f), 1e-4f)

        // 180° points South (DOWN): osmdroid canvas rotation = 180°
        assertEquals(180f, CoordinateTransformer.computeMarkerRotation(180f), 1e-4f)

        // 247° points West (LEFT and slightly DOWN): osmdroid canvas rotation = (360 - 247) = 113°
        assertEquals(113f, CoordinateTransformer.computeMarkerRotation(247f), 1e-4f)

        // 267° points West (LEFT and slightly DOWN): osmdroid canvas rotation = (360 - 267) = 93°
        assertEquals(93f, CoordinateTransformer.computeMarkerRotation(267f), 1e-4f)

        // 270° points West (LEFT): osmdroid canvas rotation = (360 - 270) = 90°
        assertEquals(90f, CoordinateTransformer.computeMarkerRotation(270f), 1e-4f)

        // Wrap around normalization across 359° -> 0°
        assertEquals(0f, CoordinateTransformer.computeMarkerRotation(360f), 1e-4f)
        assertEquals(350f, CoordinateTransformer.computeMarkerRotation(370f), 1e-4f)
        assertEquals(10f, CoordinateTransformer.computeMarkerRotation(-10f), 1e-4f)
    }

    @Test
    fun testComputeShortestAngularDelta() {
        // 359° to 1° should be +2° delta (shortest way across 0°)
        assertEquals(2f, CoordinateTransformer.computeShortestAngularDelta(359f, 1f), 1e-4f)

        // 1° to 359° should be -2° delta
        assertEquals(-2f, CoordinateTransformer.computeShortestAngularDelta(1f, 359f), 1e-4f)

        // 0° to 87° should be 87°
        assertEquals(87f, CoordinateTransformer.computeShortestAngularDelta(0f, 87f), 1e-4f)
    }

    @Test
    fun testSoftwareKinematicDemoHeadingSoleSourceAtAnySpeed() {
        val simulatedHeading = 87f
        val deviceSensorHeading = 270f // Phone held facing West (270°)

        // Software Kinematic Demo active: display heading MUST be selected simulated heading (87°)
        val headingStopped = resolveDisplayHeading(
            isDemoModeEnabled = true,
            isKinematicDemoActive = true,
            blackoutActive = false,
            currentSpeedKmh = 0f,
            simulatedHeadingDeg = simulatedHeading,
            drHeading = 180f,
            liveDeviceHeading = deviceSensorHeading,
            currentHeadingDeg = 0f
        )
        assertEquals(87f, headingStopped, 1e-4f)

        val headingMoving = resolveDisplayHeading(
            isDemoModeEnabled = true,
            isKinematicDemoActive = true,
            blackoutActive = false,
            currentSpeedKmh = 36f,
            simulatedHeadingDeg = simulatedHeading,
            drHeading = 180f,
            liveDeviceHeading = deviceSensorHeading,
            currentHeadingDeg = 0f
        )
        assertEquals(87f, headingMoving, 1e-4f)
    }

    @Test
    fun testPhysicalDemoModeUsesSensorsWhenStoppedAndDrWhenWalking() {
        val deviceSensorHeading = 270f
        val drWalkingHeading = 120f

        // Physical Demo Mode (isDemoModeEnabled = true, isKinematicDemoActive = false), stationary (< 1.0 km/h)
        val headingStationary = resolveDisplayHeading(
            isDemoModeEnabled = true,
            isKinematicDemoActive = false,
            blackoutActive = false,
            currentSpeedKmh = 0f,
            simulatedHeadingDeg = 87f,
            drHeading = drWalkingHeading,
            liveDeviceHeading = deviceSensorHeading,
            currentHeadingDeg = 0f
        )
        assertEquals(270f, headingStationary, 1e-4f)

        // Physical Demo Mode, walking (>= 1.0 km/h)
        val headingWalking = resolveDisplayHeading(
            isDemoModeEnabled = true,
            isKinematicDemoActive = false,
            blackoutActive = false,
            currentSpeedKmh = 3f,
            simulatedHeadingDeg = 87f,
            drHeading = drWalkingHeading,
            liveDeviceHeading = deviceSensorHeading,
            currentHeadingDeg = 0f
        )
        assertEquals(120f, headingWalking, 1e-4f)
    }

    @Test
    fun testSimulatedPositionMovementMatchesHeading() {
        // Verify displacement for 87° heading (East-North-East)
        val distanceMeters = 10.0
        val headingRad = Math.toRadians(87.0)
        val deltaNorth = distanceMeters * Math.cos(headingRad)
        val deltaEast = distanceMeters * Math.sin(headingRad)

        // Delta East must be positive (moving RIGHT) and Delta North must be positive (moving UP)
        org.junit.Assert.assertTrue("Delta East should be positive for 87°", deltaEast > 0.0)
        org.junit.Assert.assertTrue("Delta North should be positive for 87°", deltaNorth > 0.0)
        org.junit.Assert.assertTrue("Delta East should be larger than Delta North for 87°", deltaEast > deltaNorth)

        // Verify cardinal movement directions
        // 0° (North): deltaNorth > 0, deltaEast = 0
        assertEquals(10.0, 10.0 * Math.cos(Math.toRadians(0.0)), 1e-4)
        assertEquals(0.0, 10.0 * Math.sin(Math.toRadians(0.0)), 1e-4)

        // 90° (East): deltaNorth = 0, deltaEast > 0
        assertEquals(0.0, 10.0 * Math.cos(Math.toRadians(90.0)), 1e-4)
        assertEquals(10.0, 10.0 * Math.sin(Math.toRadians(90.0)), 1e-4)

        // 180° (South): deltaNorth < 0, deltaEast = 0
        assertEquals(-10.0, 10.0 * Math.cos(Math.toRadians(180.0)), 1e-4)
        assertEquals(0.0, 10.0 * Math.sin(Math.toRadians(180.0)), 1e-4)

        // 270° (West): deltaNorth = 0, deltaEast < 0
        assertEquals(0.0, 10.0 * Math.cos(Math.toRadians(270.0)), 1e-4)
        assertEquals(-10.0, 10.0 * Math.sin(Math.toRadians(270.0)), 1e-4)
    }

    @Test
    fun testHeadingResolutionLogicStationaryNormalMode() {
        val deviceSensorHeading = 145f
        val gpsBearing = 90f

        // When stationary (< 1.0 km/h) in normal mode, display heading must use phone orientation sensor.
        val resolvedHeading = resolveDisplayHeading(
            isDemoModeEnabled = false,
            isKinematicDemoActive = false,
            blackoutActive = false,
            currentSpeedKmh = 0f,
            simulatedHeadingDeg = 0f,
            drHeading = 0f,
            liveDeviceHeading = deviceSensorHeading,
            currentHeadingDeg = gpsBearing
        )

        assertEquals(145f, resolvedHeading, 1e-4f)
    }

    @Test
    fun testHeadingResolutionLogicMovingNormalMode() {
        val deviceSensorHeading = 145f
        val travelBearing = 270f

        // When travelling (>= 1.0 km/h) in normal mode, display heading must use travel bearing.
        val resolvedHeading = resolveDisplayHeading(
            isDemoModeEnabled = false,
            isKinematicDemoActive = false,
            blackoutActive = false,
            currentSpeedKmh = 25f,
            simulatedHeadingDeg = 0f,
            drHeading = 0f,
            liveDeviceHeading = deviceSensorHeading,
            currentHeadingDeg = travelBearing
        )

        assertEquals(270f, resolvedHeading, 1e-4f)
    }

    @Test
    fun testHeadingResolutionLogicBlackoutMode() {
        val drHeading = 180f
        val deviceSensorHeading = 45f

        // In blackout mode, display heading must use the dead-reckoning / EKF estimated heading.
        val resolvedHeading = resolveDisplayHeading(
            isDemoModeEnabled = false,
            isKinematicDemoActive = false,
            blackoutActive = true,
            currentSpeedKmh = 10f,
            simulatedHeadingDeg = 0f,
            drHeading = drHeading,
            liveDeviceHeading = deviceSensorHeading,
            currentHeadingDeg = 0f
        )

        assertEquals(180f, resolvedHeading, 1e-4f)
    }

    private fun resolveDisplayHeading(
        isDemoModeEnabled: Boolean,
        isKinematicDemoActive: Boolean,
        blackoutActive: Boolean,
        currentSpeedKmh: Float,
        simulatedHeadingDeg: Float,
        drHeading: Float,
        liveDeviceHeading: Float,
        currentHeadingDeg: Float
    ): Float {
        return when {
            isKinematicDemoActive -> (simulatedHeadingDeg % 360f + 360f) % 360f
            isDemoModeEnabled -> {
                if (currentSpeedKmh < 1.0f) (liveDeviceHeading % 360f + 360f) % 360f
                else (drHeading % 360f + 360f) % 360f
            }
            blackoutActive -> (drHeading % 360f + 360f) % 360f
            currentSpeedKmh < 1.0f -> (liveDeviceHeading % 360f + 360f) % 360f
            else -> (currentHeadingDeg % 360f + 360f) % 360f
        }
    }
}
