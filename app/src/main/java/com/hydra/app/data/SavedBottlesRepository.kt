package com.hydra.app.data

import kotlinx.coroutines.flow.Flow

class SavedBottlesRepository(private val dao: SavedBottleDao) {

    fun observeAll(): Flow<List<SavedBottleEntity>> = dao.observeAll()

    /**
     * Pair (or re-pair) a bottle. On re-pair we preserve the original [SavedBottleEntity.pairedAtMs]
     * so it always reflects the *first* time the user paired this bottle, not the most recent
     * scan. Address is updated to the latest observation since RPAs rotate.
     */
    suspend fun save(name: String, address: String) {
        val existing = dao.getByName(name)
        val pairedAt = existing?.pairedAtMs ?: System.currentTimeMillis()
        dao.upsert(
            SavedBottleEntity(
                name = name,
                lastSeenAddress = address,
                pairedAtMs = pairedAt,
            )
        )
    }

    suspend fun remove(name: String) = dao.deleteByName(name)
}
