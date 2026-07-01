package com.family.bankapp.util

import com.family.bankapp.data.entity.AccountEntity
import com.family.bankapp.data.entity.BillCycleSkipEntity
import com.family.bankapp.data.entity.BillEntity
import com.family.bankapp.data.entity.IncomeEntity
import com.family.bankapp.data.entity.PaymentRecordEntity
import com.family.bankapp.data.entity.PlaidTransactionEntity
import com.family.bankapp.data.model.AccountType
import com.family.bankapp.data.model.BillCategory
import com.family.bankapp.data.model.BillRecurrence
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

object RecurrenceNormalizer {
    /** Approximate monthly equivalent in cents for budgeting. */
    fun toMonthlyCents(amountCents: Long, recurrence: BillRecurrence): Long = when (recurrence) {
        BillRecurrence.MONTHLY -> amountCents
        BillRecurrence.BIWEEKLY -> (amountCents * 26L + 11) / 12L
        BillRecurrence.WEEKLY -> (amountCents * 52L + 11) / 12L
        BillRecurrence.YEARLY -> (amountCents + 5) / 12L
        BillRecurrence.ONE_TIME -> 0L
    }
}

data class FreeToSpendSnapshot(
    val monthlyIncomeCents: Long,
    /** Total of all bills due this month — reserved from income whether paid yet or not. */
    val allBillsThisMonthCents: Long,
    /** Plaid debits this month not linked to a bill payment. */
    val plaidMiscSpentCents: Long,
    val priorOverdueUnpaidCents: Long,
    val freeToSpendCents: Long,
    val includePriorOverdue: Boolean,
    val currentMonthLabel: String,
    val monthlyBillsCents: Long,
    val plannedFreeMonthlyCents: Long,
    val liquidBalanceCents: Long
)

object FreeToSpendCalculator {

    fun calculate(
        accounts: List<AccountEntity>,
        bills: List<BillEntity>,
        incomes: List<IncomeEntity>,
        payments: List<PaymentRecordEntity>,
        skips: List<BillCycleSkipEntity>,
        plaidTransactions: List<PlaidTransactionEntity>,
        linkedPlaidTransactionIds: Set<String>,
        includePriorOverdue: Boolean,
        today: LocalDate = LocalDate.now()
    ): FreeToSpendSnapshot {
        val monthlyIncome = incomes.sumOf { RecurrenceNormalizer.toMonthlyCents(it.amountCents, it.recurrence) }
        val monthlyBills = bills.sumOf { RecurrenceNormalizer.toMonthlyCents(it.amountCents, it.recurrence) }
        val plannedFree = monthlyIncome - monthlyBills
        val liquidBalance = freeToSpendBalance(accounts)

        val currentMonth = YearMonth.from(today)
        val allBillsThisMonth = allBillsDueThisMonthCents(bills, payments, skips, currentMonth)
        val plaidMisc = miscPlaidSpentThisMonth(
            transactions = plaidTransactions,
            linkedTransactionIds = linkedPlaidTransactionIds,
            accounts = accounts,
            yearMonth = currentMonth
        )
        val priorOverdue = if (includePriorOverdue) {
            priorUnpaidBillsCents(bills, payments, skips, currentMonth, today)
        } else {
            0L
        }

        val freeToSpend = monthlyIncome -
            allBillsThisMonth -
            plaidMisc -
            priorOverdue

        return FreeToSpendSnapshot(
            monthlyIncomeCents = monthlyIncome,
            allBillsThisMonthCents = allBillsThisMonth,
            plaidMiscSpentCents = plaidMisc,
            priorOverdueUnpaidCents = priorOverdue,
            freeToSpendCents = freeToSpend,
            includePriorOverdue = includePriorOverdue,
            currentMonthLabel = currentMonth.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy")),
            monthlyBillsCents = monthlyBills,
            plannedFreeMonthlyCents = plannedFree,
            liquidBalanceCents = liquidBalance
        )
    }

    fun freeToSpendBalance(accounts: List<AccountEntity>): Long =
        accounts.sumOf { account ->
            when (account.accountType) {
                AccountType.CREDIT -> 0L
                AccountType.CHECKING -> account.balanceCents
                AccountType.SAVINGS ->
                    if (account.includeInFreeToSpend) account.balanceCents else 0L
            }
        }

    fun trackingStartMonth(bill: BillEntity): YearMonth =
        YearMonth.from(
            Instant.ofEpochMilli(bill.trackingStartMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
        )

    private fun allBillsDueThisMonthCents(
        bills: List<BillEntity>,
        payments: List<PaymentRecordEntity>,
        skips: List<BillCycleSkipEntity>,
        yearMonth: YearMonth
    ): Long {
        var total = 0L
        bills.forEach { bill ->
            if (!billTracksMonth(bill, yearMonth)) return@forEach
            if (!MonthTimeline.billAppliesToMonth(bill, yearMonth)) return@forEach
            val dueDate = BillSchedule.dueDateForYearMonth(bill, yearMonth)
            if (BillSchedule.isCycleSkipped(skips, bill.id, dueDate)) return@forEach
            val payment = BillSchedule.paymentForCycle(payments, bill.id, dueDate)
            total += BillSchedule.amountForCycle(bill, payment)
        }
        return total
    }

    private fun miscPlaidSpentThisMonth(
        transactions: List<PlaidTransactionEntity>,
        linkedTransactionIds: Set<String>,
        accounts: List<AccountEntity>,
        yearMonth: YearMonth
    ): Long {
        val spendingPlaidAccountIds = accounts.mapNotNull { account ->
            val counts = when (account.accountType) {
                AccountType.CHECKING -> true
                AccountType.SAVINGS -> account.includeInFreeToSpend
                AccountType.CREDIT -> false
            }
            if (counts) account.plaidAccountId else null
        }.toSet()

        if (spendingPlaidAccountIds.isEmpty()) return 0L

        return transactions.sumOf { tx ->
            if (tx.plaidAccountId !in spendingPlaidAccountIds) return@sumOf 0L
            if (tx.amountCents <= 0) return@sumOf 0L
            if (tx.pending) return@sumOf 0L
            if (tx.plaidTransactionId in linkedTransactionIds) return@sumOf 0L
            val txMonth = runCatching { YearMonth.from(LocalDate.parse(tx.date)) }.getOrNull()
                ?: return@sumOf 0L
            if (txMonth != yearMonth) return@sumOf 0L
            tx.amountCents
        }
    }

    private fun billTracksMonth(bill: BillEntity, yearMonth: YearMonth): Boolean =
        !yearMonth.isBefore(trackingStartMonth(bill))

    private fun priorUnpaidBillsCents(
        bills: List<BillEntity>,
        payments: List<PaymentRecordEntity>,
        skips: List<BillCycleSkipEntity>,
        currentMonth: YearMonth,
        today: LocalDate
    ): Long {
        if (bills.isEmpty()) return 0L
        val earliestTracked = bills.minOf { trackingStartMonth(it) }
        if (!earliestTracked.isBefore(currentMonth)) return 0L

        var total = 0L
        var month = currentMonth.minusMonths(1)
        while (!month.isBefore(earliestTracked)) {
            total += unpaidForMonth(
                bills = bills,
                payments = payments,
                skips = skips,
                yearMonth = month,
                today = today,
                countUpcomingInMonth = false
            )
            month = month.minusMonths(1)
        }
        return total
    }

    private fun unpaidForMonth(
        bills: List<BillEntity>,
        payments: List<PaymentRecordEntity>,
        skips: List<BillCycleSkipEntity>,
        yearMonth: YearMonth,
        today: LocalDate,
        countUpcomingInMonth: Boolean
    ): Long {
        val currentMonth = YearMonth.from(today)
        return bills.sumOf { bill ->
            if (!billTracksMonth(bill, yearMonth)) return@sumOf 0L
            if (!MonthTimeline.billAppliesToMonth(bill, yearMonth)) return@sumOf 0L
            if (bill.category == BillCategory.OTHER) return@sumOf 0L
            val dueDate = BillSchedule.dueDateForYearMonth(bill, yearMonth)
            if (BillSchedule.isCycleSkipped(skips, bill.id, dueDate)) return@sumOf 0L
            if (yearMonth.isBefore(currentMonth) && !dueDate.isBefore(today)) return@sumOf 0L
            if (yearMonth == currentMonth && !countUpcomingInMonth && dueDate.isAfter(today)) return@sumOf 0L
            val paid = BillSchedule.paymentForCycle(payments, bill.id, dueDate) != null
            if (paid) 0L else bill.amountCents
        }
    }
}
