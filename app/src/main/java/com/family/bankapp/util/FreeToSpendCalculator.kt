package com.family.bankapp.util

import com.family.bankapp.data.entity.AccountEntity
import com.family.bankapp.data.entity.BillEntity
import com.family.bankapp.data.entity.IncomeEntity
import com.family.bankapp.data.entity.PaymentRecordEntity
import com.family.bankapp.data.model.AccountType
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
    val liquidBalanceCents: Long,
    val monthlyIncomeCents: Long,
    val monthlyBillsCents: Long,
    val plannedFreeMonthlyCents: Long,
    val currentMonthUnpaidCents: Long,
    val priorOverdueUnpaidCents: Long,
    val totalCommittedBillsCents: Long,
    val freeToSpendCents: Long,
    val includePriorOverdue: Boolean,
    val currentMonthLabel: String
)

object FreeToSpendCalculator {

    fun calculate(
        accounts: List<AccountEntity>,
        bills: List<BillEntity>,
        incomes: List<IncomeEntity>,
        payments: List<PaymentRecordEntity>,
        includePriorOverdue: Boolean,
        today: LocalDate = LocalDate.now()
    ): FreeToSpendSnapshot {
        val liquidBalance = freeToSpendBalance(accounts)
        val monthlyIncome = incomes.sumOf { RecurrenceNormalizer.toMonthlyCents(it.amountCents, it.recurrence) }
        val monthlyBills = bills.sumOf { RecurrenceNormalizer.toMonthlyCents(it.amountCents, it.recurrence) }
        val plannedFree = monthlyIncome - monthlyBills

        val currentMonth = YearMonth.from(today)
        val currentMonthUnpaid = unpaidForMonth(bills, payments, currentMonth, today, countUpcomingInMonth = true)
        val priorOverdue = if (includePriorOverdue) {
            priorUnpaidBillsCents(bills, payments, currentMonth, today)
        } else {
            0L
        }
        val committed = currentMonthUnpaid + priorOverdue
        val freeToSpend = liquidBalance - committed

        return FreeToSpendSnapshot(
            liquidBalanceCents = liquidBalance,
            monthlyIncomeCents = monthlyIncome,
            monthlyBillsCents = monthlyBills,
            plannedFreeMonthlyCents = plannedFree,
            currentMonthUnpaidCents = currentMonthUnpaid,
            priorOverdueUnpaidCents = priorOverdue,
            totalCommittedBillsCents = committed,
            freeToSpendCents = freeToSpend,
            includePriorOverdue = includePriorOverdue,
            currentMonthLabel = currentMonth.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy"))
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

    private fun billTracksMonth(bill: BillEntity, yearMonth: YearMonth): Boolean =
        !yearMonth.isBefore(trackingStartMonth(bill))

    private fun unpaidForMonth(
        bills: List<BillEntity>,
        payments: List<PaymentRecordEntity>,
        yearMonth: YearMonth,
        today: LocalDate,
        countUpcomingInMonth: Boolean
    ): Long {
        val currentMonth = YearMonth.from(today)
        return bills.sumOf { bill ->
            if (!billTracksMonth(bill, yearMonth)) return@sumOf 0L
            if (!MonthTimeline.billAppliesToMonth(bill, yearMonth)) return@sumOf 0L
            val dueDate = BillSchedule.dueDateForYearMonth(bill, yearMonth)
            if (yearMonth.isBefore(currentMonth) && !dueDate.isBefore(today)) return@sumOf 0L
            if (yearMonth == currentMonth && !countUpcomingInMonth && dueDate.isAfter(today)) return@sumOf 0L
            val paid = BillSchedule.paymentForCycle(payments, bill.id, dueDate) != null
            if (paid) 0L else bill.amountCents
        }
    }

    private fun priorUnpaidBillsCents(
        bills: List<BillEntity>,
        payments: List<PaymentRecordEntity>,
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
                yearMonth = month,
                today = today,
                countUpcomingInMonth = false
            )
            month = month.minusMonths(1)
        }
        return total
    }
}
