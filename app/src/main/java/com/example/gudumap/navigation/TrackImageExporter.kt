package com.example.gudumap.navigation

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import com.example.gudumap.map.OfflineMapManager
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView as OsmMapView
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Utility for exporting recorded navigation trajectories as high-resolution route map images
 * directly to the phone's Gallery under `Pictures/NAVIATOR`.
 */
object TrackImageExporter {
    private const val TAG = "Gudumap:TrackExporter"
    private const val IMAGE_WIDTH = 1200
    private const val IMAGE_HEIGHT = 1200

    // Layout Architecture Bounds
    private const val HEADER_BOTTOM_Y = 140f
    private const val MAP_BOTTOM_Y = 900f
    private const val MAP_HEIGHT = 760f // 900 - 140

    /**
     * Formats latitude and longitude coordinates with six decimal places and directional indicators (N/S, E/W).
     */
    fun formatCoordinate(lat: Double, lon: Double): String {
        if (!lat.isFinite() || !lon.isFinite()) return "N/A"
        val latDir = if (lat >= 0) "N" else "S"
        val lonDir = if (lon >= 0) "E" else "W"
        return String.format(Locale.US, "%.6f° %s, %.6f° %s", abs(lat), latDir, abs(lon), lonDir)
    }

    /**
     * Renders a 1200x1200px map graphic of the recorded track points and saves it to MediaStore
     * in `Pictures/NAVIATOR`.
     *
     * @return true if successfully generated and saved to Gallery, false if empty or failed.
     */
    fun saveTrackImageToGallery(
        context: Context,
        points: List<GpxTrackPoint>,
        isDemoMode: Boolean = false
    ): Boolean {
        if (points.isEmpty()) {
            Log.w(TAG, "Cannot export empty track points list")
            return false
        }

        return try {
            val bitmap = renderTrackBitmap(context, points, isDemoMode)
            val fileName = "NAVIATOR_Track_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.png"
            val saved = writeBitmapToGallery(context, bitmap, fileName)
            bitmap.recycle()
            saved
        } catch (e: Exception) {
            Log.e(TAG, "Error generating or saving track image: ${e.message}", e)
            false
        }
    }

    private fun renderTrackBitmap(
        context: Context,
        points: List<GpxTrackPoint>,
        isDemoMode: Boolean
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(IMAGE_WIDTH, IMAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. Determine Route Source & Classification
        val isSoftwareDemo = points.isNotEmpty() && (isDemoMode || points.all { it.isBlackout })
        val modeTitle = if (isSoftwareDemo) "SIMULATED DEMO TRAJECTORY" else "LIVE GPS & ESTIMATED TRAJECTORY"
        val legendText = if (isSoftwareDemo) "Red: Simulated route" else "Blue: GPS • Red: Estimated position"

        // 2. Compute Bounding Box with Padding
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE

        for (pt in points) {
            if (pt.lat.isFinite() && pt.lon.isFinite()) {
                minLat = min(minLat, pt.lat)
                maxLat = max(maxLat, pt.lat)
                minLon = min(minLon, pt.lon)
                maxLon = max(maxLon, pt.lon)
            }
        }

        val latSpan = max(maxLat - minLat, 0.002)
        val lonSpan = max(maxLon - minLon, 0.002)

        val paddedMinLat = minLat - latSpan * 0.20
        val paddedMaxLat = maxLat + latSpan * 0.20
        val paddedMinLon = minLon - lonSpan * 0.20
        val paddedMaxLon = maxLon + lonSpan * 0.20

        // Mapping functions strictly inside Map Viewport area (Y: 140f to 900f)
        fun mapLonToX(lon: Double): Float {
            val norm = (lon - paddedMinLon) / (paddedMaxLon - paddedMinLon)
            return (40f + norm * (IMAGE_WIDTH - 80f)).toFloat().coerceIn(40f, IMAGE_WIDTH - 40f)
        }

        fun mapLatToY(lat: Double): Float {
            val norm = (paddedMaxLat - lat) / (paddedMaxLat - paddedMinLat)
            return (HEADER_BOTTOM_Y + 35f + norm * (MAP_HEIGHT - 70f)).toFloat().coerceIn(HEADER_BOTTOM_Y + 35f, MAP_BOTTOM_Y - 35f)
        }

        // 3. Render Map Tile Background in Map Area (Y: 140 to 900)
        var mapBackgroundRendered = false
        val offlineManager = OfflineMapManager(context)
        val isOfflineAvailable = offlineManager.isOfflineMapAvailable()
        val isOnline = offlineManager.isOnline()

        if (isOfflineAvailable || isOnline) {
            try {
                val config = org.osmdroid.config.Configuration.getInstance()
                val osmdroidDir = File(context.filesDir, "osmdroid")
                if (!osmdroidDir.exists()) osmdroidDir.mkdirs()
                val tileCacheDir = File(osmdroidDir, "tiles")
                if (!tileCacheDir.exists()) tileCacheDir.mkdirs()
                config.osmdroidBasePath = osmdroidDir
                config.osmdroidTileCache = tileCacheDir
                config.tileFileSystemCacheMaxBytes = 500L * 1024L * 1024L
                config.tileFileSystemCacheTrimBytes = 400L * 1024L * 1024L

                val tileProvider = if (isOfflineAvailable) {
                    offlineManager.createOfflineTileProvider()
                } else null

                val mapView = when {
                    tileProvider != null -> OsmMapView(context, tileProvider)
                    isOnline -> {
                        val esriSource: ITileSource = XYTileSource(
                            "EsriWorldStreet", 0, 19, 256, ".jpg",
                            arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/")
                        )
                        OsmMapView(context).apply { setTileSource(esriSource) }
                    }
                    else -> null
                }

                if (mapView != null) {
                    mapView.setUseDataConnection(tileProvider == null)
                    mapView.isTilesScaledToDpi = true
                    if (tileProvider != null) {
                        mapView.minZoomLevel = OfflineMapManager.MIN_ZOOM.toDouble()
                        mapView.maxZoomLevel = OfflineMapManager.VISUAL_MAX_ZOOM

                    }

                    mapView.measure(
                        View.MeasureSpec.makeMeasureSpec(IMAGE_WIDTH, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(MAP_HEIGHT.toInt(), View.MeasureSpec.EXACTLY)
                    )
                    mapView.layout(0, 0, IMAGE_WIDTH, MAP_HEIGHT.toInt())

                    val geoPoints = points.map { GeoPoint(it.lat, it.lon) }
                    val bbox = BoundingBox.fromGeoPoints(geoPoints)
                    val latSpanDegrees = bbox.latNorth - bbox.latSouth
                    val lonSpanDegrees = bbox.lonEast - bbox.lonWest
                    val paddedBbox = BoundingBox(
                        (bbox.latNorth + max(latSpanDegrees * 0.20, 0.003)).coerceAtMost(85.0),
                        (bbox.lonEast + max(lonSpanDegrees * 0.20, 0.003)).coerceAtMost(180.0),
                        (bbox.latSouth - max(latSpanDegrees * 0.20, 0.003)).coerceAtLeast(-85.0),
                        (bbox.lonWest - max(lonSpanDegrees * 0.20, 0.003)).coerceAtLeast(-180.0)
                    )

                    mapView.zoomToBoundingBox(paddedBbox, false, 60)
                    mapView.postInvalidate()

                    // Wait bounded timeout (up to 400ms) for tile cache arrival
                    val startWaitTime = System.currentTimeMillis()
                    while (System.currentTimeMillis() - startWaitTime < 400L) {
                        val tileStates = mapView.overlayManager.tilesOverlay.tileStates
                        if (tileStates != null && tileStates.upToDate > 0) {
                            mapBackgroundRendered = true
                            break
                        }
                        try { Thread.sleep(20L) } catch (_: InterruptedException) {}
                    }

                    if (mapBackgroundRendered) {
                        canvas.save()
                        canvas.clipRect(0f, HEADER_BOTTOM_Y, IMAGE_WIDTH.toFloat(), MAP_BOTTOM_Y)
                        canvas.translate(0f, HEADER_BOTTOM_Y)
                        mapView.draw(canvas)
                        canvas.restore()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Offscreen map tile rendering fallback: ${e.message}")
                mapBackgroundRendered = false
            }
        }

        if (!mapBackgroundRendered) {
            // Draw Slate 900 Background & Grid fallback inside Map Viewport
            canvas.save()
            canvas.clipRect(0f, HEADER_BOTTOM_Y, IMAGE_WIDTH.toFloat(), MAP_BOTTOM_Y)
            canvas.drawColor(Color.rgb(15, 23, 42))
            val gridPaint = Paint().apply {
                color = Color.rgb(30, 41, 59)
                strokeWidth = 2f
                style = Paint.Style.STROKE
            }
            var xGrid = 0f
            while (xGrid <= IMAGE_WIDTH) {
                canvas.drawLine(xGrid, HEADER_BOTTOM_Y, xGrid, MAP_BOTTOM_Y, gridPaint)
                xGrid += 100f
            }
            var yGrid = HEADER_BOTTOM_Y
            while (yGrid <= MAP_BOTTOM_Y) {
                canvas.drawLine(0f, yGrid, IMAGE_WIDTH.toFloat(), yGrid, gridPaint)
                yGrid += 100f
            }
            canvas.restore()
        }

        // 4. Setup Line Paints for GPS (Blue) and Estimated/Demo (Red)
        val gpsLinePaint = Paint().apply {
            color = Color.rgb(37, 99, 235) // Blue #2563EB
            strokeWidth = 8f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
        }

        val blackoutLinePaint = Paint().apply {
            color = Color.rgb(220, 38, 38) // Red #DC2626
            strokeWidth = 8f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
        }

        // 5. Draw Trajectory Polylines in Map Viewport
        var realGpsCount = 0
        var blackoutCount = 0
        var totalDistanceMeters = 0.0

        for (i in 1 until points.size) {
            val ptA = points[i - 1]
            val ptB = points[i]

            val xA = mapLonToX(ptA.lon)
            val yA = mapLatToY(ptA.lat)
            val xB = mapLonToX(ptB.lon)
            val yB = mapLatToY(ptB.lat)

            val isSegBlackout = ptB.isBlackout || isSoftwareDemo
            if (isSegBlackout) blackoutCount++ else realGpsCount++

            totalDistanceMeters += CoordinateTransformer.computeDistanceBetween(
                ptA.lat, ptA.lon, ptB.lat, ptB.lon
            )

            val paint = if (isSegBlackout) blackoutLinePaint else gpsLinePaint
            canvas.drawLine(xA, yA, xB, yB, paint)
        }

        // 6. Draw Start (S) & End (E) Markers in Map Viewport
        val startPt = points.first()
        val endPt = points.last()

        fun drawMarker(lat: Double, lon: Double, label: String, colorHex: Int) {
            val cx = mapLonToX(lon)
            val cy = mapLatToY(lat)

            val fillPaint = Paint().apply {
                color = colorHex
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            val borderPaint = Paint().apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 4f
                isAntiAlias = true
            }
            val textPaint = Paint().apply {
                color = Color.WHITE
                textSize = 22f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
            }

            canvas.drawCircle(cx, cy, 22f, fillPaint)
            canvas.drawCircle(cx, cy, 22f, borderPaint)

            val textY = cy - ((textPaint.descent() + textPaint.ascent()) / 2)
            canvas.drawText(label, cx, textY, textPaint)
        }

        drawMarker(startPt.lat, startPt.lon, "S", Color.rgb(22, 163, 74)) // Green #16A34A
        drawMarker(endPt.lat, endPt.lon, "E", Color.rgb(220, 38, 38))    // Red #DC2626

        // 7. Render Header Area (Y: 0 to 140)
        val headerRect = RectF(35f, 15f, IMAGE_WIDTH - 35f, 125f)
        val headerBgPaint = Paint().apply {
            color = Color.argb(240, 15, 23, 42) // Slate 900 opaque
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val headerBorderPaint = Paint().apply {
            color = Color.rgb(51, 65, 85) // Slate 700
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }
        canvas.drawRoundRect(headerRect, 16f, 16f, headerBgPaint)
        canvas.drawRoundRect(headerRect, 16f, 16f, headerBorderPaint)

        val titlePaint = Paint().apply {
            color = Color.WHITE
            textSize = 26f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        val subTitlePaint = Paint().apply {
            color = if (isSoftwareDemo) Color.rgb(245, 158, 11) else Color.rgb(56, 189, 248)
            textSize = 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        val mapStatusPaint = Paint().apply {
            color = if (mapBackgroundRendered) Color.rgb(148, 163, 184) else Color.rgb(239, 68, 68)
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        canvas.drawText("NAVIATOR ROUTE RECORDING", 60f, 52f, titlePaint)
        canvas.drawText(modeTitle, 60f, 80f, subTitlePaint)
        val mapStatusText = if (mapBackgroundRendered) "Map data © OpenStreetMap contributors" else "Map background unavailable"
        canvas.drawText(mapStatusText, 60f, 106f, mapStatusPaint)

        // 8. Render Footer Area (Statistics Panel, Y: 900 to 1180)
        val cardRect = RectF(35f, 900f, IMAGE_WIDTH - 35f, 1180f)
        val cardBgPaint = Paint().apply {
            color = Color.argb(245, 15, 23, 42) // Slate 900 96% opaque
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val cardBorderPaint = Paint().apply {
            color = Color.rgb(71, 85, 105) // Slate 600
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }
        canvas.drawRoundRect(cardRect, 18f, 18f, cardBgPaint)
        canvas.drawRoundRect(cardRect, 18f, 18f, cardBorderPaint)

        val metaHeaderPaint = Paint().apply {
            color = Color.rgb(203, 213, 225) // Slate 300
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        val metaValuePaint = Paint().apply {
            color = Color.WHITE
            textSize = 21f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val durationMs = max(0L, endPt.timestampMs - startPt.timestampMs)
        val durationSec = durationMs / 1000L
        val mins = durationSec / 60
        val secs = durationSec % 60
        val durationStr = String.format(Locale.US, "%02dm %02ds", mins, secs)

        val distKm = totalDistanceMeters / 1000.0
        val distanceStr = String.format(Locale.US, "%.2f km", distKm)

        val avgSpeedKmh = if (durationSec > 0) (distKm / (durationSec / 3600.0)) else 0.0
        val avgSpeedStr = String.format(Locale.US, "%.1f km/h", avgSpeedKmh)

        val dateStr = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US).format(Date(startPt.timestampMs))

        val col1X = 65f
        val col2X = 320f
        val col3X = 580f
        val col4X = 840f

        val row1Y = 930f
        val row2Y = 955f

        canvas.drawText("DISTANCE", col1X, row1Y, metaHeaderPaint)
        canvas.drawText(distanceStr, col1X, row2Y, metaValuePaint)

        canvas.drawText("DURATION", col2X, row1Y, metaHeaderPaint)
        canvas.drawText(durationStr, col2X, row2Y, metaValuePaint)

        canvas.drawText("AVG SPEED", col3X, row1Y, metaHeaderPaint)
        canvas.drawText(avgSpeedStr, col3X, row2Y, metaValuePaint)

        canvas.drawText("DATE & TIME", col4X, row1Y, metaHeaderPaint)
        canvas.drawText(dateStr, col4X, row2Y, metaValuePaint)

        // Divider Line 1
        val dividerPaint = Paint().apply {
            color = Color.rgb(51, 65, 85) // Slate 700
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        canvas.drawLine(65f, 978f, IMAGE_WIDTH - 65f, 978f, dividerPaint)

        // Start & End Coordinates Section
        val startPrefixPaint = Paint().apply {
            color = Color.rgb(34, 197, 94) // Green #22C55E
            textSize = 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        val endPrefixPaint = Paint().apply {
            color = Color.rgb(239, 68, 68) // Red #EF4444
            textSize = 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        val coordValPaint = Paint().apply {
            color = Color.WHITE
            textSize = 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val startPrefix = if (isSoftwareDemo) "Start (S) [Simulated]: " else "Start (S): "
        val endPrefix = if (isSoftwareDemo) "End (E) [Simulated]: " else "End (E): "

        val startCoordStr = formatCoordinate(startPt.lat, startPt.lon)
        val endCoordStr = formatCoordinate(endPt.lat, endPt.lon)

        val coordRow1Y = 1008f
        val coordRow2Y = 1038f

        canvas.drawText(startPrefix, 65f, coordRow1Y, startPrefixPaint)
        val startPrefixWidth = startPrefixPaint.measureText(startPrefix)
        canvas.drawText(startCoordStr, 65f + startPrefixWidth, coordRow1Y, coordValPaint)

        canvas.drawText(endPrefix, 65f, coordRow2Y, endPrefixPaint)
        val endPrefixWidth = endPrefixPaint.measureText(endPrefix)
        canvas.drawText(endCoordStr, 65f + endPrefixWidth, coordRow2Y, coordValPaint)

        // Divider Line 2
        canvas.drawLine(65f, 1060f, IMAGE_WIDTH - 65f, 1060f, dividerPaint)

        // Route Legend & Gallery Output Line
        val legendPaint = Paint().apply {
            color = Color.WHITE
            textSize = 17f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        val pathPaint = Paint().apply {
            color = Color.rgb(148, 163, 184) // Slate 400
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            isAntiAlias = true
        }

        val legendY = 1130f
        canvas.drawText("Legend: $legendText", 65f, legendY, legendPaint)
        canvas.drawText("Saved to Gallery / Pictures / NAVIATOR", 660f, legendY, pathPaint)

        return bitmap
    }

    private fun writeBitmapToGallery(
        context: Context,
        bitmap: Bitmap,
        fileName: String
    ): Boolean {
        var outputStream: OutputStream? = null
        return try {
            val contentResolver = context.contentResolver

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NAVIATOR")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val imageUri = contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                ) ?: throw IllegalStateException("Failed to create MediaStore entry")

                outputStream = contentResolver.openOutputStream(imageUri)
                if (outputStream != null) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                }

                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(imageUri, contentValues, null, null)
                Log.i(TAG, "Successfully saved track PNG image to MediaStore Pictures/NAVIATOR via Scoped Storage: $fileName")
                true
            } else {
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val naviatorDir = File(picturesDir, "NAVIATOR").apply {
                    if (!exists()) mkdirs()
                }
                val imageFile = File(naviatorDir, fileName)
                outputStream = FileOutputStream(imageFile)
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)

                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DATA, imageFile.absolutePath)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                }
                contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                Log.i(TAG, "Successfully saved track PNG image to legacy storage: ${imageFile.absolutePath}")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write bitmap to gallery: ${e.message}", e)
            false
        } finally {
            try {
                outputStream?.close()
            } catch (_: Exception) {}
        }
    }
}
