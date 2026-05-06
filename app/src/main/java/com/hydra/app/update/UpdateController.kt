package com.hydra.app.update

import android.content.Context
import com.hydra.app.BuildConfig
import com.hydra.app.data.AppPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class UpdateController private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = AppPreferencesRepository.get(appContext)
    private val checker = UpdateChecker()
    private val installer = UpdateInstaller(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var lastAvailable: UpdateState.Available? = null

    fun checkOnLaunch() {
        if (checkJob?.isActive == true) return
        checkJob = scope.launch {
            if (!prefs.autoUpdateEnabled.first()) return@launch
            val last = prefs.lastUpdateCheckMs.first()
            if (System.currentTimeMillis() - last < AUTO_CHECK_INTERVAL_MS) return@launch
            runCheck(silentOnError = true, manual = false)
        }
    }

    fun checkNow() {
        if (checkJob?.isActive == true) return
        checkJob = scope.launch { runCheck(silentOnError = false, manual = true) }
    }

    fun startDownload() {
        val available = _state.value as? UpdateState.Available ?: return
        if (downloadJob?.isActive == true) return
        if (!installer.canRequestInstall()) {
            _state.value = UpdateState.PermissionRequired
            return
        }
        downloadJob = scope.launch { runDownload(available) }
    }

    fun installNow() {
        val ready = _state.value as? UpdateState.ReadyToInstall ?: return
        installer.launchInstaller(ready.file)
            .onSuccess { _state.value = UpdateState.InstallerLaunched }
            .onFailure { _state.value = UpdateState.Error(it.message ?: "installer launch failed") }
    }

    fun retry() {
        when (_state.value) {
            is UpdateState.PermissionRequired -> {
                lastAvailable?.let { _state.value = it } ?: dismiss()
            }
            is UpdateState.Error -> checkNow()
            else -> Unit
        }
    }

    fun dismiss() {
        _state.value = UpdateState.Idle
        lastAvailable = null
    }

    private suspend fun runCheck(silentOnError: Boolean, manual: Boolean) {
        if (manual) _state.value = UpdateState.Checking
        when (val result = checker.check(BuildConfig.VERSION_CODE)) {
            is UpdateState.Available -> {
                lastAvailable = result
                prefs.setLastUpdateCheck(System.currentTimeMillis())
                _state.value = result
            }
            UpdateState.UpToDate -> {
                prefs.setLastUpdateCheck(System.currentTimeMillis())
                _state.value = if (manual) UpdateState.UpToDate else UpdateState.Idle
            }
            is UpdateState.Error -> {
                _state.value = if (silentOnError) UpdateState.Idle else result
            }
            else -> Unit
        }
    }

    private suspend fun runDownload(available: UpdateState.Available) {
        val enqueue = installer.download(available)
        val downloadId = enqueue.getOrElse {
            _state.value = UpdateState.Error(it.message ?: "download failed to start")
            return
        }
        _state.value = UpdateState.Downloading(0f)
        installer.observeDownload(downloadId).collect { progress ->
            when (progress) {
                is DownloadProgress.InProgress -> {
                    _state.value = UpdateState.Downloading(progress.fraction)
                }
                is DownloadProgress.Done -> {
                    _state.value = UpdateState.ReadyToInstall(progress.file)
                    installNow()
                }
                is DownloadProgress.Failed -> {
                    _state.value = UpdateState.Error(progress.reason)
                }
            }
        }
    }

    companion object {
        private const val AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L

        @Volatile
        private var instance: UpdateController? = null

        fun get(context: Context): UpdateController = instance ?: synchronized(this) {
            instance ?: UpdateController(context.applicationContext).also { instance = it }
        }
    }
}
