package com.example.gudumap.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class GpxTrackPoint(
    val lat: Double,
    val lon: Double,
    val altMeters: Double = 0.0,
    val timestampMs: Long = System.currentTimeMillis(),
    val speedKmh: Float = 0f,
    val headingDeg: Float = 0f,
    val isBlackout: Boolean = false
)

object GpxExporter {
    private const val TAG = "Gudumap:GpxExporter"

    /**
     * Generates a standard GPX 1.1 XML string from a list of trajectory points.
     */
    fun generateGpxXml(points: List<GpxTrackPoint>, trackName: String = "Naviator Trajectory"): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Naviator Dead Reckoning System\"\n")
        sb.append("     xmlns=\"http://www.topografix.com/GPX/1/1\"\n")
        sb.append("     xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n")
        sb.append("     xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
        sb.append("  <metadata>\n")
        sb.append("    <name>").append(escapeXml(trackName)).append("</name>\n")
        sb.append("    <time>").append(dateFormat.format(Date())).append("</time>\n")
        sb.append("    <desc>Offline Dead Reckoning &amp; GNSS trajectory log captured by Naviator</desc>\n")
        sb.append("  </metadata>\n")
        sb.append("  <trk>\n")
        sb.append("    <name>").append(escapeXml(trackName)).append("</name>\n")
        sb.append("    <trkseg>\n")

        for (pt in points) {
            sb.append(String.format(Locale.US, "      <trkpt lat=\"%.7f\" lon=\"%.7f\">\n", pt.lat, pt.lon))
            sb.append(String.format(Locale.US, "        <ele>%.2f</ele>\n", pt.altMeters))
            sb.append("        <time>").append(dateFormat.format(Date(pt.timestampMs))).append("</time>\n")
            sb.append("        <extensions>\n")
            sb.append(String.format(Locale.US, "          <speedKmh>%.2f</speedKmh>\n", pt.speedKmh))
            sb.append(String.format(Locale.US, "          <headingDeg>%.1f</headingDeg>\n", pt.headingDeg))
            sb.append("          <blackoutMode>").append(pt.isBlackout).append("</blackoutMode>\n")
            sb.append("        </extensions>\n")
            sb.append("      </trkpt>\n")
        }

        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")

        return sb.toString()
    }

    /**
     * Generates a standard CSV string from a list of trajectory points.
     */
    fun generateCsv(points: List<GpxTrackPoint>): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val sb = StringBuilder()
        sb.append("timestamp,latitude,longitude,altitude_m,speed_kmh,heading_deg,is_blackout\n")

        for (pt in points) {
            sb.append(
                String.format(
                    Locale.US,
                    "%s,%.7f,%.7f,%.2f,%.2f,%.1f,%b\n",
                    dateFormat.format(Date(pt.timestampMs)),
                    pt.lat,
                    pt.lon,
                    pt.altMeters,
                    pt.speedKmh,
                    pt.headingDeg,
                    pt.isBlackout
                )
            )
        }

        return sb.toString()
    }

    /**
     * Generates a JSON string from a list of trajectory points.
     */
    fun generateJson(points: List<GpxTrackPoint>, trackName: String = "Naviator Trajectory"): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"trackName\": \"").append(escapeJson(trackName)).append("\",\n")
        sb.append("  \"pointCount\": ").append(points.size).append(",\n")
        sb.append("  \"points\": [\n")

        points.forEachIndexed { index, pt ->
            sb.append("    {\n")
            sb.append("      \"timestamp\": \"").append(dateFormat.format(Date(pt.timestampMs))).append("\",\n")
            sb.append(String.format(Locale.US, "      \"latitude\": %.7f,\n", pt.lat))
            sb.append(String.format(Locale.US, "      \"longitude\": %.7f,\n", pt.lon))
            sb.append(String.format(Locale.US, "      \"altitudeMeters\": %.2f,\n", pt.altMeters))
            sb.append(String.format(Locale.US, "      \"speedKmh\": %.2f,\n", pt.speedKmh))
            sb.append(String.format(Locale.US, "      \"headingDeg\": %.1f,\n", pt.headingDeg))
            sb.append("      \"isBlackout\": ").append(pt.isBlackout).append("\n")
            sb.append("    }").append(if (index < points.size - 1) "," else "").append("\n")
        }

        sb.append("  ]\n")
        sb.append("}\n")

        return sb.toString()
    }

    private fun escapeJson(text: String): String {
        return text.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\u000C", "\\f")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    /**
     * Saves GPX XML string to internal files directory under "gpx_tracks".
     */
    fun saveGpxToFile(context: Context, gpxXml: String, fileName: String? = null): File? {
        return try {
            val gpxDir = File(context.filesDir, "gpx_tracks").apply {
                if (!exists()) mkdirs()
            }
            val actualFileName = fileName ?: "naviator_track_${System.currentTimeMillis()}.gpx"
            val targetFile = File(gpxDir, actualFileName)
            targetFile.writeText(gpxXml)
            Log.i(TAG, "GPX file successfully written to ${targetFile.absolutePath} (${gpxXml.length} bytes)")
            targetFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save GPX file: ${e.message}", e)
            null
        }
    }

    /**
     * Creates an Android Intent chooser to share/export a track file with appropriate MIME type.
     */
    fun shareGpxFile(context: Context, file: File, mimeType: String = "application/gpx+xml") {
        try {
            val contentUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareIntent, "Export Naviator Track File").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share track file: ${e.message}", e)
        }
    }

    private fun escapeXml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
