package com.example.gudumap.navigation

/**
 * Centralized tuning parameters and constants for the NAVIATOR navigation pipeline.
 *
 * Single source of truth across GNSS transition state machine, filter logic,
 * EKF state estimator, ZUPT/NHC constraints, speedometer smoothing, track export,
 * map rendering, and ground-truth blackout evaluation.
 */
object NavigationConfig {
    // --- GNSS State Machine & Timeout Parameters ---
    /** Fix age threshold for fresh, trusted GNSS fixes (5.0s) */
    const val GPS_FRESHNESS_MS: Long = 5_000L

    /** Maximum allowed fix age for degraded GNSS state before blackout (3.0s window) */
    const val GPS_DEGRADED_TIMEOUT_MS: Long = 3_000L

    /** Timeout after which missing GNSS fix is declared sustained blackout (5.0s) */
    const val GPS_SUSTAINED_BLACKOUT_TIMEOUT_MS: Long = 5_000L

    /** Maximum accepted fix age for general validity (30.0s) */
    const val MAX_ACCEPTED_FIX_AGE_MS: Long = 30_000L

    /** Number of consecutive valid fixes required to recover from blackout (2 fixes) */
    const val REQUIRED_RECOVERY_FIX_COUNT: Int = 2

    /** Maximum acceptable horizontal accuracy in meters for GOOD GNSS state (25.0m) */
    const val MAX_GOOD_ACCURACY_METERS: Float = 25.0f

    /** Maximum acceptable speed accuracy uncertainty in m/s (3.0 m/s) */
    const val MAX_SPEED_ACCURACY_UNCERTAINTY_MPS: Float = 3.0f

    // --- Navigation Engine Tickers ---
    /** State tick update interval in milliseconds (~12.5 Hz / 80ms) */
    const val STATE_INTERVAL_MS: Long = 80L

    /** Status tick update interval in milliseconds (4 Hz / 250ms) */
    const val STATUS_TICK_MS: Long = 250L

    // --- Trail & Map Rendering Parameters ---
    /** Maximum recorded points in memory trail */
    const val MAX_TRAIL_POINTS: Int = 2_000

    /** Minimum displacement threshold in meters for trail point addition (0.1m) */
    const val TRAIL_MIN_DISTANCE_METERS: Double = 0.1

    /** Camera follow re-center distance threshold in meters (3.0m) */
    const val CAMERA_RECENTER_THRESHOLD_METERS: Double = 3.0

    // --- Speedometer Smoothing ---
    /** Exponential moving average filter factor for speedometer (alpha = 0.15) */
    const val SPEEDOMETER_ALPHA: Float = 0.15f

    /** Cutoff speed in km/h below which displayed speed snaps to zero (0.5 km/h) */
    const val SPEED_ZERO_SNAP_THRESHOLD_KMH: Float = 0.5f

    // --- Ground-Truth Blackout Evaluation ---
    /** Duration of evaluation blackout test in seconds (10.0s) */
    const val EVALUATION_MODE_DURATION_SEC: Long = 10L

    /** Duration of evaluation blackout test in milliseconds (10_000ms) */
    const val EVALUATION_MODE_DURATION_MS: Long = 10_000L

    // --- ZUPT / Motion Detection Defaults ---
    /** Accelerometer magnitude threshold for ZUPT stationary state (m/s^2) */
    const val DEFAULT_ACC_MAG_THRESHOLD: Float = 0.25f

    /** Accelerometer variance threshold for ZUPT stationary state ((m/s^2)^2) */
    const val DEFAULT_ACC_VAR_THRESHOLD: Float = 0.04f

    /** Gyroscope magnitude threshold for ZUPT stationary state (rad/s) */
    const val DEFAULT_GYRO_MAG_THRESHOLD: Float = 0.10f

    /** Minimum consecutive samples to confirm ZUPT stationary state */
    const val DEFAULT_MIN_CONSECUTIVE_ZUPT_SAMPLES: Int = 4

    // --- Smooth Re-anchoring ---
    /** Number of steps over which smooth re-anchoring converges (8 steps) */
    const val REANCHOR_SMOOTHING_STEPS: Int = 8

    // --- Performance & UI Throttling ---
    /** Throttled UI StateFlow publishing interval (~10 Hz / 100ms) */
    const val UI_STATE_PUBLISH_INTERVAL_MS: Long = 100L

    // --- Stationary Detection & Hysteresis ---
    /** Rolling window sample size for stationary multi-signal statistical analysis */
    const val STATIONARY_WINDOW_SIZE: Int = 20

    /** Minimum consecutive samples required to upgrade from UNCERTAIN to STATIONARY (~1.5s at 10Hz) */
    const val STATIONARY_CONFIRM_SAMPLES: Int = 15

    /** Minimum consecutive samples required to transition from STATIONARY to MOVING */
    const val MOVING_CONFIRM_SAMPLES: Int = 3

    /** Gyroscope variance threshold for ZUPT stationary state ((rad/s)^2) */
    const val DEFAULT_GYRO_VAR_THRESHOLD: Float = 0.01f

    /** Accelerometer variance threshold for moving state entry ((m/s^2)^2) */
    const val MOVING_ENTER_ACC_VAR_THRESHOLD: Float = 0.08f

    // --- Outlier Rejection & EKF Bounding ---
    /** Maximum plausible vehicle speed in m/s (50 m/s = 180 km/h) */
    const val MAX_PLAUSIBLE_SPEED_MPS: Float = 50.0f

    /** Maximum acceleration magnitude change rate in m/s^2 (4.0 m/s^2) */
    const val MAX_ACCELERATION_MPS2: Float = 4.0f

    /** Mahalanobis gating threshold for EKF measurement acceptance (11.34 for 3D @ 99% confidence) */
    const val MAHALANOBIS_THRESHOLD: Double = 11.34

    // --- Magnetometer & Heading Fusion ---
    /** Minimum acceptable Earth magnetic field norm in uT */
    const val MIN_MAG_FIELD_UT: Float = 20.0f

    /** Maximum acceptable Earth magnetic field norm in uT */
    const val MAX_MAG_FIELD_UT: Float = 70.0f

    /** Exponential smoothing factor for heading anomaly recovery */
    const val HEADING_RECOVERY_ALPHA: Float = 0.08f

    // --- NHC Constraints ---
    /** Non-Holonomic Constraint lateral velocity noise (m/s) */
    const val DEFAULT_NHC_LATERAL_NOISE_MPS: Double = 0.15

    /** Non-Holonomic Constraint vertical velocity noise (m/s) */
    const val DEFAULT_NHC_VERTICAL_NOISE_MPS: Double = 0.10

    // --- Map Matching Parameters ---
    /** Maximum distance in meters to snap position to road segment (25.0m) */
    const val MAX_MAP_MATCH_DISTANCE_METERS: Double = 25.0

    // --- Blackout Confidence Thresholds ---
    /** EKF horizontal position uncertainty threshold for HIGH navigation confidence (meters) */
    const val HIGH_CONFIDENCE_UNCERTAINTY_METERS: Double = 5.0

    /** EKF horizontal position uncertainty threshold for MEDIUM navigation confidence (meters) */
    const val MEDIUM_CONFIDENCE_UNCERTAINTY_METERS: Double = 25.0
}


