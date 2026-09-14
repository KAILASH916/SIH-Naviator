package com.example.gudumap.sos

import android.util.Log
import com.example.gudumap.navigation.GpsState
import com.example.gudumap.navigation.NavigationState
import com.example.gudumap.navigation.NavigationVisualMode

object SosLocationResolver {

    private const val TAG = "Gudumap:SosResolver"
    private const val FRESH_FIX_MAX_AGE_SECONDS = 5L

    private fun safeLogI(message: String) {
        try { Log.i(TAG, message) } catch (_: Throwable) { println("$TAG: $message") }
    }

    private fun safeLogW(message: String) {
        try { Log.w(TAG, message) } catch (_: Throwable) { println("$TAG: $message") }
    }

    private fun isValidMapCoordinate(lat: Double?, lon: Double?): Boolean {
        if (lat == null || lon == null) return false
        return lat.isFinite() && lon.isFinite() &&
            lat in -90.0..90.0 && lon in -180.0..180.0 &&
            (lat != 0.0 || lon != 0.0)
    }

    /**
     * Resolves the best available REAL emergency location from [NavigationState].
     *
     * GUARANTEES:
     * - Never labels a STALE or expired fix (>5s old) as "live" or "current".
     * - Combines last confirmed GPS fix AND real-movement prediction when GPS is lost.
     * - Never returns simulated Demo Mode coordinates or default Coimbatore coordinates.
     * - Handles unknown accuracy properly without treating 0 as perfect accuracy.
     */
    fun resolve(navState: NavigationState): SosLocation {
        val isDemoActive = navState.isDemoModeEnabled || navState.locationProvider == "demo" || navState.isSimulatedBlackout

        // 1. Fresh LIVE GPS Location (Only when NOT in Demo and fix age <= 5 seconds)
        if (!isDemoActive && navState.hasGpsFix && navState.gpsState == GpsState.FIXED && navState.locationAgeSeconds <= FRESH_FIX_MAX_AGE_SECONDS) {
            if (isValidMapCoordinate(navState.latitude, navState.longitude)) {
                safeLogI("Resolved SOS Location: LIVE_GPS (lat=${navState.latitude}, lon=${navState.longitude}, age=${navState.locationAgeSeconds}s)")
                return SosLocation(
                    latitude = navState.latitude,
                    longitude = navState.longitude,
                    source = SosLocationSource.LIVE_GPS,
                    accuracyMeters = navState.locationAccuracyMeters,
                    isAccuracyValid = navState.locationAccuracyMeters > 0f,
                    lastFixAgeSeconds = navState.locationAgeSeconds,
                    uncertaintyRadiusMeters = navState.uncertaintyRadiusMeters
                )
            }
        }

        // 2. Real-Movement Prediction + Last Confirmed GPS (When GPS lost during real movement)
        val isPredictionAvailable = !isDemoActive && (navState.visualMode == NavigationVisualMode.GPS_FALLBACK || navState.blackoutMode)
        if (isPredictionAvailable) {
            val predLat = navState.lastPredictedLat ?: navState.latitude
            val predLon = navState.lastPredictedLon ?: navState.longitude
            val trustedLat = navState.lastTrustedGpsLat
            val trustedLon = navState.lastTrustedGpsLon

            if (isValidMapCoordinate(predLat, predLon)) {
                safeLogI("Resolved SOS Location: PREDICTED_FROM_REAL_GPS (lat=$predLat, lon=$predLon, trustedLat=$trustedLat)")
                return SosLocation(
                    latitude = predLat,
                    longitude = predLon,
                    source = SosLocationSource.PREDICTED_FROM_REAL_GPS,
                    accuracyMeters = 0f,
                    isAccuracyValid = false,
                    lastFixAgeSeconds = navState.locationAgeSeconds,
                    uncertaintyRadiusMeters = if (navState.predictionUncertaintyMeters > 0.0) navState.predictionUncertaintyMeters else navState.uncertaintyRadiusMeters,
                    lastTrustedLat = trustedLat,
                    lastTrustedLon = trustedLon,
                    lastTrustedAgeSeconds = navState.gpsFixAgeMs / 1000L,
                    lastTrustedAccuracyMeters = navState.locationAccuracyMeters,
                    lastTrustedAccuracyValid = navState.locationAccuracyMeters > 0f
                )
            }
        }

        // 3. Last Confirmed Real GPS Fix (When prediction is unavailable but trusted GPS exists)
        val trustedLat = navState.lastTrustedGpsLat
        val trustedLon = navState.lastTrustedGpsLon
        if (isValidMapCoordinate(trustedLat, trustedLon)) {
            val ageSec = if (navState.gpsFixAgeMs > 0L) navState.gpsFixAgeMs / 1000L else navState.locationAgeSeconds
            safeLogI("Resolved SOS Location: LAST_CONFIRMED_GPS (lat=$trustedLat, lon=$trustedLon, age=${ageSec}s)")
            return SosLocation(
                latitude = trustedLat!!,
                longitude = trustedLon!!,
                source = SosLocationSource.LAST_CONFIRMED_GPS,
                accuracyMeters = navState.locationAccuracyMeters,
                isAccuracyValid = navState.locationAccuracyMeters > 0f,
                lastFixAgeSeconds = ageSec,
                uncertaintyRadiusMeters = navState.uncertaintyRadiusMeters
            )
        }

        // 4. Last Known Real Raw / Filtered Location
        val rawLat = if (!isDemoActive && isValidMapCoordinate(navState.rawLatitude, navState.rawLongitude)) navState.rawLatitude else null
        val rawLon = if (rawLat != null) navState.rawLongitude else null
        val filtLat = if (!isDemoActive && isValidMapCoordinate(navState.filteredLatitude, navState.filteredLongitude)) navState.filteredLatitude else null
        val filtLon = if (filtLat != null) navState.filteredLongitude else null

        val fallbackLat = rawLat ?: filtLat
        val fallbackLon = rawLon ?: filtLon

        if (fallbackLat != null && fallbackLon != null) {
            val ageSec = if (navState.gpsFixAgeMs > 0L) navState.gpsFixAgeMs / 1000L else navState.locationAgeSeconds
            safeLogI("Resolved SOS Location: LAST_KNOWN_GPS (lat=$fallbackLat, lon=$fallbackLon, age=${ageSec}s)")
            return SosLocation(
                latitude = fallbackLat,
                longitude = fallbackLon,
                source = SosLocationSource.LAST_KNOWN_GPS,
                accuracyMeters = navState.locationAccuracyMeters,
                isAccuracyValid = navState.locationAccuracyMeters > 0f,
                lastFixAgeSeconds = ageSec,
                uncertaintyRadiusMeters = navState.uncertaintyRadiusMeters
            )
        }

        safeLogW("Resolved SOS Location: UNAVAILABLE (No valid real location fix available)")
        return SosLocation(source = SosLocationSource.UNAVAILABLE)
    }
}
