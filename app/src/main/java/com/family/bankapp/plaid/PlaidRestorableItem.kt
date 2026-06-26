package com.family.bankapp.plaid

data class PlaidRestorableItem(
    val itemId: String,
    val institutionName: String,
    val createdAt: String? = null
)

object PlaidNameMatcher {
    fun likelySameBank(bankName: String, institutionName: String): Boolean {
        val bank = normalize(bankName)
        val institution = normalize(institutionName)
        if (bank.isBlank() || institution.isBlank()) return false
        return bank == institution || bank in institution || institution in bank
    }

    private fun normalize(value: String): String =
        value.lowercase().trim().replace(Regex("[^a-z0-9\\s]"), "").replace(Regex("\\s+"), " ")
}
