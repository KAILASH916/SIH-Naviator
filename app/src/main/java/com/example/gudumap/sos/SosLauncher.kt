package com.example.gudumap.sos

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast

object SosLauncher {

    private const val TAG = "Gudumap:SosLauncher"

    /**
     * Launches native Android SMS composer with emergency contacts and pre-filled message text.
     * Operates completely offline without internet connectivity.
     * The user must press Send inside the messaging app.
     */
    fun launchSmsComposer(context: Context, contacts: List<String>, messageText: String): Boolean {
        if (contacts.isEmpty()) {
            Toast.makeText(context, "No emergency contacts configured", Toast.LENGTH_LONG).show()
            return false
        }

        val recipientString = contacts.joinToString(";")
        val smsUri = Uri.parse("smsto:$recipientString")

        val sendToIntent = Intent(Intent.ACTION_SENDTO, smsUri).apply {
            putExtra("sms_body", messageText)
            putExtra(Intent.EXTRA_TEXT, messageText)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        return try {
            context.startActivity(sendToIntent)
            Toast.makeText(context, "Message prepared. Tap Send in your messaging app.", Toast.LENGTH_LONG).show()
            Log.i(TAG, "SMS composer opened via ACTION_SENDTO for ${contacts.size} contacts")
            true
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_SENDTO failed: ${e.message}, trying ACTION_SEND fallback")
            launchSmsFallback(context, contacts, messageText)
        }
    }

    private fun launchSmsFallback(context: Context, contacts: List<String>, messageText: String): Boolean {
        return try {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, messageText)
                putExtra("address", contacts.joinToString(";"))
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val chooser = Intent.createChooser(sendIntent, "Send Emergency SOS via")
            chooser.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(chooser)
            Toast.makeText(context, "Message prepared. Select your messaging app and recipients if needed.", Toast.LENGTH_LONG).show()
            Log.i(TAG, "SMS composer opened via ACTION_SEND chooser fallback")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch SMS intent: ${e.message}")
            Toast.makeText(context, "Unable to launch messaging app on this device", Toast.LENGTH_LONG).show()
            false
        }
    }
}
