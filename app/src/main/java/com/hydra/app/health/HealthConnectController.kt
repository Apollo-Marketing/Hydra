package com.hydra.app.health

import android.content.Context
import android.util.Log
import androidx.activity.result.contract.ActivityResultContract
import com.hydra.app.ble.BottleMath
import com.hydra.app.data.AppPreferencesRepository
import com.hydra.app.data.SipEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "HydraHC"

/**
 * Health Connect's recommended ceiling for a single insertRecords call. Larger lists are
 * rejected wholesale, so the controller chunks long backfills into this-size batches.
 */
private const val BATCH_SIZE = 1000

/**
 * Drives Health Connect sync. Sits above [HealthConnectGateway], owns the "enabled"
 * preference and the last-synced watermark, and exposes a Compose-friendly status
 * [StateFlow] for the Settings UI.
 *
 * Sync runs through [onSipsChanged]: the UI subscribes to the local sipLog Flow and calls
 * here whenever the entry list emits. Re-entrant calls are safe — the watermark advances
 * atomically, and an internal mutex serializes overlapping syncs. A re-call with no new
 * entries since the last watermark is a free no-op.
 */
class HealthConnectController private constructor(context: Context) {

    private val gateway = HealthConnectGateway(context)
    private val prefs = AppPreferencesRepository.get(context)

    /** Serializes overlapping onSipsChanged calls so the watermark write isn't racey. */
    private val syncMutex = Mutex()

    private val _status = MutableStateFlow(
        HealthConnectStatus(
            availability = gateway.availability(),
            enabled = false,
            hasPermission = false,
            lastSyncSec = 0L,
            syncedCount = 0,
        ),
    )
    val status: StateFlow<HealthConnectStatus> = _status.asStateFlow()

    /** Passthroughs so the Settings UI never imports `androidx.health.connect.client.*`. */
    val requiredPermissions: Set<String> get() = gateway.requiredPermissions
    fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        gateway.permissionContract()

    /**
     * Re-reads availability + permission grant from the SDK and the persisted prefs. Call on
     * first composition and after the permission-request result returns.
     */
    suspend fun refreshAvailabilityAndPermission() {
        val availability = gateway.availability()
        val hasPermission = gateway.hasPermissions()
        val enabled = prefs.healthConnectEnabled.first()
        val lastSyncSec = prefs.healthConnectLastSyncSec.first()
        _status.update {
            it.copy(
                availability = availability,
                enabled = enabled,
                hasPermission = hasPermission,
                lastSyncSec = lastSyncSec,
            )
        }
    }

    /**
     * Persist the user's enable/disable choice. Does NOT delete past records on disable —
     * the user can still inspect everything we already wrote inside the Health Connect app.
     */
    suspend fun setEnabled(enabled: Boolean) {
        prefs.setHealthConnectEnabled(enabled)
        _status.update { it.copy(enabled = enabled) }
    }

    /**
     * Sync entry point. Idempotent — calling repeatedly with the same [allEntries] writes
     * 0 records after the first call advances the watermark. Returns silently when disabled,
     * when Health Connect isn't installed, or when permission hasn't been granted. The first
     * call after the user toggles on backfills the entire history in chunked batches; later
     * calls only push the delta.
     */
    suspend fun onSipsChanged(allEntries: List<SipEntity>, bottleSizeMl: Int) {
        syncMutex.withLock {
            if (!prefs.healthConnectEnabled.first()) return
            if (gateway.availability() != HealthConnectAvailability.Available) return
            if (!gateway.hasPermissions()) return

            val watermark = prefs.healthConnectLastSyncSec.first()
            val pending = BottleMath.perSipDrinks(allEntries, bottleSizeMl)
                .filter { it.timestampSec > watermark }
            if (pending.isEmpty()) return

            var inserted = 0
            var maxTimestampSec = watermark
            for (batch in pending.chunked(BATCH_SIZE)) {
                val records = batch.map {
                    HealthConnectGateway.SipRecord(
                        clientId = clientIdFor(it.timestampSec),
                        startMs = it.startMs,
                        endMs = it.endMs,
                        volumeMl = it.volumeMl,
                    )
                }
                val n = gateway.writeHydration(records)
                if (n == 0) {
                    // Batch failed inside the gateway (already logged). Stop advancing the
                    // watermark here — the next sipLog tick will retry from this point.
                    break
                }
                inserted += n
                maxTimestampSec = maxOf(maxTimestampSec, batch.last().timestampSec)
            }

            if (inserted > 0) {
                prefs.setHealthConnectLastSyncSec(maxTimestampSec)
                _status.update {
                    it.copy(
                        lastSyncSec = maxTimestampSec,
                        syncedCount = it.syncedCount + inserted,
                    )
                }
                Log.d(TAG, "synced $inserted sips, watermark → $maxTimestampSec")
            }
        }
    }

    /** Remove a single sip from Health Connect by its bottle-assigned timestamp. */
    suspend fun deleteSipRecord(timestampSec: Long): Boolean =
        gateway.deleteHydration(listOf(clientIdFor(timestampSec)))

    private fun clientIdFor(timestampSec: Long): String = "hydra-sip-$timestampSec"

    companion object {
        @Volatile
        private var instance: HealthConnectController? = null

        fun get(context: Context): HealthConnectController = instance ?: synchronized(this) {
            instance ?: HealthConnectController(context.applicationContext).also { instance = it }
        }
    }
}
