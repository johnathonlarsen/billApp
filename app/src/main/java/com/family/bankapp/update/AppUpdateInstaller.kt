package com.family.bankapp.update

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

typealias DownloadProgressCallback = (AppDownloadProgress) -> Unit

object AppUpdateInstaller {

    fun currentVersionCode(context: Context): Int {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }

    fun currentVersionName(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return info.versionName ?: "?"
    }

    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    fun apkFileForVersion(context: Context, versionCode: Int): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        return File(dir, "FamilyBank-v$versionCode.apk")
    }

    fun findCachedApk(context: Context, versionCode: Int): File? {
        val file = apkFileForVersion(context, versionCode)
        return file.takeIf { isValidApkFile(it) }
    }

    suspend fun downloadApk(
        context: Context,
        manifest: AppUpdateManifest,
        onProgress: DownloadProgressCallback = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val target = apkFileForVersion(context, manifest.versionCode)
            findCachedApk(context, manifest.versionCode)?.let { cached ->
                reportProgress(onProgress, cached.length(), cached.length())
                return@runCatching cached
            }

            val url = URL(manifest.apkUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 30_000
                readTimeout = 120_000
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("Cache-Control", "no-cache")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                error("Download failed ($code)")
            }
            val totalBytes = conn.contentLengthLong.takeIf { it > 0L }
            reportProgress(onProgress, 0, totalBytes)
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        reportProgress(onProgress, downloaded, totalBytes)
                    }
                }
            }
            if (!isValidApkFile(target)) {
                target.delete()
                error("Downloaded file is not a valid APK")
            }
            target
        }
    }

    fun startInstall(context: Context, apkFile: File): Result<Unit> = runCatching {
        if (!isValidApkFile(apkFile)) {
            error("Downloaded APK is missing or invalid")
        }
        val activity = context.findActivity()
            ?: error("Could not open installer — return to the app and tap Open installer")
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(activity.packageManager) == null) {
            error("No app on this phone can install APK files")
        }
        activity.startActivity(intent)
    }

    fun startInstallOnNextFrame(context: Context, apkFile: File, onResult: (Result<Unit>) -> Unit) {
        Handler(Looper.getMainLooper()).post {
            onResult(startInstall(context, apkFile))
        }
    }

    private suspend fun reportProgress(
        onProgress: DownloadProgressCallback,
        downloaded: Long,
        totalBytes: Long?
    ) {
        withContext(Dispatchers.Main.immediate) {
            onProgress(AppDownloadProgress(downloaded, totalBytes))
        }
    }

    private fun isValidApkFile(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        return file.inputStream().use { input ->
            input.read() == 'P'.code &&
                input.read() == 'K'.code
        }
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
