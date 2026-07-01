package com.family.bankapp.util

import com.family.bankapp.data.entity.AccountEntity
import com.family.bankapp.data.entity.BillCycleSkipEntity
import com.family.bankapp.data.entity.BillEntity
import com.family.bankapp.data.entity.PaymentRecordEntity
import com.family.bankapp.data.entity.PlaidTransactionEntity
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max

object BillTransactionMatcher {

    private const val MIN_TOLERANCE_CENTS = 100L
    private const val TOLERANCE_FRACTION = 0.08
    private const val MAX_MONTHS_BACK_FOR_LATE_PAYMENT = 4

    fun transactionDate(tx: PlaidTransactionEntity): LocalDate? =
        runCatching { LocalDate.parse(tx.date) }.getOrNull()

    fun matchPatternFromTransaction(tx: PlaidTransactionEntity): String {
        val raw = tx.merchantName?.takeIf { it.isNotBlank() } ?: tx.name
        return normalizeMatchText(raw)
    }

    fun normalizeMatchText(value: String): String =
        value.lowercase().trim()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun transactionMatchesBill(
        tx: PlaidTransactionEntity,
        bill: BillEntity,
        accounts: List<AccountEntity>,
        payments: List<PaymentRecordEntity> = emptyList(),
        skips: List<BillCycleSkipEntity> = emptyList()
    ): Boolean {
        val pattern = bill.plaidMatchPattern?.takeIf { it.isNotBlank() } ?: return false
        if (tx.amountCents <= 0) return false
        if (!textMatches(tx, pattern)) return false
        if (!amountsMatch(bill.amountCents, tx.amountCents)) return false
        if (!accountMatches(tx, bill, accounts)) return false
        val txDate = transactionDate(tx) ?: return false
        val cycleDue = resolvePaymentCycle(bill, txDate, skips) ?: return false
        if (BillSchedule.paymentForCycle(payments, bill.id, cycleDue) != null) return false
        return true
    }

    fun textMatches(tx: PlaidTransactionEntity, pattern: String): Boolean {
        val normPattern = normalizeMatchText(pattern)
        if (normPattern.isBlank()) return false
        val haystack = normalizeMatchText(
            listOfNotNull(tx.merchantName, tx.name).joinToString(" ")
        )
        return haystack.contains(normPattern) || normPattern.contains(haystack)
    }

    fun amountsMatch(expectedCents: Long, actualCents: Long): Boolean {
        val tolerance = max(MIN_TOLERANCE_CENTS, (expectedCents * TOLERANCE_FRACTION).toLong())
        return abs(expectedCents - actualCents) <= tolerance
    }

    fun accountMatches(
        tx: PlaidTransactionEntity,
        bill: BillEntity,
        accounts: List<AccountEntity>
    ): Boolean {
        val linkedId = bill.linkedAccountId ?: return true
        val account = accounts.find { it.id == linkedId } ?: return true
        val plaidId = account.plaidAccountId ?: return true
        return plaidId == tx.plaidAccountId
    }

    /**
     * Finds the bill cycle this payment applies to. The transaction date must be on or after
     * that cycle's due date (supports late payments in the following month).
     */
    fun resolvePaymentCycle(
        bill: BillEntity,
        txDate: LocalDate,
        skips: List<BillCycleSkipEntity> = emptyList()
    ): LocalDate? {
        val trackingStart = YearMonth.from(
            Instant.ofEpochMilli(bill.trackingStartMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
        )
        var month = YearMonth.from(txDate)

        repeat(MAX_MONTHS_BACK_FOR_LATE_PAYMENT) {
            if (month.isBefore(trackingStart)) return null
            if (MonthTimeline.billAppliesToMonth(bill, month)) {
                val due = BillSchedule.dueDateForYearMonth(bill, month)
                if (!BillSchedule.isCycleSkipped(skips, bill.id, due) && !txDate.isBefore(due)) {
                    return due
                }
            }
            month = month.minusMonths(1)
        }
        return null
    }

    fun paidAtMillisFromTransaction(tx: PlaidTransactionEntity): Long =
        runCatching { LocalDate.parse(tx.date) }
            .map { it.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
            .getOrDefault(System.currentTimeMillis())
}
