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
     * Creates an Android Intent chooser to share/export the GPX file.
     */
    fun shareGpxFile(context: Context, file: File) {
        try {
            val contentUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareIntent, "Export Naviator GPX Track").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share GPX file: ${e.message}", e)
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
