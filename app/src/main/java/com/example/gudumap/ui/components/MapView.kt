package com.example.gudumap.ui.components

import android.content.Context
import android.graphics.Color as AndroidColor
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.ui.res.painterResource
import com.example.gudumap.R
import com.example.gudumap.map.MapMatcher
import android.widget.Toast
import com.example.gudumap.map.OfflineMapManager
import com.example.gudumap.navigation.CoordinateTransformer
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView as OsmMapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import com.example.gudumap.navigation.GpsState

private const val MAX_TRAIL_POINTS = 2000
private const val TAG = "Gudumap:MapView"

fun isValidMapCoordinate(latitude: Double, longitude: Double): Boolean {
    return latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
        (latitude != 0.0 || longitude != 0.0)
}

private fun headingCardinal(headingDeg: Float): String {
    val normalized = (headingDeg % 360f + 360f) % 360f
    return when {
        normalized < 22.5f || normalized >= 337.5f -> "N"
        normalized < 67.5f -> "NE"
        normalized < 112.5f -> "E"
        normalized < 157.5f -> "SE"
        normalized < 202.5f -> "S"
        normalized < 247.5f -> "SW"
        normalized < 292.5f -> "W"
        else -> "NW"
    }
}

enum class CoverageState {
    WAITING_FOR_LOCATION,
    IN_COVERAGE,
    OUT_OF_COVERAGE
}

private fun interpolateAngle(currentAngle: Float, targetAngle: Float, factor: Float = 0.3f): Float {
    var diff = (targetAngle - currentAngle) % 360f
    if (diff > 180f) diff -= 360f
    if (diff < -180f) diff += 360f
    return currentAngle + diff * factor
}

private fun createLkPinDrawable(context: Context): android.graphics.drawable.Drawable {
    val density = context.resources.displayMetrics.density
    val width = (36 * density).toInt()
    val height = (44 * density).toInt()
    val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)

    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.rgb(220, 38, 38)
        style = android.graphics.Paint.Style.FILL
    }

    val radius = width / 2f
    canvas.drawCircle(radius, radius, radius - 2f, paint)

    val path = android.graphics.Path().apply {
        moveTo(radius - (radius * 0.7f), radius)
        lineTo(radius + (radius * 0.7f), radius)
        lineTo(radius, height.toFloat() - 2f)
        close()
    }
    canvas.drawPath(path, paint)

    val innerPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        style = android.graphics.Paint.Style.FILL
    }
    canvas.drawCircle(radius, radius, radius * 0.65f, innerPaint)

    val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.rgb(220, 38, 38)
        textSize = 12f * density
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = android.graphics.Paint.Align.CENTER
    }
    val fontMetrics = textPaint.fontMetrics
    val baseline = radius - (fontMetrics.ascent + fontMetrics.descent) / 2f
    canvas.drawText("LK", radius, baseline, textPaint)

    return android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
}

@Composable
fun MapView(
    latitude: Double,
    longitude: Double,
    headingDeg: Float = 0f,
    deviceHeadingDeg: Float = headingDeg,
    mapStatus: String = "OFFLINE",
    offlineMapStatus: String = "AVAILABLE",
    roadName: String = "",
    blackoutMode: Boolean = false,
    naiveLatitude: Double = latitude,
    naiveLongitude: Double = longitude,
    uncertaintyRadiusMeters: Double = 0.0,
    locationAccuracyMeters: Float = 0f,
    locationProvider: String = "",
    demoTrailPoints: List<Pair<Double, Double>> = emptyList(),
    demoTrailSegments: List<List<Pair<Double, Double>>> = emptyList(),
    gpsTrailPoints: List<Pair<Double, Double>> = emptyList(),
    predictionTrailPoints: List<Pair<Double, Double>> = emptyList(),
    predictionTrailSegments: List<List<Pair<Double, Double>>> = emptyList(),
    visualMode: com.example.gudumap.navigation.NavigationVisualMode = com.example.gudumap.navigation.NavigationVisualMode.LIVE,
    lastTrustedGpsLat: Double? = null,
    lastTrustedGpsLon: Double? = null,
    lastTrustedGpsAccuracyMeters: Float? = null,
    lastTrustedGpsTimestampNs: Long? = null,
    lastKnownLocationAgeSeconds: Long? = null,
    historicalLkMarkers: List<com.example.gudumap.navigation.LkMarkerData> = emptyList(),
    lastPredictedLat: Double? = null,
    lastPredictedLon: Double? = null,
    predictionUncertaintyMeters: Double = 0.0,
    hasGpsFix: Boolean = false,
    gpsState: GpsState = GpsState.SEARCHING,
    isDemoModeEnabled: Boolean = false,
    isKinematicDemoActive: Boolean = false,
    isExpanded: Boolean = false,
    isMinimized: Boolean = false,
    isDarkMode: Boolean = false,
    showRoadDiagnosticOverlay: Boolean = false,
    showDiagnosticTrails: Boolean = false,
    onToggleExpand: (() -> Unit)? = null,
    onMinimizeMap: (() -> Unit)? = null,
    onExpandMap: (() -> Unit)? = null,
    onMyLocationClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val effectiveMinZoom = OfflineMapManager.MIN_ZOOM.toDouble()

    val recordedTrail = remember { mutableListOf<GeoPoint>() }
    val naiveTrail = remember { mutableListOf<GeoPoint>() }
    var followUser by remember { mutableStateOf(true) }
    var followHeading by remember { mutableStateOf(false) }

    var currentZoomDouble by remember { mutableStateOf(15.5) }
    var osmMapRef by remember { mutableStateOf<OsmMapView?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> osmMapRef?.onResume()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> osmMapRef?.onPause()
                androidx.lifecycle.Lifecycle.Event.ON_DESTROY -> {
                    osmMapRef?.onDetach()
                    osmMapRef = null
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            osmMapRef?.onDetach()
            osmMapRef = null
        }
    }

    val boxModifier = if (isExpanded) {
        Modifier.fillMaxSize()
    } else {
        modifier
            .fillMaxWidth()
            .height(if (isMinimized) 140.dp else 340.dp)
    }

    val darkMapBlue = Color(0xFF0F172A)
    val darkMapAndroidColor = AndroidColor.rgb(15, 23, 42)

    Box(
        modifier = boxModifier
            .background(darkMapBlue)
            .onGloballyPositioned { coordinates ->
                try {
                    Log.d(
                        TAG,
                        "PARENT_MAP_CONTAINER: width=${coordinates.size.width}, height=${coordinates.size.height}"
                    )
                } catch (_: Throwable) {}
            }
            .clip(RoundedCornerShape(if (isExpanded) 0.dp else 20.dp))
            .clipToBounds()
            .border(
                if (isExpanded) 0.dp else 1.dp,
                if (isDarkMode) Color(0xFF334155) else Color(0xFFE2E8F0),
                RoundedCornerShape(if (isExpanded) 0.dp else 20.dp)
            )
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx: Context ->
                val osmdroidDir = java.io.File(ctx.filesDir, "osmdroid")
                if (!osmdroidDir.exists()) osmdroidDir.mkdirs()
                val tileCacheDir = java.io.File(ctx.filesDir, "osmdroid/tiles")
                if (!tileCacheDir.exists()) tileCacheDir.mkdirs()

                val config = org.osmdroid.config.Configuration.getInstance()
                config.osmdroidBasePath = osmdroidDir
                config.osmdroidTileCache = tileCacheDir
                config.tileFileSystemCacheMaxBytes = 500L * 1024L * 1024L
                config.tileFileSystemCacheTrimBytes = 400L * 1024L * 1024L
                config.cacheMapTileCount = 120
                config.tileDownloadThreads = 8
                config.tileDownloadMaxQueueSize = 120

                val offlineManager = OfflineMapManager(ctx)
                val tileProvider = offlineManager.createOfflineTileProvider()

                val mapView = if (tileProvider != null) {
                    Log.i(TAG, "Offline tile provider ready (tiles=${offlineManager.getTileCount()}) -- using bundled coimbatore.mbtiles")
                    OsmMapView(ctx, tileProvider)
                } else {
                    Log.i(TAG, "Using online Esri World Street Map tile provider fallback")
                    val esriSource: ITileSource = XYTileSource(
                        "EsriWorldStreet",
                        0,
                        19,
                        256,
                        ".jpg",
                        arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/")
                    )
                    OsmMapView(ctx).apply {
                        setTileSource(esriSource)
                    }
                }

                mapView.apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    if (isExpanded) {
                        setScrollableAreaLimitDouble(null)
                    } else {
                        setScrollableAreaLimitDouble(OfflineMapManager.COIMBATORE_BOUNDS)
                    }
                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                    setMultiTouchControls(true)
                    zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
                    setUseDataConnection(tileProvider == null)
                    isTilesScaledToDpi = true

                    setOnTouchListener { _, event ->
                        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                            followUser = false
                        }
                        false
                    }

                    overlayManager.tilesOverlay.setLoadingBackgroundColor(darkMapAndroidColor)
                    overlayManager.tilesOverlay.setLoadingLineColor(AndroidColor.TRANSPARENT)

                    minZoomLevel = effectiveMinZoom
                    maxZoomLevel = OfflineMapManager.VISUAL_MAX_ZOOM
                    controller.setZoom(15.5)

                    addMapListener(object : org.osmdroid.events.MapListener {
                        override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean = false
                        override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean {
                            currentZoomDouble = event?.zoomLevel ?: zoomLevelDouble
                            return false
                        }
                    })

                    val initialPoint = if (hasGpsFix && isValidMapCoordinate(latitude, longitude)) {
                        GeoPoint(latitude, longitude)
                    } else {
                        OfflineMapManager.COIMBATORE_CENTER
                    }
                    controller.setCenter(initialPoint)

                    setBackgroundColor(darkMapAndroidColor)

                    // Add scale bar overlay for distance scaling reference
                    try {
                        val scaleBarOverlay = org.osmdroid.views.overlay.ScaleBarOverlay(this).apply {
                            setCentred(false)
                            setScaleBarOffset(24, 24)
                            setTextSize(26f)
                        }
                        overlays.add(scaleBarOverlay)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not add ScaleBarOverlay: ${e.message}")
                    }



                    // Optional road diagnostic vector overlay (disabled by default)
                    if (showRoadDiagnosticOverlay) {
                        try {
                            val matcher = MapMatcher(ctx)
                            val roadList = matcher.getRoads()
                            if (roadList.isNotEmpty()) {
                                val roadsOverlay = FolderOverlay()
                                roadsOverlay.name = "coimbatore_vector_roads"
                                for (road in roadList) {
                                    val pts = road.points.map { GeoPoint(it.lat, it.lon) }
                                    if (pts.size < 2) continue
                                    val polyline = Polyline(this).apply {
                                        outlinePaint.color = AndroidColor.argb(160, 37, 99, 235)
                                        outlinePaint.strokeWidth = 3f
                                        setPoints(pts)
                                        title = road.name
                                    }
                                    roadsOverlay.add(polyline)
                                }
                                overlays.add(roadsOverlay)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to load vector road diagnostic overlay: ${e.message}")
                        }
                    }

                    if (isValidMapCoordinate(latitude, longitude)) {
                        val marker = Marker(this).apply {
                            id = "vehicle_marker"
                            position = initialPoint
                            title = if (roadName.isNotBlank()) roadName else "Naviator Position"
                            snippet = "Lat: %.5f, Lon: %.5f".format(initialPoint.latitude, initialPoint.longitude)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            setFlat(true)
                            val initialRotation = CoordinateTransformer.computeMarkerRotation(headingDeg)
                            rotation = initialRotation
                            val iconDrawable = ContextCompat.getDrawable(ctx, R.drawable.ic_navigation_arrow)
                            if (iconDrawable != null) {
                                icon = iconDrawable
                            }
                        }
                        overlays.add(marker)
                    }

                    invalidate()
                }

                osmMapRef = mapView
                mapView
            },
            update = { map ->
                var needsRelayout = false
                if (map.layoutParams?.width != android.view.ViewGroup.LayoutParams.MATCH_PARENT ||
                    map.layoutParams?.height != android.view.ViewGroup.LayoutParams.MATCH_PARENT
                ) {
                    map.layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    needsRelayout = true
                }

                if (!map.isTilesScaledToDpi) {
                    map.isTilesScaledToDpi = true
                    needsRelayout = true
                }

                if (isExpanded) {
                    map.setScrollableAreaLimitDouble(null)
                } else {
                    map.setScrollableAreaLimitDouble(OfflineMapManager.COIMBATORE_BOUNDS)
                }

                if (needsRelayout) {
                    map.requestLayout()
                    map.invalidate()
                }

                try {
                    val dm = context.resources.displayMetrics
                    Log.d(
                        TAG,
                        "MAPVIEW_DIMENSIONS_DEBUG: Screen=${dm.widthPixels}x${dm.heightPixels}, " +
                        "MapView=${map.width}x${map.height}, LayoutParams=${map.layoutParams?.width}x${map.layoutParams?.height}, " +
                        "isExpanded=$isExpanded"
                    )
                } catch (_: Throwable) {}

                map.minZoomLevel = effectiveMinZoom
                map.maxZoomLevel = OfflineMapManager.VISUAL_MAX_ZOOM
                currentZoomDouble = map.zoomLevelDouble
                if (map.zoomLevelDouble < effectiveMinZoom) {
                    map.controller.setZoom(effectiveMinZoom)
                    currentZoomDouble = effectiveMinZoom
                } else if (map.zoomLevelDouble > OfflineMapManager.VISUAL_MAX_ZOOM) {
                    map.controller.setZoom(OfflineMapManager.VISUAL_MAX_ZOOM)
                    currentZoomDouble = OfflineMapManager.VISUAL_MAX_ZOOM
                }

                // Fixed North-Up Map Orientation
                map.mapOrientation = 0f

                val colorFilter = if (isDarkMode) {
                    val matrix = floatArrayOf(
                        -0.75f, 0.00f, 0.00f, 0.00f, 210f,
                        0.00f, -0.75f, 0.00f, 0.00f, 210f,
                        0.00f, 0.00f, -0.65f, 0.00f, 225f,
                        0.00f, 0.00f, 0.00f, 1.00f, 0f
                    )
                    android.graphics.ColorMatrixColorFilter(matrix)
                } else null
                map.overlayManager.tilesOverlay.setColorFilter(colorFilter)

                val hasValidPosition = isValidMapCoordinate(latitude, longitude)
                val currentPoint = if (hasValidPosition) GeoPoint(latitude, longitude) else null

                if (currentPoint != null && hasValidPosition) {
                    recordedTrail.add(currentPoint)
                    if (recordedTrail.size > MAX_TRAIL_POINTS) recordedTrail.removeAt(0)
                    try {
                        Log.d("GUDUMAP_POS_TRACE", "MAP_RENDER: hasValidPosition=true, visualMode=$visualMode, lat=${currentPoint.latitude}, lon=${currentPoint.longitude}, predTrailCount=${predictionTrailPoints.size}")
                    } catch (_: Throwable) {}
                }

                if (showDiagnosticTrails && blackoutMode && currentPoint != null) {
                    val naivePoint = if (isValidMapCoordinate(naiveLatitude, naiveLongitude)) {
                        GeoPoint(naiveLatitude, naiveLongitude)
                    } else {
                        currentPoint
                    }
                    naiveTrail.add(naivePoint)
                    if (naiveTrail.size > MAX_TRAIL_POINTS) naiveTrail.removeAt(0)
                }

                val existingMarker = map.overlays.filterIsInstance<Marker>().firstOrNull { it.id == "vehicle_marker" }
                if (hasValidPosition && currentPoint != null) {
                    val pt = currentPoint
                    val marker = existingMarker
                        ?: Marker(map).also {
                            it.id = "vehicle_marker"
                            it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            it.setFlat(true)
                            map.overlays.add(it)
                        }

                    marker.position = pt
                    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    marker.setFlat(true)

                    val targetRotation = CoordinateTransformer.computeMarkerRotation(headingDeg)
                    val lerpedRotation = interpolateAngle(marker.rotation, targetRotation, 0.35f)
                    marker.rotation = lerpedRotation
                    try {
                        Log.d("GUDUMAP_PDR_DEMO", "MarkerRotationUpdate: headingDeg=$headingDeg, targetRotation=$targetRotation, lerpedRotation=$lerpedRotation, lat=${pt.latitude}, lon=${pt.longitude}")
                    } catch (_: Throwable) {}

                    marker.title = if (roadName.isNotBlank()) roadName else "Location"
                    marker.snippet = "Lat: %.5f, Lon: %.5f".format(pt.latitude, pt.longitude)

                    val iconRes = when {
                        isDemoModeEnabled -> R.drawable.ic_navigation_arrow_red
                        visualMode == com.example.gudumap.navigation.NavigationVisualMode.GPS_FALLBACK -> R.drawable.ic_navigation_arrow_red
                        else -> R.drawable.ic_navigation_arrow
                    }

                    val iconDrawable = ContextCompat.getDrawable(map.context, iconRes)
                    if (iconDrawable != null) {
                        marker.icon = iconDrawable
                    }

                    // Ensure navigation marker is at top of overlays order so route/circle never covers it
                    if (map.overlays.lastOrNull() != marker) {
                        map.overlays.remove(marker)
                        map.overlays.add(marker)
                    }
                } else if (existingMarker != null) {
                    map.overlays.remove(existingMarker)
                }

                // 1. LIVE GPS Movement Trail (BLUE) - ONLY rendered when Demo Mode is OFF
                val gpsCasingPolyline = map.overlays
                    .filterIsInstance<Polyline>()
                    .firstOrNull { it.id == "recorded_gps_trail_casing" }
                    ?: Polyline(map).also {
                        it.id = "recorded_gps_trail_casing"
                        it.outlinePaint.color = if (isDarkMode) AndroidColor.rgb(15, 23, 42) else AndroidColor.WHITE
                        it.outlinePaint.strokeWidth = 10f
                        map.overlays.add(0, it)
                    }
                val gpsPolyline = map.overlays
                    .filterIsInstance<Polyline>()
                    .firstOrNull { it.id == "recorded_gps_trail" }
                    ?: Polyline(map).also {
                        it.id = "recorded_gps_trail"
                        it.outlinePaint.color = AndroidColor.rgb(37, 99, 235) // BLUE
                        it.outlinePaint.strokeWidth = 6f
                        map.overlays.add(0, it)
                    }
                val gpsPointsToRender = if (!isDemoModeEnabled) {
                    (if (gpsTrailPoints.isNotEmpty()) gpsTrailPoints else recordedTrail.map { Pair(it.latitude, it.longitude) })
                        .filter { isValidMapCoordinate(it.first, it.second) }
                } else {
                    emptyList()
                }
                val needsGpsUpdate = gpsPolyline.actualPoints.size != gpsPointsToRender.size ||
                    (gpsPointsToRender.isNotEmpty() && gpsPolyline.actualPoints.lastOrNull()?.let {
                        it.latitude != gpsPointsToRender.last().first || it.longitude != gpsPointsToRender.last().second
                    } == true)
                if (needsGpsUpdate) {
                    gpsCasingPolyline.setPoints(gpsPointsToRender.map { GeoPoint(it.first, it.second) })
                    gpsPolyline.setPoints(gpsPointsToRender.map { GeoPoint(it.first, it.second) })
                }

                // 2. DEMO Movement Trail (RED) - ALWAYS rendered when completed demo trail points exist (visibility does not depend on isKinematicDemoActive or blackoutMode)
                val validDemoSegments = if (demoTrailSegments.isNotEmpty()) {
                    demoTrailSegments.map { seg -> seg.filter { isValidMapCoordinate(it.first, it.second) } }.filter { it.size >= 2 }
                } else if (demoTrailPoints.filter { isValidMapCoordinate(it.first, it.second) }.size >= 2) {
                    listOf(demoTrailPoints.filter { isValidMapCoordinate(it.first, it.second) })
                } else {
                    emptyList()
                }

                val currentDemoSegIds = validDemoSegments.indices.map { "recorded_demo_trail_$it" }.toSet()
                val existingDemoPolylines = map.overlays.filterIsInstance<Polyline>().filter { it.id?.startsWith("recorded_demo_trail") == true }
                for (p in existingDemoPolylines) {
                    if (p.id !in currentDemoSegIds) {
                        map.overlays.remove(p)
                    }
                }

                for ((idx, seg) in validDemoSegments.withIndex()) {
                    val segId = "recorded_demo_trail_$idx"
                    val polyline = map.overlays
                        .filterIsInstance<Polyline>()
                        .firstOrNull { it.id == segId }
                        ?: Polyline(map).also {
                            it.id = segId
                            it.outlinePaint.color = AndroidColor.rgb(220, 38, 38) // RED
                            it.outlinePaint.strokeWidth = 6f
                            map.overlays.add(0, it)
                        }
                    val needsUpdate = polyline.actualPoints.size != seg.size ||
                        (seg.isNotEmpty() && polyline.actualPoints.lastOrNull()?.let {
                            it.latitude != seg.last().first || it.longitude != seg.last().second
                        } == true)
                    if (needsUpdate) {
                        polyline.setPoints(seg.map { GeoPoint(it.first, it.second) })
                    }
                }

                // 3. GPS FALLBACK / PREDICTION Trails (RED)
                val validPredictionPoints = predictionTrailPoints.filter { isValidMapCoordinate(it.first, it.second) }
                val validPredictionSegments = if (predictionTrailSegments.isNotEmpty()) {
                    predictionTrailSegments.map { seg -> seg.filter { isValidMapCoordinate(it.first, it.second) } }.filter { it.size >= 2 }
                } else if (validPredictionPoints.size >= 2) {
                    listOf(validPredictionPoints)
                } else {
                    emptyList()
                }

                val currentPredSegIds = validPredictionSegments.indices.map { "recorded_prediction_trail_$it" }.toSet()
                val existingPredPolylines = map.overlays.filterIsInstance<Polyline>().filter { it.id?.startsWith("recorded_prediction_trail") == true }
                for (p in existingPredPolylines) {
                    if (p.id !in currentPredSegIds) {
                        map.overlays.remove(p)
                    }
                }

                for ((idx, seg) in validPredictionSegments.withIndex()) {
                    val segId = "recorded_prediction_trail_$idx"
                    val polyline = map.overlays
                        .filterIsInstance<Polyline>()
                        .firstOrNull { it.id == segId }
                        ?: Polyline(map).also {
                            it.id = segId
                            it.outlinePaint.color = AndroidColor.rgb(220, 38, 38) // RED
                            it.outlinePaint.strokeWidth = 6f
                            map.overlays.add(0, it)
                        }
                    val needsUpdate = polyline.actualPoints.size != seg.size ||
                        (seg.isNotEmpty() && polyline.actualPoints.lastOrNull()?.let {
                            it.latitude != seg.last().first || it.longitude != seg.last().second
                        } == true)
                    if (needsUpdate) {
                        polyline.setPoints(seg.map { GeoPoint(it.first, it.second) })
                    }
                }

                // 4. LAST KNOWN (LK) LOCATION PIN MARKERS
                // Only shown during active DR blackout or GPS fallback after a valid fix; hidden before initial fix
                val isBlackoutOrFallback = blackoutMode || visualMode == com.example.gudumap.navigation.NavigationVisualMode.GPS_FALLBACK
                val shouldShowLk = !isDemoModeEnabled && isBlackoutOrFallback && (lastTrustedGpsLat != null || historicalLkMarkers.isNotEmpty())

                val lkMarkersToRender = if (shouldShowLk) {
                    if (historicalLkMarkers.isNotEmpty()) {
                        historicalLkMarkers.takeLast(1)
                    } else if (lastTrustedGpsLat != null && lastTrustedGpsLon != null && isValidMapCoordinate(lastTrustedGpsLat, lastTrustedGpsLon)) {
                        listOf(
                            com.example.gudumap.navigation.LkMarkerData(
                                id = "current_lk",
                                latitude = lastTrustedGpsLat,
                                longitude = lastTrustedGpsLon,
                                timestampNs = lastTrustedGpsTimestampNs ?: 0L,
                                accuracyMeters = lastTrustedGpsAccuracyMeters ?: 5f
                            )
                        )
                    } else {
                        emptyList()
                    }
                } else {
                    emptyList()
                }

                val currentLkIds = lkMarkersToRender.map { "lk_marker_${it.id}" }.toSet()
                val existingLkMarkers = map.overlays.filterIsInstance<Marker>().filter { it.id?.startsWith("lk_marker_") == true }

                for (m in existingLkMarkers) {
                    if (m.id !in currentLkIds) {
                        m.closeInfoWindow()
                        map.overlays.remove(m)
                    }
                }

                for (lk in lkMarkersToRender) {
                    val mId = "lk_marker_${lk.id}"
                    val marker = map.overlays.filterIsInstance<Marker>().firstOrNull { it.id == mId }
                        ?: Marker(map).also {
                            it.id = mId
                            it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            it.setFlat(false)
                            it.icon = createLkPinDrawable(map.context)
                            map.overlays.add(it)
                        }

                    marker.position = GeoPoint(lk.latitude, lk.longitude)
                    marker.title = "Last Known GPS Location"
                    val ageSec = if (lk.timestampNs > 0L) {
                        (System.currentTimeMillis() - lk.timestampNs) / 1000L
                    } else {
                        lastKnownLocationAgeSeconds ?: 0L
                    }
                    val formattedAge = when {
                        ageSec < 60 -> "${ageSec}s ago"
                        ageSec < 3600 -> "${ageSec / 60}m ${ageSec % 60}s ago"
                        else -> "${ageSec / 3600}h ${(ageSec % 3600) / 60}m ago"
                    }
                    marker.snippet = "Lat: %.5f, Lon: %.5f\nAccuracy: ±%.0fm\nFix age: %s".format(
                        lk.latitude, lk.longitude, lk.accuracyMeters, formattedAge
                    )

                    marker.setOnMarkerClickListener { m, mv ->
                        m.showInfoWindow()
                        mv.postDelayed({
                            try {
                                if (m.isInfoWindowOpen) {
                                    m.closeInfoWindow()
                                    mv.invalidate()
                                }
                            } catch (_: Throwable) {}
                        }, 5000L)
                        true
                    }
                }

                if (showDiagnosticTrails) {
                    val naivePolyline = map.overlays
                        .filterIsInstance<Polyline>()
                        .firstOrNull { it.id == "naive_trail" }
                        ?: Polyline(map).also {
                            it.id = "naive_trail"
                            it.outlinePaint.color = AndroidColor.argb(200, 220, 38, 38)
                            it.outlinePaint.strokeWidth = 5f
                            map.overlays.add(0, it)
                        }
                    naivePolyline.setPoints(if (blackoutMode && !isDemoModeEnabled) naiveTrail else emptyList())
                }

                // Translucent circular overlay representing uncertainty / accuracy radius
                val uncertaintyCircle = map.overlays
                    .filterIsInstance<Polygon>()
                    .firstOrNull { it.id == "uncertainty_circle" }
                    ?: Polygon(map).also {
                        it.id = "uncertainty_circle"
                        map.overlays.add(0, it)
                    }

                if (isDemoModeEnabled) {
                    if (isKinematicDemoActive) {
                        uncertaintyCircle.fillPaint.color = AndroidColor.argb(35, 245, 158, 11)   // Amber #F59E0B ~14% opacity
                        uncertaintyCircle.outlinePaint.color = AndroidColor.argb(190, 245, 158, 11) // Amber #F59E0B ~75% opacity
                        uncertaintyCircle.outlinePaint.strokeWidth = 3f
                        val demoCenter = GeoPoint(latitude, longitude)
                        val demoUncertainty = if (predictionUncertaintyMeters > 0.0) predictionUncertaintyMeters else 0.0
                        if (isValidMapCoordinate(demoCenter.latitude, demoCenter.longitude) && demoUncertainty > 0.0) {
                            uncertaintyCircle.title = "Simulated search area · ${demoUncertainty.toInt()} m"
                            uncertaintyCircle.setPoints(Polygon.pointsAsCircle(demoCenter, demoUncertainty))
                        } else {
                            uncertaintyCircle.setPoints(emptyList())
                        }
                    } else {
                        uncertaintyCircle.setPoints(emptyList())
                    }
                } else if (blackoutMode || visualMode == com.example.gudumap.navigation.NavigationVisualMode.GPS_FALLBACK) {
                    // Amber #F59E0B circle around active predicted position representing estimated search area
                    uncertaintyCircle.fillPaint.color = AndroidColor.argb(35, 245, 158, 11)   // Amber #F59E0B ~14% opacity
                    uncertaintyCircle.outlinePaint.color = AndroidColor.argb(190, 245, 158, 11) // Amber #F59E0B ~75% opacity
                    uncertaintyCircle.outlinePaint.strokeWidth = 3f

                    val predLat = lastPredictedLat ?: latitude
                    val predLon = lastPredictedLon ?: longitude
                    val predCenter = if (isValidMapCoordinate(predLat, predLon)) GeoPoint(predLat, predLon) else currentPoint
                    val effectiveUncertainty = if (predictionUncertaintyMeters > 0.0) predictionUncertaintyMeters else if (uncertaintyRadiusMeters > 0.0) uncertaintyRadiusMeters else 0.0

                    if (predCenter != null && isValidMapCoordinate(predCenter.latitude, predCenter.longitude) && effectiveUncertainty > 0.0) {
                        uncertaintyCircle.title = "Estimated search area · ${effectiveUncertainty.toInt()} m"
                        uncertaintyCircle.setPoints(Polygon.pointsAsCircle(predCenter, effectiveUncertainty))
                    } else {
                        uncertaintyCircle.setPoints(emptyList())
                    }
                } else {
                    // Blue outline and lightly transparent fill for live GNSS position accuracy radius
                    uncertaintyCircle.fillPaint.color = AndroidColor.argb(30, 37, 99, 235)    // Light transparent Blue 600
                    uncertaintyCircle.outlinePaint.color = AndroidColor.argb(140, 37, 99, 235) // Blue 600 stroke
                    uncertaintyCircle.outlinePaint.strokeWidth = 2f
                    uncertaintyCircle.title = "GPS Accuracy · ${locationAccuracyMeters.toInt()} m"
                    val displayRadius = if (locationAccuracyMeters > 0f) locationAccuracyMeters.toDouble() else uncertaintyRadiusMeters
                    if (hasValidPosition && currentPoint != null && displayRadius > 0.0) {
                        uncertaintyCircle.setPoints(Polygon.pointsAsCircle(currentPoint, displayRadius))
                    } else {
                        uncertaintyCircle.setPoints(emptyList())
                    }
                }

                if (followUser && hasValidPosition && currentPoint != null) {
                    val distMeters = currentPoint.distanceToAsDouble(map.mapCenter)
                    if (distMeters > 3.0) {
                        map.controller.setCenter(currentPoint)
                    }
                }
                map.mapOrientation = 0f
                map.invalidate()
            }
        )

        val isFallbackActive = !isDemoModeEnabled && (blackoutMode || visualMode == com.example.gudumap.navigation.NavigationVisualMode.GPS_FALLBACK)
        val hasValidLocation = if (isDemoModeEnabled || locationProvider == "demo") {
            isValidMapCoordinate(latitude, longitude)
        } else {
            (hasGpsFix || isFallbackActive) && isValidMapCoordinate(latitude, longitude)
        }

        val effectiveLat = if (hasValidLocation) latitude else Double.NaN
        val effectiveLon = if (hasValidLocation) longitude else Double.NaN

        val west = OfflineMapManager.COIMBATORE_BOUNDS.lonWest
        val east = OfflineMapManager.COIMBATORE_BOUNDS.lonEast
        val south = OfflineMapManager.COIMBATORE_BOUNDS.latSouth
        val north = OfflineMapManager.COIMBATORE_BOUNDS.latNorth

        val isInsideMBTiles = hasValidLocation &&
            (effectiveLon >= west && effectiveLon <= east) &&
            (effectiveLat >= south && effectiveLat <= north)

        val coverageState = when {
            !hasValidLocation -> CoverageState.WAITING_FOR_LOCATION
            isInsideMBTiles -> CoverageState.IN_COVERAGE
            else -> CoverageState.OUT_OF_COVERAGE
        }

        Log.d(
            TAG,
            "GPS location: lat=${if (hasGpsFix) latitude else "null"}, lon=${if (hasGpsFix) longitude else "null"} | " +
            "Demo location: lat=${if (isDemoModeEnabled || locationProvider == "demo") latitude else "null"}, lon=${if (isDemoModeEnabled || locationProvider == "demo") longitude else "null"} | " +
            "Effective location: lat=$effectiveLat, lon=$effectiveLon | " +
            "MBTiles bounds: west=$west, south=$south, east=$east, north=$north | " +
            "Inside MBTiles: $isInsideMBTiles | " +
            "Coverage state: $coverageState"
        )

        if (coverageState == CoverageState.OUT_OF_COVERAGE) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp, start = 24.dp, end = 24.dp),
                shape = RoundedCornerShape(12.dp),
                color = if (isDarkMode) Color(0xEE3F1212) else Color(0xEEFEF2F2),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFCA5A5)),
                shadowElevation = 3.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = Color(0xFFB91C1C)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Offline map unavailable for this area",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFB91C1C)
                    )
                }
            }
        }

        // Heading & Orientation Compass Badge (North-Up / Follow-Heading toggle)
        if (isValidMapCoordinate(latitude, longitude)) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 16.dp, start = 12.dp)
                    .size(48.dp)
                    .border(
                        1.dp,
                        if (isDarkMode) Color(0xFF334155) else Color(0xFFE2E8F0),
                        CircleShape
                    )
                    .clickable {
                        osmMapRef?.mapOrientation = 0f
                    },
                shape = CircleShape,
                color = if (isDarkMode) Color(0xEE1E293B) else Color(0xEEFFFFFF),
                shadowElevation = 2.dp
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "N",
                        color = Color(0xFF2563EB),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = "${headingDeg.toInt()}°",
                        color = if (isDarkMode) Color(0xFFCBD5E1) else Color(0xFF334155),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Recenter Floating Action Button (~48dp touch target)
        Surface(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, bottom = 20.dp)
                .size(48.dp)
                .border(
                    1.dp,
                    if (isDarkMode) Color(0xFF334155) else Color(0xFFE2E8F0),
                    CircleShape
                )
                .clickable {
                    followUser = true
                    onMyLocationClick?.invoke()
                    if (isValidMapCoordinate(latitude, longitude)) {
                        val currentPoint = GeoPoint(latitude, longitude)
                        val curZoom = osmMapRef?.zoomLevelDouble ?: 15.5
                        val targetZoom = curZoom.coerceIn(effectiveMinZoom, OfflineMapManager.VISUAL_MAX_ZOOM)
                        osmMapRef?.controller?.setZoom(targetZoom)
                        osmMapRef?.controller?.setCenter(currentPoint)
                        osmMapRef?.invalidate()
                    } else {
                        Toast.makeText(context, "Waiting for location fix", Toast.LENGTH_SHORT).show()
                    }
                },
            shape = CircleShape,
            color = if (isDarkMode) Color(0xFF1E293B) else Color.White,
            shadowElevation = 4.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_my_location),
                    contentDescription = "Recenter map on my position",
                    tint = Color(0xFF2563EB),
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Zoom & Expand Map Control Stack (~48dp touch targets)
        if (onToggleExpand != null || onMinimizeMap != null) {
            val canZoomOut = currentZoomDouble > effectiveMinZoom + 0.05
            val canZoomIn = currentZoomDouble < OfflineMapManager.VISUAL_MAX_ZOOM - 0.05


            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(enabled = canZoomOut) { osmMapRef?.controller?.zoomOut() },
                    shape = CircleShape,
                    color = if (isDarkMode) {
                        if (canZoomOut) Color(0xFF1E293B) else Color(0xFF0F172A)
                    } else {
                        if (canZoomOut) Color.White else Color(0xFFF1F5F9)
                    },
                    shadowElevation = if (canZoomOut) 4.dp else 0.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "-",
                            fontSize = 24.sp,
                            color = if (canZoomOut) {
                                if (isDarkMode) Color.White else Color(0xFF0F172A)
                            } else {
                                if (isDarkMode) Color(0xFF64748B) else Color(0xFF94A3B8)
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Surface(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(enabled = canZoomIn) { osmMapRef?.controller?.zoomIn() },
                    shape = CircleShape,
                    color = if (isDarkMode) {
                        if (canZoomIn) Color(0xFF1E293B) else Color(0xFF0F172A)
                    } else {
                        if (canZoomIn) Color.White else Color(0xFFF1F5F9)
                    },
                    shadowElevation = if (canZoomIn) 4.dp else 0.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "+",
                            fontSize = 24.sp,
                            color = if (canZoomIn) {
                                if (isDarkMode) Color.White else Color(0xFF0F172A)
                            } else {
                                if (isDarkMode) Color(0xFF64748B) else Color(0xFF94A3B8)
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Surface(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable {
                            when {
                                isExpanded -> onToggleExpand?.invoke()
                                isMinimized -> onExpandMap?.invoke() ?: onToggleExpand?.invoke()
                                else -> onToggleExpand?.invoke()
                            }
                        },
                    shape = CircleShape,
                    color = Color(0xFF2563EB),
                    shadowElevation = 4.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(
                                id = if (isExpanded) R.drawable.ic_map_collapse else R.drawable.ic_map_expand
                            ),
                            contentDescription = if (isExpanded) "Exit fullscreen" else "Expand map",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}
