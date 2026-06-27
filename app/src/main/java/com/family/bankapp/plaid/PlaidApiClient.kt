package com.family.bankapp.plaid

import com.family.bankapp.FamilyAppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
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

    private fun JSONObject.putLinkedItemIds(linkedItemIds: List<String>) {
        put("linked_item_ids", JSONArray(linkedItemIds))
    }

    suspend fun createLinkToken(
        replacingItemId: String? = null,
        linkedItemIds: List<String> = emptyList()
    ): Result<PlaidLinkTokenResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject().apply {
                    put("team_id", FamilyAppConfig.SUPABASE_TEAM_ID)
                    putLinkedItemIds(linkedItemIds)
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
        replacingItemId: String? = null,
        linkedItemIds: List<String> = emptyList()
    ): Result<PlaidExchangeResult> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().apply {
                put("public_token", publicToken)
                put("team_id", FamilyAppConfig.SUPABASE_TEAM_ID)
                putLinkedItemIds(linkedItemIds)
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

    suspend fun listRestorableItems(): Result<List<PlaidRestorableItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val json = postFunction(
                "plaid-items-list",
                JSONObject().put("team_id", FamilyAppConfig.SUPABASE_TEAM_ID)
            )
            val items = json.optJSONArray("items") ?: JSONArray()
            buildList {
                for (i in 0 until items.length()) {
                    val row = items.getJSONObject(i)
                    add(
                        PlaidRestorableItem(
                            itemId = row.getString("item_id"),
                            institutionName = row.optString("institution_name").ifBlank { "Unknown bank" },
                            createdAt = row.optString("created_at").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
        }
    }

    suspend fun removePlaidItem(itemId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            postFunction(
                "plaid-item-remove",
                JSONObject()
                    .put("team_id", FamilyAppConfig.SUPABASE_TEAM_ID)
                    .put("item_id", itemId)
            )
            Unit
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
