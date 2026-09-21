package com.example.gudumap.sos

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class SosStatus {
    IDLE,
    PREPARING,
    STARTING,
    BROADCASTING,
    RELAY_DETECTED,
    ACKNOWLEDGED,
    NO_DEVICE_FOUND,
    BLUETOOTH_DISABLED,
    ADVERTISING_UNSUPPORTED,
    PERMISSION_REQUIRED,
    CANCELLED,
    EXPIRED,
    ERROR
}

data class ReceivedSosItem(
    val packet: EmergencySosPacket,
    val receivedAtEpochMs: Long = System.currentTimeMillis(),
    val rssi: Int = 0,
    val isAcknowledged: Boolean = false,
    val isDismissed: Boolean = false
)

object SosRepository {

    private const val TAG = "Gudumap:SosRepository"

    val anonymousDeviceId: String by lazy {
        UUID.randomUUID().toString().take(8)
    }

    private val _sosStatus = MutableStateFlow(SosStatus.IDLE)
    val sosStatus: StateFlow<SosStatus> = _sosStatus.asStateFlow()

    private val _activeSosPacket = MutableStateFlow<EmergencySosPacket?>(null)
    val activeSosPacket: StateFlow<EmergencySosPacket?> = _activeSosPacket.asStateFlow()

    private val _acknowledgedReceivers = MutableStateFlow<Set<String>>(emptySet())
    val acknowledgedReceivers: StateFlow<Set<String>> = _acknowledgedReceivers.asStateFlow()

    private val _receivedSosList = MutableStateFlow<List<ReceivedSosItem>>(emptyList())
    val receivedSosList: StateFlow<List<ReceivedSosItem>> = _receivedSosList.asStateFlow()

    private val _selectedReceivedSos = MutableStateFlow<ReceivedSosItem?>(null)
    val selectedReceivedSos: StateFlow<ReceivedSosItem?> = _selectedReceivedSos.asStateFlow()

    private val _isScannerActive = MutableStateFlow(false)
    val isScannerActive: StateFlow<Boolean> = _isScannerActive.asStateFlow()

    private val _lastAdvTimeMs = MutableStateFlow<Long>(0L)
    val lastAdvTimeMs: StateFlow<Long> = _lastAdvTimeMs.asStateFlow()

    private val _totalPacketsReceived = MutableStateFlow(0)
    val totalPacketsReceived: StateFlow<Int> = _totalPacketsReceived.asStateFlow()

    private val seenSosIds = ConcurrentHashMap<String, Long>()

    private fun safeLogI(message: String) {
        try { Log.i(TAG, message) } catch (_: Throwable) { println("$TAG: $message") }
    }

    fun setStatus(status: SosStatus) {
        safeLogI("SosStatus transition: ${_sosStatus.value} -> $status")
        _sosStatus.value = status
    }

    fun setScannerActive(active: Boolean) {
        _isScannerActive.value = active
    }

    fun setLastAdvTime(timeMs: Long) {
        _lastAdvTimeMs.value = timeMs
    }

    fun setActivePacket(packet: EmergencySosPacket?) {
        _activeSosPacket.value = packet
        if (packet != null) {
            _acknowledgedReceivers.value = emptySet()
            setStatus(SosStatus.STARTING)
        } else if (_sosStatus.value == SosStatus.BROADCASTING || _sosStatus.value == SosStatus.ACKNOWLEDGED || _sosStatus.value == SosStatus.STARTING) {
            setStatus(SosStatus.IDLE)
        }
    }

    fun addAck(ack: SosAckPacket) {
        val active = _activeSosPacket.value ?: return
        if (ack.sosId == active.sosId) {
            val current = _acknowledgedReceivers.value.toMutableSet()
            if (current.add(ack.receiverAnonymousId)) {
                _acknowledgedReceivers.value = current
                _sosStatus.value = SosStatus.ACKNOWLEDGED
                safeLogI("ACK received for ${ack.sosId} from ${ack.receiverAnonymousId}. Total ACK count: ${current.size}")
            }
        }
    }

    fun isSeen(sosId: String): Boolean {
        val time = seenSosIds[sosId] ?: return false
        // Seen within TTL
        return System.currentTimeMillis() - time < SosBleProtocol.DEFAULT_TTL_MS
    }

    fun addReceivedSos(packet: EmergencySosPacket, rssi: Int = 0): Boolean {
        if (isSeen(packet.sosId) || packet.isExpired()) {
            return false
        }
        seenSosIds[packet.sosId] = System.currentTimeMillis()

        val item = ReceivedSosItem(packet = packet, rssi = rssi)
        val list = _receivedSosList.value.toMutableList()
        list.removeAll { it.packet.sosId == packet.sosId }
        list.add(0, item)
        _receivedSosList.value = list
        _totalPacketsReceived.value = _totalPacketsReceived.value + 1
        safeLogI("New Received SOS stored: id=${packet.sosId}, source=${packet.positionSource}, lat=${packet.currentLatitude}, lon=${packet.currentLongitude}")
        return true
    }

    fun markAcknowledged(sosId: String) {
        val list = _receivedSosList.value.toMutableList()
        val index = list.indexOfFirst { it.packet.sosId == sosId }
        if (index != -1) {
            val old = list[index]
            list[index] = old.copy(isAcknowledged = true)
            _receivedSosList.value = list
        }
    }

    fun markDismissed(sosId: String) {
        val list = _receivedSosList.value.toMutableList()
        val index = list.indexOfFirst { it.packet.sosId == sosId }
        if (index != -1) {
            val old = list[index]
            list[index] = old.copy(isDismissed = true)
            _receivedSosList.value = list
        }
        if (_selectedReceivedSos.value?.packet?.sosId == sosId) {
            _selectedReceivedSos.value = null
        }
    }

    fun selectReceivedSos(item: ReceivedSosItem?) {
        _selectedReceivedSos.value = item
    }

    fun cancelActiveSos() {
        safeLogI("Cancelling active SOS")
        _activeSosPacket.value = null
        _acknowledgedReceivers.value = emptySet()
        _sosStatus.value = SosStatus.CANCELLED
    }

    fun clearExpired() {
        val now = System.currentTimeMillis()
        seenSosIds.entries.removeIf { now - it.value > SosBleProtocol.DEFAULT_TTL_MS }
        val list = _receivedSosList.value.filter { !it.packet.isExpired(now) }
        _receivedSosList.value = list
    }
}
