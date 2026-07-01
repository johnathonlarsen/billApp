package com.family.bankapp.util

import com.family.bankapp.data.entity.PlaidTransactionEntity

object PlaidTransactionSearch {

    fun matches(tx: PlaidTransactionEntity, query: String): Boolean {
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) return true
        if (tx.name.lowercase().contains(normalized)) return true
        val merchant = tx.merchantName?.lowercase()
        return merchant != null && merchant.contains(normalized)
    }

    fun filter(
        transactions: List<PlaidTransactionEntity>,
        query: String
    ): List<PlaidTransactionEntity> {
        val normalized = query.trim()
        if (normalized.isBlank()) return transactions
        return transactions.filter { matches(it, normalized) }
    }
}
