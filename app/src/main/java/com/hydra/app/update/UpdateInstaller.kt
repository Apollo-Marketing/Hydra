package com.hydra.app.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.File

class UpdateInstaller(private val context: Context) {

    fun canRequestInstall(): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun download(available: UpdateState.Available): Result<Long> = runCatching {
        purgeStaleApks()
        val dm = requireNotNull(context.getSystemService(DownloadManager::class.java)) {
            "DownloadManager unavailable"
        }
        val request = DownloadManager.Request(Uri.parse(available.apkUrl))
            .setTitle("Hydra ${available.versionName}")
            .setDescription("Downloading update")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setMimeType(MIME_APK)
            .setDestinationInExternalFilesDir(
                context,
                null,
                "$SUBDIR/${apkFileName(available.versionName)}",
            )
        dm.enqueue(request)
    }

    fun observeDownload(id: Long): Flow<DownloadProgress> = flow {
        val dm = requireNotNull(context.getSystemService(DownloadManager::class.java)) {
            "DownloadManager unavailable"
        }
        while (currentCoroutineContext().isActive) {
            val snapshot = runCatching { readSnapshot(dm, id) }.getOrElse {
                emit(DownloadProgress.Failed(it.message ?: "query error"))
                return@flow
            }
            if (snapshot == null) {
                emit(DownloadProgress.Failed("download not found"))
                return@flow
            }
            when (snapshot.status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val file = snapshot.localUri
                        ?.let { Uri.parse(it).path }
                        ?.let { File(it) }
                    if (file != null) emit(DownloadProgress.Done(file))
                    else emit(DownloadProgress.Failed("download path missing"))
                    return@flow
                }
                DownloadManager.STATUS_FAILED -> {
                    emit(DownloadProgress.Failed("download failed (reason=${snapshot.reason})"))
                    return@flow
                }
                else -> {
                    val frac = if (snapshot.total > 0) {
                        snapshot.soFar.toFloat() / snapshot.total
                    } else 0f
                    emit(DownloadProgress.InProgress(frac.coerceIn(0f, 1f)))
                }
            }
            delay(POLL_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.IO)

    fun launchInstaller(apkFile: File): Result<Unit> = runCatching {
        val uri = FileProvider.getUriForFile(context, AUTHORITY, apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, MIME_APK)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }

    private fun purgeStaleApks() {
        runCatching {
            val dir = File(context.getExternalFilesDir(null), SUBDIR)
            if (dir.isDirectory) {
                dir.listFiles { f -> f.isFile && f.name.endsWith(".apk") }
                    ?.forEach { it.delete() }
            }
        }
    }

    private fun readSnapshot(dm: DownloadManager, id: Long): Snapshot? {
        val q = DownloadManager.Query().setFilterById(id)
        return dm.query(q)?.use { c ->
            if (!c.moveToFirst()) null
            else Snapshot(
                status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                soFar = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                localUri = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)),
                reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
            )
        }
    }

    private data class Snapshot(
        val status: Int,
        val soFar: Long,
        val total: Long,
        val localUri: String?,
        val reason: Int,
    )

    companion object {
        const val AUTHORITY = "com.hydra.app.fileprovider"
        private const val MIME_APK = "application/vnd.android.package-archive"
        private const val SUBDIR = "updates"
        private const val POLL_INTERVAL_MS = 500L

        // versionName is always digits + dots — UpdateChecker.TAG_REGEX enforces ^v\d+\.\d+\.\d+$
        private fun apkFileName(versionName: String): String =
            "Hydra-$versionName.apk"
    }
}

sealed interface DownloadProgress {
    data class InProgress(val fraction: Float) : DownloadProgress
    data class Done(val file: File) : DownloadProgress
    data class Failed(val reason: String) : DownloadProgress
}
