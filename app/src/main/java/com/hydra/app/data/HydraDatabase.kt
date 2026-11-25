package com.hydra.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SipEntity::class,
        SavedBottleEntity::class,
        FilterEntity::class,
        FilterPauseEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class HydraDatabase : RoomDatabase() {

    abstract fun sipDao(): SipDao
    abstract fun savedBottleDao(): SavedBottleDao
    abstract fun filterDao(): FilterDao

    companion object {
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `filters` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`type` TEXT NOT NULL, " +
                        "`installedAtSec` INTEGER NOT NULL, " +
                        "`removedAtSec` INTEGER)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `filter_pauses` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`filterId` INTEGER NOT NULL, " +
                        "`startSec` INTEGER NOT NULL, " +
                        "`endSec` INTEGER)",
                )
            }
        }

        @Volatile
        private var instance: HydraDatabase? = null

        fun get(context: Context): HydraDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                HydraDatabase::class.java,
                "hydra.db",
            )
                // V3 → V4 only adds the two filter tables, so it gets a real migration: a
                // destructive one would wipe sip history older than the bottle's own buffer,
                // every manual sip, and the paired bottle.
                .addMigrations(MIGRATION_3_4)
                // V1 → V2 adds saved_bottles table. V2 → V3 adds sip_log.manualVolumeMl.
                // Since sip_log can always be re-fetched from the bottle and saved_bottles
                // re-pair, destructive fallback is safer than writing a real migration.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build().also { instance = it }
        }
    }
}
