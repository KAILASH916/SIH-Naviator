package com.example.gudumap.sos

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color(0xFFDC2626),
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Broadcast Emergency SOS?",
                    fontSize = 17.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isDarkMode) Color.White else Color(0xFF0F172A)
                )
            }
        },
        text = {
            Column {
                Text(
                    text = "Broadcast an emergency location alert to nearby devices via Bluetooth Low Energy (BLE)?",
                    fontSize = 13.5.sp,
                    color = if (isDarkMode) Color(0xFFCBD5E1) else Color(0xFF475569)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "ℹ️ Operates completely offline without GPS, cellular, or internet using AI/INS fused position. Nearby devices with this app installed will receive a high-priority emergency alert.",
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
                Text("SEND SOS NOW", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, color = Color.White)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (isDarkMode) Color(0xFF0F172A) else Color(0xFFF8FAFC),
                    contentColor = if (isDarkMode) Color(0xFFF1F5F9) else Color(0xFF0F172A)
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isDarkMode) Color(0xFF475569) else Color(0xFF94A3B8)
                )
            ) {
                Text(
                    text = "CANCEL",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = if (isDarkMode) Color(0xFFF1F5F9) else Color(0xFF0F172A)
                )
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
                text = "🆘 SOS BROADCAST STARTING",
                fontSize = 17.sp,
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
                    text = "Broadcasting BLE Emergency SOS in:",
                    fontSize = 13.sp,
                    color = if (isDarkMode) Color(0xFFCBD5E1) else Color(0xFF475569)
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "$secondsLeft",
                    fontSize = 52.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFFDC2626),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Nearby app users will receive emergency alert with your best estimated location.",
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
fun SosBluetoothDisabledDialog(
    isDarkMode: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Bluetooth Disabled",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = if (isDarkMode) Color.White else Color(0xFF0F172A)
            )
        },
        text = {
            Text(
                text = "Bluetooth is required to broadcast emergency SOS alerts to nearby devices.\n\nPlease enable Bluetooth to proceed.",
                fontSize = 13.sp,
                color = if (isDarkMode) Color(0xFFCBD5E1) else Color(0xFF475569)
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    onDismiss()
                    try {
                        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                    } catch (_: Exception) {}
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("ENABLE BLUETOOTH", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
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
fun SosCancelConfirmationDialog(
    isDarkMode: Boolean,
    onConfirmCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Cancel Active Emergency SOS?",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = if (isDarkMode) Color.White else Color(0xFF0F172A)
            )
        },
        text = {
            Text(
                text = "This will stop BLE emergency broadcasting and terminate the active SOS alert session.",
                fontSize = 13.sp,
                color = if (isDarkMode) Color(0xFFCBD5E1) else Color(0xFF475569)
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirmCancel,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("CONFIRM CANCEL", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, color = Color.White)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("KEEP SOS ACTIVE", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        },
        containerColor = if (isDarkMode) Color(0xFF1E293B) else Color.White,
        shape = RoundedCornerShape(18.dp)
    )
}

@Composable
fun SosActiveCard(
    status: SosStatus,
    ackCount: Int,
    isDarkMode: Boolean,
    onCancelSosClick: () -> Unit,
    onSendSmsClick: (() -> Unit)? = null
) {
    val statusText = when (status) {
        SosStatus.BROADCASTING -> "Broadcasting to nearby devices..."
        SosStatus.ACKNOWLEDGED -> "SOS received by $ackCount nearby device${if (ackCount != 1) "s" else ""}"
        SosStatus.BLUETOOTH_DISABLED -> "Bluetooth disabled — cannot broadcast"
        SosStatus.PERMISSION_REQUIRED -> "Bluetooth permissions required"
        SosStatus.NO_DEVICE_FOUND -> "No nearby compatible devices detected yet"
        else -> "SOS Active"
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isDarkMode) Color(0xFF3f1212) else Color(0xFFFEF2F2)
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .border(
                1.5.dp,
                Color(0xFFDC2626),
                RoundedCornerShape(16.dp)
            )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(Color(0xFFDC2626), CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "🆘 SOS BROADCAST ACTIVE",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFFDC2626)
                    )
                }

                Button(
                    onClick = onCancelSosClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("CANCEL SOS", fontSize = 10.5.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = statusText,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                color = if (isDarkMode) Color(0xFFFCA5A5) else Color(0xFF991B1B)
            )

            if (ackCount > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "✓ Acknowledged by $ackCount nearby app user${if (ackCount != 1) "s" else ""}",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF16A34A)
                )
            }

            if (onSendSmsClick != null) {
                Spacer(modifier = Modifier.height(10.dp))
                androidx.compose.material3.HorizontalDivider(
                    color = if (isDarkMode) Color(0xFF521B1B) else Color(0xFFFCA5A5),
                    thickness = 1.dp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Other delivery options:",
                        fontSize = 11.sp,
                        color = if (isDarkMode) Color(0xFFFCA5A5) else Color(0xFF7F1D1D)
                    )
                    OutlinedButton(
                        onClick = onSendSmsClick,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFDC2626))
                    ) {
                        Text("💬 SEND VIA SMS", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
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

@Composable
fun SosActiveDialog(
    status: SosStatus,
    ackCount: Int,
    isDarkMode: Boolean,
    onDismiss: () -> Unit,
    onCancelSosClick: () -> Unit,
    onSendSmsClick: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = null,
        text = {
            SosActiveCard(
                status = status,
                ackCount = ackCount,
                isDarkMode = isDarkMode,
                onCancelSosClick = {
                    onDismiss()
                    onCancelSosClick()
                },
                onSendSmsClick = onSendSmsClick
            )
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
            ) {
                Text("CLOSE", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
            }
        },
        containerColor = if (isDarkMode) Color(0xFF1E293B) else Color.White,
        shape = RoundedCornerShape(20.dp)
    )
}

