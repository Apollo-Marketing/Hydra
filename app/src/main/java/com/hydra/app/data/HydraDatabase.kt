package com.hydra.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SipEntity::class, SavedBottleEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class HydraDatabase : RoomDatabase() {

    abstract fun sipDao(): SipDao
    abstract fun savedBottleDao(): SavedBottleDao

    companion object {
        @Volatile
        private var instance: HydraDatabase? = null

        fun get(context: Context): HydraDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                HydraDatabase::class.java,
                "hydra.db",
            )
                // V1 → V2 adds saved_bottles table. V2 → V3 adds sip_log.manualVolumeMl.
                // Since sip_log can always be re-fetched from the bottle and saved_bottles
                // re-pair, destructive fallback is safer than writing a real migration.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build().also { instance = it }
        }
    }
}
