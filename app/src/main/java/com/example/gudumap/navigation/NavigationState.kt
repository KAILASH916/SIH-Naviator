package com.example.gudumap.navigation

enum class NavigationVisualMode {
    LIVE,         // Trusted LIVE GPS (BLUE marker, BLUE trail, BLUE accuracy circle)
    DEMO,         // Sidebar Demo Mode ON (RED marker, RED trail)
    GPS_FALLBACK  // GPS lost/stale, prediction active (RED marker, RED trail, ORANGE prediction circle)
}

enum class MapOrientationMode {
    NORTH_UP,     // Map stays static facing North
    HEADING_UP    // Map rotates so the heading/movement direction faces UP
}

enum class MovementState {
    UNKNOWN,
    STATIONARY,
    WALKING,
    MOVING,
    HIGH_SPEED
}

/**
 * Immutable navigation state emitted by the navigation engine to the ViewModel and UI.
 */
data class NavigationState(
    val mapOrientationMode: MapOrientationMode = MapOrientationMode.NORTH_UP,
    val movementState: MovementState = MovementState.STATIONARY,
    val latitude: Double = 0.0, // 0.0/0.0 is a "no real fix yet" sentinel -- see hasGpsFix
    val longitude: Double = 0.0,
    val locationAccuracyMeters: Float = 0f,
    val locationAgeSeconds: Long = 0L,
    val locationProvider: String = "NONE",
    val speedKmh: Float = 0f,
    val isSpeedAvailable: Boolean = false,
    val headingDeg: Float = 0f,
    val deviceHeadingDeg: Float = 0f,
    val distanceMeters: Double = 0.0,
    val gnssStatus: String = "UNAVAILABLE", // "AVAILABLE" / "UNAVAILABLE"
    val navigationMode: String = "GNSS",     // "GNSS" / "DEAD RECKONING"
    val mlStatus: String = "INACTIVE",       // "ACTIVE" / "INACTIVE" / "ERROR"
    val ekfStatus: String = "ACTIVE",        // "ACTIVE" / "INACTIVE"
    val mapStatus: String = "OFFLINE",       // "ONLINE" / "OFFLINE"
    val offlineMapStatus: String = "AVAILABLE", // "AVAILABLE" / "LOADING" / "ERROR" / "NOT_AVAILABLE"
    val positionErrorMeters: Double = 0.0,
    val driftPercentage: Double = 0.0,
    val mlInferenceLatencyMs: Long = 0L,
    val accelerometerActive: Boolean = false,
    val gyroscopeActive: Boolean = false,
    val magnetometerActive: Boolean = false,
    val blackoutMode: Boolean = false,
    val gnssRecovered: Boolean = false,
    val recoveryDriftMeters: Double = 0.0,
    val recoveryErrorPercent: Double = 0.0,
    val currentRoadName: String = "",
    val motionState: String = "STATIONARY", // "STATIONARY" / "MOVING" / "ROTATING_IN_PLACE"
    val gnssNavigationMode: String = "GNSS_AVAILABLE", // "GNSS_AVAILABLE" / "GNSS_BLACKOUT" / "GNSS_RECOVERY"
    val blackoutMetrics: BlackoutMetrics = BlackoutMetrics(),
    val latestGateAction: String = "ACCEPTED", // "ACCEPTED" / "CLAMPED" / "REJECTED"
    val acceptedCount: Int = 0,
    val clampedCount: Int = 0,
    val rejectedCount: Int = 0,
    val gnssGroundTruthLat: Double? = null,
    val gnssGroundTruthLon: Double? = null,
    val blackoutDurationSeconds: Double = 0.0,
    val naiveLatitude: Double = 0.0, // Uncorrected dead-reckoning position, for demo contrast only
    val naiveLongitude: Double = 0.0,
    val uncertaintyRadiusMeters: Double = 0.0, // EKF 1-sigma circular position uncertainty
    val headingConfidence: String = "UNRELIABLE", // "HIGH" / "MEDIUM" / "LOW" / "UNRELIABLE" -- Android's own magnetometer/rotation-vector reliability signal
    val motionMode: String = "VEHICLE_MODE", // "VEHICLE_MODE" / "CONSERVATIVE_MODE" -- pedestrian-safe fallback classification, decided once at blackout entry (PROJECT_STATUS.md §24); UI hookup pending
    val hasGpsFix: Boolean = false, // true once at least one real GNSS fix has ever been obtained this session
    val isSimulatedBlackout: Boolean = false, // true when blackout is running in software demo mode
    val isInternetAvailable: Boolean = true, // true when device has active internet, false when offline (merged back from teammate's branch, PROJECT_STATUS.md §26; not yet wired to any producer on this branch -- see NavigationEngine.kt merge decision)
    val isRecordingGpx: Boolean = false,
    val recordedPointCount: Int = 0,
    val demoTrailPoints: List<Pair<Double, Double>> = emptyList(),
    val demoTrailSegments: List<List<Pair<Double, Double>>> = emptyList(),
    val gpsTrailPoints: List<Pair<Double, Double>> = emptyList(),
    val predictionTrailPoints: List<Pair<Double, Double>> = emptyList(),
    val visualMode: NavigationVisualMode = NavigationVisualMode.LIVE,
    val lastTrustedGpsLat: Double? = null,
    val lastTrustedGpsLon: Double? = null,
    val lastTrustedGpsAccuracyMeters: Float? = null,
    val lastTrustedGpsTimestampNs: Long? = null,
    val historicalLkMarkers: List<LkMarkerData> = emptyList(),
    val predictionTrailSegments: List<List<Pair<Double, Double>>> = emptyList(),
    val lastPredictedLat: Double? = null,
    val lastPredictedLon: Double? = null,
    val predictionUncertaintyMeters: Double = 0.0,
    val isDemoModeEnabled: Boolean = false,
    val hasDemoStartAnchor: Boolean = true,
    val selectedDemoSpeed: Float = 36f,
    val selectedDemoHeading: Float = 0f,
    val stepCount: Int = 0,
    val latestStrideMeters: Float = 0f,
    val statusMessage: String = "",
    val lastKnownLocationAgeSeconds: Long? = null,
    val gpsState: GpsState = GpsState.SEARCHING,
    val gpsAccuracyLevel: GpsAccuracyLevel = GpsAccuracyLevel.VERY_POOR,
    val rawLatitude: Double = 0.0,
    val rawLongitude: Double = 0.0,
    val filteredLatitude: Double = 0.0,
    val filteredLongitude: Double = 0.0,
    val rawHeadingDeg: Float = 0f,
    val filteredHeadingDeg: Float = 0f,
    val gpsFixAgeMs: Long = 0L,
    val noFixReason: String = "",
    val navigationSource: String = "GPS", // "GPS" / "DEMO" / "GPX_REPLAY" / "CSV_REPLAY"
    val timestampNs: Long = System.nanoTime()
)

data class LkMarkerData(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val timestampNs: Long,
    val accuracyMeters: Float
)
