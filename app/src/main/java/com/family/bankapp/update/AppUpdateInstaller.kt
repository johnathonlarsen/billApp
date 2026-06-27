package com.family.bankapp.update

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

typealias DownloadProgressCallback = (AppDownloadProgress) -> Unit

object AppUpdateInstaller {

    private const val MIN_APK_BYTES = 10_000L

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
        val dir = updatesDir(context)
        return File(dir, "FamilyBank-v$versionCode.apk")
    }

    private fun partialApkFileForVersion(context: Context, versionCode: Int): File {
        val dir = updatesDir(context)
        return File(dir, "FamilyBank-v$versionCode.apk.part")
    }

    private fun updatesDir(context: Context): File =
        File(context.cacheDir, "updates").apply { mkdirs() }

    fun cleanupStaleDownloads(context: Context) {
        val dir = updatesDir(context)
        dir.listFiles()?.forEach { file ->
            when {
                file.name.endsWith(".part") -> file.delete()
                file.name.endsWith(".apk") && file.length() < MIN_APK_BYTES -> file.delete()
            }
        }
    }

    fun deleteUpdateFiles(context: Context, versionCode: Int) {
        apkFileForVersion(context, versionCode).delete()
        partialApkFileForVersion(context, versionCode).delete()
    }

    fun findCachedApk(
        context: Context,
        manifest: AppUpdateManifest
    ): File? {
        cleanupStaleDownloads(context)
        val file = apkFileForVersion(context, manifest.versionCode)
        if (!isValidApkFile(context, file, manifest.apkSizeBytes)) {
            if (file.exists()) file.delete()
            return null
        }
        return file
    }

    suspend fun downloadApk(
        context: Context,
        manifest: AppUpdateManifest,
        onProgress: DownloadProgressCallback = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            findCachedApk(context, manifest)?.let { cached ->
                reportProgress(onProgress, cached.length(), cached.length())
                return@runCatching cached
            }

            val target = apkFileForVersion(context, manifest.versionCode)
            val partial = partialApkFileForVersion(context, manifest.versionCode)
            target.delete()
            partial.delete()

            val url = URL(manifest.apkUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("Cache-Control", "no-cache")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    error("Download failed ($code)")
                }
                val totalBytes = conn.contentLengthLong.takeIf { it > 0L }
                    ?: manifest.apkSizeBytes
                reportProgress(onProgress, 0, totalBytes)
                conn.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var downloaded = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            reportProgress(onProgress, downloaded, totalBytes)
                        }
                        output.flush()
                    }
                }

                val expectedBytes = manifest.apkSizeBytes ?: totalBytes
                if (expectedBytes != null && partial.length() != expectedBytes) {
                    partial.delete()
                    error("Download incomplete — try Update now again")
                }
                if (!isValidApkFile(context, partial, expectedBytes)) {
                    partial.delete()
                    error("Downloaded file is not a valid APK — try Update now again")
                }
                if (target.exists() && !target.delete()) {
                    partial.copyTo(target, overwrite = true)
                    partial.delete()
                } else if (!partial.renameTo(target)) {
                    partial.copyTo(target, overwrite = true)
                    partial.delete()
                }
                target
            } catch (e: CancellationException) {
                partial.delete()
                throw e
            } catch (e: Exception) {
                partial.delete()
                throw e
            } finally {
                conn.disconnect()
            }
        }
    }

    fun startInstall(context: Context, apkFile: File, expectedSizeBytes: Long? = null): Result<Unit> =
        runCatching {
            if (!isValidApkFile(context, apkFile, expectedSizeBytes)) {
                apkFile.delete()
                error("Download incomplete or corrupted — tap Update now to download again")
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

    fun startInstallOnNextFrame(
        context: Context,
        apkFile: File,
        expectedSizeBytes: Long? = null,
        onResult: (Result<Unit>) -> Unit
    ) {
        Handler(Looper.getMainLooper()).post {
            onResult(startInstall(context, apkFile, expectedSizeBytes))
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

    private fun isValidApkFile(
        context: Context,
        file: File,
        expectedSizeBytes: Long? = null
    ): Boolean {
        if (!file.exists() || file.length() < MIN_APK_BYTES) return false
        expectedSizeBytes?.let { expected ->
            if (file.length() != expected) return false
        }
        if (!hasZipHeader(file)) return false
        return parseArchiveInfo(context, file)?.packageName == context.packageName
    }

    private fun hasZipHeader(file: File): Boolean =
        file.inputStream().use { input ->
            input.read() == 'P'.code && input.read() == 'K'.code
        }

    @Suppress("DEPRECATION")
    private fun parseArchiveInfo(context: Context, file: File) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong())
            )
        } else {
            context.packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.GET_ACTIVITIES
            )
        }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
