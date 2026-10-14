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
    /** Total of all bills due this month, paid or not. */
    val allBillsThisMonthCents: Long,
    /** Unpaid portion of this month's bills. Paid bills are already out of the bank. */
    val remainingBillsThisMonthCents: Long,
    /** Plaid debits this month not linked to a bill, net of borrow-app re-borrow deposits. */
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
        val reservedBills = billsReservedThisMonth(bills, payments, skips, currentMonth)
        val allBillsThisMonth = reservedBills.sumOf { bill ->
            val dueDate = BillSchedule.dueDateForYearMonth(bill, currentMonth)
            val payment = BillSchedule.paymentForCycle(payments, bill.id, dueDate)
            BillSchedule.reservedAmountForCycle(bill, payment)
        }
        val remainingBills = reservedBills.sumOf { bill ->
            val dueDate = BillSchedule.dueDateForYearMonth(bill, currentMonth)
            val payment = BillSchedule.paymentForCycle(payments, bill.id, dueDate)
            BillSchedule.remainingCents(bill, payment)
        }
        val plaidMisc = miscPlaidSpentThisMonth(
            transactions = plaidTransactions,
            linkedTransactionIds = linkedPlaidTransactionIds,
            accounts = accounts,
            bills = bills,
            payments = payments,
            skips = skips,
            yearMonth = currentMonth
        )
        val priorOverdue = if (includePriorOverdue) {
            priorUnpaidBillsCents(bills, payments, skips, currentMonth, today)
        } else {
            0L
        }

        val freeToSpend = liquidBalance - remainingBills - priorOverdue

        return FreeToSpendSnapshot(
            monthlyIncomeCents = monthlyIncome,
            allBillsThisMonthCents = allBillsThisMonth,
            remainingBillsThisMonthCents = remainingBills,
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

    fun billsReservedThisMonth(
        bills: List<BillEntity>,
        payments: List<PaymentRecordEntity>,
        skips: List<BillCycleSkipEntity>,
        yearMonth: YearMonth
    ): List<BillEntity> = bills.filter { bill ->
        if (!billTracksMonth(bill, yearMonth)) return@filter false
        if (!MonthTimeline.billAppliesToMonth(bill, yearMonth)) return@filter false
        val dueDate = BillSchedule.dueDateForYearMonth(bill, yearMonth)
        !BillSchedule.isCycleSkipped(skips, bill.id, dueDate)
    }

    private fun miscPlaidSpentThisMonth(
        transactions: List<PlaidTransactionEntity>,
        linkedTransactionIds: Set<String>,
        accounts: List<AccountEntity>,
        bills: List<BillEntity>,
        payments: List<PaymentRecordEntity>,
        skips: List<BillCycleSkipEntity>,
        yearMonth: YearMonth
    ): Long {
        val spendingPlaidAccountIds = spendingPlaidAccountIds(accounts)
        if (spendingPlaidAccountIds.isEmpty()) return 0L
        val coveredIds = BillCoveredSpending.covers(
            transactions = transactions,
            countedBills = billsReservedThisMonth(bills, payments, skips, yearMonth),
            spendingPlaidAccountIds = spendingPlaidAccountIds,
            linkedTransactionIds = linkedTransactionIds,
            yearMonth = yearMonth
        ).map { it.transaction.plaidTransactionId }.toSet()
        return PlaidMiscSpending.miscSpentCents(
            transactions = transactions,
            linkedTransactionIds = linkedTransactionIds + coveredIds,
            spendingPlaidAccountIds = spendingPlaidAccountIds,
            yearMonth = yearMonth
        )
    }

    private fun spendingPlaidAccountIds(accounts: List<AccountEntity>): Set<String> =
        accounts.mapNotNull { account ->
            val counts = when (account.accountType) {
                AccountType.CHECKING -> true
                AccountType.SAVINGS -> account.includeInFreeToSpend
                AccountType.CREDIT -> false
            }
            if (counts) account.plaidAccountId else null
        }.toSet()

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
            val payment = BillSchedule.paymentForCycle(payments, bill.id, dueDate)
            BillSchedule.remainingCents(bill, payment)
        }
    }
}
