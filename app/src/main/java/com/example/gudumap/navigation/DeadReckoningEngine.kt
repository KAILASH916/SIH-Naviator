package com.example.gudumap.navigation

import android.content.Context
import android.location.Location
import com.example.gudumap.ml.InputNormalizer
import com.example.gudumap.ml.ModelMetadata
import com.example.gudumap.ml.ModelRunner
import com.example.gudumap.sensor.DiagnosticRecorder
import com.example.gudumap.sensor.ImuSample
import com.example.gudumap.sensor.OrientationSample
import com.example.gudumap.tracking.TrajectoryIntegrator
import com.example.gudumap.tracking.TrajectoryPoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

enum class GateAction {
    ACCEPTED,
    CLAMPED,
    REJECTED
}

enum class MotionMode {
    VEHICLE_MODE,
    CONSERVATIVE_MODE
}

data class NavigationEngineState(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitude: Double = 0.0,
    val speed: Float = 0f,
    val heading: Float = 0f,
    val distanceTravelled: Double = 0.0,
    val isInitialized: Boolean = false,
    val isStationary: Boolean = false,
    val motionState: String = "STATIONARY",
    val isBlackout: Boolean = false,
    val timestampNs: Long = 0L,
    val latestGateAction: String = "ACCEPTED",
    val acceptedCount: Int = 0,
    val clampedCount: Int = 0,
    val rejectedCount: Int = 0,
    val naiveLatitude: Double = 0.0,
    val naiveLongitude: Double = 0.0,
    val uncertaintyRadiusMeters: Double = 0.0,
    val motionMode: String = "VEHICLE_MODE",
    val stepCount: Int = 0,
    val latestStrideMeters: Float = 0f
)

/**
 * Navigation estimator with separate pedestrian and vehicle paths.
 *
 * Pedestrian blackout:
 * accepted step -> N/E displacement -> EKF pedestrian update.
 *
 * Vehicle blackout:
 * IMU window -> model -> plausibility gate -> EKF.
 *
 * Sensor samples never establish a geographic anchor by themselves.
 * initialize() or the first valid GNSS correction must provide one.
 *
 * Threading:
 * Mutable estimator state is protected by stateLock.
 * Navigation callbacks are dispatched outside the outer processing lock.
 */
class DeadReckoningEngine(
    val modelRunner: ModelRunner = ModelRunner(),
    val normalizer: InputNormalizer = InputNormalizer(),
    val imuBuffer: IMUBuffer = IMUBuffer(),
    val transformer: CoordinateTransformer = CoordinateTransformer(),
    val zuptDetector: ZuptDetector = ZuptDetector(),
    val ekf: EKF = EKF(),
    val nhc: NHC = NHC(),
    var mapMatcher: MapMatcher = PassThroughMapMatcher(),
    val trajectoryIntegrator: TrajectoryIntegrator =
        TrajectoryIntegrator(),
    val diagnosticRecorder: DiagnosticRecorder =
        DiagnosticRecorder(),
    val naiveIntegrator: NaiveIntegrator = NaiveIntegrator(),
    val pdrDetector: PdrDetector = PdrDetector()
) : AutoCloseable {

    companion object {
        private const val MAX_SPEED_CHANGE_MPS2 = 4.0f
        private const val MAX_PLAUSIBLE_SPEED_MPS = 50.0f
        private const val PEDESTRIAN_SPEED_CEILING_MPS = 2.5f

        private const val VEHICLE_SPEED_LOOKBACK_NS =
            10_000_000_000L

        private const val STEP_HISTORY_NS = 2_000_000_000L
        private const val WALKING_TIMEOUT_NS = 1_800_000_000L

        /*
         * A gap this large means samples were interrupted.
         * Do not bridge it with carried timing or step extrema.
         */
        private const val SAMPLE_GAP_RESET_NS =
            2_000_000_000L
    }

    private val stateLock = Any()

    private var originLat = 0.0
    private var originLon = 0.0
    private var originAltitude = 0.0

    private var currentLat = 0.0
    private var currentLon = 0.0
    private var currentHeadingDeg = 0f
    private var currentPitchDeg = 0f
    private var currentRollDeg = 0f

    private var latestOrientationMatrix: FloatArray? = null

    private var isBlackoutMode = false
    private var isEngineInitialized = false
    private var currentMotionMode = MotionMode.VEHICLE_MODE

    private var lastStateTimestampNs = 0L
    private var lastSensorTimestampNs = 0L
    private var latestGnssSpeed: Float? = null

    private var lastSanitizedGnssSpeed = 0f
    private var lastGnssSpeedTimestampNs = 0L

    private var blackoutEntrySpeedMps = 0f
    private var blackoutEntryTimestampNs = 0L

    private val recentGnssSpeedHistory =
        ArrayDeque<Pair<Long, Float>>()

    private var lastInferenceTimestampNs = 0L
    private var lastInferenceLatencyMs = 0f
    private var lastDisplacementMeters =
        floatArrayOf(0f, 0f, 0f)

    private var currentGateAction = GateAction.ACCEPTED
    private var acceptedPredictionCount = 0
    private var clampedPredictionCount = 0
    private var rejectedPredictionCount = 0

    private val pdrStepHistory =
        ArrayDeque<Pair<Long, Float>>()

    private var lastPdrStepTimestampNs = 0L
    private var lastPdrIntervalSeconds = 0.5

    private var reanchorRemainingN = 0.0
    private var reanchorRemainingE = 0.0
    private var isSmoothReanchoringActive = false

    var onNavigationStateChanged:
            ((NavigationEngineState) -> Unit)? = null

    val motionMode: MotionMode
        get() = synchronized(stateLock) { currentMotionMode }

    val acceptedCount: Int
        get() = synchronized(stateLock) {
            acceptedPredictionCount
        }

    val clampedCount: Int
        get() = synchronized(stateLock) {
            clampedPredictionCount
        }

    val rejectedCount: Int
        get() = synchronized(stateLock) {
            rejectedPredictionCount
        }

    val latestGateAction: GateAction
        get() = synchronized(stateLock) { currentGateAction }

    val blackoutMode: Boolean
        get() = synchronized(stateLock) { isBlackoutMode }

    val isInitialized: Boolean
        get() = synchronized(stateLock) {
            isEngineInitialized
        }

    val isReanchoring: Boolean
        get() = synchronized(stateLock) {
            isSmoothReanchoringActive
        }

    val isStationary: Boolean
        get() = getState().isStationary

    val distanceTravelled: Double
        get() = synchronized(stateLock) {
            trajectoryIntegrator.getTotalDistance()
        }

    val lastInferenceLatency: Float
        get() = synchronized(stateLock) {
            lastInferenceLatencyMs
        }

    init {
        imuBuffer.onWindowReadyListener = { window, timestampNs ->
            processWindowInference(window, timestampNs)
        }
    }

    fun initializeAssets(context: Context) {
        synchronized(stateLock) {
            if (!modelRunner.ready) {
                try {
                    modelRunner.initializeFromAssets(context)
                } catch (error: Exception) {
                    error.printStackTrace()
                }
            }
        }
    }

    private fun validCoordinate(
        latitude: Double,
        longitude: Double
    ): Boolean {
        return latitude.isFinite() &&
                longitude.isFinite() &&
                latitude in -90.0..90.0 &&
                longitude in -180.0..180.0
    }

    private fun normalizedHeading(heading: Float): Float {
        return ((heading % 360f) + 360f) % 360f
    }

    private fun isPedestrianTrackingLocked(): Boolean {
        return isBlackoutMode &&
                currentMotionMode == MotionMode.CONSERVATIVE_MODE
    }

    private fun clearReanchoringLocked() {
        isSmoothReanchoringActive = false
        reanchorRemainingN = 0.0
        reanchorRemainingE = 0.0
    }

    private fun clearPdrLocked() {
        pdrDetector.reset()
        pdrStepHistory.clear()
        lastPdrStepTimestampNs = 0L
        lastPdrIntervalSeconds = 0.5
    }

    private fun zeroVelocityLocked() {
        ekf.state[3] = 0.0
        ekf.state[4] = 0.0
        ekf.state[5] = 0.0
    }

    fun resetVelocitiesAndPdr() {
        synchronized(stateLock) {
            zeroVelocityLocked()
            clearPdrLocked()
            zuptDetector.reset()
        }
    }

    private fun initializeLocked(
        latitude: Double,
        longitude: Double,
        speedMps: Float?,
        bearingDeg: Float?,
        altitude: Double
    ) {
        originLat = latitude
        originLon = longitude
        originAltitude = altitude

        currentLat = latitude
        currentLon = longitude

        val speed = speedMps
            ?.takeIf { it.isFinite() && it >= 0f }
            ?.coerceAtMost(MAX_PLAUSIBLE_SPEED_MPS)

        val bearing = bearingDeg
            ?.takeIf { it.isFinite() }
            ?.let { normalizedHeading(it) }

        if (bearing != null) {
            currentHeadingDeg = bearing
        }

        val headingRad =
            Math.toRadians(currentHeadingDeg.toDouble())

        val northVelocity = if (speed != null && bearing != null) {
            speed * cos(headingRad)
        } else {
            0.0
        }

        val eastVelocity = if (speed != null && bearing != null) {
            speed * sin(headingRad)
        } else {
            0.0
        }

        latestGnssSpeed = speed

        ekf.initialize(
            pNorth = 0.0,
            pEast = 0.0,
            pDown = 0.0,
            vNorth = northVelocity,
            vEast = eastVelocity,
            vDown = 0.0
        )

        imuBuffer.reset()
        zuptDetector.reset()
        clearPdrLocked()
        trajectoryIntegrator.reset()
        naiveIntegrator.reset()
        clearReanchoringLocked()

        lastSensorTimestampNs = 0L
        lastStateTimestampNs = 0L
        lastInferenceTimestampNs = 0L
        lastInferenceLatencyMs = 0f
        lastDisplacementMeters = floatArrayOf(0f, 0f, 0f)
        blackoutEntryTimestampNs = 0L

        isEngineInitialized = true

        trajectoryIntegrator.addPoint(
            TrajectoryPoint(
                latitude = currentLat,
                longitude = currentLon,
                altitude = originAltitude,
                speedMps = speed ?: 0f,
                headingDeg = currentHeadingDeg,
                timestampNs = 0L,
                isBlackout = isBlackoutMode,
                isStationary = (speed ?: 0f) == 0f
            )
        )
    }

    fun initialize(
        latitude: Double,
        longitude: Double,
        speedMps: Float? = null,
        bearingDeg: Float? = null,
        altitude: Double = 0.0
    ) {
        require(validCoordinate(latitude, longitude)) {
            "A valid geographic anchor is required."
        }
        require(altitude.isFinite()) {
            "Altitude must be finite."
        }

        synchronized(stateLock) {
            initializeLocked(
                latitude,
                longitude,
                speedMps,
                bearingDeg,
                altitude
            )
        }

        dispatchState()
    }

    fun initialize(location: Location) {
        initialize(
            latitude = location.latitude,
            longitude = location.longitude,
            speedMps = if (location.hasSpeed()) {
                location.speed
            } else null,
            bearingDeg = if (location.hasBearing()) {
                location.bearing
            } else null,
            altitude = if (location.hasAltitude()) {
                location.altitude
            } else 0.0
        )
    }

    private fun updateCoordinatesLocked() {
        val coordinates =
            transformer.addMetricDisplacementToGeodetic(
                originLat,
                originLon,
                ekf.state[0],
                ekf.state[1]
            )

        if (validCoordinate(coordinates[0], coordinates[1])) {
            currentLat = coordinates[0]
            currentLon = coordinates[1]
        }
    }

    private fun smoothHeading(
        previous: Float,
        next: Float,
        weight: Float
    ): Float {
        val delta = ((next - previous + 540f) % 360f) - 180f
        return normalizedHeading(previous + delta * weight)
    }

    fun correctWithGnss(
        latitude: Double,
        longitude: Double,
        speedMps: Float? = null,
        bearingDeg: Float? = null,
        accuracyMeters: Float = 3.0f
    ) {
        if (!validCoordinate(latitude, longitude)) return
        if (!accuracyMeters.isFinite() || accuracyMeters <= 0f) {
            return
        }

        synchronized(stateLock) {
            if (isBlackoutMode) return

            if (!isEngineInitialized) {
                initializeLocked(
                    latitude,
                    longitude,
                    speedMps,
                    bearingDeg,
                    0.0
                )
            } else {
                val speed = speedMps?.takeIf {
                    it.isFinite() && it >= 0f
                }

                val bearing = bearingDeg?.takeIf {
                    it.isFinite()
                }

                if (speed != null) latestGnssSpeed = speed

                val north =
                    Math.toRadians(latitude - originLat) *
                            CoordinateTransformer.MEAN_EARTH_RADIUS

                val east =
                    Math.toRadians(longitude - originLon) *
                            CoordinateTransformer.MEAN_EARTH_RADIUS *
                            cos(Math.toRadians(originLat))

                val positionNoise =
                    accuracyMeters.toDouble().coerceAtLeast(1.0)

                val accepted = ekf.updateGnssPosition(
                    north,
                    east,
                    0.0,
                    noiseMeters = positionNoise,
                    mahalanobisThreshold = 11.34
                )

                /*
                 * Preserve the existing bounded recovery behaviour.
                 * NavigationEngine must validate incoming GNSS fixes.
                 */
                if (!accepted) {
                    val residualN = north - ekf.state[0]
                    val residualE = east - ekf.state[1]
                    val residual = sqrt(
                        residualN * residualN +
                                residualE * residualE
                    )

                    if (residual <= 100.0) {
                        ekf.updateGnssPosition(
                            north,
                            east,
                            0.0,
                            noiseMeters = positionNoise,
                            mahalanobisThreshold = 0.0
                        )
                    }
                }

                if (speed != null) {
                    val heading = bearing ?: currentHeadingDeg
                    val radians = Math.toRadians(heading.toDouble())
                    val velocityN = speed * cos(radians)
                    val velocityE = speed * sin(radians)

                    val velocityAccepted = ekf.updateGnssVelocity(
                        velocityN,
                        velocityE,
                        0.0,
                        mahalanobisThreshold = 11.34
                    )

                    if (!velocityAccepted) {
                        val differenceN = velocityN - ekf.state[3]
                        val differenceE = velocityE - ekf.state[4]
                        val difference = sqrt(
                            differenceN * differenceN +
                                    differenceE * differenceE
                        )

                        if (difference <= 15.0) {
                            ekf.updateGnssVelocity(
                                velocityN,
                                velocityE,
                                0.0,
                                mahalanobisThreshold = 0.0
                            )
                        }
                    }

                    if (bearing != null && speed > 1f) {
                        currentHeadingDeg = smoothHeading(
                            currentHeadingDeg,
                            bearing,
                            0.35f
                        )
                    }
                } else if (bearing != null) {
                    currentHeadingDeg = smoothHeading(
                        currentHeadingDeg,
                        bearing,
                        0.25f
                    )
                }

                updateCoordinatesLocked()
            }
        }

        dispatchState()
    }

    fun correctWithGnss(location: Location) {
        val speed: Float?

        synchronized(stateLock) {
            if (isBlackoutMode) return

            speed = sanitizeGnssSpeedLocked(
                if (location.hasSpeed()) location.speed else null,
                location.elapsedRealtimeNanos
            )

            if (speed != null &&
                location.elapsedRealtimeNanos > 0L
            ) {
                recordGnssSpeedLocked(
                    speed,
                    location.elapsedRealtimeNanos
                )
            }
        }

        correctWithGnss(
            latitude = location.latitude,
            longitude = location.longitude,
            speedMps = speed,
            bearingDeg = if (location.hasBearing()) {
                location.bearing
            } else null,
            accuracyMeters = if (location.hasAccuracy()) {
                location.accuracy
            } else 5f
        )
    }

    private fun sanitizeGnssSpeedLocked(
        rawSpeedMps: Float?,
        timestampNs: Long
    ): Float? {
        if (rawSpeedMps == null ||
            !rawSpeedMps.isFinite() ||
            rawSpeedMps < 0f
        ) {
            return null
        }

        val bounded =
            rawSpeedMps.coerceAtMost(MAX_PLAUSIBLE_SPEED_MPS)

        if (timestampNs <= 0L) return null

        if (lastGnssSpeedTimestampNs == 0L) {
            lastGnssSpeedTimestampNs = timestampNs
            lastSanitizedGnssSpeed = bounded
            return bounded
        }

        if (timestampNs < lastGnssSpeedTimestampNs) return null

        if (timestampNs == lastGnssSpeedTimestampNs) {
            return lastSanitizedGnssSpeed
        }

        val dt = (
                (timestampNs - lastGnssSpeedTimestampNs) /
                        1_000_000_000.0
                ).toFloat()

        val maxDelta = MAX_SPEED_CHANGE_MPS2 * dt
        val delta = bounded - lastSanitizedGnssSpeed

        val sanitized = if (abs(delta) > maxDelta) {
            lastSanitizedGnssSpeed + maxDelta * sign(delta)
        } else {
            bounded
        }

        lastGnssSpeedTimestampNs = timestampNs
        lastSanitizedGnssSpeed = sanitized
        return sanitized
    }

    fun sanitizeExternalGnssSpeed(
        rawSpeedMps: Float?,
        timestampNs: Long
    ): Float? {
        return synchronized(stateLock) {
            sanitizeGnssSpeedLocked(rawSpeedMps, timestampNs)
        }
    }

    private fun recordGnssSpeedLocked(
        speedMps: Float,
        timestampNs: Long
    ) {
        recentGnssSpeedHistory.addLast(timestampNs to speedMps)

        val cutoff = timestampNs - VEHICLE_SPEED_LOOKBACK_NS
        while (
            recentGnssSpeedHistory.isNotEmpty() &&
            recentGnssSpeedHistory.first().first < cutoff
        ) {
            recentGnssSpeedHistory.removeFirst()
        }
    }

    private fun classifyMotionModeLocked(): MotionMode {
        val referenceTimestamp = max(
            lastSensorTimestampNs,
            lastGnssSpeedTimestampNs
        )

        val cutoff =
            referenceTimestamp - VEHICLE_SPEED_LOOKBACK_NS

        while (
            recentGnssSpeedHistory.isNotEmpty() &&
            recentGnssSpeedHistory.first().first < cutoff
        ) {
            recentGnssSpeedHistory.removeFirst()
        }

        val maximumSpeed =
            recentGnssSpeedHistory.maxOfOrNull { it.second }
                ?: 0f

        return if (
            maximumSpeed < PEDESTRIAN_SPEED_CEILING_MPS
        ) {
            MotionMode.CONSERVATIVE_MODE
        } else {
            MotionMode.VEHICLE_MODE
        }
    }

    private fun pedestrianSpeedLocked(nowNs: Long): Float {
        if (lastPdrStepTimestampNs <= 0L ||
            pdrStepHistory.isEmpty() ||
            nowNs < lastPdrStepTimestampNs
        ) {
            return 0f
        }

        if (nowNs - lastPdrStepTimestampNs > WALKING_TIMEOUT_NS) {
            pdrStepHistory.clear()
            return 0f
        }

        val cutoff = nowNs - STEP_HISTORY_NS
        while (
            pdrStepHistory.isNotEmpty() &&
            pdrStepHistory.first().first < cutoff
        ) {
            pdrStepHistory.removeFirst()
        }

        if (pdrStepHistory.isEmpty()) return 0f

        /*
         * Count each stored step with an interval, including an
         * estimated interval before the oldest retained step.
         * This avoids dividing one step by almost zero time.
         */
        val distance =
            pdrStepHistory.sumOf { it.second.toDouble() }

        val spanSeconds = (
                pdrStepHistory.last().first -
                        pdrStepHistory.first().first
                ) / 1_000_000_000.0

        val intervals = pdrStepHistory.size - 1

        val representativeInterval = if (intervals > 0) {
            spanSeconds / intervals
        } else {
            lastPdrIntervalSeconds
        }

        val elapsedSinceLastStep =
            (nowNs - lastPdrStepTimestampNs) /
                    1_000_000_000.0

        val windowDuration = max(
            0.25,
            spanSeconds + max(
                representativeInterval,
                elapsedSinceLastStep
            )
        )

        return (distance / windowDuration)
            .toFloat()
            .coerceIn(0f, PEDESTRIAN_SPEED_CEILING_MPS)
    }

    fun getPedestrianSpeedMps(
        nowNs: Long = 0L
    ): Float {
        return synchronized(stateLock) {
            val timestamp = if (nowNs > 0L) {
                nowNs
            } else {
                lastSensorTimestampNs
            }
            pedestrianSpeedLocked(timestamp)
        }
    }

    private fun processPedestrianStepLocked(
        sample: ImuSample
    ) {
        val step = pdrDetector.processSample(
            sample,
            currentHeadingDeg
        ) ?: return

        val north = step.deltaNorthMeters
        val east = step.deltaEastMeters
        val length = step.stepLengthMeters

        if (!north.isFinite() ||
            !east.isFinite() ||
            !length.isFinite() ||
            length <= 0f
        ) {
            return
        }

        val previousTimestamp = lastPdrStepTimestampNs
        val interval = if (
            previousTimestamp > 0L &&
            sample.timestampNs > previousTimestamp
        ) {
            (
                    (sample.timestampNs - previousTimestamp) /
                            1_000_000_000.0
                    ).coerceIn(0.25, 2.0)
        } else {
            0.5
        }

        lastPdrIntervalSeconds = interval
        lastPdrStepTimestampNs = sample.timestampNs

        pdrStepHistory.addLast(sample.timestampNs to length)

        val cutoff = sample.timestampNs - STEP_HISTORY_NS
        while (
            pdrStepHistory.isNotEmpty() &&
            pdrStepHistory.first().first < cutoff
        ) {
            pdrStepHistory.removeFirst()
        }

        /*
         * Initial uncertainty assumption, not measured accuracy.
         * Persistent heading bias needs additional modelling.
         */
        val displacementStd =
            max(0.15, length.toDouble() * 0.30)

        ekf.predictPedestrianStep(
            northMeters = north,
            eastMeters = east,
            dtSeconds = interval,
            displacementStdMeters = displacementStd
        )

        updateCoordinatesLocked()
        lastStateTimestampNs = sample.timestampNs

        lastDisplacementMeters = floatArrayOf(
            north.toFloat(),
            east.toFloat(),
            0f
        )

        /*
         * Do not road-snap pedestrian steps. Snapping can make
         * genuine small movements appear stationary.
         */
        trajectoryIntegrator.addPoint(
            TrajectoryPoint(
                latitude = currentLat,
                longitude = currentLon,
                altitude = originAltitude - ekf.state[2],
                speedMps = pedestrianSpeedLocked(sample.timestampNs),
                headingDeg = currentHeadingDeg,
                timestampNs = sample.timestampNs,
                isBlackout = true,
                isStationary = false
            )
        )
    }

    /**
     * Processes a timestamped IMU sample.
     *
     * The producer should emit one sample per new accelerometer
     * reading, paired with the latest appropriate gyro reading.
     * Do not independently repeat acceleration samples on gyro
     * callbacks. NavigationEngine will be updated accordingly.
     */
    fun addSensorSample(sample: ImuSample) {
        synchronized(stateLock) {
            if (!isEngineInitialized) return
            if (sample.timestampNs <= 0L) return

            if (!sample.ax.isFinite() ||
                !sample.ay.isFinite() ||
                !sample.az.isFinite() ||
                !sample.gx.isFinite() ||
                !sample.gy.isFinite() ||
                !sample.gz.isFinite()
            ) {
                return
            }

            if (lastSensorTimestampNs > 0L &&
                sample.timestampNs <= lastSensorTimestampNs
            ) {
                return
            }

            if (lastSensorTimestampNs > 0L &&
                sample.timestampNs - lastSensorTimestampNs >
                SAMPLE_GAP_RESET_NS
            ) {
                imuBuffer.reset()
                clearPdrLocked()
                zuptDetector.reset()
                zeroVelocityLocked()
            }

            lastSensorTimestampNs = sample.timestampNs
            lastStateTimestampNs = sample.timestampNs

            val phoneAcc =
                floatArrayOf(sample.ax, sample.ay, sample.az)

            val vehicleAcc =
                transformer.transformPhoneToVehicle(phoneAcc)

            val phoneGyro = floatArrayOf(
                sample.gx - zuptDetector.estimatedGyroBiasX,
                sample.gy - zuptDetector.estimatedGyroBiasY,
                sample.gz - zuptDetector.estimatedGyroBiasZ
            )

            val vehicleGyro =
                transformer.transformPhoneToVehicle(phoneGyro)

            val transformed = ImuSample(
                timestampNs = sample.timestampNs,
                ax = vehicleAcc[0],
                ay = vehicleAcc[1],
                az = vehicleAcc[2],
                gx = vehicleGyro[0],
                gy = vehicleGyro[1],
                gz = vehicleGyro[2]
            )

            val stationary = zuptDetector.update(
                transformed,
                if (isBlackoutMode) null else latestGnssSpeed
            )

            if (isPedestrianTrackingLocked()) {
                /*
                 * Sole owner of physical pedestrian displacement.
                 * No ML-window displacement or EKF ZUPT competes
                 * with the accepted step update.
                 */
                processPedestrianStepLocked(sample)

                if (pedestrianSpeedLocked(sample.timestampNs) == 0f) {
                    zeroVelocityLocked()
                }
            } else {
                if (stationary && zuptDetector.isEnabled) {
                    ekf.updateZupt()
                }

                imuBuffer.addSample(transformed)
            }

            val worldAcc = transformer.rotateLocalToWorld(
                vehicleAcc,
                currentHeadingDeg
            )

            naiveIntegrator.addSample(
                sample.timestampNs,
                worldAcc[0],
                worldAcc[1]
            )

            diagnosticRecorder.recordSample(
                sample = transformed,
                bufferSize = imuBuffer.size,
                headingDeg = currentHeadingDeg,
                isGnssAvailable =
                    latestGnssSpeed != null && !isBlackoutMode,
                isBlackout = isBlackoutMode,
                lastInferenceTimestampNs = lastInferenceTimestampNs,
                lastInferenceLatencyMs = lastInferenceLatencyMs,
                lastDisplacement = lastDisplacementMeters
            )
        }

        dispatchState()
    }

    fun updateOrientation(orientation: OrientationSample) {
        if (!orientation.headingDegrees.isFinite()) return

        synchronized(stateLock) {
            currentHeadingDeg =
                normalizedHeading(orientation.headingDegrees)

            currentPitchDeg = Math.toDegrees(
                orientation.pitchRad.toDouble()
            ).toFloat()

            currentRollDeg = Math.toDegrees(
                orientation.rollRad.toDouble()
            ).toFloat()

            val matrix = orientation.rotationMatrix
            if (matrix.size == 9 &&
                matrix.all { it.isFinite() }
            ) {
                latestOrientationMatrix = matrix.clone()
            }
        }
    }

    /**
     * Vehicle/general model window processing.
     * Pedestrian blackout never enters this path.
     */
    private fun processWindowInference(
        window: Array<FloatArray>,
        timestampNs: Long
    ) {
        synchronized(stateLock) {
            if (!isEngineInitialized) return
            if (isPedestrianTrackingLocked()) return
            if (window.isEmpty()) return
            if (window.any { row ->
                    row.size < 6 || row.any { !it.isFinite() }
                }
            ) {
                return
            }

            val startedNs = System.nanoTime()
            val stationary = zuptDetector.isNavStationary

            var maximumHorizontalAcceleration = 0f

            for (row in window) {
                val ax = row[0] * ModelMetadata.GRAVITY_MPS2
                val ay = row[1] * ModelMetadata.GRAVITY_MPS2
                val magnitude = sqrt(ax * ax + ay * ay)

                maximumHorizontalAcceleration =
                    max(maximumHorizontalAcceleration, magnitude)
            }

            val priorSpeed = sqrt(
                ekf.state[3] * ekf.state[3] +
                        ekf.state[4] * ekf.state[4]
            ).toFloat()

            val baseSpeed = if (isBlackoutMode) {
                if (blackoutEntryTimestampNs == 0L) {
                    blackoutEntryTimestampNs = timestampNs
                }

                val elapsed = max(
                    0.0,
                    (timestampNs - blackoutEntryTimestampNs) /
                            1_000_000_000.0
                ).toFloat()

                min(
                    blackoutEntrySpeedMps +
                            MAX_SPEED_CHANGE_MPS2 * elapsed,
                    MAX_PLAUSIBLE_SPEED_MPS
                )
            } else {
                max(priorSpeed, latestGnssSpeed ?: 0f)
            }

            val dt = ModelMetadata.STRIDE_DURATION_SEC.toDouble()
            val dtFloat = dt.toFloat()

            var action = GateAction.REJECTED
            var displacement = floatArrayOf(0f, 0f, 0f)

            if (!stationary && modelRunner.ready) {
                val prediction = try {
                    modelRunner.predict(window)
                } catch (_: Exception) {
                    null
                }

                if (prediction != null &&
                    prediction.size >= 3 &&
                    prediction.take(3).all { it.isFinite() }
                ) {
                    val magnitude = sqrt(
                        prediction[0] * prediction[0] +
                                prediction[1] * prediction[1] +
                                prediction[2] * prediction[2]
                    )

                    val effectiveAcceleration =
                        max(maximumHorizontalAcceleration, 0.20f)

                    val kinematicDistance =
                        baseSpeed * dtFloat +
                                0.5f * effectiveAcceleration *
                                dtFloat * dtFloat

                    val maximumDistance = kinematicDistance + 1.2f

                    when {
                        maximumHorizontalAcceleration < 0.35f &&
                                baseSpeed < 0.30f &&
                                magnitude > maximumDistance -> {
                            action = GateAction.REJECTED
                        }

                        magnitude > maximumDistance -> {
                            action = GateAction.CLAMPED
                            val scale = if (magnitude > 0.001f) {
                                (kinematicDistance + 0.15f) /
                                        magnitude
                            } else 0f

                            displacement = floatArrayOf(
                                prediction[0] * scale,
                                prediction[1] * scale,
                                prediction[2] * scale
                            )
                        }

                        else -> {
                            action = GateAction.ACCEPTED
                            displacement = floatArrayOf(
                                prediction[0],
                                prediction[1],
                                prediction[2]
                            )
                        }
                    }
                }
            }

            recordGateActionLocked(action)

            lastInferenceTimestampNs = timestampNs
            lastInferenceLatencyMs =
                (System.nanoTime() - startedNs) / 1_000_000f

            lastDisplacementMeters = displacement.clone()

            if (stationary || action == GateAction.REJECTED) {
                ekf.predict(doubleArrayOf(0.0, 0.0, 0.0), dt)

                /*
                 * A rejected ML result alone does not prove that
                 * the device is stationary.
                 */
                if (stationary && zuptDetector.isEnabled) {
                    ekf.updateZupt()
                }

                zeroVelocityLocked()
            } else {
                val matrix = latestOrientationMatrix

                val deltaNed = if (matrix != null &&
                    matrix.size == 9
                ) {
                    transformer.rotateLocalToWorldWithMatrix(
                        displacement,
                        matrix
                    )
                } else {
                    transformer.rotateLocalToWorld(
                        displacement,
                        currentHeadingDeg
                    )
                }

                if (deltaNed.take(3).all { it.isFinite() }) {
                    ekf.predict(
                        doubleArrayOf(
                            deltaNed[0].toDouble(),
                            deltaNed[1].toDouble(),
                            deltaNed[2].toDouble()
                        ),
                        dt
                    )

                    if (nhc.isEnabled &&
                        currentMotionMode == MotionMode.VEHICLE_MODE
                    ) {
                        nhc.applyConstraint(
                            ekf,
                            currentHeadingDeg,
                            currentPitchDeg
                        )
                    }
                }
            }

            updateCoordinatesLocked()

            val northVelocity = ekf.state[3]
            val eastVelocity = ekf.state[4]

            var speed = sqrt(
                northVelocity * northVelocity +
                        eastVelocity * eastVelocity
            ).toFloat()

            if (stationary || speed < 0.1f) {
                speed = 0f
            } else if (speed > 0.5f) {
                currentHeadingDeg = normalizedHeading(
                    Math.toDegrees(
                        atan2(eastVelocity, northVelocity)
                    ).toFloat()
                )
            }

            lastStateTimestampNs = timestampNs

            val rawPoint = TrajectoryPoint(
                latitude = currentLat,
                longitude = currentLon,
                altitude = originAltitude - ekf.state[2],
                speedMps = speed,
                headingDeg = currentHeadingDeg,
                timestampNs = timestampNs,
                isBlackout = isBlackoutMode,
                isStationary = stationary
            )

            trajectoryIntegrator.addPoint(
                mapMatcher.match(rawPoint)
            )
        }

        /*
         * addSensorSample() dispatches after window processing.
         * Avoid invoking external observers inside its processing lock.
         */
    }

    fun setBlackoutMode(
        enabled: Boolean,
        isPedestrianDemo: Boolean = false
    ) {
        synchronized(stateLock) {
            if (enabled && !isEngineInitialized) return

            if (enabled) {
                val requestedMode = if (isPedestrianDemo) {
                    MotionMode.CONSERVATIVE_MODE
                } else if (isBlackoutMode) {
                    currentMotionMode
                } else {
                    classifyMotionModeLocked()
                }

                if (isBlackoutMode &&
                    currentMotionMode == requestedMode
                ) {
                    return
                }

                blackoutEntrySpeedMps = latestGnssSpeed ?: 0f
                blackoutEntryTimestampNs = 0L
                latestGnssSpeed = null

                isBlackoutMode = true
                currentMotionMode = requestedMode

                imuBuffer.reset()
                clearPdrLocked()
                zuptDetector.reset()
                clearReanchoringLocked()

                if (isPedestrianTrackingLocked()) {
                    zeroVelocityLocked()
                }

                trajectoryIntegrator.startBlackout(
                    TrajectoryPoint(
                        latitude = currentLat,
                        longitude = currentLon,
                        altitude = originAltitude - ekf.state[2],
                        speedMps = if (isPedestrianTrackingLocked()) {
                            0f
                        } else {
                            blackoutEntrySpeedMps
                        },
                        headingDeg = currentHeadingDeg,
                        timestampNs = lastStateTimestampNs,
                        isBlackout = true,
                        isStationary = isPedestrianTrackingLocked()
                    )
                )
            } else {
                if (!isBlackoutMode) return

                isBlackoutMode = false
                blackoutEntrySpeedMps = 0f
                blackoutEntryTimestampNs = 0L
                currentMotionMode = MotionMode.VEHICLE_MODE

                imuBuffer.reset()
                clearPdrLocked()
                zeroVelocityLocked()
            }
        }

        dispatchState()
    }

    private fun recordGateActionLocked(action: GateAction) {
        currentGateAction = action

        when (action) {
            GateAction.ACCEPTED -> acceptedPredictionCount++
            GateAction.CLAMPED -> clampedPredictionCount++
            GateAction.REJECTED -> rejectedPredictionCount++
        }
    }

    fun recordGateAction(action: GateAction) {
        synchronized(stateLock) {
            recordGateActionLocked(action)
        }
    }

    fun startSmoothReanchoring(
        targetLat: Double,
        targetLon: Double
    ) {
        if (!validCoordinate(targetLat, targetLon)) return

        synchronized(stateLock) {
            if (!isEngineInitialized || isBlackoutMode) return

            val targetNorth =
                Math.toRadians(targetLat - originLat) *
                        CoordinateTransformer.MEAN_EARTH_RADIUS

            val targetEast =
                Math.toRadians(targetLon - originLon) *
                        CoordinateTransformer.MEAN_EARTH_RADIUS *
                        cos(Math.toRadians(originLat))

            reanchorRemainingN = targetNorth - ekf.state[0]
            reanchorRemainingE = targetEast - ekf.state[1]
            isSmoothReanchoringActive = true
        }
    }

    private fun stepSmoothReanchoringLocked(): Boolean {
        if (!isSmoothReanchoringActive) return false

        if (isBlackoutMode) {
            clearReanchoringLocked()
            return false
        }

        var correctionN = reanchorRemainingN * 0.25
        var correctionE = reanchorRemainingE * 0.25

        val correctionMagnitude = sqrt(
            correctionN * correctionN +
                    correctionE * correctionE
        )

        if (correctionMagnitude > 1.0) {
            correctionN /= correctionMagnitude
            correctionE /= correctionMagnitude
        }

        ekf.state[0] += correctionN
        ekf.state[1] += correctionE

        reanchorRemainingN -= correctionN
        reanchorRemainingE -= correctionE

        val remaining = sqrt(
            reanchorRemainingN * reanchorRemainingN +
                    reanchorRemainingE * reanchorRemainingE
        )

        if (remaining < 0.08) {
            ekf.state[0] += reanchorRemainingN
            ekf.state[1] += reanchorRemainingE
            clearReanchoringLocked()
        }

        updateCoordinatesLocked()

        trajectoryIntegrator.addPoint(
            TrajectoryPoint(
                latitude = currentLat,
                longitude = currentLon,
                altitude = originAltitude - ekf.state[2],
                speedMps = getState().speed,
                headingDeg = currentHeadingDeg,
                timestampNs = lastStateTimestampNs,
                isBlackout = false,
                isStationary = zuptDetector.isNavStationary
            ),
            isReanchoring = true
        )

        return isSmoothReanchoringActive
    }

    fun stepSmoothReanchoring(): Boolean {
        val active = synchronized(stateLock) {
            stepSmoothReanchoringLocked()
        }

        dispatchState()
        return active
    }

    /**
     * Legacy/manual update API.
     * Requires a previously initialized geographic anchor.
     */
    fun update(
        accelNorth: Float,
        accelEast: Float,
        headingDeg: Float,
        timestampNs: Long = System.nanoTime(),
        isStationary: Boolean = false
    ): NavigationEngineState {
        synchronized(stateLock) {
            if (!isEngineInitialized) return getState()

            if (!accelNorth.isFinite() ||
                !accelEast.isFinite() ||
                !headingDeg.isFinite()
            ) {
                return getState()
            }

            currentHeadingDeg = normalizedHeading(headingDeg)

            val dt = if (lastStateTimestampNs > 0L &&
                timestampNs > lastStateTimestampNs
            ) {
                (timestampNs - lastStateTimestampNs) /
                        1_000_000_000.0
            } else {
                0.01
            }

            lastStateTimestampNs = timestampNs

            if (isStationary) {
                zeroVelocityLocked()
                return getState()
            }

            val velocityN = ekf.state[3] + accelNorth * dt
            val velocityE = ekf.state[4] + accelEast * dt

            ekf.predict(
                doubleArrayOf(
                    velocityN * dt,
                    velocityE * dt,
                    0.0
                ),
                dt
            )

            updateCoordinatesLocked()
            addLegacyPointLocked(timestampNs)
            return getState()
        }
    }

    /**
     * Legacy/manual displacement API.
     * Requires a previously initialized geographic anchor.
     */
    fun updateWithMLDisplacement(
        globalEastMeters: Float,
        globalNorthMeters: Float,
        dtSeconds: Float,
        headingDeg: Float,
        isStationary: Boolean = false
    ): NavigationEngineState {
        synchronized(stateLock) {
            if (!isEngineInitialized) return getState()

            if (!globalEastMeters.isFinite() ||
                !globalNorthMeters.isFinite() ||
                !dtSeconds.isFinite() ||
                dtSeconds <= 0f ||
                !headingDeg.isFinite()
            ) {
                return getState()
            }

            currentHeadingDeg = normalizedHeading(headingDeg)

            if (isStationary) {
                zeroVelocityLocked()
                return getState()
            }

            ekf.predict(
                doubleArrayOf(
                    globalNorthMeters.toDouble(),
                    globalEastMeters.toDouble(),
                    0.0
                ),
                dtSeconds.toDouble()
            )

            updateCoordinatesLocked()
            lastStateTimestampNs = System.nanoTime()
            addLegacyPointLocked(lastStateTimestampNs)
            return getState()
        }
    }

    private fun addLegacyPointLocked(timestampNs: Long) {
        val speed = sqrt(
            ekf.state[3] * ekf.state[3] +
                    ekf.state[4] * ekf.state[4]
        ).toFloat()

        trajectoryIntegrator.addPoint(
            TrajectoryPoint(
                latitude = currentLat,
                longitude = currentLon,
                altitude = originAltitude - ekf.state[2],
                speedMps = speed,
                headingDeg = currentHeadingDeg,
                timestampNs = timestampNs,
                isBlackout = isBlackoutMode,
                isStationary = false
            )
        )
    }

    fun getState(): NavigationEngineState {
        synchronized(stateLock) {
            val pedestrian = isPedestrianTrackingLocked()

            val speedFromSteps =
                pedestrianSpeedLocked(lastSensorTimestampNs)

            val speedFromEkf = sqrt(
                ekf.state[3] * ekf.state[3] +
                        ekf.state[4] * ekf.state[4]
            ).toFloat()

            val stationary = when {
                speedFromSteps > 0f -> false
                speedFromEkf > 0.1f -> false
                pedestrian -> true
                else -> zuptDetector.isNavStationary
            }

            var speed = if (pedestrian && speedFromSteps > 0f) {
                speedFromSteps
            } else {
                speedFromEkf
            }

            if (stationary || speed < 0.1f) speed = 0f

            val motionState = if (pedestrian) {
                when {
                    speed > 0f -> "MOVING"
                    zuptDetector.motionState.name ==
                            "ROTATING_IN_PLACE" -> "ROTATING_IN_PLACE"
                    else -> "STATIONARY"
                }
            } else {
                zuptDetector.motionState.name
            }

            val naiveCoordinates = if (isEngineInitialized) {
                transformer.addMetricDisplacementToGeodetic(
                    originLat,
                    originLon,
                    naiveIntegrator.displacementNorth,
                    naiveIntegrator.displacementEast
                )
            } else {
                doubleArrayOf(0.0, 0.0)
            }

            val uncertainty = sqrt(
                max(0.0, ekf.P[0][0]) +
                        max(0.0, ekf.P[1][1])
            )

            return NavigationEngineState(
                latitude = currentLat,
                longitude = currentLon,
                altitude = originAltitude - ekf.state[2],
                speed = speed,
                heading = currentHeadingDeg,
                distanceTravelled =
                    trajectoryIntegrator.getTotalDistance(),
                isInitialized = isEngineInitialized,
                isStationary = stationary,
                motionState = motionState,
                isBlackout = isBlackoutMode,
                timestampNs = lastStateTimestampNs,
                latestGateAction = currentGateAction.name,
                acceptedCount = acceptedPredictionCount,
                clampedCount = clampedPredictionCount,
                rejectedCount = rejectedPredictionCount,
                naiveLatitude = naiveCoordinates[0],
                naiveLongitude = naiveCoordinates[1],
                uncertaintyRadiusMeters = uncertainty,
                motionMode = currentMotionMode.name,
                stepCount = pdrDetector.totalStepCount,
                latestStrideMeters = pdrDetector.latestStrideMeters
            )
        }
    }

    private fun dispatchState() {
        val snapshot = getState()
        onNavigationStateChanged?.invoke(snapshot)
    }

    fun reset() {
        synchronized(stateLock) {
            originLat = 0.0
            originLon = 0.0
            originAltitude = 0.0
            currentLat = 0.0
            currentLon = 0.0
            currentHeadingDeg = 0f
            currentPitchDeg = 0f
            currentRollDeg = 0f
            latestOrientationMatrix = null

            isBlackoutMode = false
            isEngineInitialized = false
            currentMotionMode = MotionMode.VEHICLE_MODE

            latestGnssSpeed = null
            lastSanitizedGnssSpeed = 0f
            lastGnssSpeedTimestampNs = 0L
            recentGnssSpeedHistory.clear()

            blackoutEntrySpeedMps = 0f
            blackoutEntryTimestampNs = 0L

            lastStateTimestampNs = 0L
            lastSensorTimestampNs = 0L
            lastInferenceTimestampNs = 0L
            lastInferenceLatencyMs = 0f
            lastDisplacementMeters = floatArrayOf(0f, 0f, 0f)

            currentGateAction = GateAction.ACCEPTED
            acceptedPredictionCount = 0
            clampedPredictionCount = 0
            rejectedPredictionCount = 0

            clearReanchoringLocked()
            clearPdrLocked()

            diagnosticRecorder.clear()
            imuBuffer.reset()
            zuptDetector.reset()
            ekf.reset()
            trajectoryIntegrator.reset()
            naiveIntegrator.reset()
        }

        dispatchState()
    }

    override fun close() {
        synchronized(stateLock) {
            onNavigationStateChanged = null
            modelRunner.close()
        }
    }
}