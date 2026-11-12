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
    /**
     * Balance stored for this account. Depository accounts use Plaid
     * `balance_available` (current minus pending holds). Credit cards and
     * loans keep `balance_current` — on a card, available is remaining credit,
     * not the amount owed.
     */
    val balanceCents: Long,
    /** Plaid `balance_current`. On a checking account, current − available is the pending gap. */
    val currentBalanceCents: Long? = null
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

/**
 * Depository spendable balance in cents: Plaid `balance_available` when
 * reported, otherwise `balance_current`.
 */
fun availableBalanceCents(current: Double?, available: Double?): Long =
    ((available ?: current ?: 0.0) * 100).roundToLong()

/**
 * Balance to store, in cents.
 *
 * Checking and savings (`type` depository) use available, which already
 * subtracts pending holds. Using current there overstated free to spend by
 * the pending total (360 Checking ···7098 on 2026-11-11: current $3.41,
 * pending $21.51, available −$18.10).
 *
 * Credit cards, loans, and anything else keep current. Available on a credit
 * card is remaining credit, and free-to-spend treats a credit balance as
 * debt. Pure function so it can be unit-tested without parsing JSON
 * (org.json is stubbed in JVM unit tests).
 */
fun accountBalanceCents(type: String?, current: Double?, available: Double?): Long {
    val dollars = if (type.equals("depository", ignoreCase = true)) {
        available ?: current ?: 0.0
    } else {
        current ?: available ?: 0.0
    }
    return (dollars * 100).roundToLong()
}

fun JSONObject.toPlaidAccountsSyncResult(): PlaidAccountsSyncResult {
    val accounts = getJSONArray("accounts")
    val list = buildList {
        for (i in 0 until accounts.length()) {
            val a = accounts.getJSONObject(i)
            val current = if (!a.isNull("balance_current")) a.getDouble("balance_current") else null
            val available = if (!a.isNull("balance_available")) a.getDouble("balance_available") else null
            val type = a.optString("type").takeIf { it.isNotBlank() }
            add(
                PlaidAccountSnapshot(
                    accountId = a.getString("account_id"),
                    name = buildAccountDisplayName(a),
                    mask = a.optString("mask").takeIf { it.isNotBlank() },
                    type = type,
                    subtype = a.optString("subtype").takeIf { it.isNotBlank() },
                    balanceCents = accountBalanceCents(type, current, available),
                    currentBalanceCents = current?.let { (it * 100).roundToLong() }
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
