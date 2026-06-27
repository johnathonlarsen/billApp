package com.family.bankapp.update

import android.app.Application
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class AppUpdateDownloadState {
    data object Idle : AppUpdateDownloadState()
    data class Downloading(
        val manifest: AppUpdateManifest,
        val progress: AppDownloadProgress
    ) : AppUpdateDownloadState()
    data class Completed(
        val manifest: AppUpdateManifest,
        val apkFile: File
    ) : AppUpdateDownloadState()
    data class Interrupted(
        val manifest: AppUpdateManifest,
        val progress: AppDownloadProgress,
        val message: String
    ) : AppUpdateDownloadState()
    data class Failed(
        val manifest: AppUpdateManifest,
        val message: String
    ) : AppUpdateDownloadState()
}

/**
 * Application-scoped update download — survives activity/ViewModel recreation and app backgrounding.
 */
class AppUpdateDownloadCoordinator(
    private val app: Application
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<AppUpdateDownloadState>(AppUpdateDownloadState.Idle)
    val state: StateFlow<AppUpdateDownloadState> = _state.asStateFlow()

    private var downloadJob: Job? = null

    fun recoverPersistedDownloads() {
        AppUpdateInstaller.cleanupStaleDownloads(app)
        val installedVersion = AppUpdateInstaller.currentVersionCode(app)
        val partial = AppUpdateInstaller.findResumablePartial(app, minVersionCode = installedVersion + 1)
            ?: return
        _state.value = AppUpdateDownloadState.Interrupted(
            manifest = partial.manifest,
            progress = partial.progress,
            message = "Download paused at ${partial.progress.label}. Tap Update now to resume."
        )
    }

    fun startDownload(manifest: AppUpdateManifest) {
        val current = _state.value
        if (current is AppUpdateDownloadState.Downloading && current.manifest.versionCode == manifest.versionCode) {
            return
        }
        downloadJob?.cancel()
        downloadJob = scope.launch {
            AppUpdateInstaller.findCachedApk(app, manifest)?.let { cached ->
                _state.value = AppUpdateDownloadState.Completed(manifest, cached)
                return@launch
            }

            AppUpdateDownloadService.start(app)
            val initialProgress = AppUpdateInstaller.readPartialProgress(app, manifest)
                ?: AppDownloadProgress(0, manifest.apkSizeBytes)
            _state.value = AppUpdateDownloadState.Downloading(manifest, initialProgress)

            AppUpdateInstaller.downloadApk(
                context = app,
                manifest = manifest,
                onProgress = { progress ->
                    _state.value = AppUpdateDownloadState.Downloading(manifest, progress)
                    AppUpdateDownloadService.updateProgress(app, progress)
                },
                preservePartialOnCancel = true
            )
                .onSuccess { file ->
                    _state.value = AppUpdateDownloadState.Completed(manifest, file)
                }
                .onFailure { error ->
                    val progress = AppUpdateInstaller.readPartialProgress(app, manifest)
                    _state.value = if (progress != null && progress.bytesDownloaded > 0) {
                        AppUpdateDownloadState.Interrupted(
                            manifest = manifest,
                            progress = progress,
                            message = error.message ?: "Download paused — tap Update now to resume."
                        )
                    } else {
                        AppUpdateDownloadState.Failed(
                            manifest = manifest,
                            message = error.message ?: "Download failed"
                        )
                    }
                }

            AppUpdateDownloadService.stop(app)
        }
    }

    fun clearCompleted() {
        if (_state.value is AppUpdateDownloadState.Completed) {
            _state.value = AppUpdateDownloadState.Idle
        }
    }
}
