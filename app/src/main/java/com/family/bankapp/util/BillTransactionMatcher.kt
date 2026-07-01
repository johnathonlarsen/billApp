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
import java.time.temporal.ChronoUnit
import kotlin.math.max

object BillTransactionMatcher {

    private const val MIN_TOLERANCE_CENTS = 100L
    private const val MAX_MONTHS_FORWARD_SEARCH = 60
    private const val SIGNIFICANT_OVERDUE_DAYS = 3L

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
     * Finds the bill cycle this payment applies to using payment windows between consecutive
     * due dates. Supports early payments (e.g. June 30 for a bill due July 28), late payments
     * for overdue cycles, and same-month catch-up within a short grace period.
     */
    fun resolvePaymentCycle(
        bill: BillEntity,
        txDate: LocalDate,
        skips: List<BillCycleSkipEntity> = emptyList(),
        payments: List<PaymentRecordEntity> = emptyList()
    ): LocalDate? {
        val trackingStart = YearMonth.from(
            Instant.ofEpochMilli(bill.trackingStartMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
        )
        val searchEnd = minOf(
            trackingStart.plusMonths(MAX_MONTHS_FORWARD_SEARCH.toLong()),
            YearMonth.from(txDate).plusMonths(2)
        )

        val unpaidDueDates = mutableListOf<LocalDate>()
        var month = trackingStart
        while (!month.isAfter(searchEnd)) {
            if (MonthTimeline.billAppliesToMonth(bill, month)) {
                val due = BillSchedule.dueDateForYearMonth(bill, month)
                if (!BillSchedule.isCycleSkipped(skips, bill.id, due) &&
                    BillSchedule.paymentForCycle(payments, bill.id, due) == null
                ) {
                    unpaidDueDates.add(due)
                }
            }
            month = month.plusMonths(1)
        }
        if (unpaidDueDates.isEmpty()) return null

        val sortedUnpaid = unpaidDueDates.sorted()
        val overdue = sortedUnpaid.filter { !txDate.isBefore(it) }

        overdue.maxOrNull()?.let { latestOverdue ->
            val daysPast = ChronoUnit.DAYS.between(latestOverdue, txDate)
            if (daysPast > SIGNIFICANT_OVERDUE_DAYS) {
                return latestOverdue
            }
        }

        for (due in sortedUnpaid) {
            if (!txDate.isBefore(due)) continue
            val previousDue = previousCycleDue(bill, due, trackingStart, skips, payments)
            val windowStart = previousDue?.plusDays(1)
                ?: trackingStart.atDay(1)
            if (!txDate.isBefore(windowStart) && !txDate.isAfter(due)) {
                if (previousDue != null &&
                    !txDate.isBefore(previousDue) &&
                    YearMonth.from(previousDue) == YearMonth.from(txDate) &&
                    BillSchedule.paymentForCycle(payments, bill.id, previousDue) == null
                ) {
                    continue
                }
                return due
            }
        }

        return overdue
            .filter { YearMonth.from(it) == YearMonth.from(txDate) }
            .maxOrNull()
    }

    private fun previousCycleDue(
        bill: BillEntity,
        due: LocalDate,
        trackingStart: YearMonth,
        skips: List<BillCycleSkipEntity>,
        payments: List<PaymentRecordEntity>
    ): LocalDate? {
        var month = YearMonth.from(due).minusMonths(1)
        while (!month.isBefore(trackingStart)) {
            if (MonthTimeline.billAppliesToMonth(bill, month)) {
                val candidate = BillSchedule.dueDateForYearMonth(bill, month)
                if (!BillSchedule.isCycleSkipped(skips, bill.id, candidate) &&
                    candidate.isBefore(due)
                ) {
                    return candidate
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
