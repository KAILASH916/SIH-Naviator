package com.example.gudumap.sos

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.gudumap.MainActivity

object SosNotificationManager {

    private const val TAG = "Gudumap:SosNotification"
    private const val CHANNEL_ID = "emergency_sos_nearby_channel"
    private const val CHANNEL_NAME = "Emergency SOS Nearby"
    private const val FOREGROUND_CHANNEL_ID = "emergency_sos_active_channel"
    private const val FOREGROUND_CHANNEL_NAME = "Emergency SOS Active Service"

    const val ACTION_VIEW_SOS = "com.example.gudumap.sos.ACTION_VIEW_SOS"
    const val ACTION_ACK_SOS = "com.example.gudumap.sos.ACTION_ACK_SOS"
    const val ACTION_DISMISS_SOS = "com.example.gudumap.sos.ACTION_DISMISS_SOS"
    const val EXTRA_SOS_ID = "extra_sos_id"

    private fun safeLogI(message: String) {
        try { Log.i(TAG, message) } catch (_: Throwable) { println("$TAG: $message") }
    }

    private fun safeLogW(message: String) {
        try { Log.w(TAG, message) } catch (_: Throwable) { println("$TAG: $message") }
    }

    fun initNotificationChannels(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

                val nearbyChannel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "High-priority notifications for nearby emergency SOS alerts"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 500, 200, 500)
                    setShowBadge(true)
                }
                notificationManager.createNotificationChannel(nearbyChannel)

                val activeChannel = NotificationChannel(
                    FOREGROUND_CHANNEL_ID,
                    FOREGROUND_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Foreground service notification during active SOS broadcasting"
                    setShowBadge(false)
                }
                notificationManager.createNotificationChannel(activeChannel)
            }
        } catch (e: Throwable) {
            safeLogW("Failed to init notification channels: ${e.message}")
        }
    }

    fun showReceivedSosNotification(context: Context, packet: EmergencySosPacket): Boolean {
        initNotificationChannels(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNotifPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            if (!hasNotifPermission) {
                safeLogW("SOS_RECEIVED_NOTIFICATION_PERMISSION_DENIED for sosId=${packet.sosId}")
                return false
            }
        }

        val notificationManager = try {
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        } catch (_: Throwable) {
            null
        }

        if (notificationManager == null) {
            safeLogW("NotificationManager null, skipping notification display")
            return false
        }

        val latStr = packet.currentLatitude?.let { "%.5f".format(it) } ?: "Unknown"
        val lonStr = packet.currentLongitude?.let { "%.5f".format(it) } ?: "Unknown"
        val sourceStr = when (packet.positionSource) {
            PositionSource.GNSS -> "Live GNSS"
            PositionSource.FUSED -> "Fused Location"
            PositionSource.DEAD_RECKONING -> "AI/INS Dead Reckoning"
            PositionSource.UNKNOWN -> "Estimated Position"
        }
        val uncertaintyStr = packet.estimatedUncertaintyMeters?.let { " (±%.0fm)".format(it) } ?: ""

        val viewIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_VIEW_SOS
            putExtra(EXTRA_SOS_ID, packet.sosId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val viewPendingIntent = PendingIntent.getActivity(
            context,
            packet.sosId.hashCode(),
            viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val ackIntent = Intent(context, SosNotificationReceiver::class.java).apply {
            action = ACTION_ACK_SOS
            putExtra(EXTRA_SOS_ID, packet.sosId)
        }
        val ackPendingIntent = PendingIntent.getBroadcast(
            context,
            packet.sosId.hashCode() + 1,
            ackIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissIntent = Intent(context, SosNotificationReceiver::class.java).apply {
            action = ACTION_DISMISS_SOS
            putExtra(EXTRA_SOS_ID, packet.sosId)
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context,
            packet.sosId.hashCode() + 2,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("🆘 EMERGENCY SOS NEARBY")
            .setContentText("Nearby user requires assistance ($latStr, $lonStr)")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "A nearby user requires assistance.\n" +
                            "Current estimated location:\n$latStr, $lonStr\n\n" +
                            "Position source: $sourceStr$uncertaintyStr"
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(viewPendingIntent)
            .addAction(android.R.drawable.ic_menu_mapmode, "VIEW LOCATION", viewPendingIntent)
            .addAction(android.R.drawable.ic_menu_send, "ACKNOWLEDGE", ackPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "DISMISS", dismissPendingIntent)

        return try {
            notificationManager.notify(packet.sosId.hashCode(), builder.build())
            safeLogI("SOS_NOTIFICATION_POSTED for sosId=${packet.sosId}")
            true
        } catch (e: Exception) {
            safeLogW("Failed to display SOS notification: ${e.message}")
            false
        }
    }

    fun getForegroundServiceNotification(context: Context, statusText: String): android.app.Notification {
        initNotificationChannels(context)

        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Emergency SOS Active")
            .setContentText(statusText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }
}
