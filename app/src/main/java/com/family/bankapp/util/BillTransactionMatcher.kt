package com.family.bankapp.util

import com.family.bankapp.data.entity.AccountEntity
import com.family.bankapp.data.entity.BillEntity
import com.family.bankapp.data.entity.PlaidTransactionEntity
import com.family.bankapp.data.model.BillRecurrence
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max

object BillTransactionMatcher {

    private const val MIN_TOLERANCE_CENTS = 100L
    private const val TOLERANCE_FRACTION = 0.08

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
        accounts: List<AccountEntity>
    ): Boolean {
        val pattern = bill.plaidMatchPattern?.takeIf { it.isNotBlank() } ?: return false
        if (tx.amountCents <= 0) return false
        if (!textMatches(tx, pattern)) return false
        if (!amountsMatch(bill.amountCents, tx.amountCents)) return false
        if (!accountMatches(tx, bill, accounts)) return false
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

    fun cycleDueDateForTransaction(bill: BillEntity, txDate: LocalDate): LocalDate =
        when (bill.recurrence) {
            BillRecurrence.MONTHLY ->
                BillSchedule.dueDateForYearMonth(bill, YearMonth.from(txDate))
            BillRecurrence.YEARLY ->
                BillSchedule.dueDateForYearMonth(bill, YearMonth.of(txDate.year, 1))
            BillRecurrence.WEEKLY, BillRecurrence.BIWEEKLY, BillRecurrence.ONE_TIME ->
                BillSchedule.dueDateForYearMonth(bill, YearMonth.from(txDate))
        }

    fun paidAtMillisFromTransaction(tx: PlaidTransactionEntity): Long =
        runCatching { LocalDate.parse(tx.date) }
            .map { it.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
            .getOrDefault(System.currentTimeMillis())
}
