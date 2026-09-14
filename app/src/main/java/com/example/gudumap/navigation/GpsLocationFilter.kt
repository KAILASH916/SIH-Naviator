package com.example.gudumap.navigation

import android.location.Location
import android.os.SystemClock
import android.util.Log
import kotlin.math.max
import kotlin.math.min

/**
 * Result data class produced by [GpsLocationFilter] for every raw location update.
 */
data class FilteredGpsResult(
    val rawLatitude: Double,
    val rawLongitude: Double,
    val filteredLatitude: Double,
    val filteredLongitude: Double,
    val accuracyMeters: Float,
    val speedKmh: Float,
    val rawHeadingDeg: Float,
    val filteredHeadingDeg: Float,
    val locationAgeMs: Long,
    val isAccepted: Boolean,
    val rejectionReason: String = "",
    val noiseThresholdMeters: Double = 0.0,
    val distanceFromPreviousMeters: Double = 0.0,
    val isStationary: Boolean = false
)

/**
 * Validates, filters, suppresses stationary jitter, and smooths raw GPS location updates.
 */
class GpsLocationFilter {

    companion object {
        private const val TAG = "Gudumap:GpsFilter"
        private const val MAX_LOCATION_AGE_MS = 30_000L
        private const val STATIONARY_SPEED_THRESHOLD_MPS = 0.8f // ~2.88 km/h
        private const val WALKING_SPEED_THRESHOLD_KMH = 7.0f
    }

    private var previousFilteredLat: Double? = null
    private var previousFilteredLon: Double? = null
    private var previousFilteredHeading: Float = 0f
    private var lastFilteredTimeMs: Long = 0L
    private var previousSpeedMps: Float = 0f
    private var stationaryObservationCount: Int = 0

    /**
     * Resets internal filter memory state.
     */
    fun reset() {
        previousFilteredLat = null
        previousFilteredLon = null
        previousFilteredHeading = 0f
        lastFilteredTimeMs = 0L
        previousSpeedMps = 0f
        stationaryObservationCount = 0
    }

    /**
     * Validates whether given coordinates and parameters are plausible and non-stale.
     */
    fun isValidLocation(
        rawLat: Double,
        rawLon: Double,
        accuracy: Float,
        locationTimeMs: Long,
        hasAccuracy: Boolean = true,
        currentTimeMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (!rawLat.isFinite() || !rawLon.isFinite()) return false
        if (rawLat !in -90.0..90.0 || rawLon !in -180.0..180.0) return false
        if (rawLat == 0.0 && rawLon == 0.0) return false
        if (!hasAccuracy || accuracy <= 0f || accuracy > 100f) return false

        if (locationTimeMs > 0L) {
            val ageMs = currentTimeMs - locationTimeMs
            if (ageMs > MAX_LOCATION_AGE_MS || ageMs < -5000L) return false
            if (lastFilteredTimeMs > 0L && locationTimeMs < lastFilteredTimeMs - 500L) return false
        }
        return true
    }

    /**
     * Calculates fix age in milliseconds using monotonic elapsedRealtimeNanos when available,
     * falling back to wall-clock time if unpopulated.
     */
    fun computeLocationAgeMs(location: Location, currentTimeMs: Long = System.currentTimeMillis()): Long {
        if (location.elapsedRealtimeNanos > 0L) {
            val ageNs = android.os.SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
            if (ageNs >= 0L) {
                return ageNs / 1_000_000L
            }
        }
        val locTime = location.time
        if (locTime > 0L) {
            val ageMs = currentTimeMs - locTime
            if (ageMs >= 0L) return ageMs
        }
        return 0L
    }

    /**
     * Validates whether a given [Location] object contains plausible, non-stale coordinates.
     */
    fun isValidLocation(location: Location, currentTimeMs: Long = System.currentTimeMillis()): Boolean {
        val ageMs = computeLocationAgeMs(location, currentTimeMs)
        return isValidLocation(
            rawLat = location.latitude,
            rawLon = location.longitude,
            accuracy = if (location.hasAccuracy()) location.accuracy else 0f,
            locationTimeMs = currentTimeMs - ageMs,
            hasAccuracy = location.hasAccuracy(),
            currentTimeMs = currentTimeMs
        )
    }

    /**
     * Processes a raw [Location] update, applying validation, stationary noise suppression,
     * adaptive low-pass smoothing, and shortest-angle heading interpolation.
     */
    fun processLocation(
        location: Location,
        currentTimeMs: Long = System.currentTimeMillis()
    ): FilteredGpsResult {
        val ageMs = computeLocationAgeMs(location, currentTimeMs)
        return processLocation(
            rawLat = location.latitude,
            rawLon = location.longitude,
            accuracy = if (location.hasAccuracy()) location.accuracy else 50f,
            locationTimeMs = currentTimeMs - ageMs,
            bearingDeg = if (location.hasBearing()) location.bearing else 0f,
            speedMps = if (location.hasSpeed()) location.speed else 0f,
            hasBearing = location.hasBearing(),
            hasAccuracy = location.hasAccuracy(),
            hasSpeed = location.hasSpeed(),
            currentTimeMs = currentTimeMs
        )
    }

    /**
     * Processes a raw location update given primitive parameters.
     */
    fun processLocation(
        rawLat: Double,
        rawLon: Double,
        accuracy: Float,
        locationTimeMs: Long,
        bearingDeg: Float = 0f,
        speedMps: Float = 0f,
        hasBearing: Boolean = false,
        hasAccuracy: Boolean = true,
        hasSpeed: Boolean = false,
        currentTimeMs: Long = System.currentTimeMillis()
    ): FilteredGpsResult {
        val effectiveAccuracy = if (hasAccuracy) accuracy else 50f
        val locationTime = if (locationTimeMs > 0L) locationTimeMs else currentTimeMs
        val locationAgeMs = maxOf(0L, currentTimeMs - locationTime)

        // 1. Validation Check
        if (!isValidLocation(rawLat, rawLon, accuracy, locationTimeMs, hasAccuracy, currentTimeMs)) {
            val reason = when {
                !rawLat.isFinite() || !rawLon.isFinite() -> "Non-finite coordinates"
                rawLat !in -90.0..90.0 || rawLon !in -180.0..180.0 -> "Coordinates out of bounds"
                rawLat == 0.0 && rawLon == 0.0 -> "Sentinel 0,0 location"
                !hasAccuracy || accuracy <= 0f -> "Invalid accuracy"
                accuracy > 100f -> "Excessive accuracy uncertainty (${accuracy}m)"
                lastFilteredTimeMs > 0L && locationTimeMs < lastFilteredTimeMs - 500L -> "Out of order location timestamp"
                else -> "Stale location timestamp (${locationAgeMs}ms old)"
            }
            try { Log.w(TAG, "Location rejected by filter: $reason") } catch (_: Throwable) {}
            return FilteredGpsResult(
                rawLatitude = rawLat,
                rawLongitude = rawLon,
                filteredLatitude = previousFilteredLat ?: rawLat,
                filteredLongitude = previousFilteredLon ?: rawLon,
                accuracyMeters = effectiveAccuracy,
                speedKmh = 0f,
                rawHeadingDeg = bearingDeg,
                filteredHeadingDeg = previousFilteredHeading,
                locationAgeMs = locationAgeMs,
                isAccepted = false,
                rejectionReason = reason
            )
        }

        val prevLat = previousFilteredLat
        val prevLon = previousFilteredLon

        // First fix case
        if (prevLat == null || prevLon == null) {
            previousFilteredLat = rawLat
            previousFilteredLon = rawLon
            previousFilteredHeading = if (hasBearing) bearingDeg else 0f
            lastFilteredTimeMs = currentTimeMs
            previousSpeedMps = if (hasSpeed) speedMps else 0f
            stationaryObservationCount = if (hasSpeed && speedMps < STATIONARY_SPEED_THRESHOLD_MPS) 1 else 0

            return FilteredGpsResult(
                rawLatitude = rawLat,
                rawLongitude = rawLon,
                filteredLatitude = rawLat,
                filteredLongitude = rawLon,
                accuracyMeters = effectiveAccuracy,
                speedKmh = if (hasSpeed) speedMps * 3.6f else 0f,
                rawHeadingDeg = bearingDeg,
                filteredHeadingDeg = previousFilteredHeading,
                locationAgeMs = locationAgeMs,
                isAccepted = true,
                isStationary = stationaryObservationCount >= 1
            )
        }

        // 2. Compute Distance & Speed from Previous Fix
        val distMovedMeters = CoordinateTransformer.computeDistanceBetween(prevLat, prevLon, rawLat, rawLon)
        val dtSec = maxOf(0.1, (currentTimeMs - lastFilteredTimeMs) / 1000.0)
        val derivedSpeedMps = (distMovedMeters / dtSec).toFloat()
        val candidateSpeedMps = if (hasSpeed && speedMps >= 0f) speedMps else derivedSpeedMps

        // Spike limiting on speed change rate (max 4.0 m/s^2 acceleration/deceleration)
        val maxSpeedDelta = 4.0f * dtSec.toFloat()
        val speedDelta = candidateSpeedMps - previousSpeedMps
        val rawSpeedMps = if (kotlin.math.abs(speedDelta) > maxSpeedDelta && previousSpeedMps > 0f) {
            (previousSpeedMps + kotlin.math.sign(speedDelta) * maxSpeedDelta).coerceAtLeast(0f)
        } else {
            candidateSpeedMps.coerceAtLeast(0f)
        }

        val rawSpeedKmh = rawSpeedMps * 3.6f

        // 3. Accuracy-Aware Noise Radius & Hysteresis Stationary Check
        val noiseRadiusMeters = max(2.0, 0.35 * accuracy.toDouble())
        val rawStationary = distMovedMeters < noiseRadiusMeters && rawSpeedMps < STATIONARY_SPEED_THRESHOLD_MPS

        if (rawStationary) {
            stationaryObservationCount++
        } else {
            stationaryObservationCount = maxOf(0, stationaryObservationCount - 1)
        }

        val isStationary = rawStationary && (rawSpeedMps < 0.4f || stationaryObservationCount >= 2)

        val rawHeading = if (hasBearing && rawSpeedMps > 1.0f) {
            (bearingDeg % 360f + 360f) % 360f
        } else if (distMovedMeters > 0.8) {
            CoordinateTransformer.computeInitialBearing(prevLat, prevLon, rawLat, rawLon)
        } else {
            previousFilteredHeading
        }

        var newFiltLat: Double
        var newFiltLon: Double
        var newFiltHeading: Float
        var newFiltSpeedKmh: Float

        if (isStationary) {
            // Lock position and heading when stationary to prevent GPS jitter
            newFiltLat = prevLat
            newFiltLon = prevLon
            newFiltHeading = previousFilteredHeading
            newFiltSpeedKmh = 0f
            previousSpeedMps = 0f
        } else {
            // 4. Uncertainty-Weighted Position Low-Pass Filtering
            val estVar = 16.0 // Estimated position variance (4m^2)
            val measVar = (accuracy * accuracy).toDouble().coerceAtLeast(4.0)
            val alphaPos = (estVar / (estVar + measVar)).coerceIn(0.25, 0.85)

            newFiltLat = prevLat + alphaPos * (rawLat - prevLat)
            newFiltLon = prevLon + alphaPos * (rawLon - prevLon)
            newFiltSpeedKmh = rawSpeedKmh
            previousSpeedMps = rawSpeedMps

            // 5. Shortest-Angle Heading Interpolation across 359° -> 0° boundary
            val alphaHeading = if (rawSpeedKmh > WALKING_SPEED_THRESHOLD_KMH) 0.50f else 0.30f
            val headingDelta = CoordinateTransformer.computeShortestAngularDelta(previousFilteredHeading, rawHeading)
            newFiltHeading = CoordinateTransformer.normalizeHeading(previousFilteredHeading + alphaHeading * headingDelta)
        }

        previousFilteredLat = newFiltLat
        previousFilteredLon = newFiltLon
        previousFilteredHeading = newFiltHeading
        lastFilteredTimeMs = currentTimeMs

        try {
            Log.d(TAG, String.format(
                "GPS Fix: Raw=%.6f,%.6f | Filt=%.6f,%.6f | Acc=%.1fm | Dist=%.2fm | NoiseR=%.2fm | Speed=%.1fkm/h | Head=%.0f° | Stat=%b",
                rawLat, rawLon, newFiltLat, newFiltLon, accuracy, distMovedMeters, noiseRadiusMeters, newFiltSpeedKmh, newFiltHeading, isStationary
            ))
        } catch (_: Throwable) {}

        return FilteredGpsResult(
            rawLatitude = rawLat,
            rawLongitude = rawLon,
            filteredLatitude = newFiltLat,
            filteredLongitude = newFiltLon,
            accuracyMeters = accuracy,
            speedKmh = newFiltSpeedKmh,
            rawHeadingDeg = rawHeading,
            filteredHeadingDeg = newFiltHeading,
            locationAgeMs = locationAgeMs,
            isAccepted = true,
            noiseThresholdMeters = noiseRadiusMeters,
            distanceFromPreviousMeters = distMovedMeters,
            isStationary = isStationary
        )
    }

    /**
     * Calculates the shortest directional angle delta between [fromDeg] and [toDeg] in degrees (-180..180).
     * Correctly handles 359° -> 1° (+2°) and 1° -> 359° (-2°).
     */
    fun computeShortestAngleDelta(fromDeg: Float, toDeg: Float): Float {
        val delta = (toDeg - fromDeg + 540f) % 360f - 180f
        return delta
    }
}
