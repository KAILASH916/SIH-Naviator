package com.example.gudumap.navigation

import android.content.Context

/**
 * Thread-safe singleton container for [NavigationEngine].
 * Ensures the Compose UI (via ViewModel) and the Location Foreground Service share
 * precisely ONE navigation engine instance without duplicate location listeners,
 * sensor registrations, or inference loops.
 */
object NavigationEngineContainer {

    @Volatile
    private var instance: NavigationEngine? = null

    fun getInstance(context: Context): NavigationEngine {
        return instance ?: synchronized(this) {
            instance ?: NavigationEngine(context.applicationContext).also {
                instance = it
            }
        }
    }

    /**
     * For unit test isolation: sets or clears the shared instance.
     */
    fun setTestInstance(engine: NavigationEngine?) {
        synchronized(this) {
            instance = engine
        }
    }
}
