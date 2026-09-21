package com.example.gudumap.sos

import java.util.UUID

enum class PositionSource {
    GNSS,
    FUSED,
    DEAD_RECKONING,
    UNKNOWN
}

data class EmergencySosPacket(
    val sosId: String = UUID.randomUUID().toString(),
    val protocolVersion: Int = 1,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val currentLatitude: Double? = null,
    val currentLongitude: Double? = null,
    val lastTrustedGnssLatitude: Double? = null,
    val lastTrustedGnssLongitude: Double? = null,
    val positionSource: PositionSource = PositionSource.UNKNOWN,
    val estimatedUncertaintyMeters: Double? = null,
    val blackoutDurationMs: Long = 0L,
    val emergencyMessage: String? = null,
    val senderAnonymousId: String = UUID.randomUUID().toString().take(8),
    val ttlEpochMs: Long = System.currentTimeMillis() + 30 * 60 * 1000L // 30 mins
) {
    fun isExpired(currentTimeMs: Long = System.currentTimeMillis()): Boolean {
        return currentTimeMs > ttlEpochMs
    }

    fun hasValidPosition(): Boolean {
        val lat = currentLatitude
        val lon = currentLongitude
        return lat != null && lon != null &&
            lat.isFinite() && lon.isFinite() &&
            lat in -90.0..90.0 && lon in -180.0..180.0 &&
            (lat != 0.0 || lon != 0.0)
    }

    fun toJson(): String {
        fun escape(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        return buildString {
            append("{")
            append("\"sosId\":").append(escape(sosId)).append(",")
            append("\"protocolVersion\":").append(protocolVersion).append(",")
            append("\"createdAtEpochMs\":").append(createdAtEpochMs).append(",")
            append("\"currentLatitude\":").append(currentLatitude?.toString() ?: "null").append(",")
            append("\"currentLongitude\":").append(currentLongitude?.toString() ?: "null").append(",")
            append("\"lastTrustedGnssLatitude\":").append(lastTrustedGnssLatitude?.toString() ?: "null").append(",")
            append("\"lastTrustedGnssLongitude\":").append(lastTrustedGnssLongitude?.toString() ?: "null").append(",")
            append("\"positionSource\":").append(escape(positionSource.name)).append(",")
            append("\"estimatedUncertaintyMeters\":").append(estimatedUncertaintyMeters?.toString() ?: "null").append(",")
            append("\"blackoutDurationMs\":").append(blackoutDurationMs).append(",")
            append("\"emergencyMessage\":").append(if (emergencyMessage != null) escape(emergencyMessage) else "null").append(",")
            append("\"senderAnonymousId\":").append(escape(senderAnonymousId)).append(",")
            append("\"ttlEpochMs\":").append(ttlEpochMs)
            append("}")
        }
    }

    companion object {
        private fun extractField(json: String, key: String): String? {
            val pattern = Regex("\"$key\"\\s*:\\s*(\"[^\"]*\"|null|true|false|-?\\d+\\.?\\d*)")
            val match = pattern.find(json) ?: return null
            val valueGroup = match.groupValues[1].trim()
            if (valueGroup == "null") return null
            if (valueGroup.startsWith("\"") && valueGroup.endsWith("\"")) {
                return valueGroup.substring(1, valueGroup.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
            }
            return valueGroup
        }

        fun fromJson(jsonStr: String): EmergencySosPacket? {
            return try {
                val sosId = extractField(jsonStr, "sosId") ?: return null
                if (sosId.isBlank()) return null

                val versionStr = extractField(jsonStr, "protocolVersion") ?: "1"
                val version = versionStr.toIntOrNull() ?: 1
                if (version != 1) return null

                val createdAt = extractField(jsonStr, "createdAtEpochMs")?.toLongOrNull() ?: System.currentTimeMillis()
                val ttl = extractField(jsonStr, "ttlEpochMs")?.toLongOrNull() ?: (System.currentTimeMillis() + 30 * 60 * 1000L)

                if (System.currentTimeMillis() > ttl) return null

                val lat = extractField(jsonStr, "currentLatitude")?.toDoubleOrNull()
                val lon = extractField(jsonStr, "currentLongitude")?.toDoubleOrNull()
                val lastGnssLat = extractField(jsonStr, "lastTrustedGnssLatitude")?.toDoubleOrNull()
                val lastGnssLon = extractField(jsonStr, "lastTrustedGnssLongitude")?.toDoubleOrNull()

                val posSourceStr = extractField(jsonStr, "positionSource") ?: "UNKNOWN"
                val posSource = try {
                    PositionSource.valueOf(posSourceStr)
                } catch (_: Exception) {
                    PositionSource.UNKNOWN
                }

                val uncertainty = extractField(jsonStr, "estimatedUncertaintyMeters")?.toDoubleOrNull()
                val blackoutDuration = extractField(jsonStr, "blackoutDurationMs")?.toLongOrNull() ?: 0L
                val msg = extractField(jsonStr, "emergencyMessage")
                val senderId = extractField(jsonStr, "senderAnonymousId") ?: "anon"

                EmergencySosPacket(
                    sosId = sosId,
                    protocolVersion = version,
                    createdAtEpochMs = createdAt,
                    currentLatitude = if (lat != null && lat.isFinite()) lat else null,
                    currentLongitude = if (lon != null && lon.isFinite()) lon else null,
                    lastTrustedGnssLatitude = if (lastGnssLat != null && lastGnssLat.isFinite()) lastGnssLat else null,
                    lastTrustedGnssLongitude = if (lastGnssLon != null && lastGnssLon.isFinite()) lastGnssLon else null,
                    positionSource = posSource,
                    estimatedUncertaintyMeters = if (uncertainty != null && uncertainty.isFinite()) uncertainty else null,
                    blackoutDurationMs = blackoutDuration,
                    emergencyMessage = msg,
                    senderAnonymousId = senderId,
                    ttlEpochMs = ttl
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

data class SosAckPacket(
    val sosId: String,
    val receiverAnonymousId: String,
    val timestampEpochMs: Long = System.currentTimeMillis()
) {
    fun toJson(): String {
        fun escape(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        return buildString {
            append("{")
            append("\"sosId\":").append(escape(sosId)).append(",")
            append("\"receiverAnonymousId\":").append(escape(receiverAnonymousId)).append(",")
            append("\"timestampEpochMs\":").append(timestampEpochMs)
            append("}")
        }
    }

    companion object {
        private fun extractField(json: String, key: String): String? {
            val pattern = Regex("\"$key\"\\s*:\\s*(\"[^\"]*\"|null|true|false|-?\\d+\\.?\\d*)")
            val match = pattern.find(json) ?: return null
            val valueGroup = match.groupValues[1].trim()
            if (valueGroup == "null") return null
            if (valueGroup.startsWith("\"") && valueGroup.endsWith("\"")) {
                return valueGroup.substring(1, valueGroup.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
            }
            return valueGroup
        }

        fun fromJson(jsonStr: String): SosAckPacket? {
            return try {
                val sosId = extractField(jsonStr, "sosId") ?: return null
                val receiverId = extractField(jsonStr, "receiverAnonymousId") ?: return null
                if (sosId.isBlank() || receiverId.isBlank()) return null
                val ts = extractField(jsonStr, "timestampEpochMs")?.toLongOrNull() ?: System.currentTimeMillis()
                SosAckPacket(sosId, receiverId, ts)
            } catch (e: Exception) {
                null
            }
        }
    }
}
