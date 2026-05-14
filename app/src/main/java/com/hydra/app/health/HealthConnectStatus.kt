package com.hydra.app.health

/**
 * Snapshot of everything the Settings UI needs to render the Health Connect row:
 * platform availability, whether the user has enabled the integration, whether the
 * permission has been granted, the sync watermark, and a running count of records
 * we've pushed in this app session.
 */
data class HealthConnectStatus(
    val availability: HealthConnectAvailability,
    val enabled: Boolean,
    val hasPermission: Boolean,
    val lastSyncSec: Long,
    val syncedCount: Int,
)
