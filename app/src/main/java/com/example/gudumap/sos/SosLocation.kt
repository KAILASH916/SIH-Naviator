package com.example.gudumap.sos

enum class SosLocationSource {
    LIVE_GPS,                        // Fresh live GPS fix (<= 5s old)
    LAST_CONFIRMED_GPS,              // Stale or expired GPS fix (> 5s old)
    PREDICTED_FROM_REAL_GPS,         // Real-movement prediction combined with last confirmed GPS fix
    LAST_KNOWN_GPS,                  // Last known raw or filtered fix
    UNAVAILABLE                      // No valid real location fix
}

data class SosLocation(
    val latitude: Double = Double.NaN,
    val longitude: Double = Double.NaN,
    val source: SosLocationSource = SosLocationSource.UNAVAILABLE,
    val accuracyMeters: Float = 0f,
    val isAccuracyValid: Boolean = false,
    val lastFixAgeSeconds: Long = 0L,
    val uncertaintyRadiusMeters: Double = 0.0,
    val lastTrustedLat: Double? = null,
    val lastTrustedLon: Double? = null,
    val lastTrustedAgeSeconds: Long = 0L,
    val lastTrustedAccuracyMeters: Float = 0f,
    val lastTrustedAccuracyValid: Boolean = false
) {
    fun hasValidCoordinates(): Boolean {
        return latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
            (latitude != 0.0 || longitude != 0.0) &&
            source != SosLocationSource.UNAVAILABLE
    }

    fun hasValidLastTrusted(): Boolean {
        return lastTrustedLat != null && lastTrustedLon != null &&
            lastTrustedLat.isFinite() && lastTrustedLon.isFinite() &&
            lastTrustedLat in -90.0..90.0 && lastTrustedLon in -180.0..180.0 &&
            (lastTrustedLat != 0.0 || lastTrustedLon != 0.0)
    }
}
