package com.family.bankapp.update

import com.family.bankapp.FamilyAppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object AppUpdateClient {

    private val manifestUrls = listOf(
        FamilyAppConfig.UPDATE_MANIFEST_RAW_URL,
        FamilyAppConfig.UPDATE_MANIFEST_URL
    )

    suspend fun fetchManifest(): Result<AppUpdateManifest> = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        for (baseUrl in manifestUrls) {
            val result = runCatching { fetchManifestFromUrl(baseUrl) }
            if (result.isSuccess) {
                return@withContext result
            }
            lastError = result.exceptionOrNull()
        }
        Result.failure(lastError ?: IllegalStateException("Could not check for updates"))
    }

    private fun fetchManifestFromUrl(baseUrl: String): AppUpdateManifest {
        val cacheBustUrl = if (baseUrl.contains('?')) {
            "$baseUrl&_=${System.currentTimeMillis()}"
        } else {
            "$baseUrl?_=${System.currentTimeMillis()}"
        }
        val conn = (URL(cacheBustUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("Cache-Control", "no-cache, no-store")
            setRequestProperty("Pragma", "no-cache")
        }
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                .bufferedReader().readText()
            if (code !in 200..299) {
                error("Update check failed ($code)")
            }
            return parseManifest(JSONObject(text))
        } finally {
            conn.disconnect()
        }
    }

    fun parseManifest(json: JSONObject): AppUpdateManifest {
        val versionCode = json.getInt("versionCode")
        val versionName = json.getString("versionName")
        val apkUrl = json.getString("apkUrl").trim()
        require(apkUrl.startsWith("https://")) { "APK URL must use HTTPS" }
        return AppUpdateManifest(
            versionCode = versionCode,
            versionName = versionName,
            apkUrl = apkUrl,
            releasedAt = json.optString("releasedAt").takeIf { it.isNotBlank() },
            notes = json.optString("notes").takeIf { it.isNotBlank() },
            apkSizeBytes = json.optLong("apkSizeBytes").takeIf { it > 0L }
        )
    }
}
