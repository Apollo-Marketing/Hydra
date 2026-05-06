package com.hydra.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A bottle the user has paired with this app. Persisted across launches.
 * NOTE: BLE addresses are Resolvable Private Addresses on the Larq bottle and rotate
 * roughly every 15 minutes — this saved address is only valid for the current scan window.
 * The [name] is the stable identifier; we re-scan by name when reconnecting.
 */
@Entity(tableName = "saved_bottles")
data class SavedBottleEntity(
    @PrimaryKey val name: String,           // bottle's BLE local name — stable across RPA rotations
    val lastSeenAddress: String,            // last observed BLE address (RPA — may be stale)
    val pairedAtMs: Long,                   // first time the user paired
)
