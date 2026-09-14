package com.example.gudumap.sos

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private fun triggerHapticFeedback(context: Context) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            val vibrator = vibratorManager?.defaultVibrator
            vibrator?.vibrate(VibrationEffect.createOneShot(200L, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            @Suppress("DEPRECATION")
            vibrator?.vibrate(200L)
        }
    } catch (_: Exception) {
        // Haptic feedback failure should never crash the app
    }
}

@Composable
fun SosConfirmationDialog(
    isDarkMode: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Emergency SOS",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = if (isDarkMode) Color.White else Color(0xFF0F172A)
            )
        },
        text = {
            Column {
                Text(
                    text = "Prepare an emergency location SMS for your configured contacts?",
                    fontSize = 14.sp,
                    color = if (isDarkMode) Color(0xFFCBD5E1) else Color(0xFF475569)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "ℹ️ Preparing the message operates completely offline. Sending depends on available cell service.",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("START SOS", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, color = Color.White)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("CANCEL", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        },
        containerColor = if (isDarkMode) Color(0xFF1E293B) else Color.White,
        shape = RoundedCornerShape(18.dp)
    )
}

@Composable
fun SosCountdownDialog(
    isDarkMode: Boolean,
    initialSeconds: Int = SosConfig.SOS_COUNTDOWN_SECONDS,
    onCountdownComplete: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    var secondsLeft by remember { mutableIntStateOf(initialSeconds) }

    LaunchedEffect(Unit) {
        triggerHapticFeedback(context)
        while (secondsLeft > 0) {
            delay(1000L)
            secondsLeft -= 1
        }
        onCountdownComplete()
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                text = "🆘 SOS ACTIVATING",
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
                color = Color(0xFFDC2626)
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Preparing emergency message in:",
                    fontSize = 13.sp,
                    color = if (isDarkMode) Color(0xFFCBD5E1) else Color(0xFF475569)
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "$secondsLeft",
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFFDC2626),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Message will open in your native SMS app ready to send.",
                    fontSize = 11.sp,
                    color = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B),
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = {
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                onClick = onCancel,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("CANCEL SOS", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = Color.White)
            }
        },
        containerColor = if (isDarkMode) Color(0xFF1E293B) else Color.White,
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
fun SosNoContactsDialog(
    isDarkMode: Boolean,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "No Emergency Contacts",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = if (isDarkMode) Color.White else Color(0xFF0F172A)
            )
        },
        text = {
            Text(
                text = "No valid emergency contacts configured in SosConfig.kt.\n\nPlease edit SosConfig.kt and add your emergency mobile phone numbers.",
                fontSize = 13.sp,
                color = if (isDarkMode) Color(0xFFCBD5E1) else Color(0xFF475569)
            )
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("OK", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        },
        containerColor = if (isDarkMode) Color(0xFF1E293B) else Color.White,
        shape = RoundedCornerShape(18.dp)
    )
}
