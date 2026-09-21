package com.example.gudumap.navigation

import android.content.Context
import android.location.Location
import android.location.LocationManager as AndroidLocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.util.Log
import com.example.gudumap.map.MapMatcher
import com.example.gudumap.sensor.ImuSample
import com.example.gudumap.sensor.OrientationSample
import com.example.gudumap.sensors.HeadingConfidence
import com.example.gudumap.sensors.LocationManager

import com.example.gudumap.sensors.SensorFusionManager
import com.example.gudumap.sensors.SensorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Coordinates real GPS, physical dead reckoning and software simulation.
 *
 * Real GPS:
 * Accepted GPS fixes update the estimator and the displayed position.
 *
 * Physical demo:
 * Real motion sensors update the pedestrian estimator.
 * GPS remains a separate reference and does not reset prediction.
 *
 * Normal GPS fallback:
 * Prediction starts automatically when GPS becomes unavailable,
 * provided the current session has a valid real anchor.
 *
 * Software simulation:
 * Only the simulation timer changes simulated coordinates.
 * Its speed/heading never enter the physical estimator.
 *
 * Threading:
 * All mutable engine state is protected by engineLock.
 */
class NavigationEngine(
    private val context: Context,
    private val sensorManager: SensorManager = SensorManager(context),
    private val sensorFusionManager: SensorFusionManager =
        SensorFusionManager(),
    private val locationManager: LocationManager =
        LocationManager(context),
    val deadReckoningEngine: DeadReckoningEngine =
        DeadReckoningEngine(),
    private val mapMatcher: MapMatcher = MapMatcher(context)
) {

    companion object {
        private const val TAG = "Gudumap:NavEngine"

        private const val MAX_GYRO_PAIR_AGE_NS = 250_000_000L
    }

    private val engineLock = Any()

    val isClosed: Boolean
        get() = synchronized(engineLock) { closed }

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(NavigationState())
    val state: StateFlow<NavigationState> = _state.asStateFlow()

    private val gpsFilter = GpsLocationFilter()

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager

    private var networkCallback:
            ConnectivityManager.NetworkCallback? = null

    private var statusJob: Job? = null
    private var kinematicDemoJob: Job? = null

    private var started = false
    private var paused = true
    private var closed = false

    private var isInternetAvailable = false

    private val gnssQualityEvaluator = GnssQualityEvaluator()
    private var lastEstimatorUpdateTimeMs = 0L
    private var lastMapMatchingTimeMs = 0L

    private var latestRawGnssLocation: Location? = null
    private var lastKnownValidGpsLocation: Location? = null
    private var latestFilteredGpsResult: FilteredGpsResult? = null

    private var latestLocationReceivedElapsedMs = 0L
    private var lastAnyGnssFixElapsedMs = 0L
    private var lastTrustedGnssFixElapsedMs = 0L
    private var acceptedGnssFixCount = 0
    private var rejectedGnssFixCount = 0
    private var hasReceivedRealGnssFix = false
    private var consecutiveFreshRecoveryFixes = 0

    fun calibratePhoneMount(pitchDeg: Float, rollDeg: Float, yawDeg: Float) {
        synchronized(engineLock) {
            sensorFusionManager.phoneMountCalibrator.calibrate(pitchDeg, rollDeg, yawDeg)
            sensorFusionManager.phoneMountCalibrator.saveToPreferences(context)
            emitStateLocked(force = true)
        }
    }

    fun calibratePhoneMountFromGravity(ax: Float, ay: Float, az: Float, headingDeg: Float = 0f) {
        synchronized(engineLock) {
            sensorFusionManager.phoneMountCalibrator.calibrateFromGravity(ax, ay, az, headingDeg)
            sensorFusionManager.phoneMountCalibrator.saveToPreferences(context)
            emitStateLocked(force = true)
        }
    }

    fun resetPhoneMountCalibration() {
        synchronized(engineLock) {
            sensorFusionManager.phoneMountCalibrator.resetCalibration(context)
            emitStateLocked(force = true)
        }
    }


    private fun logNavigationStateTransition(
        oldState: String,
        newState: String,
        reason: String,
        fixAgeMs: Long,
        accuracyMeters: Float,
        provider: String
    ) {
        Log.i(
            TAG,
            "NAV_STATE_TRANSITION: [$oldState] -> [$newState] | Reason: $reason | FixAge: ${fixAgeMs}ms | Accuracy: ${accuracyMeters}m | Provider: $provider"
        )
    }

    private var lastTrustedGpsLat: Double? = null
    private var lastTrustedGpsLon: Double? = null
    private var lastTrustedGpsAccuracyMeters: Float? = null
    private var lastTrustedGpsTimestampNs: Long? = null

    private val historicalLkMarkersList = mutableListOf<LkMarkerData>()
    private val predictionTrailSegmentsList = mutableListOf<MutableList<Pair<Double, Double>>>()

    private var blackoutActive = false
    private var autoTriggeredByGpsLoss = false
    private var isSimulatedBlackoutMode = false
    private var gnssNavMode = "GNSS_AVAILABLE"

    private var isDemoModeEnabled = false
    private var isKinematicDemoActive = false
    private var hasDemoStartAnchor = false

    private var simulatedLat = Double.NaN
    private var simulatedLon = Double.NaN
    private var simulatedSpeedKmh = 36f
    private var simulatedHeadingDeg = 0f
    private var simulatedDistanceMeters = 0.0

    /*
     * Incremented whenever a simulation starts or stops.
     * A cancelled job cannot publish an update into a newer session.
     */
    private var simulationGeneration = 0L

    private var physicalDemoDistanceOrigin = 0.0
    private var trackingGapNotice: String? = null

    private var lastGyroX = 0f
    private var lastGyroY = 0f
    private var lastGyroZ = 0f
    private var lastGyroTimestampNs = 0L

    private var lastStateEmitElapsedMs = 0L

    private val recordedGpxPoints = mutableListOf<GpxTrackPoint>()
    private val demoTrailPointsList =
        mutableListOf<Pair<Double, Double>>()
    private val demoTrailSegmentsList =
        mutableListOf<MutableList<Pair<Double, Double>>>()
    private val gpsTrailPointsList =
        mutableListOf<Pair<Double, Double>>()
    private val predictionTrailPointsList =
        mutableListOf<Pair<Double, Double>>()

    private var recordingActive = false
    private var recordingContainsDemo = false
    private var lastGpxRecordElapsedMs = 0L

    private var lastPredictedLat: Double? = null
    private var lastPredictedLon: Double? = null

    private var blackoutStartWallMs = 0L
    private var blackoutStartElapsedMs = 0L
    private var blackoutStartLat = 0.0
    private var blackoutStartLon = 0.0
    private var blackoutStartDist = 0.0

    private var blackoutAcceptedStart = 0
    private var blackoutClampedStart = 0
    private var blackoutRejectedStart = 0

    private var blackoutStationaryDurationSec = 0.0
    private var blackoutRotatingDurationSec = 0.0
    private var blackoutMaximumSpeedKmh = 0f
    private var blackoutSpeedSum = 0.0
    private var blackoutSpeedCount = 0

    private var storedBlackoutMetrics = BlackoutMetrics()

    private var smoothedSpeedKmh = 0f
    private var evaluationActive = false
    private var evaluationStartElapsedMs = 0L
    private val evaluationGroundTruthFixes = mutableListOf<Location>()
    private var evaluationJob: Job? = null
    private var evaluationTimeRemainingSec = 0

    val isKinematicDemoActiveRunning: Boolean
        get() = synchronized(engineLock) {
            isKinematicDemoActive
        }

    init {
        context.getSharedPreferences(
            "naviator_preferences",
            Context.MODE_PRIVATE
        ).edit()
            .remove("tactical_night_mode")
            .apply()

        deadReckoningEngine.mapMatcher =
            OsmRoadNetworkMapMatcher(mapMatcher)
    }

    private fun normalizeHeading(value: Float): Float {
        return ((value % 360f) + 360f) % 360f
    }

    private fun isValidMapCoordinate(
        latitude: Double,
        longitude: Double
    ): Boolean {
        return latitude.isFinite() &&
                longitude.isFinite() &&
                latitude in -90.0..90.0 &&
                longitude in -180.0..180.0 &&
                (latitude != 0.0 || longitude != 0.0)
    }

    fun start() {
        synchronized(engineLock) {
            if (closed || started) return

            started = true
            paused = false

            deadReckoningEngine.initializeAssets(context)

            startSensorsLocked()
            startLocationListeningLocked()
            registerNetworkCallbackLocked()

            statusJob = scope.launch {
                while (isActive) {
                    delay(NavigationConfig.STATUS_TICK_MS)

                    synchronized(engineLock) {
                        if (!paused && !closed) {
                            emitStateLocked(force = true)
                        }
                    }
                }
            }

            emitStateLocked(force = true)
        }
    }

    private fun startSensorsLocked() {
        sensorManager.startRotationVector { data ->
            synchronized(engineLock) {
                if (paused || closed) return@synchronized

                sensorFusionManager.updateRotationVector(
                    data.rotationMatrix,
                    data.timestampNs
                )

                val orientation =
                    sensorFusionManager.getOrientation()

                deadReckoningEngine.updateOrientation(
                    OrientationSample(
                        timestampNs = data.timestampNs,
                        rotationMatrix = data.rotationMatrix,
                        quaternion = floatArrayOf(0f, 0f, 0f, 1f),
                        azimuthRad = Math.toRadians(
                            orientation.heading.toDouble()
                        ).toFloat(),
                        pitchRad = Math.toRadians(
                            orientation.pitch.toDouble()
                        ).toFloat(),
                        rollRad = Math.toRadians(
                            orientation.roll.toDouble()
                        ).toFloat()
                    )
                )

                emitStateLocked()
            }
        }

        sensorManager.startGyroscope { data ->
            synchronized(engineLock) {
                if (paused || closed) return@synchronized

                sensorFusionManager.updateGyroscope(
                    data.x,
                    data.y,
                    data.z,
                    data.timestampNs
                )

                /*
                 * Cache gyro readings.
                 * Do not send another duplicate acceleration sample
                 * to the step detector for every gyro event.
                 */
                lastGyroX = data.x
                lastGyroY = data.y
                lastGyroZ = data.z
                lastGyroTimestampNs = data.timestampNs
            }
        }

        sensorManager.startAccelerometer { data ->
            synchronized(engineLock) {
                if (paused || closed) return@synchronized

                sensorFusionManager.updateAccelerometer(
                    data.x,
                    data.y,
                    data.z,
                    data.timestampNs
                )

                /*
                 * Check loss before processing this movement sample,
                 * so the physical estimator is in the correct mode.
                 */
                updateAutomaticFallbackLocked()

                if (!isKinematicDemoActive &&
                    deadReckoningEngine.isInitialized
                ) {
                    val gyroAge =
                        data.timestampNs - lastGyroTimestampNs

                    val usableGyro =
                        lastGyroTimestampNs > 0L &&
                                gyroAge in 0L..MAX_GYRO_PAIR_AGE_NS

                    deadReckoningEngine.addSensorSample(
                        ImuSample(
                            timestampNs = data.timestampNs,
                            ax = data.x,
                            ay = data.y,
                            az = data.z,
                            gx = if (usableGyro) lastGyroX else 0f,
                            gy = if (usableGyro) lastGyroY else 0f,
                            gz = if (usableGyro) lastGyroZ else 0f
                        )
                    )
                }

                emitStateLocked()
            }
        }

        sensorManager.startMagnetometer { data ->
            synchronized(engineLock) {
                if (paused || closed) return@synchronized

                sensorFusionManager.updateMagnetometer(
                    data.x,
                    data.y,
                    data.z,
                    data.timestampNs
                )

                emitStateLocked()
            }
        }
    }

    private fun startLocationListeningLocked() {
        if (locationManager.isListening()) return
        if (!locationManager.hasLocationPermission()) return

        locationManager.startLocationUpdates(
            onLocationChanged = { location ->
                synchronized(engineLock) {
                    if (!paused && !closed) {
                        onGnssLocationChangedLocked(location)
                    }
                }
            },
            onStatusChanged = {
                synchronized(engineLock) {
                    if (!paused && !closed) {
                        emitStateLocked(force = true)
                    }
                }
            }
        )
    }

    fun retryLocationUpdatesIfNeeded() {
        synchronized(engineLock) {
            if (closed || paused) return
            startLocationListeningLocked()
            emitStateLocked(force = true)
        }
    }

    fun pause() {
        synchronized(engineLock) {
            if (closed || paused) return

            if (isKinematicDemoActive) {
                stopKinematicDemoLocked()
            }

            paused = true

            sensorManager.stopAll()
            locationManager.stopLocationUpdates()

            lastGyroTimestampNs = 0L
            deadReckoningEngine.resetVelocitiesAndPdr()

            trackingGapNotice =
                "Tracking paused; movement while away was not recorded."
        }
    }

    fun resume() {
        synchronized(engineLock) {
            if (closed) return

            if (!started) {
                start()
                return
            }

            if (!paused) return

            paused = false

            deadReckoningEngine.resetVelocitiesAndPdr()
            startSensorsLocked()
            startLocationListeningLocked()
            emitStateLocked(force = true)
        }
    }

    private fun hasValidatedInternet(): Boolean {
        val manager = connectivityManager ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities =
            manager.getNetworkCapabilities(network) ?: return false

        return capabilities.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_INTERNET
        ) && capabilities.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_VALIDATED
        )
    }

    private fun registerNetworkCallbackLocked() {
        if (networkCallback != null) return

        isInternetAvailable = hasValidatedInternet()

        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    refreshConnectivity()
                }

                override fun onLost(network: Network) {
                    refreshConnectivity()
                }

                override fun onUnavailable() {
                    refreshConnectivity()
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    refreshConnectivity()
                }

                private fun refreshConnectivity() {
                    synchronized(engineLock) {
                        if (closed) return@synchronized

                        /*
                         * Losing one network does not necessarily mean
                         * that all internet connectivity has been lost.
                         */
                        isInternetAvailable = hasValidatedInternet()

                        if (!paused) {
                            emitStateLocked(force = true)
                        }
                    }
                }
            }

        try {
            val request = NetworkRequest.Builder()
                .addCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET
                )
                .build()

            connectivityManager?.registerNetworkCallback(
                request,
                callback
            )

            networkCallback = callback
        } catch (error: Exception) {
            Log.w(TAG, "Network monitoring unavailable", error)
        }
    }

    private fun locationAgeMsLocked(
        location: Location? = latestRawGnssLocation
    ): Long {
        if (location != null && location.elapsedRealtimeNanos > 0L) {
            val difference = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
            if (difference >= 0L) {
                return difference / 1_000_000L
            }
        }

        if (lastAnyGnssFixElapsedMs > 0L) {
            return (SystemClock.elapsedRealtime() - lastAnyGnssFixElapsedMs).coerceAtLeast(0L)
        }

        if (latestLocationReceivedElapsedMs > 0L) {
            return (SystemClock.elapsedRealtime() - latestLocationReceivedElapsedMs).coerceAtLeast(0L)
        }

        return Long.MAX_VALUE
    }

    private fun gpsUsableNowLocked(): Boolean {
        val location = latestRawGnssLocation ?: return false

        return hasReceivedRealGnssFix &&
                location.provider != "demo" &&
                locationManager.hasLocationPermission() &&
                locationManager.isLocationEnabled() &&
                locationAgeMsLocked(location) <= NavigationConfig.GPS_FRESHNESS_MS
    }

    private fun hasRealAnchorLocked(): Boolean {
        val anchor = lastKnownValidGpsLocation ?: return false

        return hasReceivedRealGnssFix &&
                anchor.provider != "demo" &&
                isValidMapCoordinate(anchor.latitude, anchor.longitude)
    }

    private fun isUsableLocationLocked(location: Location): Boolean {
        if (location.provider == "demo") return false

        if (!isValidMapCoordinate(
                location.latitude,
                location.longitude
            )
        ) {
            return false
        }

        if (location.hasAccuracy() &&
            (!location.accuracy.isFinite() ||
                    location.accuracy <= 0f ||
                    location.accuracy > 100f)
        ) {
            return false
        }

        if (location.elapsedRealtimeNanos > 0L) {
            val age =
                SystemClock.elapsedRealtimeNanos() -
                        location.elapsedRealtimeNanos

            if (age < 0L ||
                age > NavigationConfig.MAX_ACCEPTED_FIX_AGE_MS * 1_000_000L
            ) {
                return false
            }

            val previousTimestamp =
                latestRawGnssLocation?.elapsedRealtimeNanos ?: 0L

            if (previousTimestamp > 0L &&
                location.elapsedRealtimeNanos <= previousTimestamp
            ) {
                return false
            }
        }

        return true
    }

    private fun shouldAcceptLocationLocked(location: Location): Boolean {
        val previous = latestRawGnssLocation ?: return true

        if (locationAgeMsLocked(previous) > NavigationConfig.GPS_FRESHNESS_MS) {
            return true
        }

        val previousAccuracy = if (previous.hasAccuracy()) {
            previous.accuracy
        } else Float.MAX_VALUE

        val newAccuracy = if (location.hasAccuracy()) {
            location.accuracy
        } else Float.MAX_VALUE

        if (previous.provider == AndroidLocationManager.GPS_PROVIDER &&
            location.provider ==
            AndroidLocationManager.NETWORK_PROVIDER &&
            newAccuracy >= previousAccuracy * 0.75f
        ) {
            return false
        }

        return newAccuracy <= previousAccuracy + 15f ||
                location.provider == AndroidLocationManager.GPS_PROVIDER
    }

    private fun onGnssLocationChangedLocked(location: Location) {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        lastAnyGnssFixElapsedMs = nowElapsedMs

        if (!isValidMapCoordinate(location.latitude, location.longitude)) {
            rejectedGnssFixCount++
            return
        }

        latestRawGnssLocation = Location(location)

        if (!isUsableLocationLocked(location) || !shouldAcceptLocationLocked(location)) {
            rejectedGnssFixCount++
            emitStateLocked(force = true)
            return
        }

        val filtered = gpsFilter.processLocation(location)

        if (!filtered.isAccepted ||
            !isValidMapCoordinate(
                filtered.filteredLatitude,
                filtered.filteredLongitude
            )
        ) {
            rejectedGnssFixCount++
            emitStateLocked(force = true)
            return
        }

        acceptedGnssFixCount++
        lastTrustedGnssFixElapsedMs = nowElapsedMs
        latestFilteredGpsResult = filtered
        latestLocationReceivedElapsedMs = nowElapsedMs
        hasReceivedRealGnssFix = true

        /*
         * Preserve timestamp, accuracy and provider metadata.
         * Replace only the coordinates with the accepted filtered fix.
         */
        val trustedFix = Location(location).apply {
            latitude = filtered.filteredLatitude
            longitude = filtered.filteredLongitude
        }

        lastKnownValidGpsLocation = trustedFix
        lastTrustedGpsLat = trustedFix.latitude
        lastTrustedGpsLon = trustedFix.longitude
        lastTrustedGpsAccuracyMeters = if (trustedFix.hasAccuracy()) trustedFix.accuracy else 5.0f
        lastTrustedGpsTimestampNs = System.currentTimeMillis()

        if (isDemoModeEnabled) {
            /*
             * Physical demo and software simulation both keep live
             * fixes as separate references.
             */
            if (!isKinematicDemoActive && !hasDemoStartAnchor) {
                initializePhysicalDemoLocked(trustedFix)
            }

            emitStateLocked(force = true)
            return
        }

        val isFreshRecoveryFix = locationAgeMsLocked(trustedFix) <= NavigationConfig.GPS_FRESHNESS_MS &&
                (!trustedFix.hasAccuracy() || trustedFix.accuracy <= NavigationConfig.MAX_GOOD_ACCURACY_METERS)

        if (blackoutActive && autoTriggeredByGpsLoss) {
            if (isFreshRecoveryFix) {
                consecutiveFreshRecoveryFixes++
            } else {
                consecutiveFreshRecoveryFixes = 0
            }

            val isAccurateFix = trustedFix.hasAccuracy() && trustedFix.accuracy <= 15.0f
            val confirmRecovery = consecutiveFreshRecoveryFixes >= NavigationConfig.REQUIRED_RECOVERY_FIX_COUNT || isAccurateFix

            if (confirmRecovery) {
                finishBlackoutMetricsLocked(trustedFix)

                // Wire smooth re-anchoring to glide position smoothly to recovered GNSS fix (Priority 4)
                deadReckoningEngine.startSmoothReanchoring(trustedFix.latitude, trustedFix.longitude)

                blackoutActive = false
                autoTriggeredByGpsLoss = false
                isSimulatedBlackoutMode = false
                consecutiveFreshRecoveryFixes = 0

                deadReckoningEngine.setBlackoutMode(false)
                gnssNavMode = "GNSS_AVAILABLE"

                _state.update {
                    it.copy(
                        gnssRecovered = true,
                        recoveryDriftMeters =
                            storedBlackoutMetrics.positionErrorMeters,
                        recoveryErrorPercent =
                            storedBlackoutMetrics.driftPercentage
                    )
                }

                logNavigationStateTransition(
                    oldState = "GPS_FALLBACK",
                    newState = "LIVE",
                    reason = "GPS_RECOVERED_STABLE_FIX",
                    fixAgeMs = locationAgeMsLocked(trustedFix),
                    accuracyMeters = if (trustedFix.hasAccuracy()) trustedFix.accuracy else 0f,
                    provider = trustedFix.provider ?: "GPS"
                )
            }
        }

        if (evaluationActive) {
            evaluationGroundTruthFixes.add(Location(trustedFix))
            emitStateLocked(force = true)
            return
        }

        if (!blackoutActive) {
            deadReckoningEngine.correctWithGnss(trustedFix)
            trackingGapNotice = null
        }

        emitStateLocked(force = true)
    }

    private fun beginBlackoutMetricsLocked(
        latitude: Double,
        longitude: Double
    ) {
        blackoutStartWallMs = System.currentTimeMillis()
        blackoutStartElapsedMs = SystemClock.elapsedRealtime()
        blackoutStartLat = latitude
        blackoutStartLon = longitude
        blackoutStartDist = deadReckoningEngine.distanceTravelled

        blackoutAcceptedStart = deadReckoningEngine.acceptedCount
        blackoutClampedStart = deadReckoningEngine.clampedCount
        blackoutRejectedStart = deadReckoningEngine.rejectedCount

        blackoutStationaryDurationSec = 0.0
        blackoutRotatingDurationSec = 0.0
        blackoutMaximumSpeedKmh = 0f
        blackoutSpeedSum = 0.0
        blackoutSpeedCount = 0

        storedBlackoutMetrics = BlackoutMetrics(
            blackoutStartTime = blackoutStartWallMs,
            blackoutStartLatitude = latitude,
            blackoutStartLongitude = longitude,
            drLatitude = latitude,
            drLongitude = longitude
        )
    }

    private fun finishBlackoutMetricsLocked(recovery: Location?) {
        val dr = deadReckoningEngine.getState()

        val duration = if (blackoutStartElapsedMs > 0L) {
            (
                    SystemClock.elapsedRealtime() -
                            blackoutStartElapsedMs
                    ) / 1000.0
        } else 0.0

        val distance =
            max(0.0, dr.distanceTravelled - blackoutStartDist)

        val referenceDistance = if (recovery != null) {
            CoordinateTransformer.computeDistanceBetween(
                blackoutStartLat,
                blackoutStartLon,
                recovery.latitude,
                recovery.longitude
            )
        } else 0.0

        val error = if (recovery != null) {
            CoordinateTransformer.computeDistanceBetween(
                dr.latitude,
                dr.longitude,
                recovery.latitude,
                recovery.longitude
            )
        } else 0.0

        storedBlackoutMetrics = BlackoutMetrics(
            blackoutStartTime = blackoutStartWallMs,
            blackoutEndTime = System.currentTimeMillis(),
            blackoutDurationSeconds = max(0.0, duration),
            blackoutStartLatitude = blackoutStartLat,
            blackoutStartLongitude = blackoutStartLon,
            blackoutEndGnssLatitude = recovery?.latitude ?: dr.latitude,
            blackoutEndGnssLongitude = recovery?.longitude ?: dr.longitude,
            drLatitude = dr.latitude,
            drLongitude = dr.longitude,
            gnssReferenceDistance = referenceDistance,
            drDistance = distance,
            positionErrorMeters = error,
            distanceErrorMeters = if (recovery != null) {
                abs(distance - referenceDistance)
            } else 0.0,
            driftPercentage = if (recovery != null && distance > 1.0) {
                error / distance * 100.0
            } else 0.0,
            maximumPositionErrorMeters = error,
            maximumSpeedKmh = blackoutMaximumSpeedKmh,
            averageSpeedKmh = if (blackoutSpeedCount > 0) {
                (blackoutSpeedSum / blackoutSpeedCount).toFloat()
            } else 0f,
            mlInferenceMs = deadReckoningEngine.lastInferenceLatency.toLong(),
            numberOfAcceptedMLPredictions =
                max(0, dr.acceptedCount - blackoutAcceptedStart),
            numberOfClampedMLPredictions =
                max(0, dr.clampedCount - blackoutClampedStart),
            numberOfRejectedMLPredictions =
                max(0, dr.rejectedCount - blackoutRejectedStart),
            stationaryDuration = blackoutStationaryDurationSec,
            rotatingInPlaceDuration = blackoutRotatingDurationSec
        )
    }

    private fun enterNormalFallbackLocked(
        automatic: Boolean,
        simulated: Boolean = false,
        transitionReason: String = "AUTOMATIC_LOSS"
    ): Boolean {
        if (isDemoModeEnabled || isKinematicDemoActive) return false
        if (!hasRealAnchorLocked()) return false

        if (blackoutActive) {
            if (!automatic) autoTriggeredByGpsLoss = false
            return true
        }

        val anchor = lastKnownValidGpsLocation ?: return false

        lastTrustedGpsLat = anchor.latitude
        lastTrustedGpsLon = anchor.longitude
        lastTrustedGpsAccuracyMeters = if (anchor.hasAccuracy()) anchor.accuracy else 5.0f
        lastTrustedGpsTimestampNs = System.currentTimeMillis()

        val lkMarker = LkMarkerData(
            id = "lk_single",
            latitude = anchor.latitude,
            longitude = anchor.longitude,
            timestampNs = System.currentTimeMillis(),
            accuracyMeters = if (anchor.hasAccuracy()) anchor.accuracy else 5.0f
        )
        historicalLkMarkersList.clear() // Priority 10: At most ONE Last Known GNSS marker
        historicalLkMarkersList.add(lkMarker)

        /*
         * Preserve the estimator's current position and speed history.
         * Reinitializing every transition would clear those histories.
         */
        if (!deadReckoningEngine.isInitialized) {
            deadReckoningEngine.initialize(anchor)
        }

        val dr = deadReckoningEngine.getState()
        if (!isValidMapCoordinate(dr.latitude, dr.longitude)) {
            return false
        }

        deadReckoningEngine.setBlackoutMode(true)

        blackoutActive = true
        autoTriggeredByGpsLoss = automatic
        isSimulatedBlackoutMode = simulated
        gnssNavMode = "GNSS_BLACKOUT"
        consecutiveFreshRecoveryFixes = 0

        val newSegment = mutableListOf<Pair<Double, Double>>(dr.latitude to dr.longitude)
        predictionTrailSegmentsList.add(newSegment)

        predictionTrailPointsList.clear()
        predictionTrailPointsList.add(dr.latitude to dr.longitude)

        beginBlackoutMetricsLocked(dr.latitude, dr.longitude)

        _state.update { it.copy(gnssRecovered = false) }

        logNavigationStateTransition(
            oldState = "LIVE",
            newState = "GPS_FALLBACK",
            reason = transitionReason,
            fixAgeMs = locationAgeMsLocked(anchor),
            accuracyMeters = if (anchor.hasAccuracy()) anchor.accuracy else 0f,
            provider = anchor.provider ?: "GPS"
        )

        return true
    }

    fun setBlackoutMode(
        enabled: Boolean,
        isAutomatic: Boolean = false,
        isSimulated: Boolean = false
    ) {
        synchronized(engineLock) {
            if (closed) return

            if (enabled) {
                if (!isDemoModeEnabled) {
                    enterNormalFallbackLocked(
                        automatic = isAutomatic,
                        simulated = isSimulated
                    )
                }
            } else if (!isDemoModeEnabled && blackoutActive) {
                if (gpsUsableNowLocked()) {
                    val anchor = lastKnownValidGpsLocation

                    finishBlackoutMetricsLocked(anchor)
                    blackoutActive = false
                    autoTriggeredByGpsLoss = false
                    isSimulatedBlackoutMode = false
                    gnssNavMode = "GNSS_AVAILABLE"

                    deadReckoningEngine.setBlackoutMode(false)

                    if (anchor != null) {
                        deadReckoningEngine.correctWithGnss(anchor)
                    }
                } else {
                    /*
                     * Ending a manual blackout must not turn off
                     * essential prediction while GPS is still absent.
                     */
                    autoTriggeredByGpsLoss = true
                    isSimulatedBlackoutMode = false
                }
            }

            emitStateLocked(force = true)
        }
    }

    fun toggleBlackout() {
        synchronized(engineLock) {
            setBlackoutMode(!blackoutActive)
        }
    }

    private fun initializePhysicalDemoLocked(anchor: Location) {
        if (!isValidMapCoordinate(anchor.latitude, anchor.longitude)) {
            hasDemoStartAnchor = false
            return
        }

        deadReckoningEngine.setBlackoutMode(false)

        val deviceHeading = sensorFusionManager.getOrientation().heading

        deadReckoningEngine.initialize(
            latitude = anchor.latitude,
            longitude = anchor.longitude,
            speedMps = 0f,
            bearingDeg = if (deviceHeading.isFinite()) {
                normalizeHeading(deviceHeading)
            } else 0f,
            altitude = if (anchor.hasAltitude()) anchor.altitude else 0.0
        )

        deadReckoningEngine.setBlackoutMode(
            enabled = true,
            isPedestrianDemo = true
        )

        hasDemoStartAnchor = true
        blackoutActive = true
        autoTriggeredByGpsLoss = false
        isSimulatedBlackoutMode = true
        gnssNavMode = "GNSS_BLACKOUT"

        physicalDemoDistanceOrigin =
            deadReckoningEngine.distanceTravelled

        demoTrailPointsList.clear()
        demoTrailPointsList.add(anchor.latitude to anchor.longitude)

        simulatedLat = anchor.latitude
        simulatedLon = anchor.longitude

        beginBlackoutMetricsLocked(anchor.latitude, anchor.longitude)
    }

    fun setDemoModeEnabled(enabled: Boolean) {
        synchronized(engineLock) {
            if (closed || isDemoModeEnabled == enabled) return

            if (enabled) {
                isDemoModeEnabled = true
                cancelSimulationLocked()

                autoTriggeredByGpsLoss = false

                val dr = deadReckoningEngine.getState()

                if (hasRealAnchorLocked() &&
                    dr.isInitialized &&
                    isValidMapCoordinate(dr.latitude, dr.longitude)
                ) {
                    /*
                     * Continue from the real estimator, not a prior
                     * software simulation endpoint.
                     */
                    val physicalAnchor =
                        Location(lastKnownValidGpsLocation!!).apply {
                            latitude = dr.latitude
                            longitude = dr.longitude
                        }

                    initializePhysicalDemoLocked(physicalAnchor)
                } else {
                    hasDemoStartAnchor = false
                    blackoutActive = false
                    deadReckoningEngine.setBlackoutMode(false)
                }
            } else {
                cancelSimulationLocked()
                isDemoModeEnabled = false
                hasDemoStartAnchor = false
                isSimulatedBlackoutMode = false
                autoTriggeredByGpsLoss = false

                if (gpsUsableNowLocked()) {
                    blackoutActive = false
                    gnssNavMode = "GNSS_AVAILABLE"
                    deadReckoningEngine.setBlackoutMode(false)

                    lastKnownValidGpsLocation?.let {
                        deadReckoningEngine.correctWithGnss(it)
                    }
                    trackingGapNotice = null
                } else if (
                    hasRealAnchorLocked() &&
                    deadReckoningEngine.isInitialized
                ) {
                    /*
                     * Preserve the physical estimate; do not jump
                     * backward to an older GPS anchor.
                     */
                    blackoutActive = true
                    autoTriggeredByGpsLoss = true
                    gnssNavMode = "GNSS_BLACKOUT"

                    deadReckoningEngine.setBlackoutMode(true)

                    val dr = deadReckoningEngine.getState()
                    predictionTrailPointsList.clear()
                    predictionTrailPointsList.add(
                        dr.latitude to dr.longitude
                    )
                    beginBlackoutMetricsLocked(
                        dr.latitude,
                        dr.longitude
                    )
                } else {
                    blackoutActive = false
                    gnssNavMode = "GNSS_AVAILABLE"
                    deadReckoningEngine.setBlackoutMode(false)
                }
            }

            emitStateLocked(force = true)
        }
    }

    /**
     * A physical demo requires a real anchor.
     * Camera centre is deliberately not used as a real starting fix.
     */
    fun resolveDemoStartPosition(
        currentMapCenter: Pair<Double, Double>? = null
    ): Pair<Double, Double>? {
        synchronized(engineLock) {
            if (!hasRealAnchorLocked()) return null

            val dr = deadReckoningEngine.getState()
            if (dr.isInitialized &&
                isValidMapCoordinate(dr.latitude, dr.longitude)
            ) {
                return dr.latitude to dr.longitude
            }

            val anchor = lastKnownValidGpsLocation ?: return null
            return anchor.latitude to anchor.longitude
        }
    }

    fun startKinematicDemo(
        startLat: Double = Double.NaN,
        startLon: Double = Double.NaN,
        initialSpeedKmh: Float = 36f,
        initialHeadingDeg: Float = 0f
    ) {
        synchronized(engineLock) {
            if (closed || paused) return

            if (!isDemoModeEnabled) {
                setDemoModeEnabled(true)
            }

            val startingPosition =
                if (isValidMapCoordinate(startLat, startLon)) {
                    startLat to startLon
                } else if (
                    hasDemoStartAnchor &&
                    isValidMapCoordinate(simulatedLat, simulatedLon)
                ) {
                    simulatedLat to simulatedLon
                } else {
                    resolveDemoStartPosition()
                }

            if (startingPosition == null) {
                hasDemoStartAnchor = false
                emitStateLocked(force = true)
                return
            }

            cancelSimulationLocked()

            simulatedLat = startingPosition.first
            simulatedLon = startingPosition.second

            simulatedSpeedKmh = if (initialSpeedKmh.isFinite()) {
                initialSpeedKmh.coerceAtLeast(0f)
            } else 0f

            simulatedHeadingDeg =
                if (initialHeadingDeg.isFinite()) {
                    normalizeHeading(initialHeadingDeg)
                } else 0f

            simulatedDistanceMeters = 0.0
            hasDemoStartAnchor = true
            isKinematicDemoActive = true

            blackoutActive = true
            isSimulatedBlackoutMode = true
            autoTriggeredByGpsLoss = false
            gnssNavMode = "GNSS_BLACKOUT"

            /*
             * The physical estimator is not initialized with software
             * coordinates and receives no acceleration during simulation.
             */
            deadReckoningEngine.resetVelocitiesAndPdr()

            val newStartPt = simulatedLat to simulatedLon
            if (demoTrailSegmentsList.isEmpty()) {
                demoTrailSegmentsList.add(mutableListOf(newStartPt))
            } else {
                val lastSeg = demoTrailSegmentsList.last()
                val lastPt = lastSeg.lastOrNull()
                if (lastPt != null) {
                    val gap = CoordinateTransformer.computeDistanceBetween(
                        lastPt.first, lastPt.second,
                        newStartPt.first, newStartPt.second
                    )
                    if (gap > 5.0) {
                        demoTrailSegmentsList.add(mutableListOf(newStartPt))
                    } else if (lastPt != newStartPt) {
                        lastSeg.add(newStartPt)
                    }
                } else {
                    lastSeg.add(newStartPt)
                }
            }

            if (demoTrailPointsList.isEmpty()) {
                demoTrailPointsList.add(newStartPt)
            } else if (demoTrailPointsList.lastOrNull() != newStartPt) {
                demoTrailPointsList.add(newStartPt)
            }

            val generation = simulationGeneration
            var lastTickNs = SystemClock.elapsedRealtimeNanos()

            kinematicDemoJob = scope.launch {
                while (isActive) {
                    delay(100L)

                    synchronized(engineLock) {
                        if (closed || paused ||
                            !isKinematicDemoActive ||
                            generation != simulationGeneration
                        ) {
                            return@launch
                        }

                        val nowNs = SystemClock.elapsedRealtimeNanos()
                        val dt =
                            (nowNs - lastTickNs) / 1_000_000_000.0

                        lastTickNs = nowNs

                        /*
                         * Do not fast-forward through an execution gap.
                         */
                        if (dt > 0.0 && dt <= 1.0) {
                            stepKinematicDemoLocked(dt)
                        }
                    }
                }
            }

            emitStateLocked(force = true)
        }
    }

    private fun cancelSimulationLocked() {
        simulationGeneration++
        isKinematicDemoActive = false
        kinematicDemoJob?.cancel()
        kinematicDemoJob = null
    }

    private fun stepKinematicDemoLocked(dtSec: Double) {
        if (!isDemoModeEnabled || !isKinematicDemoActive) return
        if (!dtSec.isFinite() || dtSec <= 0.0) return

        val distance =
            (simulatedSpeedKmh.toDouble() / 3.6) * dtSec

        if (distance > 0.0 && distance.isFinite()) {
            val radians =
                Math.toRadians(simulatedHeadingDeg.toDouble())

            val north = distance * cos(radians)
            val east = distance * sin(radians)

            val coordinates =
                CoordinateTransformer.addMetricDisplacementToGeodetic(
                    simulatedLat,
                    simulatedLon,
                    north,
                    east
                )

            if (isValidMapCoordinate(coordinates[0], coordinates[1])) {
                simulatedLat = coordinates[0]
                simulatedLon = coordinates[1]
                simulatedDistanceMeters += distance

                appendTrailLocked(
                    demoTrailPointsList,
                    simulatedLat,
                    simulatedLon
                )
            }
        }

        emitStateLocked(force = true)
    }

    fun stepKinematicDemo(dtSec: Double) {
        synchronized(engineLock) {
            stepKinematicDemoLocked(dtSec)
        }
    }

    fun updateKinematicDemoControls(
        speedKmh: Float,
        headingDeg: Float
    ) {
        synchronized(engineLock) {
            if (speedKmh.isFinite()) {
                simulatedSpeedKmh = speedKmh.coerceAtLeast(0f)
            }

            if (headingDeg.isFinite()) {
                simulatedHeadingDeg = normalizeHeading(headingDeg)
            }

            emitStateLocked(force = true)
        }
    }

    private fun stopKinematicDemoLocked() {
        cancelSimulationLocked()
        isDemoModeEnabled = false
        hasDemoStartAnchor = false
        isSimulatedBlackoutMode = false

        if (gpsUsableNowLocked()) {
            blackoutActive = false
            gnssNavMode = "GNSS_AVAILABLE"
            deadReckoningEngine.setBlackoutMode(false)
            lastKnownValidGpsLocation?.let {
                deadReckoningEngine.correctWithGnss(it)
            }
        } else if (hasRealAnchorLocked() && deadReckoningEngine.isInitialized) {
            blackoutActive = true
            autoTriggeredByGpsLoss = true
            gnssNavMode = "GNSS_BLACKOUT"
            deadReckoningEngine.setBlackoutMode(true)
        } else {
            blackoutActive = false
            gnssNavMode = "GNSS_AVAILABLE"
            deadReckoningEngine.setBlackoutMode(false)
        }
    }

    fun stopKinematicDemo() {
        synchronized(engineLock) {
            stopKinematicDemoLocked()
            emitStateLocked(force = true)
        }
    }

    fun resetKinematicDemoAnchor(
        lat: Double = Double.NaN,
        lon: Double = Double.NaN
    ) {
        synchronized(engineLock) {
            cancelSimulationLocked()

            simulatedSpeedKmh = 36f
            simulatedHeadingDeg = 0f
            simulatedDistanceMeters = 0.0
            demoTrailPointsList.clear()
            demoTrailSegmentsList.clear()

            val resetPos = if (isValidMapCoordinate(lat, lon)) {
                lat to lon
            } else {
                resolveDemoStartPosition()
            }

            if (resetPos != null && isValidMapCoordinate(resetPos.first, resetPos.second)) {
                simulatedLat = resetPos.first
                simulatedLon = resetPos.second
                demoTrailPointsList.add(simulatedLat to simulatedLon)
                demoTrailSegmentsList.add(mutableListOf(simulatedLat to simulatedLon))

                val resetLocation = (lastKnownValidGpsLocation ?: Location(AndroidLocationManager.GPS_PROVIDER)).apply {
                    latitude = simulatedLat
                    longitude = simulatedLon
                }
                initializePhysicalDemoLocked(resetLocation)
            } else {
                hasDemoStartAnchor = false
                blackoutActive = false
                deadReckoningEngine.setBlackoutMode(false)
            }

            emitStateLocked(force = true)
        }
    }

    private fun appendTrailLocked(
        trail: MutableList<Pair<Double, Double>>,
        latitude: Double,
        longitude: Double
    ) {
        if (!isValidMapCoordinate(latitude, longitude)) return

        val previous = trail.lastOrNull()

        if (previous != null) {
            val distance =
                CoordinateTransformer.computeDistanceBetween(
                    previous.first,
                    previous.second,
                    latitude,
                    longitude
                )

            if (!distance.isFinite() ||
                distance < NavigationConfig.TRAIL_MIN_DISTANCE_METERS
            ) {
                return
            }
        }

        trail.add(latitude to longitude)

        if (trail === demoTrailPointsList) {
            if (demoTrailSegmentsList.isEmpty()) {
                demoTrailSegmentsList.add(mutableListOf(latitude to longitude))
            } else {
                val activeSegment = demoTrailSegmentsList.last()
                if (activeSegment.lastOrNull() != (latitude to longitude)) {
                    activeSegment.add(latitude to longitude)
                    if (activeSegment.size > NavigationConfig.MAX_TRAIL_POINTS) {
                        activeSegment.removeAt(0)
                    }
                }
            }
        }

        if (trail === predictionTrailPointsList && predictionTrailSegmentsList.isNotEmpty()) {
            val activeSegment = predictionTrailSegmentsList.last()
            activeSegment.add(latitude to longitude)
            if (activeSegment.size > NavigationConfig.MAX_TRAIL_POINTS) {
                activeSegment.removeAt(0)
            }
        }

        if (trail.size > NavigationConfig.MAX_TRAIL_POINTS) {
            trail.removeAt(0)
        }
    }

    private fun updateAutomaticFallbackLocked() {
        if (paused || closed ||
            isDemoModeEnabled || isKinematicDemoActive
        ) {
            return
        }

        val nowElapsedMs = SystemClock.elapsedRealtime()
        val anyFixAgeMs = if (lastAnyGnssFixElapsedMs > 0L) (nowElapsedMs - lastAnyGnssFixElapsedMs) else Long.MAX_VALUE
        val isLocEnabled = locationManager.isLocationEnabled()
        val hasPermission = locationManager.hasLocationPermission()

        val immediateLossNeeded = (!isLocEnabled || !hasPermission) && hasRealAnchorLocked()
        val sustainedLossNeeded = hasRealAnchorLocked() && anyFixAgeMs >= NavigationConfig.GPS_SUSTAINED_BLACKOUT_TIMEOUT_MS

        if ((immediateLossNeeded || sustainedLossNeeded) && !blackoutActive) {
            val reason = when {
                !hasPermission -> "LOCATION_PERMISSION_REVOKED"
                !isLocEnabled -> "LOCATION_SERVICES_DISABLED"
                else -> "SUSTAINED_GPS_LOSS_${anyFixAgeMs}MS"
            }
            enterNormalFallbackLocked(automatic = true, transitionReason = reason)
        }
    }

    private fun resolvedGpsStateLocked(): GpsState {
        val isLocEnabled = locationManager.isLocationEnabled()
        val hasPermission = locationManager.hasLocationPermission()

        if (!hasPermission) return GpsState.PERMISSION_REQUIRED
        if (!isLocEnabled) return GpsState.GPS_DISABLED
        if (!hasReceivedRealGnssFix) return GpsState.SEARCHING

        val nowElapsedMs = SystemClock.elapsedRealtime()
        val anyFixAgeMs = if (lastAnyGnssFixElapsedMs > 0L) (nowElapsedMs - lastAnyGnssFixElapsedMs) else Long.MAX_VALUE
        val trustedFixAgeMs = if (lastTrustedGnssFixElapsedMs > 0L) (nowElapsedMs - lastTrustedGnssFixElapsedMs) else Long.MAX_VALUE
        val accuracy = latestFilteredGpsResult?.accuracyMeters ?: (latestRawGnssLocation?.accuracy ?: 50f)

        return when {
            blackoutActive -> GpsState.LOST
            anyFixAgeMs >= NavigationConfig.GPS_SUSTAINED_BLACKOUT_TIMEOUT_MS -> GpsState.LOST
            trustedFixAgeMs >= NavigationConfig.GPS_DEGRADED_TIMEOUT_MS || accuracy > NavigationConfig.MAX_GOOD_ACCURACY_METERS -> GpsState.STALE
            consecutiveFreshRecoveryFixes in 1 until NavigationConfig.REQUIRED_RECOVERY_FIX_COUNT -> GpsState.ACQUIRING
            else -> GpsState.FIXED
        }
    }

    private fun emitStateLocked(force: Boolean = false) {
        if (closed) return

        val nowElapsed = SystemClock.elapsedRealtime()

        if (!force &&
            nowElapsed - lastStateEmitElapsedMs < NavigationConfig.STATE_INTERVAL_MS
        ) {
            return
        }

        val elapsedSinceEmission = if (lastStateEmitElapsedMs > 0L) {
            (
                    (nowElapsed - lastStateEmitElapsedMs) / 1000.0
                    ).coerceIn(0.0, 1.0)
        } else 0.0

        lastStateEmitElapsedMs = nowElapsed

        updateAutomaticFallbackLocked()

        if (deadReckoningEngine.isReanchoring) {
            deadReckoningEngine.stepSmoothReanchoring()
        }

        /*
         * Read the estimator AFTER mode transitions.
         * Do not publish a snapshot captured before initialization.
         */
        val dr = deadReckoningEngine.getState()
        val gpsState = resolvedGpsStateLocked()
        val gpsFresh = gpsUsableNowLocked()
        val ageMs = locationAgeMsLocked()

        val physicalPrediction =
            !isKinematicDemoActive &&
                    (isDemoModeEnabled || blackoutActive) &&
                    dr.isInitialized &&
                    hasRealAnchorLocked() &&
                    (!isDemoModeEnabled || hasDemoStartAnchor)

        val positionAvailable = when {
            isKinematicDemoActive ->
                isValidMapCoordinate(simulatedLat, simulatedLon)

            physicalPrediction ->
                isValidMapCoordinate(dr.latitude, dr.longitude)

            gpsFresh ->
                latestFilteredGpsResult != null

            else ->
                lastKnownValidGpsLocation != null ||
                (latestRawGnssLocation != null && isValidMapCoordinate(latestRawGnssLocation!!.latitude, latestRawGnssLocation!!.longitude)) ||
                isValidMapCoordinate(dr.latitude, dr.longitude)
        }

        var latitude = 0.0
        var longitude = 0.0
        var speedKmh = 0f
        var distanceMeters = 0.0

        val deviceHeadingRaw =
            sensorFusionManager.getOrientation().heading

        val deviceHeading = if (deviceHeadingRaw.isFinite()) {
            normalizeHeading(deviceHeadingRaw)
        } else {
            normalizeHeading(dr.heading)
        }

        var heading = deviceHeading

        when {
            isKinematicDemoActive -> {
                latitude = simulatedLat
                longitude = simulatedLon
                speedKmh = simulatedSpeedKmh
                heading = simulatedHeadingDeg
                distanceMeters = simulatedDistanceMeters
            }

            physicalPrediction -> {
                latitude = dr.latitude
                longitude = dr.longitude
                speedKmh = dr.speed * 3.6f

                heading = if (dr.speed < 0.1f) {
                    deviceHeading
                } else {
                    normalizeHeading(dr.heading)
                }

                distanceMeters = if (isDemoModeEnabled) {
                    max(
                        0.0,
                        dr.distanceTravelled -
                                physicalDemoDistanceOrigin
                    )
                } else {
                    dr.distanceTravelled
                }

                if (isDemoModeEnabled) {
                    /*
                     * Prepare the next software run from the current
                     * physical position without changing its speed.
                     */
                    simulatedLat = latitude
                    simulatedLon = longitude
                }
            }

            gpsFresh -> {
                val filtered = latestFilteredGpsResult!!

                latitude = filtered.filteredLatitude
                longitude = filtered.filteredLongitude
                speedKmh = filtered.speedKmh

                heading = if (speedKmh < 1f) {
                    deviceHeading
                } else {
                    normalizeHeading(filtered.filteredHeadingDeg)
                }

                distanceMeters = dr.distanceTravelled
            }

            else -> {
                val fallbackPos = when {
                    lastKnownValidGpsLocation != null -> lastKnownValidGpsLocation!!.latitude to lastKnownValidGpsLocation!!.longitude
                    latestRawGnssLocation != null && isValidMapCoordinate(latestRawGnssLocation!!.latitude, latestRawGnssLocation!!.longitude) -> latestRawGnssLocation!!.latitude to latestRawGnssLocation!!.longitude
                    isValidMapCoordinate(dr.latitude, dr.longitude) -> dr.latitude to dr.longitude
                    else -> 0.0 to 0.0
                }
                latitude = fallbackPos.first
                longitude = fallbackPos.second
                heading = deviceHeading
                distanceMeters = dr.distanceTravelled
            }
        }

        if (!speedKmh.isFinite() || speedKmh < 0f) {
            speedKmh = 0f
        }

        val rawSpeedKmh = speedKmh
        smoothedSpeedKmh = NavigationConfig.SPEEDOMETER_ALPHA * rawSpeedKmh + (1f - NavigationConfig.SPEEDOMETER_ALPHA) * smoothedSpeedKmh
        if (smoothedSpeedKmh < 0f) smoothedSpeedKmh = 0f

        val isStationary = if (isKinematicDemoActive) (rawSpeedKmh == 0f) else dr.isStationary
        val displayedSpeedKmh = if (isStationary || smoothedSpeedKmh < NavigationConfig.SPEED_ZERO_SNAP_THRESHOLD_KMH) {
            0f
        } else {
            smoothedSpeedKmh
        }

        val visualMode = when {
            isDemoModeEnabled -> NavigationVisualMode.DEMO
            physicalPrediction -> NavigationVisualMode.GPS_FALLBACK
            else -> NavigationVisualMode.LIVE
        }

        if (positionAvailable) {
            when (visualMode) {
                NavigationVisualMode.DEMO -> {
                    appendTrailLocked(
                        demoTrailPointsList,
                        latitude,
                        longitude
                    )
                }

                NavigationVisualMode.GPS_FALLBACK -> {
                    appendTrailLocked(
                        predictionTrailPointsList,
                        latitude,
                        longitude
                    )
                }

                NavigationVisualMode.LIVE -> {
                    appendTrailLocked(
                        gpsTrailPointsList,
                        latitude,
                        longitude
                    )
                }
            }
        }

        /*
         * Real prediction references exclude all demo positions.
         * The demo UI uses the active latitude/longitude directly.
         */
        if (physicalPrediction && !isDemoModeEnabled) {
            lastPredictedLat = latitude
            lastPredictedLon = longitude
        }

        val source = when {
            isDemoModeEnabled -> "DEMO"
            physicalPrediction -> "DEAD_RECKONING"
            gpsFresh -> "GPS"
            else -> "NONE"
        }

        val statusMessage = when {
            isKinematicDemoActive ->
                "Software simulation active"

            isDemoModeEnabled && !positionAvailable ->
                "Waiting for a real GPS starting location"

            isDemoModeEnabled ->
                trackingGapNotice
                    ?: "Physical walking prediction active"

            physicalPrediction ->
                trackingGapNotice
                    ?: "GPS unavailable — tracking estimated"

            gpsFresh && !isInternetAvailable ->
                "Live GPS — internet unavailable"

            gpsFresh ->
                "Live GPS"

            else ->
                "Acquiring precise satellite location..."
        }

        sensorFusionManager.updateMagnetometerAccuracy(
            sensorManager.magnetometerAccuracy
        )

        sensorFusionManager.updateRotationVectorAccuracy(
            sensorManager.rotationVectorAccuracy
        )

        val accuracy = latestFilteredGpsResult?.accuracyMeters
            ?: latestRawGnssLocation?.takeIf {
                it.hasAccuracy()
            }?.accuracy
            ?: 0f

        val uncertainty = when {
            isKinematicDemoActive -> 0.0
            physicalPrediction -> dr.uncertaintyRadiusMeters
            gpsFresh -> accuracy.toDouble()
            else -> 0.0
        }

        val movementState = when {
            !positionAvailable -> MovementState.UNKNOWN
            speedKmh > 35f -> MovementState.HIGH_SPEED
            speedKmh > 7f -> MovementState.MOVING
            speedKmh > 0.1f -> MovementState.WALKING
            else -> MovementState.STATIONARY
        }

        val noFixReason = when (gpsState) {
            GpsState.PERMISSION_REQUIRED -> "NO_PERMISSION"
            GpsState.GPS_DISABLED -> "LOCATION_SERVICES_DISABLED"
            GpsState.SEARCHING -> "NO_REAL_FIX"
            GpsState.LOST -> "LOCATION_TOO_OLD"
            GpsState.STALE -> "STALE_LOCATION"
            else -> ""
        }

        val liveMetrics =
            if (physicalPrediction && blackoutStartElapsedMs > 0L) {
                if (dr.isStationary) {
                    blackoutStationaryDurationSec +=
                        elapsedSinceEmission
                }

                if (dr.motionState == "ROTATING_IN_PLACE") {
                    blackoutRotatingDurationSec +=
                        elapsedSinceEmission
                }

                blackoutMaximumSpeedKmh =
                    max(blackoutMaximumSpeedKmh, speedKmh)

                blackoutSpeedSum += speedKmh
                blackoutSpeedCount++

                BlackoutMetrics(
                    blackoutStartTime = blackoutStartWallMs,
                    blackoutDurationSeconds = max(
                        0.0,
                        (nowElapsed - blackoutStartElapsedMs) / 1000.0
                    ),
                    blackoutStartLatitude = blackoutStartLat,
                    blackoutStartLongitude = blackoutStartLon,
                    drLatitude = latitude,
                    drLongitude = longitude,
                    drDistance = max(
                        0.0,
                        dr.distanceTravelled - blackoutStartDist
                    ),
                    maximumSpeedKmh = blackoutMaximumSpeedKmh,
                    averageSpeedKmh = if (blackoutSpeedCount > 0) {
                        (blackoutSpeedSum / blackoutSpeedCount).toFloat()
                    } else 0f,
                    mlInferenceMs =
                        deadReckoningEngine.lastInferenceLatency.toLong(),
                    numberOfAcceptedMLPredictions =
                        max(0, dr.acceptedCount - blackoutAcceptedStart),
                    numberOfClampedMLPredictions =
                        max(0, dr.clampedCount - blackoutClampedStart),
                    numberOfRejectedMLPredictions =
                        max(0, dr.rejectedCount - blackoutRejectedStart),
                    stationaryDuration = blackoutStationaryDurationSec,
                    rotatingInPlaceDuration = blackoutRotatingDurationSec
                )
            } else {
                storedBlackoutMetrics
            }

        if (recordingActive &&
            positionAvailable &&
            nowElapsed - lastGpxRecordElapsedMs >= 1_000L
        ) {
            lastGpxRecordElapsedMs = nowElapsed
            recordingContainsDemo =
                recordingContainsDemo || isDemoModeEnabled

            recordedGpxPoints.add(
                GpxTrackPoint(
                    lat = latitude,
                    lon = longitude,
                    altMeters = if (isKinematicDemoActive) {
                        0.0
                    } else dr.altitude,
                    timestampMs = System.currentTimeMillis(),
                    speedKmh = speedKmh,
                    headingDeg = heading,
                    isBlackout =
                        physicalPrediction || isDemoModeEnabled
                )
            )
        }

        _state.update { previous ->
            previous.copy(
                movementState = movementState,
                latitude = latitude,
                longitude = longitude,
                locationAccuracyMeters =
                    if (gpsFresh && !isDemoModeEnabled) accuracy else 0f,
                locationAgeSeconds =
                    if (ageMs != Long.MAX_VALUE) ageMs / 1000L else 0L,
                locationProvider = when {
                    isDemoModeEnabled -> "demo"
                    physicalPrediction -> "dead_reckoning"
                    gpsFresh -> latestRawGnssLocation?.provider ?: "GPS"
                    else -> "NONE"
                },
                speedKmh = speedKmh,
                isSpeedAvailable = positionAvailable,
                headingDeg = normalizeHeading(heading),
                deviceHeadingDeg = deviceHeading,
                distanceMeters = distanceMeters,
                gnssStatus =
                    if (gpsFresh && !isDemoModeEnabled && !blackoutActive) {
                        "AVAILABLE"
                    } else "UNAVAILABLE",
                navigationMode = when {
                    isKinematicDemoActive -> "SIMULATION"
                    physicalPrediction -> "DEAD RECKONING"
                    gpsFresh -> "GNSS"
                    else -> "WAITING FOR GPS"
                },
                statusMessage = statusMessage,
                gnssNavigationMode = when {
                    isDemoModeEnabled || physicalPrediction ->
                        "GNSS_BLACKOUT"
                    gpsFresh -> "GNSS_AVAILABLE"
                    else -> "WAITING_FOR_INITIAL_FIX"
                },
                mlStatus = when {
                    !deadReckoningEngine.modelRunner.ready -> "NOT_READY"
                    (physicalPrediction || isKinematicDemoActive) && dr.motionMode == "VEHICLE_MODE" -> "INFERENCE_RUNNING"
                    deadReckoningEngine.modelRunner.ready -> "MODEL_READY"
                    else -> "INACTIVE"
                },
                ekfStatus = when {
                    !deadReckoningEngine.ekf.isInitialized -> "UNINITIALIZED"
                    physicalPrediction -> "PREDICTING"
                    isKinematicDemoActive -> "SIMULATING"
                    gpsFresh -> "GPS_ALIGNED"
                    else -> "INITIALIZED"
                },
                mapStatus =
                    if (isInternetAvailable) "ONLINE" else "OFFLINE",
                mlInferenceLatencyMs =
                    dr.takeIf { it.motionMode == "VEHICLE_MODE" }
                        ?.let {
                            deadReckoningEngine.lastInferenceLatency.toLong()
                        } ?: 0L,
                accelerometerActive = sensorManager.isAccelerometerActive,
                gyroscopeActive = sensorManager.isGyroscopeActive,
                magnetometerActive = sensorManager.isMagnetometerActive,
                hasGpsFix = hasRealAnchorLocked() || (gpsFresh && !isDemoModeEnabled),
                noFixReason = noFixReason,
                /*
                 * Never use the former banner text as a road name.
                 * Actual road-name rendering can remain in the map layer.
                 */
                currentRoadName = "",
                motionState = if (isKinematicDemoActive) {
                    if (speedKmh > 0f) "MOVING" else "STATIONARY"
                } else dr.motionState,
                latestGateAction = dr.latestGateAction,
                acceptedCount = dr.acceptedCount,
                clampedCount = dr.clampedCount,
                rejectedCount = dr.rejectedCount,
                blackoutMode = blackoutActive,
                isSimulatedBlackout = isDemoModeEnabled ||
                        isSimulatedBlackoutMode,
                blackoutDurationSeconds =
                    liveMetrics.blackoutDurationSeconds,
                blackoutMetrics = liveMetrics,
                naiveLatitude = dr.naiveLatitude,
                naiveLongitude = dr.naiveLongitude,
                uncertaintyRadiusMeters = uncertainty,
                headingConfidence =
                    sensorFusionManager.headingConfidence.name,
                motionMode = dr.motionMode,
                isInternetAvailable = isInternetAvailable,
                isRecordingGpx = recordingActive,
                recordedPointCount = recordedGpxPoints.size,

                /*
                 * Publish list contents, not only changes in size.
                 * Capped lists continue changing after reaching 2,000.
                 */
                demoTrailPoints = demoTrailPointsList.toList(),
                demoTrailSegments = demoTrailSegmentsList.map { it.toList() },
                gpsTrailPoints = gpsTrailPointsList.toList(),
                predictionTrailPoints =
                    predictionTrailPointsList.toList(),
                predictionTrailSegments = predictionTrailSegmentsList.map { it.toList() },
                historicalLkMarkers = historicalLkMarkersList.toList(),

                visualMode = visualMode,
                lastTrustedGpsLat = lastTrustedGpsLat,
                lastTrustedGpsLon = lastTrustedGpsLon,
                lastTrustedGpsAccuracyMeters = lastTrustedGpsAccuracyMeters,
                lastTrustedGpsTimestampNs = lastTrustedGpsTimestampNs,
                lastKnownLocationAgeSeconds = if (lastTrustedGpsTimestampNs != null && lastTrustedGpsTimestampNs!! > 0L) {
                    (System.currentTimeMillis() - lastTrustedGpsTimestampNs!!) / 1000L
                } else null,
                lastPredictedLat = if (physicalPrediction &&
                    !isDemoModeEnabled
                ) lastPredictedLat else null,
                lastPredictedLon = if (physicalPrediction &&
                    !isDemoModeEnabled
                ) lastPredictedLon else null,
                predictionUncertaintyMeters =
                    if (physicalPrediction) uncertainty else 0.0,
                isDemoModeEnabled = isDemoModeEnabled,
                hasDemoStartAnchor = hasDemoStartAnchor,
                selectedDemoSpeed = simulatedSpeedKmh,
                selectedDemoHeading = simulatedHeadingDeg,
                stepCount = dr.stepCount,
                latestStrideMeters = dr.latestStrideMeters,
                gpsState = gpsState,
                gpsAccuracyLevel =
                    GpsAccuracyLevel.fromAccuracy(
                        if (accuracy > 0f) accuracy else 50f
                    ),
                rawLatitude = latestRawGnssLocation?.latitude ?: 0.0,
                rawLongitude = latestRawGnssLocation?.longitude ?: 0.0,
                filteredLatitude =
                    latestFilteredGpsResult?.filteredLatitude ?: 0.0,
                filteredLongitude =
                    latestFilteredGpsResult?.filteredLongitude ?: 0.0,
                rawHeadingDeg =
                    latestRawGnssLocation?.takeIf {
                        it.hasBearing()
                    }?.bearing ?: 0f,
                filteredHeadingDeg =
                    latestFilteredGpsResult?.filteredHeadingDeg ?: 0f,
                gpsFixAgeMs = ageMs,
                rawSpeedKmh = rawSpeedKmh,
                filteredSpeedKmh = smoothedSpeedKmh,
                displayedSpeedKmh = displayedSpeedKmh,
                isEvaluationActive = evaluationActive,
                evaluationTimeRemainingSec = evaluationTimeRemainingSec,
                navigationSource = source,
                positionSource = when {
                    isKinematicDemoActive -> "DEMO SIMULATION"
                    physicalPrediction -> "DEAD RECKONING"
                    gpsFresh -> "FUSED (GNSS + EKF)"
                    else -> "WAITING FOR GPS"
                },
                speedSource = when {
                    isKinematicDemoActive -> "DEMO SIMULATION"
                    physicalPrediction -> "EKF + AI DEAD RECKONING"
                    gpsFresh -> "GNSS + EKF"
                    else -> "UNAVAILABLE"
                },
                headingSource = when {
                    isKinematicDemoActive -> "DEMO SIMULATION"
                    physicalPrediction -> "INERTIAL ORIENTATION"
                    else -> "FUSED ORIENTATION"
                },
                accuracySource = when {
                    isKinematicDemoActive -> "SIMULATED SEARCH RADIUS"
                    physicalPrediction -> "ESTIMATED EKF UNCERTAINTY"
                    gpsFresh -> "GNSS MEASUREMENT ACCURACY"
                    else -> "UNAVAILABLE"
                },
                appState = if (started) "READY" else "INITIALIZING",
                gnssState = when {
                    !hasReceivedRealGnssFix -> "ACQUIRING"
                    blackoutActive -> "BLACKOUT"
                    consecutiveFreshRecoveryFixes in 1 until NavigationConfig.REQUIRED_RECOVERY_FIX_COUNT -> "RECOVERING"
                    gpsFresh -> gnssQualityEvaluator.currentQualityScore.name
                    else -> "DEGRADED"
                },
                stationaryConfidence = deadReckoningEngine.zuptDetector.stationaryConfidence,
                navigationConfidence = when {
                    uncertainty <= NavigationConfig.HIGH_CONFIDENCE_UNCERTAINTY_METERS && sensorFusionManager.headingConfidence != HeadingConfidence.UNRELIABLE -> "HIGH"
                    uncertainty <= NavigationConfig.MEDIUM_CONFIDENCE_UNCERTAINTY_METERS -> "MEDIUM"
                    else -> "LOW"
                },
                zuptActive = deadReckoningEngine.zuptDetector.isStationary,
                isMountCalibrated = sensorFusionManager.phoneMountCalibrator.isCalibrated,
                mountPitchDeg = sensorFusionManager.phoneMountCalibrator.mountPitchDeg,
                mountRollDeg = sensorFusionManager.phoneMountCalibrator.mountRollDeg,
                mountYawDeg = sensorFusionManager.phoneMountCalibrator.mountYawDeg,
                onnxInferenceTimeMs = deadReckoningEngine.lastInferenceLatency.toLong(),
                estimatorUpdateTimeMs = lastEstimatorUpdateTimeMs,
                mapMatchingTimeMs = lastMapMatchingTimeMs,
                uiPublishRateHz = 10.0f,
                sensorRateHz = 50.0f,
                timestampNs = SystemClock.elapsedRealtimeNanos()
            )
        }
    }


    fun startGpxRecording() {
        synchronized(engineLock) {
            recordedGpxPoints.clear()
            lastGpxRecordElapsedMs = 0L
            recordingContainsDemo = isDemoModeEnabled
            recordingActive = true
            emitStateLocked(force = true)
        }
    }

    fun stopGpxRecording() {
        synchronized(engineLock) {
            recordingActive = false
            emitStateLocked(force = true)
        }
    }

    fun exportGpxTrack(context: Context): java.io.File? {
        val points = synchronized(engineLock) {
            recordedGpxPoints.toList()
        }

        if (points.isEmpty()) return null

        val xml = GpxExporter.generateGpxXml(points)
        val file = GpxExporter.saveGpxToFile(context, xml)

        if (file != null) {
            GpxExporter.shareGpxFile(context, file)
        }

        return file
    }

    fun exportTrackImageToGallery(context: Context): Boolean {
        val snapshot = synchronized(engineLock) {
            recordedGpxPoints.toList() to recordingContainsDemo
        }

        if (snapshot.first.isEmpty()) return false

        return TrackImageExporter.saveTrackImageToGallery(
            context,
            snapshot.first,
            snapshot.second
        )
    }

    fun exportCsvTrack(context: Context): java.io.File? {
        val points = synchronized(engineLock) {
            recordedGpxPoints.toList()
        }
        if (points.isEmpty()) return null
        val csv = GpxExporter.generateCsv(points)
        val file = GpxExporter.saveGpxToFile(context, csv, "naviator_track_${System.currentTimeMillis()}.csv")
        if (file != null) {
            GpxExporter.shareGpxFile(context, file, "text/csv")
        }
        return file
    }

    fun exportJsonTrack(context: Context): java.io.File? {
        val points = synchronized(engineLock) {
            recordedGpxPoints.toList()
        }
        if (points.isEmpty()) return null
        val json = GpxExporter.generateJson(points)
        val file = GpxExporter.saveGpxToFile(context, json, "naviator_track_${System.currentTimeMillis()}.json")
        if (file != null) {
            GpxExporter.shareGpxFile(context, file, "application/json")
        }
        return file
    }

    fun clearSession() {
        synchronized(engineLock) {
            demoTrailPointsList.clear()
            demoTrailSegmentsList.clear()
            gpsTrailPointsList.clear()
            predictionTrailPointsList.clear()
            predictionTrailSegmentsList.clear()
            recordedGpxPoints.clear()
            historicalLkMarkersList.clear()
            emitStateLocked(force = true)
        }
    }

    fun start10sEvaluationMode() {
        synchronized(engineLock) {
            if (closed || evaluationActive) return
            evaluationActive = true
            evaluationStartElapsedMs = SystemClock.elapsedRealtime()
            evaluationGroundTruthFixes.clear()
            evaluationTimeRemainingSec = NavigationConfig.EVALUATION_MODE_DURATION_SEC.toInt()

            val anchor = lastKnownValidGpsLocation
            if (anchor != null) {
                beginBlackoutMetricsLocked(anchor.latitude, anchor.longitude)
            }

            evaluationJob = scope.launch {
                for (remaining in NavigationConfig.EVALUATION_MODE_DURATION_SEC.toInt() downTo 1) {
                    synchronized(engineLock) {
                        evaluationTimeRemainingSec = remaining
                    }
                    emitStateLocked(force = true)
                    delay(1000L)
                }
                synchronized(engineLock) {
                    finishEvaluationModeLocked()
                }
            }
            emitStateLocked(force = true)
        }
    }

    private fun finishEvaluationModeLocked() {
        evaluationActive = false
        evaluationJob?.cancel()
        evaluationJob = null
        evaluationTimeRemainingSec = 0

        val lastGt = evaluationGroundTruthFixes.lastOrNull() ?: lastKnownValidGpsLocation
        finishBlackoutMetricsLocked(lastGt)
        emitStateLocked(force = true)
    }

    fun stop() {
        synchronized(engineLock) {
            if (closed) return

            paused = true
            closed = true

            cancelSimulationLocked()

            statusJob?.cancel()
            statusJob = null

            sensorManager.stopAll()
            locationManager.stopLocationUpdates()

            networkCallback?.let { callback ->
                try {
                    connectivityManager?.unregisterNetworkCallback(
                        callback
                    )
                } catch (_: Exception) {
                    // Already unregistered or unavailable.
                }
            }

            networkCallback = null

            deadReckoningEngine.close()
            scope.cancel()
        }
    }
}