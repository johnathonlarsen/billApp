package com.family.bankapp.update

import org.json.JSONObject
import java.io.File

data class AppUpdateDownloadMeta(
    val versionCode: Int,
    val apkUrl: String,
    val apkSizeBytes: Long?
) {
    fun matches(manifest: AppUpdateManifest): Boolean =
        versionCode == manifest.versionCode &&
            apkUrl == manifest.apkUrl &&
            apkSizeBytes == manifest.apkSizeBytes

    fun toJson(): JSONObject = JSONObject().apply {
        put("versionCode", versionCode)
        put("apkUrl", apkUrl)
        if (apkSizeBytes != null) put("apkSizeBytes", apkSizeBytes)
    }

    companion object {
        fun fromManifest(manifest: AppUpdateManifest) = AppUpdateDownloadMeta(
            versionCode = manifest.versionCode,
            apkUrl = manifest.apkUrl,
            apkSizeBytes = manifest.apkSizeBytes
        )

        fun read(file: File): AppUpdateDownloadMeta? = runCatching {
            if (!file.exists()) return null
            val json = JSONObject(file.readText())
            AppUpdateDownloadMeta(
                versionCode = json.getInt("versionCode"),
                apkUrl = json.getString("apkUrl"),
                apkSizeBytes = json.optLong("apkSizeBytes").takeIf { it > 0L }
            )
        }.getOrNull()

        fun write(file: File, meta: AppUpdateDownloadMeta) {
            file.parentFile?.mkdirs()
            file.writeText(meta.toJson().toString())
        }
    }
}
