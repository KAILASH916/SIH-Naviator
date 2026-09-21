package com.example.gudumap.ui.screens

import android.location.Location
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gudumap.sos.EmergencySosPacket
import com.example.gudumap.sos.PositionSource
import com.example.gudumap.sos.ReceivedSosItem
import kotlin.math.roundToInt

private fun calculateStraightLineDistanceMeters(
    myLat: Double?,
    myLon: Double?,
    targetLat: Double?,
    targetLon: Double?
): Int? {
    if (myLat == null || myLon == null || targetLat == null || targetLon == null) return null
    if (!myLat.isFinite() || !myLon.isFinite() || !targetLat.isFinite() || !targetLon.isFinite()) return null
    if (myLat == 0.0 && myLon == 0.0) return null

    val results = FloatArray(1)
    Location.distanceBetween(myLat, myLon, targetLat, targetLon, results)
    return results[0].roundToInt()
}

@Composable
fun ReceivedSosDialog(
    item: ReceivedSosItem,
    myCurrentLat: Double? = null,
    myCurrentLon: Double? = null,
    isDarkMode: Boolean = false,
    onViewOnMap: (Double, Double) -> Unit,
    onAcknowledge: (String) -> Unit,
    onDismiss: (String) -> Unit
) {
    val packet = item.packet
    val distMeters = calculateStraightLineDistanceMeters(
        myCurrentLat, myCurrentLon,
        packet.currentLatitude, packet.currentLongitude
    )

    val positionSourceLabel = when (packet.positionSource) {
        PositionSource.GNSS -> "Live GNSS Fix"
        PositionSource.FUSED -> "Fused Position"
        PositionSource.DEAD_RECKONING -> "AI/INS Estimated Position"
        PositionSource.UNKNOWN -> "Estimated Position"
    }

    val timeAgoSec = (System.currentTimeMillis() - item.receivedAtEpochMs) / 1000L
    val timeAgoText = when {
        timeAgoSec < 60 -> "Just now"
        timeAgoSec < 3600 -> "${timeAgoSec / 60}m ago"
        else -> "${timeAgoSec / 3600}h ago"
    }

    AlertDialog(
        onDismissRequest = { onDismiss(packet.sosId) },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🆘 ", fontSize = 20.sp)
                    Text(
                        text = "EMERGENCY SOS NEARBY",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFFDC2626)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Text(
                    text = "A nearby user activated emergency SOS over Bluetooth.",
                    fontSize = 12.5.sp,
                    color = if (isDarkMode) Color(0xFFCBD5E1) else Color(0xFF475569)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Detail Box
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isDarkMode) Color(0xFF0F172A) else Color(0xFFF8FAFC)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            if (isDarkMode) Color(0xFF334155) else Color(0xFFE2E8F0),
                            RoundedCornerShape(12.dp)
                        )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Current Position",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B)
                        )
                        Text(
                            text = if (packet.hasValidPosition()) {
                                "%.6f°, %.6f°".format(packet.currentLatitude, packet.currentLongitude)
                            } else "Position Unavailable",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (isDarkMode) Color.White else Color(0xFF0F172A)
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                Text(
                                    text = "Position Source",
                                    fontSize = 10.5.sp,
                                    color = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B)
                                )
                                Text(
                                    text = positionSourceLabel,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF2563EB)
                                )
                            }

                            if (packet.estimatedUncertaintyMeters != null && packet.estimatedUncertaintyMeters > 0) {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "Uncertainty",
                                        fontSize = 10.5.sp,
                                        color = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B)
                                    )
                                    Text(
                                        text = "±%.0f m".format(packet.estimatedUncertaintyMeters),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isDarkMode) Color(0xFFCBD5E1) else Color(0xFF334155)
                                    )
                                }
                            }
                        }

                        if (distMeters != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (isDarkMode) Color(0xFF1E293B) else Color(0xFFEFF6FF),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = "📍 Approximately $distMeters m away (straight-line)",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF1D4ED8)
                                )
                            }
                        }

                        if (packet.lastTrustedGnssLatitude != null && packet.lastTrustedGnssLongitude != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Last Confirmed GNSS: %.5f, %.5f".format(
                                    packet.lastTrustedGnssLatitude,
                                    packet.lastTrustedGnssLongitude
                                ),
                                fontSize = 10.5.sp,
                                color = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B)
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Received: $timeAgoText • ID: ${packet.sosId.take(8)}",
                            fontSize = 10.sp,
                            color = if (isDarkMode) Color(0xFF64748B) else Color(0xFF94A3B8)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (packet.hasValidPosition()) {
                    Button(
                        onClick = { onViewOnMap(packet.currentLatitude!!, packet.currentLongitude!!) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("VIEW ON MAP", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = { onAcknowledge(packet.sosId) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (item.isAcknowledged) Color(0xFF16A34A) else Color(0xFFD97706)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            if (item.isAcknowledged) "ACKNOWLEDGED ✓" else "ACKNOWLEDGE",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = Color.White
                        )
                    }

                    OutlinedButton(
                        onClick = { onDismiss(packet.sosId) },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("DISMISS", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }
            }
        },
        containerColor = if (isDarkMode) Color(0xFF1E293B) else Color.White,
        shape = RoundedCornerShape(20.dp)
    )
}
