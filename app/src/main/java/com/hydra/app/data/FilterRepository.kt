package com.hydra.app.data

import androidx.room.withTransaction
import com.hydra.app.filter.FilterType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** The active filter plus its temporary-removal periods. */
data class ActiveFilter(
    val filter: FilterEntity,
    val pauses: List<FilterPauseEntity>,
) {
    /** True while the filter is temporarily out of the bottle. */
    val isPaused: Boolean get() = pauses.any { it.endSec == null }
}

class FilterRepository(private val db: HydraDatabase) {

    private val dao = db.filterDao()

    fun observeActive(): Flow<ActiveFilter?> =
        combine(dao.observeActive(), dao.observePauses()) { filter, pauses ->
            filter?.let { f -> ActiveFilter(f, pauses.filter { it.filterId == f.id }) }
        }

    suspend fun getActive(): ActiveFilter? {
        val f = dao.getActive() ?: return null
        return ActiveFilter(f, dao.getPauses(f.id))
    }

    /**
     * Start tracking a new filter installed at [atSec]. Any filter that was active is ended
     * at the same moment — so this doubles as "replace filter".
     */
    suspend fun install(type: FilterType, atSec: Long) = db.withTransaction {
        dao.closeOpenPauses(atSec)
        dao.endActiveFilters(atSec)
        dao.insertFilter(FilterEntity(type = type.key, installedAtSec = atSec))
    }

    /** Stop tracking the active filter without installing a new one. */
    suspend fun remove(atSec: Long = nowSec()) = db.withTransaction {
        dao.closeOpenPauses(atSec)
        dao.endActiveFilters(atSec)
    }

    /** Filter temporarily taken out: water drunk from now on doesn't count against it. */
    suspend fun pause(atSec: Long = nowSec()) = db.withTransaction {
        val active = dao.getActive() ?: return@withTransaction
        if (dao.openPauseCount(active.id) == 0) {
            dao.insertPause(FilterPauseEntity(filterId = active.id, startSec = atSec))
        }
    }

    /** Filter put back: counting resumes. Health is not reset. */
    suspend fun resume(atSec: Long = nowSec()) = dao.closeOpenPauses(atSec)

    private fun nowSec(): Long = System.currentTimeMillis() / 1000L
}
