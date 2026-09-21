package com.example.gudumap.navigation

import android.location.Location

enum class GnssQualityScore {
    EXCELLENT,
    GOOD,
    FAIR,
    POOR,
    LOST
}

data class GnssQualityResult(
    val qualityScore: GnssQualityScore = GnssQualityScore.LOST,
    val scoreName: String = "LOST",
    val accuracyMeters: Float = 999f,
    val fixAgeMs: Long = Long.MAX_VALUE,
    val isTrusted: Boolean = false
)

/**
 * Multi-criteria GNSS Quality Evaluator with Hysteresis and Internet Independence.
 */
class GnssQualityEvaluator {

    var currentQualityScore: GnssQualityScore = GnssQualityScore.LOST
        private set

    private var candidateScore: GnssQualityScore = GnssQualityScore.LOST
    private var candidateCount: Int = 0

    companion object {
        private const val CONFIRMATION_COUNT = 2
    }

    /**
     * Evaluates GNSS quality based on primitive parameters (safe for unit tests).
     */
    fun evaluate(accuracyMeters: Float, fixAgeMs: Long, speedAccuracyMps: Float = 2.0f): GnssQualityResult {
        if (fixAgeMs > 10_000L) {
            updateQualityState(GnssQualityScore.LOST)
            return GnssQualityResult(
                qualityScore = currentQualityScore,
                scoreName = currentQualityScore.name,
                accuracyMeters = 999f,
                fixAgeMs = fixAgeMs,
                isTrusted = false
            )
        }

        val evaluatedScore = when {
            accuracyMeters <= 5.0f && fixAgeMs <= 1500L && speedAccuracyMps <= 0.5f -> GnssQualityScore.EXCELLENT
            accuracyMeters <= 15.0f && fixAgeMs <= 3000L -> GnssQualityScore.GOOD
            accuracyMeters <= 35.0f && fixAgeMs <= 5000L -> GnssQualityScore.FAIR
            accuracyMeters > 35.0f || fixAgeMs > 5000L -> GnssQualityScore.POOR
            else -> GnssQualityScore.LOST
        }

        updateQualityState(evaluatedScore)

        val isTrusted = currentQualityScore == GnssQualityScore.EXCELLENT || currentQualityScore == GnssQualityScore.GOOD

        return GnssQualityResult(
            qualityScore = currentQualityScore,
            scoreName = currentQualityScore.name,
            accuracyMeters = accuracyMeters,
            fixAgeMs = fixAgeMs,
            isTrusted = isTrusted
        )
    }

    /**
     * Evaluates incoming location attributes strictly using hardware GPS parameters.
     */
    fun evaluateLocation(location: Location?, fixAgeMs: Long): GnssQualityResult {
        if (location == null) {
            return evaluate(999f, Long.MAX_VALUE, 2.0f)
        }

        val accuracy = try {
            if (location.hasAccuracy()) location.accuracy else 50f
        } catch (_: Exception) {
            50f
        }

        val speedAccuracy = try {
            if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else 2f
        } catch (_: Exception) {
            2f
        }

        return evaluate(accuracy, fixAgeMs, speedAccuracy)
    }


    private fun updateQualityState(newScore: GnssQualityScore) {
        if (newScore == currentQualityScore) {
            candidateCount = 0
            return
        }

        if (newScore == candidateScore) {
            candidateCount++
            if (candidateCount >= CONFIRMATION_COUNT) {
                currentQualityScore = newScore
                candidateCount = 0
            }
        } else {
            candidateScore = newScore
            candidateCount = 1
        }
    }

    fun reset() {
        currentQualityScore = GnssQualityScore.LOST
        candidateScore = GnssQualityScore.LOST
        candidateCount = 0
    }
}
