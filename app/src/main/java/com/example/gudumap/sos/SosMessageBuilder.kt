package com.example.gudumap.sos

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

object SosMessageBuilder {

    /**
     * Builds the formatted emergency SOS SMS message body based on resolved [SosLocation].
     */
    fun buildMessage(sosLoc: SosLocation): String {
        return when (sosLoc.source) {
            SosLocationSource.LIVE_GPS -> buildLiveGpsMessage(sosLoc)
            SosLocationSource.LAST_CONFIRMED_GPS, SosLocationSource.LAST_KNOWN_GPS -> buildLastConfirmedMessage(sosLoc)
            SosLocationSource.PREDICTED_FROM_REAL_GPS -> buildCombinedPredictedMessage(sosLoc)
            SosLocationSource.UNAVAILABLE -> buildUnavailableMessage()
        }
    }

    private fun formatAgeString(ageSeconds: Long): String {
        return when {
            ageSeconds <= 0L -> "just now"
            ageSeconds < 60L -> "$ageSeconds seconds ago"
            else -> {
                val mins = ageSeconds / 60L
                if (mins == 1L) "1 minute ago" else "$mins minutes ago"
            }
        }
    }

    private fun buildLiveGpsMessage(loc: SosLocation): String {
        val mapsUrl = formatMapsUrl(loc.latitude, loc.longitude)
        val timeStr = SimpleDateFormat("HH:mm", Locale.US).format(Date())
        val accuracyLine = if (loc.isAccuracyValid && loc.accuracyMeters > 0f) {
            "\nAccuracy: ±${loc.accuracyMeters.roundToInt()} m"
        } else {
            "\nAccuracy: Unknown"
        }

        return """
            |🆘 EMERGENCY SOS
            |
            |I may need assistance.
            |
            |Current location (Live GPS):
            |$mapsUrl$accuracyLine
            |Time: $timeStr
            |
            |Sent from NAVIGATOR
        """.trimMargin()
    }

    private fun buildLastConfirmedMessage(loc: SosLocation): String {
        val mapsUrl = formatMapsUrl(loc.latitude, loc.longitude)
        val ageStr = formatAgeString(loc.lastFixAgeSeconds)
        val accuracyLine = if (loc.isAccuracyValid && loc.accuracyMeters > 0f) {
            "\nAccuracy at fix: ±${loc.accuracyMeters.roundToInt()} m"
        } else ""

        return """
            |🆘 EMERGENCY SOS
            |
            |I may need assistance.
            |
            |GPS signal is currently unavailable.
            |
            |Last confirmed GPS location:
            |$mapsUrl
            |Fix age: $ageStr$accuracyLine
            |
            |Sent from NAVIGATOR
        """.trimMargin()
    }

    private fun buildCombinedPredictedMessage(loc: SosLocation): String {
        val estMapsUrl = formatMapsUrl(loc.latitude, loc.longitude)
        val uncertainty = loc.uncertaintyRadiusMeters.roundToInt()
        val uncertaintyLine = if (uncertainty > 0) "\nEstimated uncertainty: ±$uncertainty m" else ""

        val lastConfirmedSection = if (loc.hasValidLastTrusted()) {
            val lastTrustedUrl = formatMapsUrl(loc.lastTrustedLat!!, loc.lastTrustedLon!!)
            val ageStr = formatAgeString(loc.lastTrustedAgeSeconds)
            val accLine = if (loc.lastTrustedAccuracyValid && loc.lastTrustedAccuracyMeters > 0f) {
                " (±${loc.lastTrustedAccuracyMeters.roundToInt()} m)"
            } else ""
            "Last confirmed GPS location:\n$lastTrustedUrl\nFix age: $ageStr$accLine\n\n"
        } else ""

        return """
            |🆘 EMERGENCY SOS
            |
            |I may need assistance.
            |
            |GPS signal is unavailable.
            |
            |${lastConfirmedSection}Estimated current location (UNCONFIRMED PREDICTION):
            |$estMapsUrl$uncertaintyLine
            |
            |Note: Estimated position is calculated from real movement sensors during GPS outage and is not a confirmed GPS fix.
            |
            |Sent from NAVIGATOR
        """.trimMargin()
    }

    private fun buildUnavailableMessage(): String {
        return """
            |🆘 EMERGENCY SOS
            |
            |I may need assistance.
            |
            |Current location could not be determined.
            |
            |Sent from NAVIGATOR
        """.trimMargin()
    }

    private fun formatMapsUrl(lat: Double, lon: Double): String {
        return "https://www.google.com/maps/search/?api=1&query=%.6f,%.6f".format(Locale.US, lat, lon)
    }
}
