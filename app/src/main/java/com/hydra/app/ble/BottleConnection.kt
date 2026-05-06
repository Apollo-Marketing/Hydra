package com.hydra.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hydra.app.data.SipEntity
import com.hydra.app.data.SipRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "HydraBle"

object BottleConfig {
    const val SCAN_TIMEOUT_MS = 15_000L
    const val PACKET_HISTORY = 200
    const val EVENT_HISTORY = 200

    // Sip-log paginated sync params.
    // We always use SEARCH_ALGO_TIMESTAMP — empirically it returns batches per request,
    // whereas INCREMENT mode trickles one entry per request and is ~10× slower.
    const val SIP_PAGE_SIZE = 100                 // entries per RequestGetCapTofLog page
    const val SIP_PAGE_QUIET_MS = 400L            // end-of-page once entries are flowing: this long without one
    const val SIP_PAGE_FIRST_RESPONSE_MS = 2_500L // wait this long for the FIRST entry of a new page before declaring it empty
                                                  // (older bottles take longer to seek deeper into the ring buffer)

    /**
     * The bottle's size in mL — used to look up the volume polynomial in [BottleMath].
     * Read this dynamically from a `RequestGetCapTofSettings` response (TofSettings.bottleSizeInMilliliter)
     * later. For now hardcoded to the user's known PureVis 2 1000mL bottle.
     */
    const val BOTTLE_SIZE_ML = 1000

    val NUS_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    val NUS_TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    val NUS_RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Standard BLE services we read for status info.
    val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    val DEVICE_INFO_SERVICE: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val DI_MANUFACTURER: UUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
    val DI_MODEL: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
    val DI_SERIAL: UUID = UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb")
    val DI_HW_REV: UUID = UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb")
    val DI_FW_REV: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
    val DI_SW_REV: UUID = UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb")
}

enum class ConnectionState { Disconnected, Searching, Connecting, Discovering, Ready, Error }

/** Multi-page sip-log sync state. */
sealed class SyncState {
    data object Idle : SyncState()
    data class Syncing(val page: Int, val received: Int) : SyncState()
}

data class CharacteristicInfo(val uuid: UUID, val properties: List<String>)
data class ServiceInfo(val uuid: UUID, val characteristics: List<CharacteristicInfo>)
data class IncomingPacket(val timestamp: Long, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is IncomingPacket && other.timestamp == timestamp && other.bytes.contentEquals(bytes)
    override fun hashCode(): Int = 31 * timestamp.hashCode() + bytes.contentHashCode()
}

data class ScannedDevice(val name: String, val address: String, val rssi: Int)

private fun ByteArray.hex(): String =
    joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }

private fun shortName(typeUrl: String): String =
    typeUrl.substringAfterLast('/')

class BottleConnection(
    context: Context,
    private val repository: SipRepository,
) {

    init { Log.d(TAG, "BottleConnection created") }

    private val appContext = context.applicationContext
    private val adapter =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val seqCounter = AtomicInteger(1)

    private val _state = MutableStateFlow(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _currentAddress = MutableStateFlow<String?>(null)
    val currentAddress: StateFlow<String?> = _currentAddress.asStateFlow()

    private val _services = MutableStateFlow<List<ServiceInfo>>(emptyList())
    val services: StateFlow<List<ServiceInfo>> = _services.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** Raw NUS TX packets — kept for the collapsible debug log. */
    private val _packets = MutableStateFlow<List<IncomingPacket>>(emptyList())
    val packets: StateFlow<List<IncomingPacket>> = _packets.asStateFlow()

    private val _subscribed = MutableStateFlow(false)
    val subscribed: StateFlow<Boolean> = _subscribed.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scannedDevices: StateFlow<List<ScannedDevice>> = _scannedDevices.asStateFlow()

    /** Typed event feed for the UI. Replay 0 (only new events) but keep buffer for late subscribers. */
    private val _events = MutableSharedFlow<BottleEvent>(
        replay = 0,
        extraBufferCapacity = BottleConfig.EVENT_HISTORY,
    )
    val events: SharedFlow<BottleEvent> = _events.asSharedFlow()

    /** Persistent sip log — backed by Room DB, accumulates across app sessions. */
    val sipLog: Flow<List<SipEntity>> = repository.observeAll()

    /** Multi-page sync state for the UI's "syncing…" indicator. */
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    // ── Sync orchestration state (private) ─────────────────────────────────────
    private val currentPage = mutableListOf<SipEntry>()
    private var currentPageNumber = 0
    private var totalReceivedThisSync = 0
    private var nextFromTimestamp = 0L
    private var pageQuietJob: Job? = null
    /** Once true, we don't re-trigger auto-sync until the connection drops. */
    private var autoSyncTriggered = false

    private var gatt: BluetoothGatt? = null
    private var scanCallback: ScanCallback? = null
    private val scanTimeout = Runnable {
        Log.w(TAG, "scan timed out")
        stopScan()
        if (_state.value == ConnectionState.Searching) {
            _lastError.value = "Bottle not found within ${BottleConfig.SCAN_TIMEOUT_MS / 1000}s"
            _state.value = ConnectionState.Error
        }
    }

    fun clearPackets() {
        Log.d(TAG, "clearPackets")
        _packets.value = emptyList()
    }

    /**
     * Wipe all stored sips from the local DB. Cancels any in-flight sync first so the
     * pagination loop doesn't keep inserting entries we're about to delete. A handful of
     * already-in-transit notifications may still upsert in the cleanup window — they'll
     * be re-fetched cleanly on the next sync (which starts from `since=0`).
     */
    fun clearSipLog() {
        Log.d(TAG, "clearSipLog")
        scope.launch {
            resetSyncState()
            repository.deleteAll()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status=$status newState=$newState")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "  connection error, closing")
                _lastError.value = "Connection error: status=$status"
                _state.value = ConnectionState.Error
                g.close()
                gatt = null
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "  STATE_CONNECTED → discoverServices()")
                    _state.value = ConnectionState.Discovering
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "  STATE_DISCONNECTED")
                    _state.value = ConnectionState.Disconnected
                    _services.value = emptyList()
                    _currentAddress.value = null
                    _subscribed.value = false
                    resetSyncState()
                    g.close()
                    gatt = null
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered status=$status services=${g.services.size}")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _lastError.value = "Service discovery failed: status=$status"
                _state.value = ConnectionState.Error
                return
            }
            _services.value = g.services.map { it.toInfo() }
            subscribeToNus(g)
            _state.value = ConnectionState.Ready
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            Log.d(
                TAG,
                "onDescriptorWrite descriptor=${descriptor.uuid} char=${descriptor.characteristic.uuid} status=$status",
            )
            if (descriptor.uuid == BottleConfig.CCCD &&
                descriptor.characteristic.uuid == BottleConfig.NUS_TX
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "  ✓ NUS TX subscribed — auto-sending SetCapTime")
                    _subscribed.value = true
                    autoSendSetTime()
                } else {
                    Log.e(TAG, "  ✗ CCCD write failed status=$status")
                    _subscribed.value = false
                    _lastError.value = "CCCD write failed: status=$status"
                }
            }
        }

        // API 33+ thread-safe variant
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleNotification(characteristic, value)
        }

        @Suppress("DEPRECATION")
        @Deprecated("API 32 and below")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT >= 33) return
            handleNotification(characteristic, characteristic.value ?: return)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            Log.d(TAG, "onCharacteristicWrite char=${characteristic.uuid} status=$status")
        }

        // API 33+
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            handleRead(characteristic, value, status)
        }

        @Suppress("DEPRECATION")
        @Deprecated("API 32 and below")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT >= 33) return
            handleRead(characteristic, characteristic.value ?: return, status)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "onMtuChanged mtu=$mtu status=$status")
        }
    }

    private fun handleNotification(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        Log.i(TAG, "RX ${characteristic.uuid} (${value.size}B): ${value.hex()}")
        if (characteristic.uuid != BottleConfig.NUS_TX) return

        appendPacket(value)

        val now = System.currentTimeMillis()
        val decoded = Frame.decode(value)
        when {
            decoded == null -> {
                _events.tryEmit(BottleEvent.Unknown(now, value.copyOf()))
            }
            decoded.error != null -> {
                _events.tryEmit(
                    BottleEvent.ProtocolError(
                        timestamp = now,
                        seq = decoded.seq,
                        statusCode = decoded.status,
                        rawBytes = decoded.error,
                    )
                )
            }
            decoded.typeUrl != null && decoded.valueBytes != null -> {
                emitDecodedResponse(now, decoded.seq, decoded.typeUrl, decoded.valueBytes)
            }
            else -> {
                // Status-only response (no Any body). status=1 is the bottle's success ACK;
                // anything else is a protocol error.
                if (decoded.status == BottleProtocol.STATUS_OK) {
                    _events.tryEmit(BottleEvent.Ack(now, decoded.seq, BottleProtocol.STATUS_OK))
                    // Auto-sync sip log right after the SetCapTime ACK (sequence 1).
                    if (decoded.seq == 1 && !autoSyncTriggered) {
                        autoSyncTriggered = true
                        Log.d(TAG, "auto-sync trigger fired after first ACK")
                        syncSipLog()
                    }
                } else {
                    _events.tryEmit(
                        BottleEvent.ProtocolError(
                            timestamp = now,
                            seq = decoded.seq,
                            statusCode = decoded.status,
                            rawBytes = value.copyOf(),
                        )
                    )
                }
            }
        }
    }

    /**
     * Dispatch on response type URL: try a typed parser first, fall back to the
     * generic protobuf field walker so even unknown responses are inspectable.
     */
    private fun emitDecodedResponse(now: Long, seq: Int?, typeUrl: String, value: ByteArray) {
        when (shortName(typeUrl)) {
            "ResponseGetCapTofLog" -> {
                val entry = Responses.parseTofLogEntry(value)
                if (entry != null) {
                    // Persist + (if syncing) accumulate for page-tracking. Both happen on the
                    // single Main-immediate scope so currentPage / pageQuietJob are never
                    // touched concurrently from the binder thread.
                    scope.launch {
                        repository.upsertAll(listOf(entry))
                        if (_syncState.value is SyncState.Syncing) {
                            currentPage.add(entry)
                            armPageQuietTimer()
                        }
                    }
                    _events.tryEmit(BottleEvent.SipReceived(now, seq, entry))
                } else {
                    emitUnparsed(now, seq, typeUrl, value)
                }
            }
            "ResponseGetCapHydroReminderSettings" -> {
                Responses.parseHydroReminderSettings(value)?.let {
                    _events.tryEmit(BottleEvent.SettingsReceived(now, seq, typeUrl, "Reminder", it.format()))
                } ?: emitUnparsed(now, seq, typeUrl, value)
            }
            "ResponseGetCapDoNotDisturbSettings" -> {
                Responses.parseDoNotDisturbSettings(value)?.let {
                    _events.tryEmit(BottleEvent.SettingsReceived(now, seq, typeUrl, "DND", it.format()))
                } ?: emitUnparsed(now, seq, typeUrl, value)
            }
            "ResponseGetCapLowBatterySettings" -> {
                Responses.parseLowBatterySettings(value)?.let {
                    _events.tryEmit(BottleEvent.SettingsReceived(now, seq, typeUrl, "Battery alerts", it.format()))
                } ?: emitUnparsed(now, seq, typeUrl, value)
            }
            "ResponseGetCapTofSettings" -> {
                Responses.parseTofSettings(value)?.let {
                    _events.tryEmit(BottleEvent.SettingsReceived(now, seq, typeUrl, "TOF", it.format()))
                } ?: emitUnparsed(now, seq, typeUrl, value)
            }
            "ResponseGetCapUvConfig" -> {
                Responses.parseUvConfig(value)?.let {
                    _events.tryEmit(BottleEvent.SettingsReceived(now, seq, typeUrl, "UV", it.format()))
                } ?: emitUnparsed(now, seq, typeUrl, value)
            }
            "ResponseGetCapUiState" -> {
                Responses.parseUiState(value)?.let {
                    _events.tryEmit(BottleEvent.SettingsReceived(now, seq, typeUrl, "UI state", it.format()))
                } ?: emitUnparsed(now, seq, typeUrl, value)
            }
            else -> emitUnparsed(now, seq, typeUrl, value)
        }
    }

    private fun emitUnparsed(now: Long, seq: Int?, typeUrl: String, value: ByteArray) {
        val fields = Wire.walkFields(value)
        _events.tryEmit(BottleEvent.UnparsedResponse(now, seq, typeUrl, value.size, fields))
    }

    private fun handleRead(c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
        Log.i(TAG, "READ ${c.uuid} status=$status: ${value.hex()}")
        if (status != BluetoothGatt.GATT_SUCCESS) {
            _events.tryEmit(BottleEvent.Unknown(System.currentTimeMillis(), value.copyOf()))
            return
        }
        val now = System.currentTimeMillis()
        val (name, parsed) = when (c.uuid) {
            BottleConfig.BATTERY_LEVEL -> "Battery" to "${value[0].toInt() and 0xFF}%"
            BottleConfig.DI_MANUFACTURER -> "Manufacturer" to String(value)
            BottleConfig.DI_MODEL -> "Model" to String(value)
            BottleConfig.DI_SERIAL -> "Serial" to String(value)
            BottleConfig.DI_HW_REV -> "Hardware rev" to String(value)
            BottleConfig.DI_FW_REV -> "Firmware rev" to String(value)
            BottleConfig.DI_SW_REV -> "Software rev" to String(value)
            else -> c.uuid.toString() to value.hex()
        }
        _events.tryEmit(BottleEvent.StandardRead(now, name, parsed))
    }

    @SuppressLint("MissingPermission")
    private fun subscribeToNus(g: BluetoothGatt) {
        Log.d(TAG, "subscribeToNus")
        val service = g.getService(BottleConfig.NUS_SERVICE) ?: run {
            Log.e(TAG, "  NUS service not found"); _lastError.value = "NUS service not found"; return
        }
        val tx = service.getCharacteristic(BottleConfig.NUS_TX) ?: run {
            Log.e(TAG, "  NUS TX char not found"); _lastError.value = "NUS TX characteristic not found"; return
        }
        val notifOk = g.setCharacteristicNotification(tx, true)
        Log.d(TAG, "  setCharacteristicNotification(true) → $notifOk")
        if (!notifOk) {
            _lastError.value = "setCharacteristicNotification returned false"; return
        }
        val cccd = tx.getDescriptor(BottleConfig.CCCD) ?: run {
            Log.e(TAG, "  CCCD descriptor missing"); _lastError.value = "CCCD descriptor missing on NUS TX"; return
        }
        val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val writeRes = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, enable)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = enable
            @Suppress("DEPRECATION")
            if (g.writeDescriptor(cccd)) 0 else -1
        }
        Log.d(TAG, "  writeDescriptor(ENABLE_NOTIFICATION) → $writeRes (await onDescriptorWrite…)")
    }

    private fun autoSendSetTime() {
        val payload = RequestPayloads.setTime(System.currentTimeMillis())
        sendCommand(BottleProtocol.Requests.SET_TIME, payload)
    }

    /**
     * Send a Larq protobuf command. Returns the sequence number used, or null if not connected.
     */
    @SuppressLint("MissingPermission")
    fun sendCommand(typeUrl: String, valueBytes: ByteArray = RequestPayloads.EMPTY): Int? {
        val g = gatt ?: run {
            Log.w(TAG, "sendCommand: not connected"); return null
        }
        val service = g.getService(BottleConfig.NUS_SERVICE) ?: run {
            Log.w(TAG, "sendCommand: NUS service unavailable"); return null
        }
        val rx = service.getCharacteristic(BottleConfig.NUS_RX) ?: run {
            Log.w(TAG, "sendCommand: NUS RX unavailable"); return null
        }
        val seq = seqCounter.getAndIncrement()
        val frame = Frame.encode(typeUrl, valueBytes, seq)
        Log.d(TAG, "sendCommand seq=$seq ${shortName(typeUrl)} ${frame.size}B: ${frame.hex()}")

        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(rx, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION") run {
                rx.value = frame
                rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION") g.writeCharacteristic(rx)
            }
        }
        if (ok) {
            _events.tryEmit(BottleEvent.CommandSent(System.currentTimeMillis(), typeUrl, seq))
            return seq
        }
        Log.w(TAG, "sendCommand write returned false")
        return null
    }

    /**
     * Manual trigger for the same paginated sync that auto-fires on connect.
     * Idempotent — no-op if a sync is already in progress.
     */
    fun requestSipLog() = syncSipLog()

    /**
     * Multi-page sip-log traversal mirroring the official app's ELTimelineTraversal:
     * - First request starts at last-known-timestamp + 1 (or 0 if the DB is empty)
     * - Each subsequent page advances the from-timestamp past the most recent entry received
     * - All pages use SEARCH_ALGO_TIMESTAMP — see BottleConfig comment for why
     * - End of stream = quiescence (no notification for SIP_PAGE_QUIET_MS) with empty current page
     * - Every entry persists to the DB; sync events feed the activity log
     */
    fun syncSipLog() {
        // Atomic gate: only one caller wins the Idle → Syncing transition.
        // page=0/received=0 sentinel is overwritten by sendNextPage on the first page.
        if (!_syncState.compareAndSet(SyncState.Idle, SyncState.Syncing(page = 0, received = 0))) {
            Log.d(TAG, "syncSipLog: already syncing, ignoring")
            return
        }
        scope.launch {
            val last = repository.lastTimestamp()
            val from = if (last == 0L) 0L else last + 1
            startSync(from)
        }
    }

    private fun startSync(from: Long) {
        Log.d(TAG, "startSync from=$from")
        nextFromTimestamp = from
        currentPageNumber = 0  // sendNextPage increments to 1 before the first request
        totalReceivedThisSync = 0
        currentPage.clear()
        _events.tryEmit(BottleEvent.SyncStarted(System.currentTimeMillis(), from))
        sendNextPage()
    }

    private fun sendNextPage() {
        currentPageNumber++
        _syncState.value = SyncState.Syncing(currentPageNumber, totalReceivedThisSync)
        // SEARCH_ALGO_TIMESTAMP returns batches of entries newer than fromTimestamp;
        // we advance fromTimestamp past the last received timestamp for the next page.
        val payload = RequestPayloads.getTofLog(
            sinceSec = nextFromTimestamp,
            maxCount = BottleConfig.SIP_PAGE_SIZE,
            algo = BottleProtocol.SEARCH_ALGO_TIMESTAMP,
        )
        Log.d(TAG, "sync page=$currentPageNumber from=$nextFromTimestamp")
        sendCommand(BottleProtocol.Requests.GET_TOF_LOG, payload)
        // Use the longer "first response" timeout — the in-page quiet timer takes over
        // once any entry arrives (see armPageQuietTimer in the response handler).
        armFirstResponseTimer()
    }

    private fun armFirstResponseTimer() {
        pageQuietJob?.cancel()
        pageQuietJob = scope.launch {
            delay(BottleConfig.SIP_PAGE_FIRST_RESPONSE_MS)
            onPageComplete()
        }
    }

    private fun armPageQuietTimer() {
        pageQuietJob?.cancel()
        pageQuietJob = scope.launch {
            delay(BottleConfig.SIP_PAGE_QUIET_MS)
            onPageComplete()
        }
    }

    private fun onPageComplete() {
        val page = currentPage.toList()
        currentPage.clear()
        Log.d(TAG, "page $currentPageNumber complete: ${page.size} entries")
        if (page.isEmpty()) {
            Log.i(TAG, "sync done: $totalReceivedThisSync new entries across $currentPageNumber pages")
            _events.tryEmit(BottleEvent.SyncFinished(System.currentTimeMillis(), totalReceivedThisSync))
            _syncState.value = SyncState.Idle
            return
        }
        totalReceivedThisSync += page.size
        // +1 so we don't re-fetch the boundary entry on the next page.
        nextFromTimestamp = page.maxOf { it.timestampSec } + 1
        _events.tryEmit(BottleEvent.SyncProgress(System.currentTimeMillis(), currentPageNumber, totalReceivedThisSync))
        sendNextPage()
    }

    private fun resetSyncState() {
        pageQuietJob?.cancel()
        pageQuietJob = null
        currentPage.clear()
        currentPageNumber = 0
        totalReceivedThisSync = 0
        autoSyncTriggered = false
        _syncState.value = SyncState.Idle
    }

    /** Send a SetCapTime command with the current system time. */
    fun requestSetTimeNow(): Int? =
        sendCommand(BottleProtocol.Requests.SET_TIME, RequestPayloads.setTime(System.currentTimeMillis()))

    /**
     * Manually write raw bytes to NUS RX (preserved from the playground "Advanced" panel).
     * Not framed — sends [bytes] verbatim. Returns null on success or an error string.
     */
    @SuppressLint("MissingPermission")
    fun writeRxRaw(bytes: ByteArray): String? {
        Log.d(TAG, "writeRxRaw ${bytes.size}B: ${bytes.hex()}")
        val g = gatt ?: return "Not connected"
        val service = g.getService(BottleConfig.NUS_SERVICE) ?: return "NUS service unavailable"
        val rx = service.getCharacteristic(BottleConfig.NUS_RX) ?: return "NUS RX unavailable"
        return if (Build.VERSION.SDK_INT >= 33) {
            val r = g.writeCharacteristic(
                rx, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            )
            if (r == BluetoothGatt.GATT_SUCCESS) null else "writeCharacteristic=$r"
        } else {
            @Suppress("DEPRECATION") run {
                rx.value = bytes
                rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                if (g.writeCharacteristic(rx)) null else "writeCharacteristic returned false"
            }
        }
    }

    /** Read a standard BLE characteristic (battery level, device info). */
    @SuppressLint("MissingPermission")
    fun readStandard(serviceUuid: UUID, charUuid: UUID): Boolean {
        val g = gatt ?: return false
        val s = g.getService(serviceUuid) ?: return false
        val c = s.getCharacteristic(charUuid) ?: return false
        return g.readCharacteristic(c)
    }

    fun readBatteryLevel(): Boolean =
        readStandard(BottleConfig.BATTERY_SERVICE, BottleConfig.BATTERY_LEVEL)

    /** Reads each Device Information char in sequence. Each result arrives as a StandardRead event. */
    fun readDeviceInfo() {
        // Android serializes GATT operations, so kicking off all reads back-to-back is safe;
        // the stack queues them.
        listOf(
            BottleConfig.DI_MANUFACTURER,
            BottleConfig.DI_MODEL,
            BottleConfig.DI_SERIAL,
            BottleConfig.DI_HW_REV,
            BottleConfig.DI_FW_REV,
            BottleConfig.DI_SW_REV,
        ).forEach { readStandard(BottleConfig.DEVICE_INFO_SERVICE, it) }
    }

    private fun appendPacket(bytes: ByteArray) {
        val pkt = IncomingPacket(System.currentTimeMillis(), bytes)
        _packets.value = (_packets.value + pkt).takeLast(BottleConfig.PACKET_HISTORY)
    }

    /** Begin scan-and-connect to a Larq bottle by exact local name. */
    @SuppressLint("MissingPermission")
    fun findAndConnect(targetName: String) {
        val target = targetName
        Log.d(TAG, "findAndConnect target=$target")
        val a = adapter ?: run {
            Log.e(TAG, "  adapter null"); _lastError.value = "Bluetooth not available"; _state.value = ConnectionState.Error; return
        }
        if (!a.isEnabled) {
            Log.e(TAG, "  bluetooth disabled"); _lastError.value = "Bluetooth is disabled"; _state.value = ConnectionState.Error; return
        }
        stop()
        _lastError.value = null
        _state.value = ConnectionState.Searching
        _scannedDevices.value = emptyList()
        seqCounter.set(1)
        resetSyncState()

        val scanner = a.bluetoothLeScanner ?: run {
            Log.e(TAG, "  scanner null"); _lastError.value = "Scanner unavailable"; _state.value = ConnectionState.Error; return
        }
        val filters = listOf(ScanFilter.Builder().setDeviceName(target).build())
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: "Unknown"
                val address = result.device.address
                val rssi = result.rssi
                Log.d(TAG, "scan hit: $address ($name) rssi=$rssi")

                val current = _scannedDevices.value
                if (current.none { it.address == address }) {
                    _scannedDevices.value = current + ScannedDevice(name, address, rssi)
                } else {
                    _scannedDevices.value = current.map {
                        if (it.address == address) it.copy(rssi = rssi) else it
                    }
                }

                // Auto-connect to the first matching device since we filter by exact name.
                stopScan()
                connectTo(result.device)
            }
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "scan failed code=$errorCode")
                stopScan()
                _lastError.value = "Scan failed: code $errorCode"
                _state.value = ConnectionState.Error
            }
        }
        scanCallback = cb
        scanner.startScan(filters, settings, cb)
        Log.d(TAG, "  scan started, name filter=$target")
        mainHandler.postDelayed(scanTimeout, BottleConfig.SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    fun connectToAddress(address: String) {
        val a = adapter ?: return
        val device = a.getRemoteDevice(address)
        stopScan()
        connectTo(device)
    }

    @SuppressLint("MissingPermission")
    private fun connectTo(device: BluetoothDevice) {
        Log.d(TAG, "connectTo ${device.address}")
        _currentAddress.value = device.address
        _state.value = ConnectionState.Connecting
        gatt = device.connectGatt(appContext, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        mainHandler.removeCallbacks(scanTimeout)
        scanCallback?.let {
            Log.d(TAG, "stopScan")
            adapter?.bluetoothLeScanner?.stopScan(it)
        }
        scanCallback = null
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        Log.d(TAG, "stop")
        stopScan()
        gatt?.disconnect()
        if (gatt == null && _state.value != ConnectionState.Disconnected) {
            _state.value = ConnectionState.Disconnected
            _currentAddress.value = null
        }
    }

    /**
     * Permanent teardown — cancels the background scope. Call from the screen's
     * onDispose so coroutines don't outlive the activity. After dispose, this
     * BottleConnection instance must not be reused.
     */
    fun dispose() {
        Log.d(TAG, "dispose")
        stop()
        scope.cancel()
    }

    private fun BluetoothGattService.toInfo() = ServiceInfo(
        uuid = uuid,
        characteristics = characteristics.map { it.toInfo() },
    )

    private fun BluetoothGattCharacteristic.toInfo() = CharacteristicInfo(
        uuid = uuid,
        properties = buildList {
            val p = properties
            if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("READ")
            if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("WRITE")
            if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WRITE_NR")
            if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
            if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
        },
    )
}
