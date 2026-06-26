package com.family.bankapp.sync

import com.family.bankapp.plaid.PlaidApiBudget
import com.family.bankapp.plaid.PlaidUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal Supabase PostgREST client for non-PII shared state (Plaid slot counts, team config).
 * Uses the anon key only — no service role key in the app.
 */
object SupabaseSharedStateClient {

    data class Config(
        val projectUrl: String,
        val anonKey: String,
        val teamId: String = DEFAULT_TEAM_ID
    ) {
        val isConfigured: Boolean
            get() = projectUrl.isNotBlank() && anonKey.isNotBlank()
    }

    const val DEFAULT_TEAM_ID = "family-bank"

    suspend fun fetchPlaidUsage(config: Config): Result<PlaidUsage> = withContext(Dispatchers.IO) {
        runCatching {
            require(config.isConfigured) { "Supabase URL and anon key required" }
            val body = JSONObject().put("p_team_id", config.teamId).toString()
            val json = postRpc(config, "get_plaid_usage", body)
            PlaidUsage(
                limit = json.getInt("limit"),
                used = json.getInt("used"),
                remaining = json.getInt("remaining"),
                atLimit = json.getBoolean("at_limit"),
                trialNote = json.optString("trial_note").takeIf { it.isNotBlank() },
                source = PlaidUsage.UsageSource.SUPABASE
            )
        }
    }

    suspend fun registerPlaidSlot(config: Config): Result<PlaidUsage> = withContext(Dispatchers.IO) {
        runCatching {
            require(config.isConfigured) { "Supabase URL and anon key required" }
            val body = JSONObject().put("p_team_id", config.teamId).toString()
            val json = postRpc(config, "register_plaid_slot", body)
            if (!json.optBoolean("ok", false)) {
                error(json.optString("error", "Could not register slot"))
            }
            PlaidUsage(
                limit = json.getInt("limit"),
                used = json.getInt("used"),
                remaining = json.getInt("remaining"),
                atLimit = json.getBoolean("at_limit"),
                source = PlaidUsage.UsageSource.SUPABASE
            )
        }
    }

    suspend fun fetchPlaidApiBudget(config: Config): Result<PlaidApiBudget> = withContext(Dispatchers.IO) {
        runCatching {
            require(config.isConfigured) { "Supabase not configured" }
            val body = JSONObject().put("p_team_id", config.teamId).toString()
            val json = postRpc(config, "get_plaid_api_budget", body)
            PlaidApiBudget(
                limit = json.getInt("limit"),
                used = json.getInt("used"),
                remaining = json.getInt("remaining"),
                atLimit = json.getBoolean("at_limit"),
                periodMonth = json.optString("period_month").takeIf { it.isNotBlank() },
                note = json.optString("note").takeIf { it.isNotBlank() }
            )
        }
    }

    private fun postRpc(config: Config, functionName: String, jsonBody: String): JSONObject {
        val base = config.projectUrl.trim().removeSuffix("/")
        val conn = (URL("$base/rest/v1/rpc/$functionName").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("apikey", config.anonKey)
            setRequestProperty("Authorization", "Bearer ${config.anonKey}")
        }
        conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val responseText = (if (code in 200..299) conn.inputStream else conn.errorStream)
            .bufferedReader().readText()
        if (code !in 200..299) {
            error("Supabase returned $code: $responseText")
        }
        return JSONObject(responseText)
    }
}
