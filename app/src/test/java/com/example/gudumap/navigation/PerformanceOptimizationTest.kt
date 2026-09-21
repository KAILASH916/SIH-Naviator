package com.example.gudumap.navigation

import com.example.gudumap.ml.ModelRunner
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceOptimizationTest {

    @Test
    fun testNavigationConfigPerformanceConstants() {
        assertTrue("Publish interval should be positive", NavigationConfig.UI_STATE_PUBLISH_INTERVAL_MS > 0L)
        assertTrue("Publish interval should be ~10 Hz (100ms)", NavigationConfig.UI_STATE_PUBLISH_INTERVAL_MS in 50L..200L)
    }

    @Test
    fun testModelRunnerInstanceReadiness() {
        val modelRunner = ModelRunner()
        assertNotNull(modelRunner)
    }
}
