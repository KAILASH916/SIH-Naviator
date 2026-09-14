package com.example.gudumap.sensors

import android.hardware.SensorManager
import kotlin.math.sqrt

data class OrientationData(
    val heading: Float = 0f, // Azimuth in degrees [0, 360)
    val pitch: Float = 0f,   // Pitch in degrees [-90, 90]
    val roll: Float = 0f     // Roll in degrees [-180, 180]
)

data class WorldAcceleration(
    val north: Float = 0f, // m/s^2 (towards geographic North)
    val east: Float = 0f,  // m/s^2 (towards East)
    val vertical: Float = 0f, // m/s^2 (towards Up)
    val timestampNs: Long = 0L
)

/**
 * How much to trust the current heading estimate. TYPE_ROTATION_VECTOR fuses the
 * magnetometer, and magnetometer reliability is exactly what degrades inside a vehicle
 * chassis or tunnel/parking-garage rebar -- the scenarios this project targets. Previously
 * Android's own SENSOR_STATUS_* reliability signal for these sensors was read and discarded;
 * this surfaces it instead of pretending heading quality is constant.
 */
enum class HeadingConfidence {
    HIGH, MEDIUM, LOW, UNRELIABLE
}

class SensorFusionManager {

    private val linearAccelerometer = FloatArray(3)
    private val magnetometer = FloatArray(3)
    private val gyroscope = FloatArray(3)

    // Current 3x3 rotation matrix from device to world (X=East, Y=North, Z=Up)
    private val rotationMatrix = FloatArray(9) { if (it % 4 == 0) 1f else 0f }
    private val inclinationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    private var hasAccelerometer = false
    private var hasMagnetometer = false
    private var hasGyroscope = false
    private var hasHardwareRotation = false

    private var fusedAzimuth = 0f
    private var fusedPitch = 0f
    private var fusedRoll = 0f

    private var lastGyroTimestampNs = 0L
    private var lastAccelTimestampNs = 0L

    private val gyroWeight = 0.98f
    private val sensorWeight = 0.02f

    var gyroBiasX: Float = 0f
        private set
    var gyroBiasY: Float = 0f
        private set
    var gyroBiasZ: Float = 0f
        private set

    fun setGyroBias(x: Float, y: Float, z: Float) {
        gyroBiasX = x
        gyroBiasY = y
        gyroBiasZ = z
    }

    private var magnetometerAccuracy: Int = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var rotationVectorAccuracy: Int = SensorManager.SENSOR_STATUS_UNRELIABLE

    fun updateMagnetometerAccuracy(accuracy: Int) {
        magnetometerAccuracy = accuracy
    }

    fun updateRotationVectorAccuracy(accuracy: Int) {
        rotationVectorAccuracy = accuracy
    }

    /**
     * Worst-of-the-two reliability across the magnetometer and the rotation-vector sensor
     * that consumes it. Deliberately pessimistic: either sensor being unreliable means the
     * fused heading it feeds into is suspect, regardless of what the other one reports.
     */
    val magneticFieldNorm: Float
        get() {
            if (!hasMagnetometer) return 0f
            return sqrt(magnetometer[0] * magnetometer[0] + magnetometer[1] * magnetometer[1] + magnetometer[2] * magnetometer[2])
        }

    val isMagneticAnomaly: Boolean
        get() {
            if (!hasMagnetometer) return false
            val norm = magneticFieldNorm
            // Nominal Earth magnetic field is ~25 to ~65 uT. Values outside this indicate metal/structural interference.
            return norm < 20f || norm > 70f
        }

    /**
     * Worst-of-the-two reliability across the magnetometer and the rotation-vector sensor
     * that consumes it, combined with live magnetic field magnitude anomaly checks.
     */
    val headingConfidence: HeadingConfidence
        get() {
            if (isMagneticAnomaly) return HeadingConfidence.LOW
            val worst = minOf(magnetometerAccuracy, rotationVectorAccuracy)
            return when (worst) {
                SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> HeadingConfidence.HIGH
                SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> HeadingConfidence.MEDIUM
                SensorManager.SENSOR_STATUS_ACCURACY_LOW -> HeadingConfidence.LOW
                else -> HeadingConfidence.UNRELIABLE // SENSOR_STATUS_UNRELIABLE, SENSOR_STATUS_NO_CONTACT, or not yet reported
            }
        }

    fun updateAccelerometer(x: Float, y: Float, z: Float, timestampNs: Long = System.nanoTime()) {
        linearAccelerometer[0] = x
        linearAccelerometer[1] = y
        linearAccelerometer[2] = z
        lastAccelTimestampNs = timestampNs
        hasAccelerometer = true

        if (!hasHardwareRotation) {
            updateSensorOrientation()
        }
    }

    fun updateMagnetometer(x: Float, y: Float, z: Float, timestampNs: Long = System.nanoTime()) {
        magnetometer[0] = x
        magnetometer[1] = y
        magnetometer[2] = z
        hasMagnetometer = true

        if (!hasHardwareRotation) {
            updateSensorOrientation()
        }
    }

    fun updateGyroscope(x: Float, y: Float, z: Float, timestampNs: Long = System.nanoTime()) {
        // 1. Online bias estimation during quiet periods
        val rawMag = sqrt(x * x + y * y + z * z)
        if (rawMag < 0.015f) {
            gyroBiasX = gyroBiasX * 0.98f + x * 0.02f
            gyroBiasY = gyroBiasY * 0.98f + y * 0.02f
            gyroBiasZ = gyroBiasZ * 0.98f + z * 0.02f
        }

        // 2. Subtract calibrated bias
        var calX = x - gyroBiasX
        var calY = y - gyroBiasY
        var calZ = z - gyroBiasZ

        // 3. Micro-noise deadband filtering below 0.005 rad/s (~0.28 deg/s) to prevent static drift creep
        val calMag = sqrt(calX * calX + calY * calY + calZ * calZ)
        if (calMag < 0.005f) {
            calX = 0f
            calY = 0f
            calZ = 0f
        }

        gyroscope[0] = calX
        gyroscope[1] = calY
        gyroscope[2] = calZ
        hasGyroscope = true

        if (lastGyroTimestampNs == 0L) {
            lastGyroTimestampNs = timestampNs
            return
        }

        val dt = (timestampNs - lastGyroTimestampNs) / 1_000_000_000f
        lastGyroTimestampNs = timestampNs

        if (dt <= 0f || dt > 0.2f) return

        if (!hasHardwareRotation) {
            // 4. Exact 3D Rodrigues' Exponential Map Rotation Integration
            val wx = calX * dt
            val wy = calY * dt
            val wz = calZ * dt
            val theta = sqrt(wx * wx + wy * wy + wz * wz)

            if (theta > 1e-6f) {
                val sinTheta = kotlin.math.sin(theta.toDouble()).toFloat()
                val cosTheta = kotlin.math.cos(theta.toDouble()).toFloat()
                val kx = wx / theta
                val ky = wy / theta
                val kz = wz / theta
                val omc = 1f - cosTheta

                // 3x3 delta rotation matrix dR for 3D body rotation vector
                val dR = FloatArray(9)
                dR[0] = cosTheta + kx * kx * omc
                dR[1] = kx * ky * omc - kz * sinTheta
                dR[2] = kx * kz * omc + ky * sinTheta

                dR[3] = ky * kx * omc + kz * sinTheta
                dR[4] = cosTheta + ky * ky * omc
                dR[5] = ky * kz * omc - kx * sinTheta

                dR[6] = kz * kx * omc - ky * sinTheta
                dR[7] = kz * ky * omc + kx * sinTheta
                dR[8] = cosTheta + kz * kz * omc

                // R_new = R_old * dR
                val newR = FloatArray(9)
                for (r in 0..2) {
                    for (c in 0..2) {
                        newR[r * 3 + c] = rotationMatrix[r * 3 + 0] * dR[0 * 3 + c] +
                                         rotationMatrix[r * 3 + 1] * dR[1 * 3 + c] +
                                         rotationMatrix[r * 3 + 2] * dR[2 * 3 + c]
                    }
                }

                // Orthonormalize rotation matrix to prevent numerical drift over time
                orthonormalize(newR)
                System.arraycopy(newR, 0, rotationMatrix, 0, 9)

                computeOrientationAngles(rotationMatrix, orientationAngles)
                fusedAzimuth = orientationAngles[0]
                fusedPitch = orientationAngles[1]
                fusedRoll = orientationAngles[2]
            }

            if (hasAccelerometer && hasMagnetometer) {
                val success = SensorManager.getRotationMatrix(
                    inclinationMatrix,
                    null,
                    linearAccelerometer,
                    magnetometer
                )
                if (success) {
                    val sensorAngles = FloatArray(3)
                    computeOrientationAngles(inclinationMatrix, sensorAngles)

                    // Adaptive complementary weight: trust gyro more during active rotations
                    val dynamicGyroWeight = if (calMag > 0.1f) 0.99f else gyroWeight
                    val dynamicSensorWeight = 1f - dynamicGyroWeight

                    fusedAzimuth = complementaryFilter(fusedAzimuth, sensorAngles[0], dynamicGyroWeight, dynamicSensorWeight)
                    fusedPitch = complementaryFilter(fusedPitch, sensorAngles[1], dynamicGyroWeight, dynamicSensorWeight)
                    fusedRoll = complementaryFilter(fusedRoll, sensorAngles[2], dynamicGyroWeight, dynamicSensorWeight)
                }
            }
        }
    }

    /**
     * Updates rotation directly from Android's hardware-fused Rotation Vector sensor.
     */
    fun updateRotationVector(matrix: FloatArray, timestampNs: Long = System.nanoTime()) {
        if (matrix.size >= 9) {
            System.arraycopy(matrix, 0, rotationMatrix, 0, 9)
            computeOrientationAngles(rotationMatrix, orientationAngles)
            fusedAzimuth = orientationAngles[0]
            fusedPitch = orientationAngles[1]
            fusedRoll = orientationAngles[2]
            hasHardwareRotation = true
        }
    }

    private fun updateSensorOrientation() {
        if (!hasAccelerometer || !hasMagnetometer) return

        val success = SensorManager.getRotationMatrix(
            rotationMatrix,
            inclinationMatrix,
            linearAccelerometer,
            magnetometer
        )

        if (success) {
            computeOrientationAngles(rotationMatrix, orientationAngles)
            if (!hasGyroscope) {
                fusedAzimuth = orientationAngles[0]
                fusedPitch = orientationAngles[1]
                fusedRoll = orientationAngles[2]
            }
        }
    }

    private fun computeOrientationAngles(r: FloatArray, out: FloatArray) {
        out[0] = kotlin.math.atan2(r[1], r[4])
        val pitchSin = (-r[7]).coerceIn(-1f, 1f)
        out[1] = kotlin.math.asin(pitchSin)
        out[2] = kotlin.math.atan2(-r[6], r[8])
    }

    private fun complementaryFilter(
        gyroAngle: Float,
        sensorAngle: Float,
        gWeight: Float = gyroWeight,
        sWeight: Float = sensorWeight
    ): Float {
        var diff = sensorAngle - gyroAngle
        while (diff > Math.PI.toFloat()) diff -= (2f * Math.PI.toFloat())
        while (diff < -Math.PI.toFloat()) diff += (2f * Math.PI.toFloat())

        return normalizeAngle(gyroAngle * gWeight + (gyroAngle + diff) * sWeight)
    }

    private fun normalizeAngle(angle: Float): Float {
        var result = angle
        while (result > Math.PI.toFloat()) result -= 2f * Math.PI.toFloat()
        while (result < -Math.PI.toFloat()) result += 2f * Math.PI.toFloat()
        return result
    }

    private fun orthonormalize(r: FloatArray) {
        var x0 = r[0]; var x1 = r[1]; var x2 = r[2]
        var lenX = sqrt(x0 * x0 + x1 * x1 + x2 * x2)
        if (lenX > 0f) { x0 /= lenX; x1 /= lenX; x2 /= lenX }
        r[0] = x0; r[1] = x1; r[2] = x2

        var y0 = r[3]; var y1 = r[4]; var y2 = r[5]
        val dot = y0 * x0 + y1 * x1 + y2 * x2
        y0 -= dot * x0; y1 -= dot * x1; y2 -= dot * x2
        var lenY = sqrt(y0 * y0 + y1 * y1 + y2 * y2)
        if (lenY > 0f) { y0 /= lenY; y1 /= lenY; y2 /= lenY }
        r[3] = y0; r[4] = y1; r[5] = y2

        r[6] = x1 * y2 - x2 * y1
        r[7] = x2 * y0 - x0 * y2
        r[8] = x0 * y1 - x1 * y0
    }

    fun getOrientation(): OrientationData {
        var heading = Math.toDegrees(fusedAzimuth.toDouble()).toFloat()
        if (heading < 0f) heading += 360f

        return OrientationData(
            heading = heading,
            pitch = Math.toDegrees(fusedPitch.toDouble()).toFloat(),
            roll = Math.toDegrees(fusedRoll.toDouble()).toFloat()
        )
    }

    fun getRotationMatrix(): FloatArray {
        return rotationMatrix.clone()
    }

    /**
     * Transforms device linear acceleration to World Coordinates:
     * East (X), North (Y), Vertical (Z).
     */
    fun getWorldAcceleration(): WorldAcceleration {
        val ax = linearAccelerometer[0]
        val ay = linearAccelerometer[1]
        val az = linearAccelerometer[2]

        // World coordinates = R * Device coordinates
        val east = rotationMatrix[0] * ax + rotationMatrix[1] * ay + rotationMatrix[2] * az
        val north = rotationMatrix[3] * ax + rotationMatrix[4] * ay + rotationMatrix[5] * az
        val vertical = rotationMatrix[6] * ax + rotationMatrix[7] * ay + rotationMatrix[8] * az

        return WorldAcceleration(
            north = north,
            east = east,
            vertical = vertical,
            timestampNs = lastAccelTimestampNs
        )
    }

    fun reset() {
        fusedAzimuth = 0f
        fusedPitch = 0f
        fusedRoll = 0f
        lastGyroTimestampNs = 0L
        lastAccelTimestampNs = 0L
        hasAccelerometer = false
        hasMagnetometer = false
        hasGyroscope = false
        hasHardwareRotation = false
        gyroBiasX = 0f
        gyroBiasY = 0f
        gyroBiasZ = 0f
        magnetometerAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        rotationVectorAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        for (i in 0 until 9) {
            rotationMatrix[i] = if (i % 4 == 0) 1f else 0f
        }
    }
}