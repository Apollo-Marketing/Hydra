package com.hydra.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One physical filter cartridge the user has installed. At most one row has
 * `removedAtSec == null` — that is the filter currently tracked. Replaced/removed filters
 * stay in the table as history.
 *
 * Filter state is app-side only: the bottle has no notion of a filter (the official app
 * keeps this in its cloud; we keep it locally).
 */
@Entity(tableName = "filters")
data class FilterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** `FilterType.key` — "essential" or "advanced". */
    val type: String,
    val installedAtSec: Long,
    /** Null while this filter is the active one. */
    val removedAtSec: Long? = null,
)

/**
 * A stretch of time when the filter was temporarily taken out of the bottle. Water drunk
 * inside a pause does not count toward the filter's volume. `endSec == null` means the
 * filter is out right now. The filter's age is NOT paused — it always runs from install.
 */
@Entity(tableName = "filter_pauses")
data class FilterPauseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filterId: Long,
    val startSec: Long,
    val endSec: Long? = null,
)
