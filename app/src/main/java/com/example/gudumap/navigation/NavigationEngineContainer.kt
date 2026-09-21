package com.example.gudumap.navigation

import android.content.Context

/**
 * Thread-safe singleton container for [NavigationEngine].
 * Ensures the Compose UI (via ViewModel) and Location Foreground Service share
 * precisely ONE active navigation engine instance without duplicate location listeners,
 * sensor registrations, or inference loops.
 *
 * Automatically recreates a fresh engine if the previous instance was closed.
 */
object NavigationEngineContainer {

    @Volatile
    private var instance: NavigationEngine? = null

    fun getInstance(context: Context): NavigationEngine {
        val current = instance
        if (current != null && !current.isClosed) {
            return current
        }

        return synchronized(this) {
            val syncCurrent = instance
            if (syncCurrent != null && !syncCurrent.isClosed) {
                syncCurrent
            } else {
                NavigationEngine(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    /**
     * Clears the current stored engine instance.
     */
    fun clearInstance() {
        synchronized(this) {
            instance = null
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
