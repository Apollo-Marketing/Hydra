package com.hydra.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SipDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<SipEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: SipEntity)

    @Query("SELECT * FROM sip_log ORDER BY timestampSec DESC")
    fun observeAll(): Flow<List<SipEntity>>

    @Query("SELECT MAX(timestampSec) FROM sip_log")
    suspend fun maxTimestamp(): Long?

    @Query("DELETE FROM sip_log")
    suspend fun deleteAll()

    /**
     * Delete a single manually-logged row by its timestamp. The `manualVolumeMl IS NOT NULL`
     * guard means a stray call with a BLE-synced timestamp is a no-op — bottle data is the
     * source of truth and only resyncs from the bottle should mutate it.
     */
    @Query("DELETE FROM sip_log WHERE timestampSec = :timestampSec AND manualVolumeMl IS NOT NULL")
    suspend fun deleteManualByTimestamp(timestampSec: Long)
}
