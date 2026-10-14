package com.family.bankapp.util

import com.family.bankapp.data.entity.PlaidTransactionEntity
import java.time.LocalDate
import java.time.YearMonth

/**
 * Misc Plaid spending for free-to-spend.
 *
 * - Normal purchases: debit totals only.
 * - Cash-advance / borrow apps: net repayments (debits) against re-borrow deposits
 *   (credits) from the prior month and current month — only fees stay counted.
 * - Moves between your own Capital One 360 accounts ("Withdrawal to 360 …"): ignored.
 *   Plaid often puts that text on the transaction name while the merchant is the bank,
 *   so both fields are checked.
 */
object PlaidMiscSpending {

    private val ROLLOVER_LENDER_MARKERS = listOf(
        "EARNIN",
        "TILT",
        "MONEYLION",
        "DAVE.COM",
        "DAVE INC",
        "BRIGIT",
        "CLEO",
        "KLOVER",
        "CASH APP BORROW",
        "ALBERT",
        "EMPOWER",
        "FLOATME",
        "VOLA",
        "INSTACASH",
        "MONEYCARD",
        "GRID MONEY",
        "GENESIS FIN",
        "CREDIT GENIE"
    )

    fun merchantKey(tx: PlaidTransactionEntity): String {
        if (isOwnCapOneTransfer(tx)) return "internal_transfer_360"
        val raw = primaryLabel(tx).uppercase()
        return when {
            raw.contains("EARNIN") -> "earnin"
            raw.contains("TILT") -> "tilt"
            raw.contains("MONEYLION") -> "moneylion"
            raw.contains("CASH APP") && raw.contains("BORROW") -> "cashapp_borrow"
            raw.contains("DAVE") -> "dave"
            raw.contains("BRIGIT") -> "brigit"
            raw.contains("CLEO") -> "cleo"
            raw.contains("KLOVER") -> "klover"
            raw.contains("ALBERT") -> "albert"
            raw.contains("EMPOWER") -> "empower"
            raw.contains("FLOATME") -> "floatme"
            raw.contains("VOLA") -> "vola"
            raw.contains("INSTACASH") -> "instacash"
            else -> raw.lowercase().trim()
        }
    }

    fun isInternalTransfer(key: String): Boolean = key == "internal_transfer_360"

    /**
     * True when the transaction is money moved to or from another Capital One 360 account.
     * Matches either the bank description or the merchant, e.g. name
     * "Withdrawal to 360 Checking ***********" with merchant "Capital One".
     */
    fun isOwnCapOneTransfer(tx: PlaidTransactionEntity): Boolean {
        val raw = listOfNotNull(tx.name, tx.merchantName?.takeIf { it.isNotBlank() })
            .joinToString(" ")
            .uppercase()
        return raw.contains("WITHDRAWAL TO 360") ||
            raw.contains("WITHDRAWAL FROM 360") ||
            raw.contains("TRANSFER TO 360") ||
            raw.contains("TRANSFER FROM 360")
    }

    private fun primaryLabel(tx: PlaidTransactionEntity): String =
        tx.merchantName?.takeIf { it.isNotBlank() } ?: tx.name

    fun isRolloverLender(key: String): Boolean = key in ROLLOVER_LENDER_KEYS

    private val ROLLOVER_LENDER_KEYS = setOf(
        "earnin", "tilt", "moneylion", "cashapp_borrow", "dave", "brigit", "cleo",
        "klover", "albert", "empower", "floatme", "vola", "instacash"
    )

    fun isRolloverLenderName(name: String, merchantName: String? = null): Boolean {
        val raw = (merchantName?.takeIf { it.isNotBlank() } ?: name).uppercase()
        return ROLLOVER_LENDER_MARKERS.any { raw.contains(it) } ||
            (raw.contains("CASH APP") && !raw.contains("PAYIN"))
    }

    data class MerchantNet(
        val label: String,
        val debitsThisMonthCents: Long,
        val creditsOffsetCents: Long,
        val countedCents: Long
    )

    fun miscSpentCents(
        transactions: List<PlaidTransactionEntity>,
        linkedTransactionIds: Set<String>,
        spendingPlaidAccountIds: Set<String>,
        yearMonth: YearMonth
    ): Long = merchantNets(transactions, linkedTransactionIds, spendingPlaidAccountIds, yearMonth)
        .sumOf { it.countedCents }

    fun merchantNets(
        transactions: List<PlaidTransactionEntity>,
        linkedTransactionIds: Set<String>,
        spendingPlaidAccountIds: Set<String>,
        yearMonth: YearMonth
    ): List<MerchantNet> {
        if (spendingPlaidAccountIds.isEmpty()) return emptyList()

        val eligible = transactions.filter { tx ->
            tx.plaidAccountId in spendingPlaidAccountIds &&
                !tx.pending &&
                !tx.excludeFromFreeToSpend &&
                tx.plaidTransactionId !in linkedTransactionIds &&
                parseDate(tx.date) != null
        }

        val lookbackStart = yearMonth.minusMonths(1).atDay(1)
        val monthEnd = yearMonth.atEndOfMonth()

        return eligible
            .groupBy { merchantKey(it) }
            .mapNotNull { (key, txs) ->
                if (isInternalTransfer(key)) return@mapNotNull null

                val debitsThisMonth = txs.filter {
                    it.amountCents > 0 && YearMonth.from(parseDate(it.date)!!) == yearMonth
                }
                val debitTotal = debitsThisMonth.sumOf { it.amountCents }
                if (debitTotal == 0L) return@mapNotNull null

                val label = debitsThisMonth.first().let {
                    it.merchantName?.takeIf { m -> m.isNotBlank() } ?: it.name
                }

                val counted = if (isRolloverLender(key)) {
                    val creditTotal = txs.filter { it.amountCents < 0 }
                        .filter {
                            val date = parseDate(it.date)!!
                            !date.isBefore(lookbackStart) && !date.isAfter(monthEnd)
                        }
                        .sumOf { -it.amountCents }
                    maxOf(0L, debitTotal - creditTotal)
                } else {
                    debitTotal
                }

                val creditOffset = if (isRolloverLender(key)) debitTotal - counted else 0L
                MerchantNet(
                    label = label,
                    debitsThisMonthCents = debitTotal,
                    creditsOffsetCents = creditOffset,
                    countedCents = counted
                )
            }
            .filter { it.countedCents > 0L || it.creditsOffsetCents > 0L }
            .sortedByDescending { it.countedCents }
    }

    private fun parseDate(date: String): LocalDate? =
        runCatching { LocalDate.parse(date) }.getOrNull()
}
