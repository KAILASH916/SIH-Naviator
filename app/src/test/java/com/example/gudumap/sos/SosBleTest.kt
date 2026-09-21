package com.example.gudumap.sos

import com.example.gudumap.navigation.NavigationState
import com.example.gudumap.navigation.NavigationVisualMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class SosBleTest {

    @Before
    fun setUp() {
        SosRepository.cancelActiveSos()
        SosRepository.setStatus(SosStatus.IDLE)
    }

    @Test
    fun testCentralizedServiceUuidEquality() {
        val fixedUuid: UUID = SosBleProtocol.SOS_SERVICE_UUID
        val expectedUuidStr = "0000fa10-0000-1000-8000-00805f9b34fb"

        assertEquals(expectedUuidStr, fixedUuid.toString().lowercase())

        // Verify Advertiser UUID, Scan Filter UUID, and GATT Service UUID use the centralized fixed UUID
        val advertiserUuid = SosBleProtocol.SOS_SERVICE_UUID
        val scanFilterUuid = SosBleProtocol.SOS_SERVICE_UUID
        val gattServiceUuid = SosBleProtocol.SOS_SERVICE_UUID

        assertEquals(fixedUuid, advertiserUuid)
        assertEquals(fixedUuid, scanFilterUuid)
        assertEquals(fixedUuid, gattServiceUuid)
        assertEquals(advertiserUuid, scanFilterUuid)
        assertEquals(scanFilterUuid, gattServiceUuid)
    }

    @Test
    fun testEmergencySosPacketJsonSerializationAndDeserialization() {
        val original = EmergencySosPacket(
            sosId = "sos_test_12345",
            protocolVersion = 1,
            createdAtEpochMs = 1700000000000L,
            currentLatitude = 11.0168,
            currentLongitude = 76.9558,
            lastTrustedGnssLatitude = 11.0185,
            lastTrustedGnssLongitude = 76.9572,
            positionSource = PositionSource.DEAD_RECKONING,
            estimatedUncertaintyMeters = 18.5,
            blackoutDurationMs = 45000L,
            emergencyMessage = "Medical Emergency",
            senderAnonymousId = "anon_device_1",
            ttlEpochMs = System.currentTimeMillis() + 600000L
        )

        val jsonString = original.toJson()
        val restored = EmergencySosPacket.fromJson(jsonString)

        assertNotNull(restored)
        assertEquals("sos_test_12345", restored!!.sosId)
        assertEquals(1, restored.protocolVersion)
        assertEquals(11.0168, restored.currentLatitude!!, 1e-6)
        assertEquals(76.9558, restored.currentLongitude!!, 1e-6)
        assertEquals(11.0185, restored.lastTrustedGnssLatitude!!, 1e-6)
        assertEquals(76.9572, restored.lastTrustedGnssLongitude!!, 1e-6)
        assertEquals(PositionSource.DEAD_RECKONING, restored.positionSource)
        assertEquals(18.5, restored.estimatedUncertaintyMeters!!, 1e-3)
        assertEquals(45000L, restored.blackoutDurationMs)
        assertEquals("Medical Emergency", restored.emergencyMessage)
        assertEquals("anon_device_1", restored.senderAnonymousId)
    }

    @Test
    fun testInvalidOrMalformedPacketRejection() {
        // Missing sosId
        val badJson1 = """{"protocolVersion":1, "currentLatitude":11.0}"""
        assertNull(EmergencySosPacket.fromJson(badJson1))

        // Unsupported protocol version
        val badJson2 = """{"sosId":"s1", "protocolVersion":99}"""
        assertNull(EmergencySosPacket.fromJson(badJson2))

        // Expired packet
        val expiredJson = """{"sosId":"s2", "protocolVersion":1, "ttlEpochMs":1000}"""
        assertNull(EmergencySosPacket.fromJson(expiredJson))
    }

    @Test
    fun testSosTtlExpiry() {
        val now = System.currentTimeMillis()
        val expiredPacket = EmergencySosPacket(ttlEpochMs = now - 5000L)
        assertTrue(expiredPacket.isExpired(now))

        val validPacket = EmergencySosPacket(ttlEpochMs = now + 60000L)
        assertFalse(validPacket.isExpired(now))
    }

    @Test
    fun testAckPacketJsonSerialization() {
        val ack = SosAckPacket(sosId = "sos_99", receiverAnonymousId = "rec_77")
        val json = ack.toJson()
        val restored = SosAckPacket.fromJson(json)

        assertNotNull(restored)
        assertEquals("sos_99", restored!!.sosId)
        assertEquals("rec_77", restored.receiverAnonymousId)
    }

    @Test
    fun testRepositoryDuplicateSuppression() {
        val packet = EmergencySosPacket(sosId = "dup_test_1", currentLatitude = 11.0168, currentLongitude = 76.9558)

        val addedFirst = SosRepository.addReceivedSos(packet)
        assertTrue(addedFirst)

        val addedSecond = SosRepository.addReceivedSos(packet)
        assertFalse("Duplicate sosId must be suppressed", addedSecond)
    }

    @Test
    fun testRepositoryAckTrackingAndStatusTransition() {
        val packet = EmergencySosPacket(sosId = "sos_ack_test", currentLatitude = 11.0168, currentLongitude = 76.9558)
        SosRepository.setActivePacket(packet)

        assertEquals(SosStatus.STARTING, SosRepository.sosStatus.value)
        assertEquals(0, SosRepository.acknowledgedReceivers.value.size)

        SosRepository.setStatus(SosStatus.BROADCASTING)
        assertEquals(SosStatus.BROADCASTING, SosRepository.sosStatus.value)

        // Receive ACK 1
        val ack1 = SosAckPacket(sosId = "sos_ack_test", receiverAnonymousId = "device_A")
        SosRepository.addAck(ack1)

        assertEquals(SosStatus.ACKNOWLEDGED, SosRepository.sosStatus.value)
        assertEquals(1, SosRepository.acknowledgedReceivers.value.size)

        // Duplicate ACK 1 from device_A
        SosRepository.addAck(ack1)
        assertEquals(1, SosRepository.acknowledgedReceivers.value.size)

        // Receive ACK 2 from device_B
        val ack2 = SosAckPacket(sosId = "sos_ack_test", receiverAnonymousId = "device_B")
        SosRepository.addAck(ack2)
        assertEquals(2, SosRepository.acknowledgedReceivers.value.size)
    }

    @Test
    fun testRepositoryCancelSos() {
        val packet = EmergencySosPacket(sosId = "sos_cancel_test")
        SosRepository.setActivePacket(packet)
        assertEquals(SosStatus.STARTING, SosRepository.sosStatus.value)

        SosRepository.setStatus(SosStatus.BROADCASTING)
        assertEquals(SosStatus.BROADCASTING, SosRepository.sosStatus.value)

        SosRepository.cancelActiveSos()
        assertEquals(SosStatus.CANCELLED, SosRepository.sosStatus.value)
        assertNull(SosRepository.activeSosPacket.value)
    }

    @Test
    fun testPositionSourceMappingInBlackoutMode() {
        val blackoutState = NavigationState(
            latitude = 11.0250,
            longitude = 76.9650,
            blackoutMode = true,
            visualMode = NavigationVisualMode.GPS_FALLBACK,
            predictionUncertaintyMeters = 22.0,
            lastTrustedGpsLat = 11.0185,
            lastTrustedGpsLon = 76.9572
        )

        val resolvedLoc = SosLocationResolver.resolve(blackoutState)
        assertEquals(SosLocationSource.PREDICTED_FROM_REAL_GPS, resolvedLoc.source)
        assertEquals(11.0250, resolvedLoc.latitude, 1e-6)
        assertEquals(76.9650, resolvedLoc.longitude, 1e-6)
        assertEquals(11.0185, resolvedLoc.lastTrustedLat!!, 1e-6)
    }
}
