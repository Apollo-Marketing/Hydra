package com.hydra.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedBottleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bottle: SavedBottleEntity)

    @Query("SELECT * FROM saved_bottles ORDER BY pairedAtMs ASC")
    fun observeAll(): Flow<List<SavedBottleEntity>>

    @Query("SELECT * FROM saved_bottles WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): SavedBottleEntity?

    @Query("DELETE FROM saved_bottles WHERE name = :name")
    suspend fun deleteByName(name: String)
}
