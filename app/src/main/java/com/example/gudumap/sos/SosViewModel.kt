package com.example.gudumap.sos

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.example.gudumap.navigation.GpsState
import com.example.gudumap.navigation.NavigationState
import com.example.gudumap.navigation.NavigationVisualMode
import kotlinx.coroutines.flow.StateFlow

class SosViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "Gudumap:SosViewModel"
    }

    val sosStatus: StateFlow<SosStatus> = SosRepository.sosStatus
    val activeSosPacket: StateFlow<EmergencySosPacket?> = SosRepository.activeSosPacket
    val acknowledgedReceivers: StateFlow<Set<String>> = SosRepository.acknowledgedReceivers
    val receivedSosList: StateFlow<List<ReceivedSosItem>> = SosRepository.receivedSosList
    val selectedReceivedSos: StateFlow<ReceivedSosItem?> = SosRepository.selectedReceivedSos
    val isScannerActive: StateFlow<Boolean> = SosRepository.isScannerActive
    val lastAdvTimeMs: StateFlow<Long> = SosRepository.lastAdvTimeMs
    val totalPacketsReceived: StateFlow<Int> = SosRepository.totalPacketsReceived

    private val bleManager = SosBleManager(application)

    init {
        SosNotificationManager.initNotificationChannels(application)
        retryScanning()
    }

    fun isBluetoothEnabled(): Boolean = bleManager.isBluetoothEnabled()
    fun isBleSupported(): Boolean = bleManager.isBleSupported()
    fun isMultipleAdvertisementSupported(): Boolean = bleManager.isMultipleAdvertisementSupported()
    fun hasScanPermission(): Boolean = bleManager.hasScanPermission()
    fun hasAdvertisePermission(): Boolean = bleManager.hasAdvertisePermission()
    fun hasConnectPermission(): Boolean = bleManager.hasConnectPermission()

    fun retryScanning(): Boolean {
        return bleManager.startScanning()
    }

    /**
     * Map current READ-ONLY [NavigationState] to an [EmergencySosPacket].
     * GUARANTEES:
     * - Does NOT alter estimator math or navigation logic.
     * - Captures fused position, last trusted GNSS, uncertainty, blackout state.
     */
    fun buildPacketFromNavState(navState: NavigationState, customMessage: String? = null): EmergencySosPacket {
        val isDemo = navState.isDemoModeEnabled || navState.locationProvider == "demo"

        val lat = if (!isDemo && navState.latitude.isFinite() && (navState.latitude != 0.0 || navState.longitude != 0.0)) navState.latitude else null
        val lon = if (!isDemo && navState.longitude.isFinite() && (navState.latitude != 0.0 || navState.longitude != 0.0)) navState.longitude else null

        val lastGnssLat = navState.lastTrustedGpsLat
        val lastGnssLon = navState.lastTrustedGpsLon

        val source = when {
            !isDemo && navState.hasGpsFix && navState.gpsState == GpsState.FIXED && navState.locationAgeSeconds <= 5L -> PositionSource.GNSS
            navState.blackoutMode || navState.visualMode == NavigationVisualMode.GPS_FALLBACK -> PositionSource.DEAD_RECKONING
            lat != null && lon != null -> PositionSource.FUSED
            else -> PositionSource.UNKNOWN
        }

        val uncertainty = when {
            navState.predictionUncertaintyMeters > 0.0 -> navState.predictionUncertaintyMeters
            navState.uncertaintyRadiusMeters > 0.0 -> navState.uncertaintyRadiusMeters
            navState.locationAccuracyMeters > 0f -> navState.locationAccuracyMeters.toDouble()
            else -> null
        }

        val blackoutDuration = if (navState.blackoutMode) navState.gpsFixAgeMs else 0L

        val packet = EmergencySosPacket(
            currentLatitude = lat,
            currentLongitude = lon,
            lastTrustedGnssLatitude = lastGnssLat,
            lastTrustedGnssLongitude = lastGnssLon,
            positionSource = source,
            estimatedUncertaintyMeters = uncertainty,
            blackoutDurationMs = blackoutDuration,
            emergencyMessage = customMessage,
            senderAnonymousId = SosRepository.anonymousDeviceId
        )
        Log.i(TAG, "SOS_PACKET_CREATED for sosId=${packet.sosId}, source=${packet.positionSource}")
        return packet
    }

    fun startSosBroadcast(navState: NavigationState, customMessage: String? = null) {
        Log.i(TAG, "SOS_BUTTON_PRESSED")

        if (!bleManager.isBluetoothEnabled()) {
            SosRepository.setStatus(SosStatus.BLUETOOTH_DISABLED)
            Log.w(TAG, "Cannot start SOS: Bluetooth is disabled")
            return
        }

        val packet = buildPacketFromNavState(navState, customMessage)
        SosRepository.setActivePacket(packet)

        // Launch Foreground Service for persistent BLE broadcasting
        SosForegroundService.startService(getApplication())
        Log.i(TAG, "Started SOS service for broadcast id=${packet.sosId}")
    }

    fun cancelSos() {
        SosRepository.cancelActiveSos()
        SosForegroundService.stopService(getApplication())
        bleManager.stopSosBroadcast()
        Log.i(TAG, "Cancelled SOS broadcast")
    }

    fun acknowledgeReceivedSos(sosId: String) {
        SosRepository.markAcknowledged(sosId)
    }

    fun dismissReceivedSos(sosId: String) {
        SosRepository.markDismissed(sosId)
    }

    fun selectReceivedSos(item: ReceivedSosItem?) {
        SosRepository.selectReceivedSos(item)
    }

    override fun onCleared() {
        super.onCleared()
        bleManager.stopScanning()
    }
}
