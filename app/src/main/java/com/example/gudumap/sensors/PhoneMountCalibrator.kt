package com.example.gudumap.sensors

import android.content.Context
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.atan2

/**
 * Transforms sensor measurements from local Phone Coordinate Frame into Vehicle Body Coordinate Frame.
 *
 * Vehicle Body Frame:
 * - X: Right (starboard)
 * - Y: Forward (towards vehicle nose)
 * - Z: Up (perpendicular to road surface)
 *
 * Persists calibration angles across app restarts via SharedPreferences.
 */
class PhoneMountCalibrator {

    var isCalibrated: Boolean = false
        private set

    var mountPitchDeg: Float = 0f
        private set
    var mountRollDeg: Float = 0f
        private set
    var mountYawDeg: Float = 0f
        private set

    // 3x3 Rotation Matrix mapping Phone Frame to Vehicle Body Frame
    private val mountRotationMatrix = FloatArray(9) { if (it % 4 == 0) 1f else 0f }

    fun calibrate(pitchDeg: Float, rollDeg: Float, yawDeg: Float) {
        mountPitchDeg = pitchDeg
        mountRollDeg = rollDeg
        mountYawDeg = yawDeg
        isCalibrated = true
        recomputeMatrix()
    }

    /**
     * Estimates mount pitch & roll from static gravity vector (stationary vehicle).
     */
    fun calibrateFromGravity(ax: Float, ay: Float, az: Float, headingDeg: Float = 0f) {
        val norm = sqrt(ax * ax + ay * ay + az * az)
        if (norm < 1e-3f) return

        val rollRad = atan2(ax.toDouble(), sqrt((ay * ay + az * az).toDouble())).toFloat()
        val pitchRad = atan2(-ay.toDouble(), az.toDouble()).toFloat()

        mountRollDeg = Math.toDegrees(rollRad.toDouble()).toFloat()
        mountPitchDeg = Math.toDegrees(pitchRad.toDouble()).toFloat()
        mountYawDeg = headingDeg
        isCalibrated = true
        recomputeMatrix()
    }

    private fun recomputeMatrix() {
        val pitchRad = Math.toRadians(mountPitchDeg.toDouble())
        val rollRad = Math.toRadians(mountRollDeg.toDouble())
        val yawRad = Math.toRadians(mountYawDeg.toDouble())

        val cp = cos(pitchRad).toFloat()
        val sp = sin(pitchRad).toFloat()
        val cr = cos(rollRad).toFloat()
        val sr = sin(rollRad).toFloat()
        val cy = cos(yawRad).toFloat()
        val sy = sin(yawRad).toFloat()

        // R = Rz(yaw) * Rx(pitch) * Ry(roll)
        mountRotationMatrix[0] = cy * cr + sy * sp * sr
        mountRotationMatrix[1] = sr * cp
        mountRotationMatrix[2] = cy * sp * cr - sy * sr
        mountRotationMatrix[3] = -sy * cr + cy * sp * sr
        mountRotationMatrix[4] = cy * cp
        mountRotationMatrix[5] = -sy * sp * cr - cy * sr
        mountRotationMatrix[6] = -cp * sr
        mountRotationMatrix[7] = sp
        mountRotationMatrix[8] = cp * cr
    }

    /**
     * Transforms Phone acceleration (ax, ay, az) into Vehicle Body Frame (X=Right, Y=Forward, Z=Up).
     */
    fun transformAccelerometer(ax: Float, ay: Float, az: Float): FloatArray {
        if (!isCalibrated) return floatArrayOf(ax, ay, az)

        val vx = mountRotationMatrix[0] * ax + mountRotationMatrix[1] * ay + mountRotationMatrix[2] * az
        val vy = mountRotationMatrix[3] * ax + mountRotationMatrix[4] * ay + mountRotationMatrix[5] * az
        val vz = mountRotationMatrix[6] * ax + mountRotationMatrix[7] * ay + mountRotationMatrix[8] * az
        return floatArrayOf(vx, vy, vz)
    }

    /**
     * Transforms Phone angular velocity (gx, gy, gz) into Vehicle Body Frame.
     */
    fun transformGyroscope(gx: Float, gy: Float, gz: Float): FloatArray {
        if (!isCalibrated) return floatArrayOf(gx, gy, gz)

        val vx = mountRotationMatrix[0] * gx + mountRotationMatrix[1] * gy + mountRotationMatrix[2] * gz
        val vy = mountRotationMatrix[3] * gx + mountRotationMatrix[4] * gy + mountRotationMatrix[5] * gz
        val vz = mountRotationMatrix[6] * gx + mountRotationMatrix[7] * gy + mountRotationMatrix[8] * gz
        return floatArrayOf(vx, vy, vz)
    }

    fun saveToPreferences(context: Context) {
        val prefs = context.getSharedPreferences("naviator_preferences", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("mount_calibrated", isCalibrated)
            .putFloat("mount_pitch", mountPitchDeg)
            .putFloat("mount_roll", mountRollDeg)
            .putFloat("mount_yaw", mountYawDeg)
            .apply()
    }

    fun loadFromPreferences(context: Context) {
        val prefs = context.getSharedPreferences("naviator_preferences", Context.MODE_PRIVATE)
        isCalibrated = prefs.getBoolean("mount_calibrated", false)
        if (isCalibrated) {
            mountPitchDeg = prefs.getFloat("mount_pitch", 0f)
            mountRollDeg = prefs.getFloat("mount_roll", 0f)
            mountYawDeg = prefs.getFloat("mount_yaw", 0f)
            recomputeMatrix()
        }
    }

    fun resetCalibration(context: Context? = null) {
        isCalibrated = false
        mountPitchDeg = 0f
        mountRollDeg = 0f
        mountYawDeg = 0f
        for (i in 0 until 9) {
            mountRotationMatrix[i] = if (i % 4 == 0) 1f else 0f
        }
        context?.let { ctx ->
            ctx.getSharedPreferences("naviator_preferences", Context.MODE_PRIVATE)
                .edit()
                .remove("mount_calibrated")
                .remove("mount_pitch")
                .remove("mount_roll")
                .remove("mount_yaw")
                .apply()
        }
    }
}
