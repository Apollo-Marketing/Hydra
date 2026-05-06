package com.hydra.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "HydraScan"

/** A bottle the scanner has discovered (deduplicated by name). */
data class DiscoveredBottle(
    val name: String,
    val address: String,
    val rssi: Int,
)

/** Scan state for UI. */
sealed class ScanState {
    data object Idle : ScanState()
    data object Scanning : ScanState()
    data class Error(val message: String) : ScanState()
}

/**
 * Lightweight scan-only component. Independent of [BottleConnection] — does NOT auto-connect
 * to anything. Surfaces nearby Larq bottles (by name prefix) for the pairing UI to choose from.
 *
 * Caller is responsible for ensuring BLUETOOTH_SCAN / BLUETOOTH_CONNECT permissions are granted
 * before calling [start].
 */
class BottleScanner(context: Context) {

    private val appContext = context.applicationContext
    private val adapter =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state.asStateFlow()

    private val _discovered = MutableStateFlow<List<DiscoveredBottle>>(emptyList())
    val discovered: StateFlow<List<DiscoveredBottle>> = _discovered.asStateFlow()

    private var callback: ScanCallback? = null
    private val timeout = Runnable {
        Log.d(TAG, "scan timeout — stopping")
        stop()
    }

    /** Begin scanning. Auto-stops after [timeoutMs] (default 30s). Idempotent. */
    @SuppressLint("MissingPermission")
    fun start(namePrefix: String = "LARQ_", timeoutMs: Long = 30_000L) {
        if (_state.value is ScanState.Scanning) {
            Log.d(TAG, "start: already scanning, ignoring")
            return
        }
        val a = adapter ?: run {
            _state.value = ScanState.Error("Bluetooth not available")
            return
        }
        if (!a.isEnabled) {
            _state.value = ScanState.Error("Bluetooth is off")
            return
        }
        val scanner = a.bluetoothLeScanner ?: run {
            _state.value = ScanState.Error("BLE scanner unavailable")
            return
        }

        _discovered.value = emptyList()
        _state.value = ScanState.Scanning

        // No ScanFilter — we get all advertising devices and filter by name prefix in code.
        // This catches both bottles using their friendly name and any LARQ_* variant.
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName ?: return
                if (!name.startsWith(namePrefix)) return
                val newEntry = DiscoveredBottle(
                    name = name,
                    address = result.device.address,
                    rssi = result.rssi,
                )
                val current = _discovered.value
                _discovered.value = if (current.none { it.name == newEntry.name }) {
                    current + newEntry
                } else {
                    current.map { if (it.name == newEntry.name) newEntry else it }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "scan failed code=$errorCode")
                _state.value = ScanState.Error("Scan failed (code $errorCode)")
            }
        }
        callback = cb

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(emptyList(), settings, cb)
        mainHandler.postDelayed(timeout, timeoutMs)
        Log.d(TAG, "scan started, prefix=$namePrefix timeoutMs=$timeoutMs")
    }

    /** Stop scanning. Idempotent. */
    @SuppressLint("MissingPermission")
    fun stop() {
        mainHandler.removeCallbacks(timeout)
        callback?.let {
            adapter?.bluetoothLeScanner?.stopScan(it)
            Log.d(TAG, "scan stopped")
        }
        callback = null
        if (_state.value is ScanState.Scanning) {
            _state.value = ScanState.Idle
        }
    }
}
