package com.hydra.app.health

import android.content.Context
import android.util.Log
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Volume
import java.time.Instant
import java.time.ZoneId

private const val TAG = "HydraHC"

/**
 * The sole point of contact with `androidx.health.connect.client`. Every caller upstream
 * deals in [HealthConnectAvailability] and [SipRecord] — the SDK's record, permission, and
 * client types are not exposed. Reason: Health Connect's public surface has shifted between
 * major versions before (Metadata constructor → factory methods in 1.1.x); confining the
 * imports here keeps the blast radius of the next reshuffle to a single file.
 *
 * Write-only by design — see [requiredPermissions]. We never read other apps' hydration so
 * that bottle-derived intake stays the source of truth in our app.
 *
 * All SDK calls are wrapped in `runCatching` because Health Connect's IPC to the provider
 * can throw `RemoteException` if it crashes mid-call; we log and continue rather than
 * surface an opaque IPC error to the sync controller, which would have to bail out anyway.
 */
class HealthConnectGateway(context: Context) {

    private val appContext = context.applicationContext

    val requiredPermissions: Set<String> = setOf(
        HealthPermission.getWritePermission(HydrationRecord::class),
    )

    fun availability(): HealthConnectAvailability =
        when (HealthConnectClient.getSdkStatus(appContext)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.Available
            HealthConnectClient.SDK_UNAVAILABLE -> HealthConnectAvailability.NotInstalled
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectAvailability.UpdateRequired
            else -> HealthConnectAvailability.Unsupported
        }

    suspend fun hasPermissions(): Boolean {
        val granted = clientOrNull()?.permissionController?.getGrantedPermissions() ?: return false
        return granted.containsAll(requiredPermissions)
    }

    fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()

    /**
     * Per-sip intake event handed to [writeHydration]. `startMs == endMs` is allowed and
     * represents an instantaneous sip (a manually-logged drink, where we have no "duration
     * over which the user drank"); the gateway widens it by 1ms internally to satisfy
     * HydrationRecord's `startTime < endTime` invariant.
     */
    data class SipRecord(
        val clientId: String,
        val startMs: Long,
        val endMs: Long,
        val volumeMl: Double,
    )

    /** Returns the count actually inserted. Never throws — returns 0 on any failure. */
    suspend fun writeHydration(records: List<SipRecord>): Int {
        if (records.isEmpty()) return 0
        val client = clientOrNull() ?: return 0
        return runCatching {
            val zone = ZoneId.systemDefault()
            val mapped = records.map { it.toHydrationRecord(zone) }
            client.insertRecords(mapped)
            mapped.size
        }.onFailure { Log.w(TAG, "writeHydration failed (${records.size} records)", it) }
            .getOrDefault(0)
    }

    /** Returns true if the delete went through, false if anything went wrong. */
    suspend fun deleteHydration(clientIds: List<String>): Boolean {
        if (clientIds.isEmpty()) return true
        val client = clientOrNull() ?: return false
        return runCatching {
            client.deleteRecords(
                recordType = HydrationRecord::class,
                recordIdsList = emptyList(),
                clientRecordIdsList = clientIds,
            )
            true
        }.onFailure { Log.w(TAG, "deleteHydration failed (${clientIds.size} ids)", it) }
            .getOrDefault(false)
    }

    /** Null when availability is anything but [HealthConnectAvailability.Available]. */
    private fun clientOrNull(): HealthConnectClient? =
        if (availability() == HealthConnectAvailability.Available) {
            runCatching { HealthConnectClient.getOrCreate(appContext) }
                .onFailure { Log.w(TAG, "getOrCreate failed", it) }
                .getOrNull()
        } else null

    private fun SipRecord.toHydrationRecord(zone: ZoneId): HydrationRecord {
        val start = Instant.ofEpochMilli(startMs)
        val end = Instant.ofEpochMilli(if (endMs > startMs) endMs else startMs + 1)
        val offset = zone.rules.getOffset(start)
        return HydrationRecord(
            startTime = start,
            startZoneOffset = offset,
            endTime = end,
            endZoneOffset = offset,
            volume = Volume.liters(volumeMl / 1000.0),
            metadata = Metadata.manualEntry(clientRecordId = clientId),
        )
    }
}
