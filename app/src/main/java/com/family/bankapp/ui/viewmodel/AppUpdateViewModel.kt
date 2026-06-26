package com.family.bankapp.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.family.bankapp.update.AppDownloadProgress
import com.family.bankapp.update.AppUpdateClient
import com.family.bankapp.update.AppUpdateInstaller
import com.family.bankapp.update.AppUpdateManifest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed class AppUpdateUiState {
    data object Idle : AppUpdateUiState()
    data object Checking : AppUpdateUiState()
    data object UpToDate : AppUpdateUiState()
    data class Available(val manifest: AppUpdateManifest) : AppUpdateUiState()
    data class Downloading(val progress: AppDownloadProgress) : AppUpdateUiState()
    data class ReadyToInstall(val manifest: AppUpdateManifest, val apkFile: File) : AppUpdateUiState()
    data class Error(val message: String) : AppUpdateUiState()
}

class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<AppUpdateUiState>(AppUpdateUiState.Idle)
    val state: StateFlow<AppUpdateUiState> = _state.asStateFlow()

    private val _launchInstall = MutableSharedFlow<File>(extraBufferCapacity = 1)
    val launchInstall: SharedFlow<File> = _launchInstall.asSharedFlow()

    val currentVersionName: String
        get() = AppUpdateInstaller.currentVersionName(getApplication())

    val currentVersionCode: Int
        get() = AppUpdateInstaller.currentVersionCode(getApplication())

    fun checkForUpdate() {
        if (_state.value is AppUpdateUiState.Checking || _state.value is AppUpdateUiState.Downloading) return

        viewModelScope.launch {
            _state.value = AppUpdateUiState.Checking
            AppUpdateClient.fetchManifest()
                .onSuccess { manifest ->
                    _state.value = if (manifest.versionCode > currentVersionCode) {
                        AppUpdateUiState.Available(manifest)
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
            is AppUpdateUiState.Available -> downloadAndInstall(current.manifest)
            is AppUpdateUiState.ReadyToInstall -> requestInstall(current.apkFile, current.manifest)
            else -> Unit
        }
    }

    private fun requestInstall(apkFile: File, manifest: AppUpdateManifest) {
        _state.value = AppUpdateUiState.ReadyToInstall(manifest, apkFile)
        _launchInstall.tryEmit(apkFile)
    }

    private fun downloadAndInstall(manifest: AppUpdateManifest) {
        viewModelScope.launch {
            _state.value = AppUpdateUiState.Downloading(
                AppDownloadProgress(0, null)
            )
            val context = getApplication<Application>()
            if (!AppUpdateInstaller.canInstallPackages(context)) {
                _state.value = AppUpdateUiState.Error(
                    "Allow installs from this app in Settings, then tap Update now again."
                )
                AppUpdateInstaller.openInstallPermissionSettings(context)
                return@launch
            }

            AppUpdateInstaller.downloadApk(context, manifest) { progress ->
                _state.value = AppUpdateUiState.Downloading(progress)
            }
                .onSuccess { file ->
                    requestInstall(file, manifest)
                }
                .onFailure { e ->
                    _state.value = AppUpdateUiState.Error(e.message ?: "Download failed")
                }
        }
    }
}
