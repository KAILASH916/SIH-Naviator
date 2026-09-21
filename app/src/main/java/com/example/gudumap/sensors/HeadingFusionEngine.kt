package com.example.gudumap.sensors

import kotlin.math.abs

enum class ActiveHeadingSource {
    GNSS_BEARING,
    ROTATION_VECTOR,
    GYRO_PROPAGATED,
    MAGNETOMETER_FUSED
}

/**
 * Multi-source Heading Fusion Engine with Magnetic Anomaly Rejection,
 * Gyroscope Propagation, Circular 360° Wrapping, and Smooth Recovery.
 */
class HeadingFusionEngine {

    var activeHeadingSource: ActiveHeadingSource = ActiveHeadingSource.ROTATION_VECTOR
        private set

    var headingConfidence: HeadingConfidence = HeadingConfidence.HIGH
        private set

    var currentHeadingDeg: Float = 0f
        private set

    var declinationDeg: Float = 0f
        private set

    val trueHeadingDeg: Float
        get() = normalizeHeadingDeg(currentHeadingDeg + declinationDeg)

    fun updateLocationForDeclination(lat: Double, lon: Double, alt: Double = 0.0, timestampMs: Long = System.currentTimeMillis()) {
        if (lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 && lat != 0.0 && lon != 0.0) {
            try {
                val geoField = android.hardware.GeomagneticField(
                    lat.toFloat(), lon.toFloat(), alt.toFloat(), timestampMs
                )
                declinationDeg = geoField.declination
            } catch (_: Throwable) {}
        }
    }

    private var isAnomalyActive: Boolean = false
    private var normalMagCount: Int = 0

    companion object {
        private const val MIN_MAG_NORM = 20.0f
        private const val MAX_MAG_NORM = 70.0f
        private const val NORMAL_CONFIRM_SAMPLES = 5
        private const val RECOVERY_ALPHA = 0.08f // Exponential blending factor during recovery
    }

    /**
     * Circular angle difference in degrees, wrapped to [-180.0, +180.0].
     */
    fun shortestAngleDiff(fromDeg: Float, toDeg: Float): Float {
        var diff = (toDeg - fromDeg) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        return diff
    }

    /**
     * Wraps angle to [0.0, 360.0).
     */
    fun normalizeHeadingDeg(headingDeg: Float): Float {
        var result = headingDeg % 360f
        if (result < 0f) result += 360f
        return result
    }

    /**
     * Evaluates magnetic field magnitude for structural/rebar anomalies.
     */
    fun updateMagnetometer(magneticFieldNorm: Float, sensorAccuracy: Int) {
        val normAnomaly = magneticFieldNorm < MIN_MAG_NORM || magneticFieldNorm > MAX_MAG_NORM
        if (normAnomaly || sensorAccuracy == android.hardware.SensorManager.SENSOR_STATUS_UNRELIABLE) {
            isAnomalyActive = true
            normalMagCount = 0
            headingConfidence = HeadingConfidence.LOW
        } else {
            normalMagCount++
            if (normalMagCount >= NORMAL_CONFIRM_SAMPLES) {
                isAnomalyActive = false
                headingConfidence = when (sensorAccuracy) {
                    android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> HeadingConfidence.HIGH
                    android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> HeadingConfidence.MEDIUM
                    else -> HeadingConfidence.LOW
                }
            }
        }
    }

    /**
     * Integrates z-axis gyroscope rate during magnetic anomalies or tunnels.
     */
    fun updateGyroscope(gzRadPerSec: Float, dtSeconds: Float) {
        if (isAnomalyActive || activeHeadingSource == ActiveHeadingSource.GYRO_PROPAGATED) {
            val deltaDeg = Math.toDegrees((gzRadPerSec * dtSeconds).toDouble()).toFloat()
            currentHeadingDeg = normalizeHeadingDeg(currentHeadingDeg + deltaDeg)
            activeHeadingSource = ActiveHeadingSource.GYRO_PROPAGATED
        }
    }

    /**
     * Accepts trusted GNSS bearing when vehicle speed is sufficient (> 5 km/h).
     */
    fun updateGnssBearing(bearingDeg: Float, speedKmh: Float, isGnssTrusted: Boolean) {
        if (isGnssTrusted && speedKmh > 5.0f && bearingDeg in 0f..360f) {
            val diff = shortestAngleDiff(currentHeadingDeg, bearingDeg)
            currentHeadingDeg = normalizeHeadingDeg(currentHeadingDeg + diff * 0.15f)
            activeHeadingSource = ActiveHeadingSource.GNSS_BEARING
            headingConfidence = HeadingConfidence.HIGH
        }
    }

    /**
     * Updates heading from sensor fusion / rotation vector when magnetic environment is clear.
     */
    fun updateSensorOrientation(sensorHeadingDeg: Float, confidence: HeadingConfidence) {
        val normHeading = normalizeHeadingDeg(sensorHeadingDeg)

        if (isAnomalyActive) {
            // Anomaly active: Do NOT override heading with magnetic values
            return
        }

        if (activeHeadingSource == ActiveHeadingSource.GYRO_PROPAGATED) {
            // Gradual recovery blending
            val diff = shortestAngleDiff(currentHeadingDeg, normHeading)
            currentHeadingDeg = normalizeHeadingDeg(currentHeadingDeg + diff * RECOVERY_ALPHA)
            if (abs(diff) < 2.0f) {
                activeHeadingSource = ActiveHeadingSource.ROTATION_VECTOR
            }
        } else if (activeHeadingSource != ActiveHeadingSource.GNSS_BEARING) {
            currentHeadingDeg = normHeading
            activeHeadingSource = ActiveHeadingSource.ROTATION_VECTOR
        }
        headingConfidence = confidence
    }

    fun reset() {
        currentHeadingDeg = 0f
        activeHeadingSource = ActiveHeadingSource.ROTATION_VECTOR
        headingConfidence = HeadingConfidence.UNRELIABLE
        isAnomalyActive = false
        normalMagCount = 0
    }
}
