package com.example.gudumap.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxExporterTest {

    @Test
    fun testGenerateGpxXmlStructure() {
        val points = listOf(
            GpxTrackPoint(
                lat = 11.0168,
                lon = 76.9558,
                altMeters = 411.5,
                timestampMs = 1700000000000L,
                speedKmh = 25.4f,
                headingDeg = 180.0f,
                isBlackout = false
            ),
            GpxTrackPoint(
                lat = 11.0175,
                lon = 76.9565,
                altMeters = 412.0,
                timestampMs = 1700000005000L,
                speedKmh = 30.1f,
                headingDeg = 185.5f,
                isBlackout = true
            )
        )

        val xml = GpxExporter.generateGpxXml(points, "Coimbatore Test Track")

        assertTrue(xml.contains("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(xml.contains("<gpx version=\"1.1\" creator=\"Naviator Dead Reckoning System\""))
        assertTrue(xml.contains("<name>Coimbatore Test Track</name>"))
        assertTrue(xml.contains("lat=\"11.0168000\" lon=\"76.9558000\""))
        assertTrue(xml.contains("lat=\"11.0175000\" lon=\"76.9565000\""))
        assertTrue(xml.contains("<ele>411.50</ele>"))
        assertTrue(xml.contains("<ele>412.00</ele>"))
        assertTrue(xml.contains("<blackoutMode>false</blackoutMode>"))
        assertTrue(xml.contains("<blackoutMode>true</blackoutMode>"))
        assertTrue(xml.contains("</trkseg>"))
        assertTrue(xml.contains("</trk>"))
        assertTrue(xml.contains("</gpx>"))
    }

    @Test
    fun testEmptyPointsListGeneratesValidHeader() {
        val xml = GpxExporter.generateGpxXml(emptyList(), "Empty Track")
        assertTrue(xml.contains("<name>Empty Track</name>"))
        assertTrue(xml.contains("<trkseg>"))
        assertTrue(xml.contains("</trkseg>"))
    }

    @Test
    fun testGenerateCsvStructure() {
        val points = listOf(
            GpxTrackPoint(
                lat = 11.0168,
                lon = 76.9558,
                altMeters = 411.5,
                timestampMs = 1700000000000L,
                speedKmh = 25.4f,
                headingDeg = 180.0f,
                isBlackout = false
            )
        )
        val csv = GpxExporter.generateCsv(points)
        assertTrue(csv.contains("timestamp,latitude,longitude,altitude_m,speed_kmh,heading_deg,is_blackout"))
        assertTrue(csv.contains("11.0168000"))
        assertTrue(csv.contains("76.9558000"))
        assertTrue(csv.contains("411.50"))
        assertTrue(csv.contains("25.40"))
        assertTrue(csv.contains("180.0"))
        assertTrue(csv.contains("false"))
    }

    @Test
    fun testGenerateJsonStructure() {
        val points = listOf(
            GpxTrackPoint(
                lat = 11.0168,
                lon = 76.9558,
                altMeters = 411.5,
                timestampMs = 1700000000000L,
                speedKmh = 25.4f,
                headingDeg = 180.0f,
                isBlackout = true
            )
        )
        val json = GpxExporter.generateJson(points, "Test Session")
        assertTrue(json.contains("\"trackName\": \"Test Session\""))
        assertTrue(json.contains("\"pointCount\": 1"))
        assertTrue(json.contains("\"latitude\": 11.0168000"))
        assertTrue(json.contains("\"longitude\": 76.9558000"))
        assertTrue(json.contains("\"isBlackout\": true"))
    }
}
