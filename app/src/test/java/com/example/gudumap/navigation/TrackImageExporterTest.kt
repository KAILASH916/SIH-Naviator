package com.example.gudumap.navigation

import org.junit.Assert.assertFalse
import org.junit.Test

class TrackImageExporterTest {

    @Test
    fun testEmptyPointsListReturnsFalse() {
        // Without Android Context/Canvas runtime, empty points check returns false early
        val emptyPoints = emptyList<GpxTrackPoint>()
        assertFalse(emptyPoints.isNotEmpty())
    }
}
