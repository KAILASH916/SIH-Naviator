package com.example.gudumap.tracking

import com.example.gudumap.navigation.CoordinateTransformer
import kotlin.math.max

data class TrajectoryPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 0.0,
    val speedMps: Float = 0f,
    val headingDeg: Float = 0f,
    val timestampNs: Long = System.nanoTime(),
    val isBlackout: Boolean = false,
    val isStationary: Boolean = false
)

data class BlackoutEvaluation(
    val positionErrorMeters: Double = 0.0,
    val driftPercentage: Double = 0.0,
    val blackoutDistanceMeters: Double = 0.0
)

/**
 * Stores trajectory points and accumulates travelled distance.
 *
 * Physical prediction:
 * Small accepted movements must count, including pedestrian steps
 * shorter than one metre.
 *
 * Live GPS:
 * Retains the existing frequency-dependent distance thresholds.
 *
 * The navigation estimator remains responsible for rejecting false
 * steps, invalid movement and implausible position jumps. This class
 * does not convert speed into artificial distance.
 */
class TrajectoryIntegrator(
    private val maxPoints: Int = 1000
) {

    companion object {
        private const val HIGH_FREQUENCY_UPDATE_SEC = 0.1

        private const val HIGH_FREQUENCY_MIN_DISTANCE_METERS =
            0.00001

        private const val LOW_FREQUENCY_MIN_DISTANCE_METERS =
            1.0

        /*
         * Numerical tolerance only.
         * Accepted predicted steps must not be discarded merely
         * because they are less than one metre long.
         */
        private const val PREDICTED_MIN_DISTANCE_METERS =
            0.00001
    }

    private val lock = Any()

    private val points =
        ArrayList<TrajectoryPoint>(maxPoints.coerceAtLeast(1))

    private var totalDistanceTravelled = 0.0
    private var blackoutStartDistance = 0.0
    private var blackoutStartPoint: TrajectoryPoint? = null

    init {
        require(maxPoints > 0) {
            "maxPoints must be greater than zero."
        }
    }

    private fun hasValidCoordinates(point: TrajectoryPoint): Boolean {
        return point.latitude.isFinite() &&
                point.longitude.isFinite() &&
                point.latitude in -90.0..90.0 &&
                point.longitude in -180.0..180.0
    }

    /**
     * Adds a navigation point.
     *
     * Reanchoring corrections and explicitly stationary updates
     * do not increase distance. They still update the stored
     * reference so their positional shift is not counted later.
     */
    fun addPoint(
        point: TrajectoryPoint,
        isReanchoring: Boolean = false
    ) {
        if (!hasValidCoordinates(point)) return

        synchronized(lock) {
            val previous = points.lastOrNull()

            if (previous != null) {
                val distance =
                    CoordinateTransformer.computeDistanceBetween(
                        previous.latitude,
                        previous.longitude,
                        point.latitude,
                        point.longitude
                    )

                val elapsedSeconds =
                    (point.timestampNs - previous.timestampNs) /
                            1_000_000_000.0

                val minimumDistance = when {
                    /*
                     * The estimator has already accepted this
                     * predicted movement. Count short steps.
                     */
                    point.isBlackout ->
                        PREDICTED_MIN_DISTANCE_METERS

                    elapsedSeconds in
                            0.0..HIGH_FREQUENCY_UPDATE_SEC ->
                        HIGH_FREQUENCY_MIN_DISTANCE_METERS

                    else ->
                        LOW_FREQUENCY_MIN_DISTANCE_METERS
                }

                if (
                    !point.isStationary &&
                    !isReanchoring &&
                    distance.isFinite() &&
                    distance >= minimumDistance
                ) {
                    totalDistanceTravelled += distance
                }
            }

            points.add(point)

            if (points.size > maxPoints) {
                points.removeAt(0)
            }
        }
    }

    /**
     * Marks the beginning of a blackout evaluation interval.
     * Does not add an extra trajectory segment.
     */
    fun startBlackout(currentPoint: TrajectoryPoint) {
        if (!hasValidCoordinates(currentPoint)) return

        synchronized(lock) {
            blackoutStartPoint = currentPoint
            blackoutStartDistance = totalDistanceTravelled
        }
    }

    /**
     * Compares an estimated position against a supplied reference.
     * The reference is not necessarily exact ground truth.
     */
    fun evaluateDrift(
        estimatedLat: Double,
        estimatedLon: Double,
        gnssReferenceLat: Double,
        gnssReferenceLon: Double
    ): BlackoutEvaluation {
        val positionError =
            CoordinateTransformer.computeDistanceBetween(
                estimatedLat,
                estimatedLon,
                gnssReferenceLat,
                gnssReferenceLon
            )

        return synchronized(lock) {
            val blackoutDistance = max(
                0.0,
                totalDistanceTravelled - blackoutStartDistance
            )

            val driftPercentage = if (blackoutDistance > 1.0) {
                positionError / blackoutDistance * 100.0
            } else {
                0.0
            }

            BlackoutEvaluation(
                positionErrorMeters = positionError,
                driftPercentage = driftPercentage,
                blackoutDistanceMeters = blackoutDistance
            )
        }
    }

    fun getTotalDistance(): Double {
        return synchronized(lock) {
            totalDistanceTravelled
        }
    }

    fun getBlackoutStartPoint(): TrajectoryPoint? {
        return synchronized(lock) {
            blackoutStartPoint
        }
    }

    fun getBlackoutStartDistance(): Double {
        return synchronized(lock) {
            blackoutStartDistance
        }
    }

    fun getBlackoutDistance(): Double {
        return synchronized(lock) {
            max(
                0.0,
                totalDistanceTravelled - blackoutStartDistance
            )
        }
    }

    fun getPoints(): List<TrajectoryPoint> {
        return synchronized(lock) {
            ArrayList(points)
        }
    }

    fun reset() {
        synchronized(lock) {
            points.clear()
            totalDistanceTravelled = 0.0
            blackoutStartDistance = 0.0
            blackoutStartPoint = null
        }
    }
}