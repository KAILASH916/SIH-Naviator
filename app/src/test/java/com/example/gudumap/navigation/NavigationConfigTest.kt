package com.example.gudumap.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationConfigTest {

    @Test
    fun testNavigationConfigConstantsAndBounds() {
        // GNSS Freshness & Timeout bounds
        assertEquals(5000L, NavigationConfig.GPS_FRESHNESS_MS)
        assertEquals(3000L, NavigationConfig.GPS_DEGRADED_TIMEOUT_MS)
        assertEquals(5000L, NavigationConfig.GPS_SUSTAINED_BLACKOUT_TIMEOUT_MS)
        assertEquals(30000L, NavigationConfig.MAX_ACCEPTED_FIX_AGE_MS)
        assertEquals(2, NavigationConfig.REQUIRED_RECOVERY_FIX_COUNT)
        assertEquals(25.0f, NavigationConfig.MAX_GOOD_ACCURACY_METERS, 1e-4f)

        // Engine tickers
        assertEquals(80L, NavigationConfig.STATE_INTERVAL_MS)
        assertEquals(250L, NavigationConfig.STATUS_TICK_MS)

        // Speedometer parameters
        assertTrue("Alpha should be between 0 and 1", NavigationConfig.SPEEDOMETER_ALPHA in 0.01f..1.0f)
        assertEquals(0.5f, NavigationConfig.SPEED_ZERO_SNAP_THRESHOLD_KMH, 1e-4f)

        // Ground-truth evaluation
        assertEquals(10L, NavigationConfig.EVALUATION_MODE_DURATION_SEC)
        assertEquals(10000L, NavigationConfig.EVALUATION_MODE_DURATION_MS)

        // ZUPT defaults
        assertEquals(0.25f, NavigationConfig.DEFAULT_ACC_MAG_THRESHOLD, 1e-4f)
        assertEquals(0.04f, NavigationConfig.DEFAULT_ACC_VAR_THRESHOLD, 1e-4f)
        assertEquals(0.10f, NavigationConfig.DEFAULT_GYRO_MAG_THRESHOLD, 1e-4f)
        assertEquals(4, NavigationConfig.DEFAULT_MIN_CONSECUTIVE_ZUPT_SAMPLES)
    }
}
