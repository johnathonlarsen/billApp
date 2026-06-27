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
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

typealias DownloadProgressCallback = (AppDownloadProgress) -> Unit

data class ResumablePartialDownload(
    val manifest: AppUpdateManifest,
    val progress: AppDownloadProgress
)

object AppUpdateInstaller {

    private const val MIN_APK_BYTES = 10_000L
    private const val META_SAVE_INTERVAL_BYTES = 256_000L

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

    private fun metaFileForVersion(context: Context, versionCode: Int): File {
        val dir = updatesDir(context)
        return File(dir, "FamilyBank-v$versionCode.apk.part.meta")
    }

    private fun updatesDir(context: Context): File =
        File(context.cacheDir, "updates").apply { mkdirs() }

    private fun parseVersionCodeFromFileName(name: String): Int? {
        val match = VERSION_FILE_REGEX.find(name) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    /** Removes finished installs for older versions and orphan files without metadata. */
    fun cleanupStaleDownloads(context: Context) {
        val dir = updatesDir(context)
        val installedVersion = currentVersionCode(context)
        dir.listFiles()?.forEach { file ->
            val versionCode = parseVersionCodeFromFileName(file.name)
            when {
                file.name.endsWith(".part.meta") -> {
                    val metaVersion = parseVersionCodeFromFileName(file.name.replace(".meta", ""))
                    if (metaVersion != null && metaVersion <= installedVersion) {
                        file.delete()
                    }
                }
                file.name.endsWith(".part") -> {
                    if (versionCode == null) {
                        file.delete()
                    } else {
                        val meta = metaFileForVersion(context, versionCode)
                        if (versionCode <= installedVersion || !meta.exists()) {
                            file.delete()
                            meta.delete()
                        }
                    }
                }
                file.name.endsWith(".apk") && versionCode != null && versionCode <= installedVersion -> {
                    file.delete()
                }
                file.name.endsWith(".apk") && file.length() < MIN_APK_BYTES -> file.delete()
            }
        }
    }

    fun deleteUpdateFiles(context: Context, versionCode: Int) {
        apkFileForVersion(context, versionCode).delete()
        partialApkFileForVersion(context, versionCode).delete()
        metaFileForVersion(context, versionCode).delete()
    }

    fun findCachedApk(
        context: Context,
        manifest: AppUpdateManifest
    ): File? {
        val file = apkFileForVersion(context, manifest.versionCode)
        if (!isValidApkFile(context, file, manifest.apkSizeBytes)) {
            if (file.exists()) file.delete()
            return null
        }
        partialApkFileForVersion(context, manifest.versionCode).delete()
        metaFileForVersion(context, manifest.versionCode).delete()
        return file
    }

    fun readPartialProgress(context: Context, manifest: AppUpdateManifest): AppDownloadProgress? {
        val partial = partialApkFileForVersion(context, manifest.versionCode)
        val meta = AppUpdateDownloadMeta.read(metaFileForVersion(context, manifest.versionCode))
            ?: return null
        if (!meta.matches(manifest) || !partial.exists() || partial.length() <= 0L) return null
        val total = manifest.apkSizeBytes ?: meta.apkSizeBytes
        if (total != null && partial.length() >= total) return null
        return AppDownloadProgress(partial.length(), total)
    }

    fun findResumablePartial(context: Context, minVersionCode: Int): ResumablePartialDownload? {
        val dir = updatesDir(context)
        return dir.listFiles()
            ?.filter { it.name.endsWith(".part") }
            ?.mapNotNull { partial ->
                val versionCode = parseVersionCodeFromFileName(partial.name) ?: return@mapNotNull null
                if (versionCode < minVersionCode) return@mapNotNull null
                val meta = AppUpdateDownloadMeta.read(metaFileForVersion(context, versionCode))
                    ?: return@mapNotNull null
                if (partial.length() <= 0L) return@mapNotNull null
                val manifest = AppUpdateManifest(
                    versionCode = meta.versionCode,
                    versionName = "?",
                    apkUrl = meta.apkUrl,
                    apkSizeBytes = meta.apkSizeBytes
                )
                val total = meta.apkSizeBytes
                if (total != null && partial.length() >= total) return@mapNotNull null
                ResumablePartialDownload(
                    manifest = manifest,
                    progress = AppDownloadProgress(partial.length(), total)
                )
            }
            ?.maxByOrNull { it.manifest.versionCode }
    }

    suspend fun downloadApk(
        context: Context,
        manifest: AppUpdateManifest,
        onProgress: DownloadProgressCallback = {},
        preservePartialOnCancel: Boolean = true
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            findCachedApk(context, manifest)?.let { cached ->
                reportProgress(onProgress, cached.length(), cached.length())
                return@runCatching cached
            }

            val target = apkFileForVersion(context, manifest.versionCode)
            val partial = partialApkFileForVersion(context, manifest.versionCode)
            val metaFile = metaFileForVersion(context, manifest.versionCode)

            var resumeFrom = readPartialProgress(context, manifest)?.bytesDownloaded ?: 0L
            if (resumeFrom <= 0L) {
                target.delete()
                partial.delete()
                metaFile.delete()
            }

            AppUpdateDownloadMeta.write(metaFile, AppUpdateDownloadMeta.fromManifest(manifest))

            val (conn, startOffset) = openDownloadConnection(manifest.apkUrl, resumeFrom, partial)
            try {
                val totalBytes = when (conn.responseCode) {
                    HttpURLConnection.HTTP_PARTIAL -> {
                        val contentRange = conn.getHeaderField("Content-Range")
                        parseTotalFromContentRange(contentRange)
                            ?: manifest.apkSizeBytes
                            ?: (startOffset + conn.contentLengthLong.coerceAtLeast(0L))
                    }
                    else -> conn.contentLengthLong.takeIf { it > 0L } ?: manifest.apkSizeBytes
                }

                reportProgress(onProgress, startOffset, totalBytes)

                var downloaded = startOffset
                var lastMetaSave = startOffset
                conn.inputStream.use { input ->
                    FileOutputStream(partial, startOffset > 0L).use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (downloaded - lastMetaSave >= META_SAVE_INTERVAL_BYTES) {
                                AppUpdateDownloadMeta.write(
                                    metaFile,
                                    AppUpdateDownloadMeta.fromManifest(manifest)
                                )
                                lastMetaSave = downloaded
                            }
                            reportProgress(onProgress, downloaded, totalBytes)
                        }
                        output.flush()
                    }
                }

                finalizeDownload(context, partial, target, metaFile, manifest, totalBytes)
            } catch (e: CancellationException) {
                if (!preservePartialOnCancel) {
                    partial.delete()
                    metaFile.delete()
                } else {
                    AppUpdateDownloadMeta.write(metaFile, AppUpdateDownloadMeta.fromManifest(manifest))
                }
                throw e
            } catch (e: Exception) {
                if (!preservePartialOnCancel || partial.length() <= 0L) {
                    partial.delete()
                    metaFile.delete()
                }
                throw e
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun openDownloadConnection(
        apkUrl: String,
        resumeFrom: Long,
        partial: File
    ): Pair<HttpURLConnection, Long> {
        if (resumeFrom > 0L) {
            val resumeConn = openConnection(apkUrl, resumeFrom)
            if (resumeConn.responseCode == HttpURLConnection.HTTP_PARTIAL) {
                return resumeConn to resumeFrom
            }
            resumeConn.disconnect()
            partial.delete()
        }
        return openConnection(apkUrl, resumeFrom = 0L) to 0L
    }

    private fun openConnection(apkUrl: String, resumeFrom: Long): HttpURLConnection {
        val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30_000
            readTimeout = 90_000
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("Cache-Control", "no-cache")
            if (resumeFrom > 0L) {
                setRequestProperty("Range", "bytes=$resumeFrom-")
            }
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            error("Download failed ($code)")
        }
        return conn
    }

    private fun finalizeDownload(
        context: Context,
        partial: File,
        target: File,
        metaFile: File,
        manifest: AppUpdateManifest,
        totalBytes: Long?
    ): File {
        val expectedBytes = manifest.apkSizeBytes ?: totalBytes
        if (expectedBytes != null && partial.length() != expectedBytes) {
            error("Download incomplete — tap Update now to resume")
        }
        if (!isValidApkFile(context, partial, expectedBytes)) {
            error("Downloaded file is not a valid APK — tap Update now to retry")
        }
        if (target.exists() && !target.delete()) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        } else if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
        metaFile.delete()
        return target
    }

    private fun parseTotalFromContentRange(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        val totalPart = header.substringAfterLast('/')
        return totalPart.toLongOrNull()
    }

    fun startInstall(context: Context, apkFile: File, expectedSizeBytes: Long? = null): Result<Unit> =
        runCatching {
            if (!isValidApkFile(context, apkFile, expectedSizeBytes)) {
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

    private val VERSION_FILE_REGEX = Regex("""FamilyBank-v(\d+)\.apk(?:\.part(?:\.meta)?)?""")
}
