package com.family.bankapp.plaid

import com.family.bankapp.data.model.AccountType
import org.json.JSONObject
import kotlin.math.roundToLong

data class PlaidAccountSnapshot(
    val accountId: String,
    val name: String,
    val mask: String?,
    val type: String?,
    val subtype: String?,
    val balanceCents: Long
)

data class PlaidTransactionSnapshot(
    val transactionId: String,
    val accountId: String,
    val amountCents: Long,
    val date: String,
    val name: String,
    val merchantName: String?,
    val pending: Boolean
)

data class PlaidAccountsSyncResult(
    val accounts: List<PlaidAccountSnapshot>
)

data class PlaidTransactionsSyncResult(
    val added: List<PlaidTransactionSnapshot>,
    val removedIds: List<String>,
    val nextCursor: String?,
    val hasMore: Boolean
)

data class PlaidBankSyncResult(
    val accountsImported: Int,
    val transactionsAdded: Int,
    val transactionsRemoved: Int,
    val hasMoreTransactions: Boolean,
    val autoPaymentsApplied: Int = 0
)

fun JSONObject.toPlaidAccountsSyncResult(): PlaidAccountsSyncResult {
    val accounts = getJSONArray("accounts")
    val list = buildList {
        for (i in 0 until accounts.length()) {
            val a = accounts.getJSONObject(i)
            val balance = when {
                !a.isNull("balance_current") -> a.getDouble("balance_current")
                !a.isNull("balance_available") -> a.getDouble("balance_available")
                else -> 0.0
            }
            add(
                PlaidAccountSnapshot(
                    accountId = a.getString("account_id"),
                    name = buildAccountDisplayName(a),
                    mask = a.optString("mask").takeIf { it.isNotBlank() },
                    type = a.optString("type").takeIf { it.isNotBlank() },
                    subtype = a.optString("subtype").takeIf { it.isNotBlank() },
                    balanceCents = (balance * 100).roundToLong()
                )
            )
        }
    }
    return PlaidAccountsSyncResult(list)
}

fun JSONObject.toPlaidTransactionsSyncResult(): PlaidTransactionsSyncResult {
    val addedArray = getJSONArray("added")
    val removedArray = optJSONArray("removed")
    val removedIds = buildList {
        if (removedArray != null) {
            for (i in 0 until removedArray.length()) {
                add(removedArray.getString(i))
            }
        }
    }
    val added = buildList {
        for (i in 0 until addedArray.length()) {
            val t = addedArray.getJSONObject(i)
            add(
                PlaidTransactionSnapshot(
                    transactionId = t.getString("transaction_id"),
                    accountId = t.getString("account_id"),
                    amountCents = (t.getDouble("amount") * 100).roundToLong(),
                    date = t.getString("date"),
                    name = t.getString("name"),
                    merchantName = t.optString("merchant_name").takeIf { it.isNotBlank() },
                    pending = t.optBoolean("pending", false)
                )
            )
        }
    }
    return PlaidTransactionsSyncResult(
        added = added,
        removedIds = removedIds,
        nextCursor = optString("next_cursor").takeIf { it.isNotBlank() },
        hasMore = optBoolean("has_more", false)
    )
}

private fun buildAccountDisplayName(a: JSONObject): String {
    val name = a.optString("name").takeIf { it.isNotBlank() }
    val mask = a.optString("mask").takeIf { it.isNotBlank() }
    return when {
        name != null && mask != null -> "$name ···$mask"
        name != null -> name
        else -> a.optString("official_name").ifBlank { "Account" }
    }
}

fun mapPlaidAccountType(subtype: String?, type: String?): AccountType {
    val key = (subtype ?: type ?: "").lowercase()
    return when {
        "checking" in key -> AccountType.CHECKING
        "savings" in key || "cd" in key -> AccountType.SAVINGS
        "credit" in key -> AccountType.CREDIT
        else -> AccountType.CHECKING
    }
}
