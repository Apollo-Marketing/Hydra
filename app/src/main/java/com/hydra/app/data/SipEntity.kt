package com.hydra.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.hydra.app.ble.SipEntry

/**
 * One sip event persisted to the local Room database.
 * Primary key is the bottle-assigned timestamp (Unix epoch SECONDS) — this also gives us
 * free deduplication: re-syncing returns the same entries which UPSERT silently overwrite.
 *
 * Most rows come from the bottle and have `manualVolumeMl == null`; their volume is derived
 * from `distanceMm` via `BottleMath`. Manually-logged sips set `manualVolumeMl` to the
 * user-entered amount and use [TRIGGER_MANUAL] as their `triggerType`; their `distanceMm`
 * is meaningless and must not be passed to the polynomial.
 */
@Entity(tableName = "sip_log")
data class SipEntity(
    @PrimaryKey val timestampSec: Long,
    val triggerType: Int,
    val distanceMm: Int,
    val kcps: Int,
    val uvLedTempOhm: Float,
    /** When our app received this entry from the bottle (epoch ms). */
    val receivedAtMs: Long,
    /** Non-null only for manually-logged sips; carries the user-entered volume directly. */
    val manualVolumeMl: Float? = null,
) {
    companion object {
        /** Sentinel `triggerType` for manually-logged sips. Outside the bottle's real range (~0..10). */
        const val TRIGGER_MANUAL = 99
    }
}

/** Convert a fresh in-memory protocol entry into a row for persistence. */
fun SipEntry.toEntity(receivedAtMs: Long = System.currentTimeMillis()): SipEntity =
    SipEntity(
        timestampSec = timestampSec,
        triggerType = triggerType,
        distanceMm = distanceMm,
        kcps = kcps,
        uvLedTempOhm = uvLedTempOhm,
        receivedAtMs = receivedAtMs,
    )
