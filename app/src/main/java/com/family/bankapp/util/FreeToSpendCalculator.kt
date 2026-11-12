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
    /** Spendable cash used in the formula, after pending holds that are still in the stored balance. */
    val liquidBalanceCents: Long,
    /** Pending outflows on spending accounts, from transactions or from current − available. */
    val pendingHoldsCents: Long = 0,
    /**
     * Part of [pendingHoldsCents] subtracted to produce [liquidBalanceCents].
     * Zero when the stored balance is already Plaid's available balance.
     */
    val pendingSubtractedCents: Long = 0
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
        val spendable = spendableBalance(accounts, plaidTransactions)
        val liquidBalance = spendable.spendableCents

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
            liquidBalanceCents = liquidBalance,
            pendingHoldsCents = spendable.pendingHoldsCents,
            pendingSubtractedCents = spendable.pendingSubtractedCents
        )
    }

    private data class SpendableBalance(
        val spendableCents: Long,
        val pendingHoldsCents: Long,
        val pendingSubtractedCents: Long
    )

    /**
     * Cash that can still be spent.
     *
     * After a Plaid sync, checking and savings store available balance, which
     * already subtracts pending holds (currentBalanceCents differs from
     * balanceCents). Until that sync, the phone still has the current balance,
     * so pending debits cached from the last transaction sync are subtracted
     * here. That is the $21.51 still inside the $175 free-to-spend figure.
     */
    private fun spendableBalance(
        accounts: List<AccountEntity>,
        transactions: List<PlaidTransactionEntity>
    ): SpendableBalance {
        val pendingByAccount = transactions
            .filter { it.pending && it.amountCents > 0 }
            .groupBy { it.plaidAccountId }
            .mapValues { (_, txs) -> txs.sumOf { it.amountCents } }

        var spendable = 0L
        var pendingHolds = 0L
        var pendingSubtracted = 0L
        accounts.forEach { account ->
            if (!countsTowardFreeToSpend(account)) return@forEach
            val current = account.currentBalanceCents
            val balanceAlreadyAvailable = current != null && current != account.balanceCents
            if (balanceAlreadyAvailable) {
                spendable += account.balanceCents
                pendingHolds += (current - account.balanceCents).coerceAtLeast(0L)
            } else {
                val pending = account.plaidAccountId?.let { pendingByAccount[it] } ?: 0L
                spendable += account.balanceCents - pending
                pendingHolds += pending
                pendingSubtracted += pending
            }
        }
        return SpendableBalance(spendable, pendingHolds, pendingSubtracted)
    }

    private fun countsTowardFreeToSpend(account: AccountEntity): Boolean = when (account.accountType) {
        AccountType.CREDIT -> false
        AccountType.CHECKING -> true
        AccountType.SAVINGS -> account.includeInFreeToSpend
    }

    /** Pending outflows saved for this account. Zero when none are detected. */
    fun pendingDebitCents(
        account: AccountEntity,
        transactions: List<PlaidTransactionEntity>
    ): Long {
        val plaidId = account.plaidAccountId ?: return 0L
        return transactions.sumOf { tx ->
            if (tx.plaidAccountId == plaidId && tx.pending && tx.amountCents > 0) tx.amountCents else 0L
        }
    }

    fun freeToSpendBalance(accounts: List<AccountEntity>): Long =
        accounts.sumOf { account ->
            if (countsTowardFreeToSpend(account)) account.balanceCents else 0L
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
