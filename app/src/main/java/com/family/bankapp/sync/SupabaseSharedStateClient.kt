package com.family.bankapp.sync

import com.family.bankapp.FamilyAppConfig
import com.family.bankapp.plaid.PlaidApiBudget
import com.family.bankapp.plaid.PlaidApiClient
import com.family.bankapp.plaid.PlaidUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Shared household state via authenticated Edge Functions.
 * Never calls PostgREST with the anon key — RPCs/tables are service-role only.
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
            val usage = fetchStatus(config).getJSONObject("usage")
            PlaidUsage(
                limit = usage.getInt("limit"),
                used = usage.getInt("used"),
                remaining = usage.getInt("remaining"),
                atLimit = usage.getBoolean("at_limit"),
                trialNote = usage.optString("trial_note").takeIf { it.isNotBlank() },
                source = PlaidUsage.UsageSource.SUPABASE
            )
        }
    }

    suspend fun fetchPlaidApiBudget(config: Config): Result<PlaidApiBudget> = withContext(Dispatchers.IO) {
        runCatching {
            require(config.isConfigured) { "Supabase not configured" }
            val budget = fetchStatus(config).getJSONObject("budget")
            PlaidApiBudget(
                limit = budget.getInt("limit"),
                used = budget.getInt("used"),
                remaining = budget.getInt("remaining"),
                atLimit = budget.getBoolean("at_limit"),
                periodMonth = budget.optString("period_month").takeIf { it.isNotBlank() },
                note = budget.optString("note").takeIf { it.isNotBlank() }
            )
        }
    }

    private fun fetchStatus(config: Config): JSONObject {
        val body = JSONObject().put("team_id", config.teamId.ifBlank { FamilyAppConfig.SUPABASE_TEAM_ID })
        return PlaidApiClient.postFunction("plaid-status", body)
    }
}
