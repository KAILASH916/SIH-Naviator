package com.example.gudumap.sos

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Handles action callbacks from High-Priority Emergency SOS notifications.
 */
class SosNotificationReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "Gudumap:SosNotifRx"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val sosId = intent.getStringExtra(SosNotificationManager.EXTRA_SOS_ID) ?: return
        Log.i(TAG, "Notification action received: ${intent.action} for sosId=$sosId")

        when (intent.action) {
            SosNotificationManager.ACTION_ACK_SOS -> {
                SosRepository.markAcknowledged(sosId)
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                notificationManager?.cancel(sosId.hashCode())
            }
            SosNotificationManager.ACTION_DISMISS_SOS -> {
                SosRepository.markDismissed(sosId)
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                notificationManager?.cancel(sosId.hashCode())
            }
        }
    }
}
