package com.family.bankapp.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.family.bankapp.BankAppApplication
import com.family.bankapp.update.AppDownloadProgress
import com.family.bankapp.update.AppUpdateClient
import com.family.bankapp.update.AppUpdateDownloadCoordinator
import com.family.bankapp.update.AppUpdateDownloadState
import com.family.bankapp.update.AppUpdateInstaller
import com.family.bankapp.update.AppUpdateManifest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed class AppUpdateUiState {
    data object Idle : AppUpdateUiState()
    data object Checking : AppUpdateUiState()
    data object UpToDate : AppUpdateUiState()
    data class Available(val manifest: AppUpdateManifest) : AppUpdateUiState()
    data class Downloading(val progress: AppDownloadProgress) : AppUpdateUiState()
    data class ReadyToInstall(
        val manifest: AppUpdateManifest,
        val apkFile: File,
        val installAttempt: Long
    ) : AppUpdateUiState()
    data class Error(val message: String) : AppUpdateUiState()
}

class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as BankAppApplication
    private val downloadCoordinator: AppUpdateDownloadCoordinator = app.appUpdateDownloadCoordinator

    private val _state = MutableStateFlow<AppUpdateUiState>(AppUpdateUiState.Idle)
    val state: StateFlow<AppUpdateUiState> = _state.asStateFlow()

    private var nextInstallAttempt = 0L
    private var activeManifest: AppUpdateManifest? = null

    init {
        AppUpdateInstaller.cleanupStaleDownloads(app)
        downloadCoordinator.recoverPersistedDownloads()
        viewModelScope.launch {
            downloadCoordinator.state.collect { downloadState ->
                applyDownloadState(downloadState)
            }
        }
    }

    val currentVersionName: String
        get() = AppUpdateInstaller.currentVersionName(getApplication())

    val currentVersionCode: Int
        get() = AppUpdateInstaller.currentVersionCode(getApplication())

    fun checkForUpdate() {
        if (_state.value is AppUpdateUiState.Checking) return
        if (_state.value is AppUpdateUiState.Downloading &&
            downloadCoordinator.state.value is AppUpdateDownloadState.Downloading
        ) {
            return
        }

        viewModelScope.launch {
            _state.value = AppUpdateUiState.Checking
            AppUpdateClient.fetchManifest()
                .onSuccess { manifest ->
                    activeManifest = manifest
                    _state.value = if (manifest.versionCode > currentVersionCode) {
                        AppUpdateInstaller.findCachedApk(app, manifest)?.let { cached ->
                            readyToInstall(manifest, cached)
                            return@launch
                        }
                        AppUpdateInstaller.readPartialProgress(app, manifest)?.let { progress ->
                            AppUpdateUiState.Error(
                                "Download paused at ${progress.label}. Tap Update now to resume."
                            )
                        } ?: AppUpdateUiState.Available(manifest)
                    } else {
                        AppUpdateUiState.UpToDate
                    }
                }
                .onFailure { e ->
                    _state.value = AppUpdateUiState.Error(
                        e.message ?: "Could not check for updates"
                    )
                }
        }
    }

    fun updateNow() {
        when (val current = _state.value) {
            is AppUpdateUiState.Available -> startDownload(current.manifest)
            is AppUpdateUiState.ReadyToInstall -> retryInstall(current)
            is AppUpdateUiState.Downloading -> activeManifest?.let(::startDownload)
            is AppUpdateUiState.Error -> activeManifest?.let(::startDownload)
            else -> Unit
        }
    }

    fun onInstallLaunchFailed(message: String) {
        val ready = _state.value as? AppUpdateUiState.ReadyToInstall ?: run {
            _state.value = AppUpdateUiState.Error(message)
            return
        }
        if (AppUpdateInstaller.findCachedApk(app, ready.manifest) == null) {
            AppUpdateInstaller.deleteUpdateFiles(app, ready.manifest.versionCode)
            _state.value = AppUpdateUiState.Available(
                ready.manifest.copy(
                    notes = "Previous download was incomplete. Tap Update now to resume."
                )
            )
            return
        }
        _state.value = ready
    }

    private fun applyDownloadState(downloadState: AppUpdateDownloadState) {
        when (downloadState) {
            AppUpdateDownloadState.Idle -> Unit
            is AppUpdateDownloadState.Downloading -> {
                activeManifest = downloadState.manifest
                _state.value = AppUpdateUiState.Downloading(downloadState.progress)
            }
            is AppUpdateDownloadState.Completed -> {
                activeManifest = downloadState.manifest
                readyToInstall(downloadState.manifest, downloadState.apkFile)
                downloadCoordinator.clearCompleted()
            }
            is AppUpdateDownloadState.Interrupted -> {
                activeManifest = downloadState.manifest
                _state.value = AppUpdateUiState.Error(downloadState.message)
            }
            is AppUpdateDownloadState.Failed -> {
                activeManifest = downloadState.manifest
                _state.value = AppUpdateUiState.Error(downloadState.message)
            }
        }
    }

    private fun readyToInstall(manifest: AppUpdateManifest, apkFile: File) {
        nextInstallAttempt += 1
        _state.value = AppUpdateUiState.ReadyToInstall(
            manifest = manifest,
            apkFile = apkFile,
            installAttempt = nextInstallAttempt
        )
    }

    private fun retryInstall(current: AppUpdateUiState.ReadyToInstall) {
        val cached = AppUpdateInstaller.findCachedApk(app, current.manifest)
        if (cached == null) {
            AppUpdateInstaller.deleteUpdateFiles(app, current.manifest.versionCode)
            _state.value = AppUpdateUiState.Available(current.manifest)
            return
        }
        nextInstallAttempt += 1
        _state.value = current.copy(apkFile = cached, installAttempt = nextInstallAttempt)
    }

    private fun startDownload(manifest: AppUpdateManifest) {
        activeManifest = manifest
        if (!AppUpdateInstaller.canInstallPackages(app)) {
            _state.value = AppUpdateUiState.Error(
                "Allow installs from this app in Settings, then tap Update now again."
            )
            AppUpdateInstaller.openInstallPermissionSettings(app)
            return
        }
        AppUpdateInstaller.findCachedApk(app, manifest)?.let { cached ->
            readyToInstall(manifest, cached)
            return
        }
        downloadCoordinator.startDownload(manifest)
    }
}
