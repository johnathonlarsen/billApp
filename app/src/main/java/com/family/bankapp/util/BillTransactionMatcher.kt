package com.family.bankapp.util

import com.family.bankapp.data.entity.AccountEntity
import com.family.bankapp.data.entity.BillCycleSkipEntity
import com.family.bankapp.data.entity.BillEntity
import com.family.bankapp.data.entity.PaymentRecordEntity
import com.family.bankapp.data.entity.PlaidTransactionEntity
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max

object BillTransactionMatcher {

    private const val MIN_TOLERANCE_CENTS = 100L

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
        if (bill.plaidCycleMonthOffset == null) return false
        if (tx.amountCents <= 0) return false
        if (!textMatches(tx, pattern)) return false
        if (!amountQualifies(bill.amountCents, tx.amountCents)) return false
        if (!accountMatches(tx, bill, accounts)) return false
        val txDate = transactionDate(tx) ?: return false
        val cycleDue = resolvePaymentCycle(bill, txDate, skips, payments) ?: return false
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

    /** Payment can be higher than the bill template; small rounding below template is allowed. */
    fun amountQualifies(templateCents: Long, actualCents: Long): Boolean {
        if (actualCents <= 0) return false
        if (templateCents <= 0) return true
        val minAllowed = max(0L, templateCents - MIN_TOLERANCE_CENTS)
        return actualCents >= minAllowed
    }

    /** @deprecated Use [amountQualifies] for bill payment matching. */
    fun amountsMatch(expectedCents: Long, actualCents: Long): Boolean =
        amountQualifies(expectedCents, actualCents)

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
     * Uses the bill's [BillEntity.plaidCycleMonthOffset] (set when linking from a transaction)
     * to pick the cycle due date relative to the transaction date.
     */
    fun resolvePaymentCycle(
        bill: BillEntity,
        txDate: LocalDate,
        skips: List<BillCycleSkipEntity> = emptyList(),
        payments: List<PaymentRecordEntity> = emptyList()
    ): LocalDate? {
        val offset = bill.plaidCycleMonthOffset ?: return null
        val due = PlaidBillCycle.dueDateForOffset(bill, txDate, offset)
        if (BillSchedule.isCycleSkipped(skips, bill.id, due)) return null
        if (BillSchedule.paymentForCycle(payments, bill.id, due) != null) return null
        return due
    }

    fun paidAtMillisFromTransaction(tx: PlaidTransactionEntity): Long =
        runCatching { LocalDate.parse(tx.date) }
            .map { it.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
            .getOrDefault(System.currentTimeMillis())
}
