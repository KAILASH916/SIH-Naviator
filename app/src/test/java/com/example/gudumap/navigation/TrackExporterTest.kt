package com.example.gudumap.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackExporterTest {

    private val samplePoints = listOf(
        GpxTrackPoint(
            lat = 11.0168,
            lon = 76.9558,
            altMeters = 410.5,
            timestampMs = 1700000000000L,
            speedKmh = 12.5f,
            headingDeg = 90.0f,
            isBlackout = false
        ),
        GpxTrackPoint(
            lat = 11.0170,
            lon = 76.9560,
            altMeters = 411.0,
            timestampMs = 1700000005000L,
            speedKmh = 14.0f,
            headingDeg = 95.0f,
            isBlackout = true
        )
    )

    @Test
    fun testGpxXmlGeneration() {
        val xml = GpxExporter.generateGpxXml(samplePoints, "Test Track")
        assertTrue("GPX header present", xml.contains("<?xml version=\"1.1\"") || xml.contains("<?xml version=\"1.0\""))
        assertTrue("Track name present", xml.contains("<name>Test Track</name>"))
        assertTrue("Latitude present", xml.contains("lat=\"11.0168000\""))
        assertTrue("Longitude present", xml.contains("lon=\"76.9558000\""))
        assertTrue("Blackout mode extension present", xml.contains("<blackoutMode>true</blackoutMode>"))
    }

    @Test
    fun testCsvGeneration() {
        val csv = GpxExporter.generateCsv(samplePoints)
        val lines = csv.trim().split("\n")
        assertEquals(3, lines.size) // Header + 2 rows
        assertEquals("timestamp,latitude,longitude,altitude_m,speed_kmh,heading_deg,is_blackout", lines[0])
        assertTrue("CSV first point lat", lines[1].contains("11.0168000"))
        assertTrue("CSV first point lon", lines[1].contains("76.9558000"))
        assertTrue("CSV second point blackout true", lines[2].contains("true"))
    }

    @Test
    fun testJsonGeneration() {
        val json = GpxExporter.generateJson(samplePoints, "JSON Test Track")
        assertTrue("JSON trackName present", json.contains("\"trackName\": \"JSON Test Track\""))
        assertTrue("JSON pointCount present", json.contains("\"pointCount\": 2"))
        assertTrue("JSON lat present", json.contains("\"latitude\": 11.0168000"))
        assertTrue("JSON blackout present", json.contains("\"isBlackout\": true"))
    }
}
