package com.example.gudumap.sos

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

class SosBleManager(private val context: Context) {

    companion object {
        private const val TAG = "Gudumap:SosBle"
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var scanner: BluetoothLeScanner? = null

    private var isAdvertising = false
    private var isScanning = false
    private var pendingPacketForAdv: EmergencySosPacket? = null

    private val connectingDevices = ConcurrentHashMap<String, Long>()

    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled
    }

    fun isBleSupported(): Boolean {
        val hasFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        return bluetoothAdapter != null && hasFeature
    }

    fun isMultipleAdvertisementSupported(): Boolean {
        return bluetoothAdapter != null && bluetoothAdapter.isMultipleAdvertisementSupported
    }

    fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun hasAdvertisePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Starts BLE Advertising and GATT Server for active SOS broadcasting.
     * Guaranteed to wait for GATT service addition before starting BLE advertiser.
     */
    @Synchronized
    fun startSosBroadcast(packet: EmergencySosPacket): Boolean {
        Log.i(TAG, "BLE_ADVERTISE_REQUESTED for sosId=${packet.sosId}")

        if (!isBluetoothEnabled()) {
            Log.w(TAG, "Bluetooth disabled, cannot start BLE advertisement")
            SosRepository.setStatus(SosStatus.BLUETOOTH_DISABLED)
            return false
        }

        if (!isBleSupported()) {
            Log.e(TAG, "BLE hardware not supported on this device")
            SosRepository.setStatus(SosStatus.ADVERTISING_UNSUPPORTED)
            return false
        }

        if (!isMultipleAdvertisementSupported()) {
            Log.e(TAG, "isMultipleAdvertisementSupported=false on this device")
            SosRepository.setStatus(SosStatus.ADVERTISING_UNSUPPORTED)
            return false
        }

        if (!hasAdvertisePermission() || !hasConnectPermission()) {
            Log.w(TAG, "Missing BLUETOOTH_ADVERTISE or BLUETOOTH_CONNECT runtime permissions")
            SosRepository.setStatus(SosStatus.PERMISSION_REQUIRED)
            return false
        }

        try {
            stopSosBroadcast()
            pendingPacketForAdv = packet

            // 1. Setup GATT Server to serve full SOS payload and receive ACK writes
            gattServer = bluetoothManager?.openGattServer(context, gattServerCallback)
            if (gattServer == null) {
                Log.e(TAG, "Failed to open GATT server")
                SosRepository.setStatus(SosStatus.ERROR)
                return false
            }

            val service = BluetoothGattService(
                SosBleProtocol.SOS_SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )

            // Packet Characteristic (READ)
            val packetChar = BluetoothGattCharacteristic(
                SosBleProtocol.SOS_PACKET_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )

            // ACK Characteristic (WRITE / WRITE_NO_RESPONSE)
            val ackChar = BluetoothGattCharacteristic(
                SosBleProtocol.SOS_ACK_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )

            service.addCharacteristic(packetChar)
            service.addCharacteristic(ackChar)

            val addSuccess = gattServer?.addService(service) ?: false
            if (!addSuccess) {
                Log.e(TAG, "Failed to initiate addService for GATT Server")
                SosRepository.setStatus(SosStatus.ERROR)
                return false
            }

            SosRepository.setStatus(SosStatus.STARTING)
            return true

        } catch (e: SecurityException) {
            Log.e(TAG, "Missing Bluetooth permissions: ${e.message}")
            SosRepository.setStatus(SosStatus.PERMISSION_REQUIRED)
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting BLE SOS broadcast: ${e.message}", e)
            SosRepository.setStatus(SosStatus.ERROR)
            return false
        }
    }

    private fun proceedWithAdvertising() {
        try {
            advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
            if (advertiser == null) {
                Log.e(TAG, "BluetoothLeAdvertiser is null")
                SosRepository.setStatus(SosStatus.ADVERTISING_UNSUPPORTED)
                return
            }

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build()

            val pUuid = ParcelUuid(SosBleProtocol.SOS_SERVICE_UUID)
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(pUuid)
                .build()

            advertiser?.startAdvertising(settings, data, advertiseCallback)
            isAdvertising = true
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting BLE advertiser: ${e.message}")
            SosRepository.setStatus(SosStatus.PERMISSION_REQUIRED)
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting BLE advertiser: ${e.message}", e)
            SosRepository.setStatus(SosStatus.ERROR)
        }
    }

    @Synchronized
    fun stopSosBroadcast() {
        try {
            if (isAdvertising) {
                advertiser?.stopAdvertising(advertiseCallback)
                isAdvertising = false
                Log.i(TAG, "BLE SOS Advertising stopped")
            }
            gattServer?.close()
            gattServer = null
            pendingPacketForAdv = null
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException stopping BLE broadcast: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Exception stopping BLE broadcast: ${e.message}")
        }
    }

    /**
     * Starts BLE Scanner to discover nearby active SOS broadcasts.
     */
    @Synchronized
    fun startScanning(): Boolean {
        Log.i(TAG, "BLE_SCAN_REQUESTED")

        if (!isBluetoothEnabled()) {
            Log.w(TAG, "Bluetooth disabled, scanner cannot start")
            SosRepository.setScannerActive(false)
            return false
        }

        if (!hasScanPermission()) {
            Log.w(TAG, "BLUETOOTH_SCAN permission missing, scanner cannot start")
            SosRepository.setScannerActive(false)
            return false
        }

        if (isScanning) {
            Log.i(TAG, "BLE_SCAN_STARTED (Already active)")
            SosRepository.setScannerActive(true)
            return true
        }

        try {
            scanner = bluetoothAdapter?.bluetoothLeScanner
            if (scanner == null) {
                Log.w(TAG, "BluetoothLeScanner unavailable")
                SosRepository.setScannerActive(false)
                return false
            }

            val pUuid = ParcelUuid(SosBleProtocol.SOS_SERVICE_UUID)
            val filter = ScanFilter.Builder()
                .setServiceUuid(pUuid)
                .build()

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            scanner?.startScan(listOf(filter), settings, scanCallback)
            isScanning = true
            SosRepository.setScannerActive(true)
            Log.i(TAG, "BLE_SCAN_STARTED")
            return true
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing Bluetooth permissions for scan: ${e.message}")
            SosRepository.setScannerActive(false)
            return false
        } catch (e: Exception) {
            Log.w(TAG, "Exception starting BLE scanner: ${e.message}")
            SosRepository.setScannerActive(false)
            return false
        }
    }

    @Synchronized
    fun stopScanning() {
        if (!isScanning) return
        try {
            scanner?.stopScan(scanCallback)
            isScanning = false
            SosRepository.setScannerActive(false)
            Log.i(TAG, "BLE SOS Scanner stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Exception stopping BLE scanner: ${e.message}")
        }
    }

    // GATT Server Callback
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            super.onServiceAdded(status, service)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "GATT_SERVICE_READY")
                proceedWithAdvertising()
            } else {
                Log.e(TAG, "GATT Service Addition failed with status=$status")
                SosRepository.setStatus(SosStatus.ERROR)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
            if (characteristic.uuid == SosBleProtocol.SOS_PACKET_CHARACTERISTIC_UUID) {
                val activePacket = SosRepository.activeSosPacket.value ?: pendingPacketForAdv
                val payloadStr = activePacket?.toJson() ?: ""
                val payloadBytes = payloadStr.toByteArray(StandardCharsets.UTF_8)

                val slice = if (offset < payloadBytes.size) {
                    payloadBytes.copyOfRange(offset, payloadBytes.size)
                } else byteArrayOf()

                try {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
                    Log.i(TAG, "Sent SOS packet payload (${slice.size} bytes, offset $offset) to receiver ${device.address}")
                } catch (e: SecurityException) {
                    Log.w(TAG, "SecurityException sending GATT response: ${e.message}")
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)
            if (characteristic.uuid == SosBleProtocol.SOS_ACK_CHARACTERISTIC_UUID && value != null) {
                val ackStr = String(value, StandardCharsets.UTF_8)
                val ack = SosAckPacket.fromJson(ackStr)
                if (ack != null) {
                    Log.i(TAG, "SOS_ACK_RECEIVED from device ${device.address}: receiverId=${ack.receiverAnonymousId}")
                    SosRepository.addAck(ack)
                }
                if (responseNeeded) {
                    try {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                    } catch (_: SecurityException) {}
                }
            }
        }
    }

    // BLE Advertise Callback
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i(TAG, "BLE_ADVERTISE_STARTED")
            SosRepository.setStatus(SosStatus.BROADCASTING)
            SosRepository.setLastAdvTime(System.currentTimeMillis())
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "BLE_ADVERTISE_FAILED:$errorCode")
            SosRepository.setStatus(SosStatus.ERROR)
        }
    }

    // BLE Scan Callback
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val rssi = result.rssi

            Log.i(TAG, "BLE_SCAN_RESULT:${device.address}/RSSI=$rssi")

            // Verify Service UUID
            val serviceUuids = result.scanRecord?.serviceUuids
            val matchesUuid = serviceUuids?.any { it.uuid == SosBleProtocol.SOS_SERVICE_UUID } == true
            if (matchesUuid) {
                Log.i(TAG, "SOS_UUID_MATCHED for device=${device.address}")
                connectAndReadSosPacket(device, rssi)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE_SCAN_FAILED:$errorCode")
            SosRepository.setScannerActive(false)
        }
    }

    private fun connectAndReadSosPacket(device: BluetoothDevice, rssi: Int) {
        if (!hasConnectPermission()) {
            Log.w(TAG, "BLUETOOTH_CONNECT permission missing, skipping GATT connect")
            return
        }

        val deviceAddress = device.address
        val lastConnect = connectingDevices[deviceAddress] ?: 0L
        if (System.currentTimeMillis() - lastConnect < 5000L) {
            // Rate limit connection attempts to same device within 5 seconds
            return
        }
        connectingDevices[deviceAddress] = System.currentTimeMillis()

        Log.i(TAG, "GATT_CONNECT_STARTED to ${device.address}")

        try {
            device.connectGatt(context, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                    Log.i(TAG, "GATT connectionStateChange: status=$status, newState=$newState")
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        Log.i(TAG, "GATT_CONNECTED to ${device.address}")
                        try {
                            // Request larger MTU for single-read packet retrieval
                            val mtuRequested = gatt?.requestMtu(512) ?: false
                            if (!mtuRequested) {
                                Log.w(TAG, "requestMtu failed, falling back to discoverServices directly")
                                gatt?.discoverServices()
                            }
                        } catch (e: SecurityException) {
                            Log.w(TAG, "SecurityException requesting MTU/discovering services: ${e.message}")
                            gatt?.close()
                        }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        Log.i(TAG, "GATT disconnected from ${device.address}")
                        connectingDevices.remove(deviceAddress)
                        gatt?.close()
                    }
                }

                override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
                    super.onMtuChanged(gatt, mtu, status)
                    Log.i(TAG, "GATT onMtuChanged: mtu=$mtu, status=$status")
                    try {
                        gatt?.discoverServices()
                    } catch (e: SecurityException) {
                        Log.w(TAG, "SecurityException discovering services post-MTU: ${e.message}")
                        gatt?.close()
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                    Log.i(TAG, "GATT onServicesDiscovered: status=$status")
                    if (status == BluetoothGatt.GATT_SUCCESS && gatt != null) {
                        val service = gatt.getService(SosBleProtocol.SOS_SERVICE_UUID)
                        val characteristic = service?.getCharacteristic(SosBleProtocol.SOS_PACKET_CHARACTERISTIC_UUID)
                        if (characteristic != null) {
                            try {
                                gatt.readCharacteristic(characteristic)
                            } catch (e: SecurityException) {
                                gatt.close()
                            }
                        } else {
                            Log.w(TAG, "SOS_PACKET_CHARACTERISTIC_UUID not found on device ${device.address}")
                            gatt.close()
                        }
                    } else {
                        gatt?.close()
                    }
                }

                override fun onCharacteristicRead(
                    gatt: BluetoothGatt?,
                    characteristic: BluetoothGattCharacteristic?,
                    status: Int
                ) {
                    Log.i(TAG, "SOS_PACKET_READ: status=$status")
                    if (status == BluetoothGatt.GATT_SUCCESS && characteristic != null) {
                        @Suppress("DEPRECATION")
                        val bytes = characteristic.value ?: byteArrayOf()
                        val jsonStr = String(bytes, StandardCharsets.UTF_8)
                        val packet = EmergencySosPacket.fromJson(jsonStr)

                        if (packet != null) {
                            Log.i(TAG, "SOS_PACKET_VALID: sosId=${packet.sosId}, source=${packet.positionSource}")
                            val isNew = SosRepository.addReceivedSos(packet, rssi)
                            if (isNew) {
                                val notifPosted = SosNotificationManager.showReceivedSosNotification(context, packet)
                                if (notifPosted) {
                                    Log.i(TAG, "SOS_NOTIFICATION_POSTED for sosId=${packet.sosId}")
                                }
                                // Send BLE ACK back to sender
                                if (gatt != null) {
                                    sendAckToSender(gatt, packet.sosId)
                                    return
                                }
                            }
                        } else {
                            Log.w(TAG, "Failed to parse EmergencySosPacket from GATT read payload (len=${bytes.size})")
                        }
                    }
                    try {
                        gatt?.disconnect()
                    } catch (_: SecurityException) {}
                }

                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt?,
                    characteristic: BluetoothGattCharacteristic?,
                    status: Int
                ) {
                    super.onCharacteristicWrite(gatt, characteristic, status)
                    if (characteristic?.uuid == SosBleProtocol.SOS_ACK_CHARACTERISTIC_UUID) {
                        Log.i(TAG, "GATT ACK write finished with status=$status")
                    }
                    try {
                        gatt?.disconnect()
                    } catch (_: SecurityException) {}
                }
            }, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException connecting to GATT device: ${e.message}")
            connectingDevices.remove(deviceAddress)
        }
    }

    private fun sendAckToSender(gatt: BluetoothGatt, sosId: String) {
        try {
            val service = gatt.getService(SosBleProtocol.SOS_SERVICE_UUID)
            val ackChar = service?.getCharacteristic(SosBleProtocol.SOS_ACK_CHARACTERISTIC_UUID)
            if (ackChar != null) {
                val ack = SosAckPacket(sosId = sosId, receiverAnonymousId = SosRepository.anonymousDeviceId)
                val ackBytes = ack.toJson().toByteArray(StandardCharsets.UTF_8)
                @Suppress("DEPRECATION")
                ackChar.value = ackBytes
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(ackChar)
                SosRepository.markAcknowledged(sosId)
                Log.i(TAG, "SOS_ACK_SENT for sosId=$sosId to sender ${gatt.device.address}")
            } else {
                gatt.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send BLE ACK: ${e.message}")
            try { gatt.disconnect() } catch (_: Throwable) {}
        }
    }
}
