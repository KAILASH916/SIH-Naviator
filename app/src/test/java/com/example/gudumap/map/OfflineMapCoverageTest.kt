package com.example.gudumap.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineMapCoverageTest {

    @Test
    fun testCoimbatoreBoundsCheck() {
        val bounds = OfflineMapManager.COIMBATORE_BOUNDS

        // Coimbatore center point (11.0168, 76.9558) MUST be inside bounds
        assertTrue(bounds.contains(11.0168, 76.9558))

        // Point in Chennai (13.0827, 80.2707) MUST be outside bounds
        assertFalse(bounds.contains(13.0827, 80.2707))

        // Point in Bangalore (12.9716, 77.5946) MUST be outside bounds
        assertFalse(bounds.contains(12.9716, 77.5946))
    }

    @Test
    fun testOfflineMapMetadataZoomLevelsAndBounds() {
        val metadata = OfflineMapMetadata(
            regionId = "coimbatore",
            regionName = "Coimbatore Metropolitan Area",
            bounds = OfflineMapManager.COIMBATORE_BOUNDS,
            minZoom = OfflineMapManager.MIN_ZOOM,
            maxZoom = OfflineMapManager.MAX_ZOOM,
            tileCount = 915
        )

        assertEquals("Coimbatore Metropolitan Area", metadata.regionName)
        assertEquals(11, metadata.minZoom)
        assertEquals(16, metadata.maxZoom)
        assertEquals(915, metadata.tileCount)
    }
}
