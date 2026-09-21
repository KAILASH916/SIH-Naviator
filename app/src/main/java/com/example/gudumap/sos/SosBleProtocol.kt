package com.example.gudumap.sos

import java.util.UUID

object SosBleProtocol {
    /** Fixed Service UUID for Gudumap BLE Emergency SOS */
    val SOS_SERVICE_UUID: UUID = UUID.fromString("0000FA10-0000-1000-8000-00805F9B34FB")

    /** Fixed Characteristic UUID for reading full Emergency SOS JSON payload */
    val SOS_PACKET_CHARACTERISTIC_UUID: UUID = UUID.fromString("a8f40002-9f2b-4b19-95e3-4f9011110002")

    /** Fixed Characteristic UUID for writing ACK responses from receivers */
    val SOS_ACK_CHARACTERISTIC_UUID: UUID = UUID.fromString("a8f40003-9f2b-4b19-95e3-4f9011110003")

    const val PROTOCOL_MARKER: String = "NAVI_SOS_V1"
    const val PROTOCOL_VERSION: Int = 1
    const val DEFAULT_TTL_MS: Long = 30 * 60 * 1000L // 30 Minutes TTL
    const val MANUFACTURER_ID: Int = 0x05FF // Custom test/reserved manufacturer ID for BLE frame
}
