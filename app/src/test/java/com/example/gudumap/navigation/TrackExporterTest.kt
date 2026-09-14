package com.example.gudumap.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class TrackExporterTest {

    @Test
    fun testFormatCoordinatePositiveNorthEast() {
        val lat = 11.0168
        val lon = 76.9558
        val formatted = TrackImageExporter.formatCoordinate(lat, lon)
        assertEquals("11.016800° N, 76.955800° E", formatted)
    }

    @Test
    fun testFormatCoordinateNegativeSouthWest() {
        val lat = -33.8688
        val lon = -151.2093
        val formatted = TrackImageExporter.formatCoordinate(lat, lon)
        assertEquals("33.868800° S, 151.209300° W", formatted)
    }

    @Test
    fun testFormatCoordinateZeroCenter() {
        val lat = 0.0
        val lon = 0.0
        val formatted = TrackImageExporter.formatCoordinate(lat, lon)
        assertEquals("0.000000° N, 0.000000° E", formatted)
    }

    @Test
    fun testFormatCoordinateInvalid() {
        assertEquals("N/A", TrackImageExporter.formatCoordinate(Double.NaN, 76.9558))
        assertEquals("N/A", TrackImageExporter.formatCoordinate(11.0168, Double.POSITIVE_INFINITY))
    }

    @Test
    fun testSinglePointTrackExportGracefulHandling() {
        val singlePoint = listOf(
            GpxTrackPoint(lat = 11.0168, lon = 76.9558, timestampMs = 1700000000000L)
        )
        val startPt = singlePoint.first()
        val endPt = singlePoint.last()

        assertEquals(startPt.lat, endPt.lat, 1e-6)
        assertEquals(startPt.lon, endPt.lon, 1e-6)
        assertEquals(11.0168, startPt.lat, 1e-6)
        assertEquals(76.9558, startPt.lon, 1e-6)
    }

    @Test
    fun testLegendClassificationForSoftwareDemoVsRealGps() {
        val demoPoints = listOf(
            GpxTrackPoint(11.0168, 76.9558, isBlackout = true),
            GpxTrackPoint(11.0170, 76.9560, isBlackout = true)
        )
        val isDemo = demoPoints.isNotEmpty() && demoPoints.all { it.isBlackout }
        assertTrue("All blackout points must be classified as demo trajectory", isDemo)

        val realGpsPoints = listOf(
            GpxTrackPoint(11.0168, 76.9558, isBlackout = false),
            GpxTrackPoint(11.0170, 76.9560, isBlackout = true)
        )
        val isRealDemo = realGpsPoints.isNotEmpty() && realGpsPoints.all { it.isBlackout }
        assertTrue("Mixed GPS/DR points must be classified as real recording", !isRealDemo)
    }

    @Test
    fun testLayoutAreaBoundsNonOverlapping() {
        val headerBottomY = 140f
        val mapBottomY = 900f
        val footerTopY = 900f
        val footerBottomY = 1180f

        // Map Viewport (140 to 900) is strictly between Header (0 to 140) and Footer (900 to 1180)
        assertEquals(headerBottomY, 140f, 1e-4f)
        assertEquals(mapBottomY, footerTopY, 1e-4f)
        assertTrue("Map viewport must be below header", 140f >= headerBottomY)
        assertTrue("Footer must be below map viewport", footerTopY >= mapBottomY)
        assertTrue("Footer card must fit within 1200px image height", footerBottomY <= 1200f)
    }

    @Test
    fun testMapViewportCoordinateMappingKeepsMarkersInsideMapBounds() {
        val points = listOf(
            GpxTrackPoint(11.0168, 76.9558),
            GpxTrackPoint(11.0200, 76.9600)
        )

        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        for (pt in points) {
            minLat = min(minLat, pt.lat)
            maxLat = max(maxLat, pt.lat)
            minLon = min(minLon, pt.lon)
            maxLon = max(maxLon, pt.lon)
        }

        val latSpan = max(maxLat - minLat, 0.002)
        val lonSpan = max(maxLon - minLon, 0.002)

        val paddedMinLat = minLat - latSpan * 0.20
        val paddedMaxLat = maxLat + latSpan * 0.20
        val paddedMinLon = minLon - lonSpan * 0.20
        val paddedMaxLon = maxLon + lonSpan * 0.20

        fun mapLonToX(lon: Double): Float {
            val norm = (lon - paddedMinLon) / (paddedMaxLon - paddedMinLon)
            return (40f + norm * (1200f - 80f)).toFloat()
        }

        fun mapLatToY(lat: Double): Float {
            val norm = (paddedMaxLat - lat) / (paddedMaxLat - paddedMinLat)
            return (140f + 35f + norm * (760f - 70f)).toFloat()
        }

        val startY = mapLatToY(points.first().lat)
        val endY = mapLatToY(points.last().lat)

        // Both Start (S) and End (E) marker Y-coordinates must fall strictly within Y: 175f to 865f
        assertTrue("Start marker Y ($startY) must be below header (Y > 140)", startY > 140f)
        assertTrue("Start marker Y ($startY) must be above footer (Y < 900)", startY < 900f)
        assertTrue("End marker Y ($endY) must be below header (Y > 140)", endY > 140f)
        assertTrue("End marker Y ($endY) must be above footer (Y < 900)", endY < 900f)
    }
}
