package com.hydra.app.update

import java.io.File

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState

    data class Available(
        val versionCode: Int,
        val versionName: String,
        val releaseNotes: String,
        val apkUrl: String,
        val sizeBytes: Long,
    ) : UpdateState

    data class Downloading(val progress: Float) : UpdateState
    data class ReadyToInstall(val file: File) : UpdateState
    data object InstallerLaunched : UpdateState
    data object PermissionRequired : UpdateState

    data class Error(val message: String) : UpdateState
}
