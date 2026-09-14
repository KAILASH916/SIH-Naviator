package com.example.gudumap.navigation

import kotlin.math.cos
import kotlin.math.sin

/**
 * Non-Holonomic Constraints (NHC) for wheeled vehicle dead reckoning.
 *
 * In land vehicles under normal driving conditions without sideslip or flight:
 * - Lateral velocity in vehicle body frame: v_y^b ~ 0
 * - Vertical velocity in vehicle body frame: v_z^b ~ 0
 *
 * Applied as an EKF pseudo-measurement update.
 * Modular: Can be enabled/disabled dynamically.
 */
class NHC(
    var isEnabled: Boolean = true,
    var lateralNoiseMps: Double = 0.15,   // Lateral velocity measurement noise
    var verticalNoiseMps: Double = 0.10   // Vertical velocity measurement noise
) {

    /**
     * Apply 3D NHC measurement update to the EKF.
     *
     * @param ekf Active Extended Kalman Filter
     * @param headingDeg Vehicle heading in degrees (clockwise from North, yaw)
     * @param pitchDeg Vehicle pitch in degrees (positive nose up)
     * @param rollDeg Vehicle roll in degrees (positive right wing down)
     */
    fun applyConstraint(ekf: EKF, headingDeg: Float, pitchDeg: Float = 0f, rollDeg: Float = 0f) {
        if (!isEnabled || !ekf.isInitialized) return

        val psi = Math.toRadians(headingDeg.toDouble())
        val theta = Math.toRadians(pitchDeg.toDouble())
        val phi = Math.toRadians(rollDeg.toDouble())

        val cosH = cos(psi); val sinH = sin(psi)
        val cosP = cos(theta); val sinP = sin(theta)
        val cosR = cos(phi); val sinR = sin(phi)

        // Navigation-to-body transformation matrix R_n^b = (R_b^n)^T
        // Body lateral Y axis row of R_n^b:
        val rLatN = sinR * sinP * cosH - cosR * sinH
        val rLatE = sinR * sinP * sinH + cosR * cosH
        val rLatD = sinR * cosP

        // Body vertical Z axis row of R_n^b:
        val rVertN = cosR * sinP * cosH + sinR * sinH
        val rVertE = cosR * sinP * sinH - sinR * cosH
        val rVertD = cosR * cosP

        // Pseudo-measurement z = [0, 0] (lateral velocity = 0, vertical velocity = 0)
        val z = doubleArrayOf(0.0, 0.0)

        // Measurement matrix H (2 x 6) relating [p_N, p_E, p_D, v_N, v_E, v_D]
        val H = arrayOf(
            // v_lateral row
            doubleArrayOf(0.0, 0.0, 0.0, rLatN, rLatE, rLatD),
            // v_vertical row
            doubleArrayOf(0.0, 0.0, 0.0, rVertN, rVertE, rVertD)
        )

        val rLat = lateralNoiseMps * lateralNoiseMps
        val rVert = verticalNoiseMps * verticalNoiseMps
        val R = arrayOf(
            doubleArrayOf(rLat, 0.0),
            doubleArrayOf(0.0, rVert)
        )

        ekf.updateMeasurement(z, H, R)
    }
}
