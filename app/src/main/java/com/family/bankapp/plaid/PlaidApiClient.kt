package com.family.bankapp.plaid

import com.family.bankapp.FamilyAppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class PlaidLinkTokenResult(
    val linkToken: String,
    val warning: String? = null
)

data class PlaidExchangeResult(
    val itemId: String,
    val slotId: String? = null
)

/** Calls Supabase Edge Functions — Plaid secrets and access tokens stay server-side. */
object PlaidApiClient {

    suspend fun createLinkToken(replacingItemId: String? = null): Result<PlaidLinkTokenResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject().apply {
                    put("team_id", FamilyAppConfig.SUPABASE_TEAM_ID)
                    replacingItemId?.let { put("replacing_item_id", it) }
                }
                val json = postFunction("plaid-link-token", body)
                PlaidLinkTokenResult(
                    linkToken = json.getString("link_token"),
                    warning = json.optString("warning").takeIf { it.isNotBlank() }
                )
            }
        }

    suspend fun exchangePublicToken(
        publicToken: String,
        replacingItemId: String? = null
    ): Result<PlaidExchangeResult> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().apply {
                put("public_token", publicToken)
                put("team_id", FamilyAppConfig.SUPABASE_TEAM_ID)
                replacingItemId?.let { put("replacing_item_id", it) }
            }
            val json = postFunction("plaid-exchange", body)
            PlaidExchangeResult(
                itemId = json.getString("item_id"),
                slotId = json.optString("slot_id").takeIf { it.isNotBlank() }
            )
        }
    }

    suspend fun syncAccounts(itemId: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        runCatching {
            postFunction(
                "plaid-accounts-sync",
                JSONObject().put("team_id", FamilyAppConfig.SUPABASE_TEAM_ID).put("item_id", itemId)
            )
        }
    }

    suspend fun syncTransactions(itemId: String, cursor: String? = null): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            runCatching {
                postFunction(
                    "plaid-transactions-sync",
                    JSONObject().apply {
                        put("team_id", FamilyAppConfig.SUPABASE_TEAM_ID)
                        put("item_id", itemId)
                        cursor?.let { put("cursor", it) }
                    }
                )
            }
        }

    fun postFunction(functionName: String, body: JSONObject): JSONObject {
        val base = FamilyAppConfig.SUPABASE_URL.trim().removeSuffix("/")
        val conn = (URL("$base/functions/v1/$functionName").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("apikey", FamilyAppConfig.SUPABASE_ANON_KEY)
            setRequestProperty("Authorization", "Bearer ${FamilyAppConfig.SUPABASE_ANON_KEY}")
            setRequestProperty("X-Family-Bank-Key", FamilyAppConfig.EDGE_FUNCTION_SECRET)
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val responseText = (if (code in 200..299) conn.inputStream else conn.errorStream)
            .bufferedReader().readText()
        if (code !in 200..299) {
            val err = runCatching { JSONObject(responseText).optString("error") }.getOrNull()
            error(err?.takeIf { it.isNotBlank() } ?: "Plaid function returned $code")
        }
        return JSONObject(responseText)
    }
}
