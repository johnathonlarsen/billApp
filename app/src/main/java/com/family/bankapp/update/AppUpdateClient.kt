package com.family.bankapp.update

import com.family.bankapp.FamilyAppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object AppUpdateClient {

    suspend fun fetchManifest(): Result<AppUpdateManifest> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(FamilyAppConfig.UPDATE_MANIFEST_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Cache-Control", "no-cache")
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                .bufferedReader().readText()
            if (code !in 200..299) {
                error("Update check failed ($code)")
            }
            parseManifest(JSONObject(text))
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
