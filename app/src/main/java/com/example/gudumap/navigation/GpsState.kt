package com.example.gudumap.navigation

/**
 * Unified GPS state representing the current operational status of system location services,
 * permissions, satellite search, and fix quality/freshness.
 */
enum class GpsState {
    GPS_DISABLED,        // System location service (GPS/Network) is turned OFF
    PERMISSION_REQUIRED, // Location permission is not granted
    SEARCHING,           // Listening for location, but no fix received yet
    ACQUIRING,           // First location fix received, but accuracy is poor (> 25 m)
    FIXED,               // Active valid fix received with acceptable accuracy (<= 25 m)
    WEAK,                // Fix received recently, but reported accuracy is poor (> 25 m)
    STALE,               // No new fix update received for several seconds (5–15s)
    LOST                 // Location fix unavailable for an extended period (> 15s)
}

/**
 * Classification of reported GPS horizontal accuracy in meters.
 */
enum class GpsAccuracyLevel(val label: String, val maxMeters: Float) {
    EXCELLENT("EXCELLENT", 5.0f),
    GOOD("GOOD", 10.0f),
    FAIR("FAIR", 25.0f),
    POOR("POOR", 50.0f),
    VERY_POOR("VERY POOR", Float.MAX_VALUE);

    companion object {
        fun fromAccuracy(accuracyMeters: Float): GpsAccuracyLevel {
            return when {
                accuracyMeters <= 0f -> VERY_POOR
                accuracyMeters <= 5.0f -> EXCELLENT
                accuracyMeters <= 10.0f -> GOOD
                accuracyMeters <= 25.0f -> FAIR
                accuracyMeters <= 50.0f -> POOR
                else -> VERY_POOR
            }
        }
    }
}
