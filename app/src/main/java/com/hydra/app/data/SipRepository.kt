package com.hydra.app.data

import com.hydra.app.ble.SipEntry
import kotlinx.coroutines.flow.Flow

/**
 * Thin wrapper around [SipDao]. Lets BottleConnection talk in domain types
 * (SipEntry) without importing Room directly.
 */
class SipRepository(private val dao: SipDao) {

    fun observeAll(): Flow<List<SipEntity>> = dao.observeAll()

    /** Most recent stored timestamp in seconds, or 0 if the DB is empty. */
    suspend fun lastTimestamp(): Long = dao.maxTimestamp() ?: 0L

    suspend fun upsertAll(entries: List<SipEntry>) {
        if (entries.isEmpty()) return
        dao.upsertAll(entries.map { it.toEntity() })
    }

    /**
     * Persist a manually-logged sip. The bottle never sends these — they originate from a
     * user tapping "Log a sip" in the app. `distanceMm`/`kcps`/`uvLedTempOhm` are zeroed; only
     * `manualVolumeMl` carries real intake info, and consumers (BottleMath) check that
     * field to decide whether to skip the distance-polynomial path for this row.
     */
    suspend fun insertManual(
        volumeMl: Int,
        atSec: Long,
        receivedAtMs: Long = System.currentTimeMillis(),
    ) {
        dao.insert(
            SipEntity(
                timestampSec = atSec,
                triggerType = SipEntity.TRIGGER_MANUAL,
                distanceMm = 0,
                kcps = 0,
                uvLedTempOhm = 0f,
                receivedAtMs = receivedAtMs,
                manualVolumeMl = volumeMl.toFloat(),
            ),
        )
    }

    suspend fun deleteAll() = dao.deleteAll()

    /** Remove a manually-logged sip by its timestamp. No-op for BLE-synced rows. */
    suspend fun deleteManual(timestampSec: Long) = dao.deleteManualByTimestamp(timestampSec)
}
