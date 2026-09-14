package com.example.gudumap.sos

import android.util.Log

/**
 * Dedicated configuration file for SOS Emergency Location feature.
 * Emergency contact numbers can be manually edited in this file.
 */
object SosConfig {

    private const val TAG = "Gudumap:SosConfig"

    /**
     * Emergency mobile phone number recipients.
     */
    val emergencyContacts: List<String> = listOf(
        "+919361313801"
    )

    /**
     * Countdown duration in seconds before SOS SMS composer is launched.
     */
    const val SOS_COUNTDOWN_SECONDS: Int = 5

    /**
     * Returns sanitized non-blank emergency contact numbers.
     */
    fun getValidContacts(): List<String> {
        val valid = emergencyContacts
            .map { sanitizePhoneNumber(it) }
            .filter { it.isNotBlank() && !isPlaceholder(it) }
        try { Log.i(TAG, "SOS contacts configured: ${valid.size}") } catch (_: Throwable) {}
        return valid
    }

    /**
     * Checks if valid emergency contacts are present.
     */
    fun hasValidContacts(): Boolean {
        return getValidContacts().isNotEmpty()
    }

    /**
     * Sanitizes phone number strings by removing spaces and hyphens.
     */
    fun sanitizePhoneNumber(number: String): String {
        return number.replace(" ", "").replace("-", "").trim()
    }

    private fun isPlaceholder(number: String): Boolean {
        return number.contains("X", ignoreCase = true) || number.isEmpty()
    }
}
