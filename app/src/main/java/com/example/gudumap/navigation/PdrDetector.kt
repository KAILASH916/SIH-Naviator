package com.example.gudumap.navigation

import com.example.gudumap.sensor.ImuSample
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Result of a detected pedestrian step event.
 */
data class StepEvent(
    val stepIndex: Int,
    val stepLengthMeters: Float,
    val timestampNs: Long,
    val deltaNorthMeters: Double,
    val deltaEastMeters: Double
)

/**
 * Pedestrian Dead Reckoning (PDR) Step Detector and Weinberg Step Length Estimator.
 * Formula: S = K * (a_max - a_min)^(1/4)
 * where K is the Weinberg calibration constant (default K = 0.43).
 */
class PdrDetector(
    var kWeinberg: Float = 0.43f,
    private val minStepIntervalNs: Long = 280_000_000L, // Minimum 280ms between steps (~3.5 steps/sec max)
    private val minStepAccRangeMps2: Float = 1.2f        // Minimum peak-to-trough range for step detection
) {

    companion object {
        private const val GRAVITY_MPS2 = 9.80665f
        private const val MIN_STRIDE_METERS = 0.35f
        private const val MAX_STRIDE_METERS = 1.30f
    }

    var totalStepCount: Int = 0
        private set

    var latestStrideMeters: Float = 0.65f
        private set

    private var lastStepTimestampNs: Long = 0L
    private var windowAccMax: Float = 0f
    private var windowAccMin: Float = Float.MAX_VALUE
    private var lastAccMag: Float = GRAVITY_MPS2
    private var isRising: Boolean = false

    /**
     * Process an incoming IMU sample for step detection.
     * @param sample Processed IMU sample (ax, ay, az in m/s^2).
     * @param headingDeg Current vehicle/user orientation heading in degrees (0..360).
     * @return StepEvent if a step was detected during this sample, or null otherwise.
     */
    fun processSample(sample: ImuSample, headingDeg: Float): StepEvent? {
        val ax = sample.ax
        val ay = sample.ay
        val az = sample.az
        val accMag = sqrt(ax * ax + ay * ay + az * az)

        // Track peak (a_max) and trough (a_min) in current stride window
        if (accMag > windowAccMax) windowAccMax = accMag
        if (accMag < windowAccMin) windowAccMin = accMag

        var stepEvent: StepEvent? = null

        // Detect peak: handles both raw acceleration (with gravity ~9.81 m/s^2) and linear acceleration (without gravity ~0 m/s^2)
        val dynamicAccMag = if (accMag > 5.0f) kotlin.math.abs(accMag - GRAVITY_MPS2) else accMag
        if (dynamicAccMag > 1.2f || accMag > 11.0f) {
            isRising = true
        }

        if (isRising && accMag < lastAccMag) {
            val nowNs = sample.timestampNs
            val timeSinceLastStepNs = if (lastStepTimestampNs == 0L) minStepIntervalNs + 1L else nowNs - lastStepTimestampNs

            val accRange = windowAccMax - windowAccMin
            if (timeSinceLastStepNs >= minStepIntervalNs && accRange >= minStepAccRangeMps2) {
                // Weinberg Step Length Formula: S = K * (a_max - a_min)^(1/4)
                val diffRatio = max(0.1f, accRange / GRAVITY_MPS2)
                val rawStride = kWeinberg * Math.pow(diffRatio.toDouble(), 0.25).toFloat()
                val strideMeters = rawStride.coerceIn(MIN_STRIDE_METERS, MAX_STRIDE_METERS)

                totalStepCount++
                latestStrideMeters = strideMeters
                lastStepTimestampNs = nowNs
                isRising = false

                val headingRad = Math.toRadians(headingDeg.toDouble())
                val dNorth = strideMeters * cos(headingRad)
                val dEast = strideMeters * sin(headingRad)

                stepEvent = StepEvent(
                    stepIndex = totalStepCount,
                    stepLengthMeters = strideMeters,
                    timestampNs = nowNs,
                    deltaNorthMeters = dNorth,
                    deltaEastMeters = dEast
                )

                // Reset window extrema for next step
                windowAccMax = accMag
                windowAccMin = accMag
            }
        }

        lastAccMag = accMag
        return stepEvent
    }

    fun reset() {
        totalStepCount = 0
        latestStrideMeters = 0.65f
        lastStepTimestampNs = 0L
        windowAccMax = 0f
        windowAccMin = Float.MAX_VALUE
        lastAccMag = GRAVITY_MPS2
        isRising = false
    }
}
