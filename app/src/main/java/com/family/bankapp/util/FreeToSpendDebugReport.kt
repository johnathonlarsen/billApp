package com.family.bankapp.util

import com.family.bankapp.data.entity.AccountEntity
import com.family.bankapp.data.entity.BillEntity
import com.family.bankapp.data.entity.IncomeEntity
import com.family.bankapp.data.entity.PaymentRecordEntity
import com.family.bankapp.data.entity.BillCycleSkipEntity
import com.family.bankapp.data.entity.PlaidTransactionEntity
import com.family.bankapp.data.model.AccountType
import com.family.bankapp.data.model.BillCategory
import com.family.bankapp.data.repository.OverviewData
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object FreeToSpendDebugReport {

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val monthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")

    fun build(
        snapshot: FreeToSpendSnapshot,
        overview: OverviewData,
        appVersion: String,
        today: LocalDate = LocalDate.now()
    ): String = buildString {
        val currentMonth = YearMonth.from(today)
        appendLine("Family Bank — Free to spend debug")
        appendLine("Generated: ${Instant.now()} (local date ${today.format(dateFormatter)})")
        appendLine("App version: $appVersion")
        appendLine()

        appendLine("=== Formula ===")
        appendFormulaBreakdown(snapshot)
        appendLine()

        appendLine("=== Result ===")
        appendLine("freeToSpendCents: ${snapshot.freeToSpendCents}")
        appendLine("monthlyIncomeCents: ${snapshot.monthlyIncomeCents}")
        appendLine("allBillsThisMonthCents: ${snapshot.allBillsThisMonthCents}")
        appendLine("plaidMiscSpentCents: ${snapshot.plaidMiscSpentCents}")
        appendLine("priorOverdueUnpaidCents: ${snapshot.priorOverdueUnpaidCents}")
        appendLine("includePriorOverdue: ${snapshot.includePriorOverdue}")
        appendLine("liquidBalanceCents (reference): ${snapshot.liquidBalanceCents}")
        appendLine("monthlyBillsCents (plan): ${snapshot.monthlyBillsCents}")
        appendLine("plannedFreeMonthlyCents: ${snapshot.plannedFreeMonthlyCents}")
        appendLine()

        appendLine("=== Income sources (${overview.incomes.size}) ===")
        if (overview.incomes.isEmpty()) {
            appendLine("(none)")
        } else {
            overview.incomes.forEach { income ->
                val monthly = RecurrenceNormalizer.toMonthlyCents(income.amountCents, income.recurrence)
                appendLine(
                    "- ${income.name}: ${MoneyFormatter.format(income.amountCents)} " +
                        "${income.recurrence.name.lowercase()} -> ${MoneyFormatter.format(monthly)}/mo"
                )
            }
            appendLine("Total monthly income: ${MoneyFormatter.format(snapshot.monthlyIncomeCents)}")
        }
        appendLine()

        appendLine("=== Bills this month (${currentMonth.format(monthFormatter)}) ===")
        appendCurrentMonthBills(
            bills = overview.bills,
            payments = overview.payments,
            skips = overview.billCycleSkips,
            yearMonth = currentMonth
        )
        appendLine()

        if (snapshot.includePriorOverdue) {
            appendLine("=== Prior unpaid bills ===")
            appendPriorUnpaidBills(
                bills = overview.bills,
                payments = overview.payments,
                skips = overview.billCycleSkips,
                currentMonth = currentMonth,
                today = today
            )
            appendLine()
        }

        appendLine("=== Plaid misc spending this month ===")
        appendPlaidMiscTransactions(
            transactions = overview.plaidTransactions,
            linkedTransactionIds = overview.linkedPlaidTransactionIds,
            accounts = overview.accounts,
            yearMonth = currentMonth
        )
        appendLine()

        appendLine("=== Accounts in liquid balance reference ===")
        appendAccounts(overview.accounts, overview.banks.associateBy { it.id })
        appendLine()

        appendLine("=== Counts ===")
        appendLine("Active bills: ${overview.bills.size}")
        appendLine("Payment records: ${overview.payments.size}")
        appendLine("Plaid transactions cached: ${overview.plaidTransactions.size}")
        appendLine("Linked Plaid transactions: ${overview.linkedPlaidTransactionIds.size}")
    }.trimEnd()

    private fun StringBuilder.appendFormulaBreakdown(snapshot: FreeToSpendSnapshot) {
        appendLine("Income:              ${MoneyFormatter.format(snapshot.monthlyIncomeCents)}")
        appendLine("  - bills:           ${MoneyFormatter.format(snapshot.allBillsThisMonthCents)}")
        appendLine("  - spending (Plaid): ${MoneyFormatter.format(snapshot.plaidMiscSpentCents)}")
        appendLine("  - prior overdue:   ${MoneyFormatter.format(snapshot.priorOverdueUnpaidCents)}")
        appendLine("= ${MoneyFormatter.format(snapshot.freeToSpendCents)} free to spend")
        appendLine(
            "Compact: Income ${MoneyFormatter.format(snapshot.monthlyIncomeCents)}" +
                " - bills ${MoneyFormatter.format(snapshot.allBillsThisMonthCents)}" +
                " - spending ${MoneyFormatter.format(snapshot.plaidMiscSpentCents)}" +
                " - prior ${MoneyFormatter.format(snapshot.priorOverdueUnpaidCents)}"
        )
    }

    private fun StringBuilder.appendCurrentMonthBills(
        bills: List<BillEntity>,
        payments: List<PaymentRecordEntity>,
        skips: List<BillCycleSkipEntity>,
        yearMonth: YearMonth
    ) {
        var any = false
        bills.forEach { bill ->
            if (!billTracksMonth(bill, yearMonth)) return@forEach
            if (!MonthTimeline.billAppliesToMonth(bill, yearMonth)) return@forEach
            val dueDate = BillSchedule.dueDateForYearMonth(bill, yearMonth)
            if (BillSchedule.isCycleSkipped(skips, bill.id, dueDate)) return@forEach
            val payment = BillSchedule.paymentForCycle(payments, bill.id, dueDate)
            val status = when {
                BillSchedule.isFullyPaid(bill, payment) -> "PAID"
                BillSchedule.isPartiallyPaid(bill, payment) ->
                    "PARTIAL (${MoneyFormatter.format(BillSchedule.paidAmountCents(payment))} of ${MoneyFormatter.format(bill.amountCents)})"
                else -> "UNPAID"
            }
            val cycleAmount = BillSchedule.reservedAmountForCycle(bill, payment)
            val countsToward = "counts toward free to spend"
            any = true
            appendLine(
                "- ${bill.name} | ${bill.category.label} | due ${dueDate.format(dateFormatter)} | " +
                    "$status | ${MoneyFormatter.format(cycleAmount)} | $countsToward"
            )
        }
        if (!any) appendLine("(none)")
    }

    private fun StringBuilder.appendPriorUnpaidBills(
        bills: List<BillEntity>,
        payments: List<PaymentRecordEntity>,
        skips: List<BillCycleSkipEntity>,
        currentMonth: YearMonth,
        today: LocalDate
    ) {
        if (bills.isEmpty()) {
            appendLine("(none)")
            return
        }
        val earliestTracked = bills.minOf { FreeToSpendCalculator.trackingStartMonth(it) }
        if (!earliestTracked.isBefore(currentMonth)) {
            appendLine("(none — no prior tracked months)")
            return
        }
        var any = false
        var month = currentMonth.minusMonths(1)
        while (!month.isBefore(earliestTracked)) {
            bills.forEach { bill ->
                if (!billTracksMonth(bill, month)) return@forEach
                if (!MonthTimeline.billAppliesToMonth(bill, month)) return@forEach
                if (bill.category == BillCategory.OTHER) return@forEach
                val dueDate = BillSchedule.dueDateForYearMonth(bill, month)
                if (BillSchedule.isCycleSkipped(skips, bill.id, dueDate)) return@forEach
                if (month.isBefore(YearMonth.from(today)) && !dueDate.isBefore(today)) return@forEach
                val payment = BillSchedule.paymentForCycle(payments, bill.id, dueDate)
                val remaining = BillSchedule.remainingCents(bill, payment)
                if (remaining <= 0L) return@forEach
                any = true
                appendLine(
                    "- ${bill.name} | ${month.format(monthFormatter)} | due ${dueDate.format(dateFormatter)} | " +
                        MoneyFormatter.format(remaining)
                )
            }
            month = month.minusMonths(1)
        }
        if (!any) appendLine("(none)")
    }

    private fun StringBuilder.appendPlaidMiscTransactions(
        transactions: List<PlaidTransactionEntity>,
        linkedTransactionIds: Set<String>,
        accounts: List<AccountEntity>,
        yearMonth: YearMonth
    ) {
        val spendingPlaidAccountIds = accounts.mapNotNull { account ->
            val counts = when (account.accountType) {
                AccountType.CHECKING -> true
                AccountType.SAVINGS -> account.includeInFreeToSpend
                AccountType.CREDIT -> false
            }
            if (counts) account.plaidAccountId else null
        }.toSet()
        val accountNameByPlaidId = accounts.mapNotNull { account ->
            account.plaidAccountId?.let { it to account.name }
        }.toMap()

        val merchantNets = PlaidMiscSpending.merchantNets(
            transactions = transactions,
            linkedTransactionIds = linkedTransactionIds,
            spendingPlaidAccountIds = spendingPlaidAccountIds,
            yearMonth = yearMonth
        )

        val misc = transactions.filter { tx ->
            tx.plaidAccountId in spendingPlaidAccountIds &&
                tx.amountCents > 0 &&
                !tx.pending &&
                !tx.excludeFromFreeToSpend &&
                tx.plaidTransactionId !in linkedTransactionIds &&
                !PlaidMiscSpending.isInternalTransfer(PlaidMiscSpending.merchantKey(tx)) &&
                runCatching { YearMonth.from(LocalDate.parse(tx.date)) }.getOrNull() == yearMonth
        }.sortedByDescending { it.date }

        val excluded = transactions.filter { tx ->
            tx.plaidAccountId in spendingPlaidAccountIds &&
                tx.amountCents > 0 &&
                !tx.pending &&
                tx.excludeFromFreeToSpend &&
                tx.plaidTransactionId !in linkedTransactionIds &&
                runCatching { YearMonth.from(LocalDate.parse(tx.date)) }.getOrNull() == yearMonth
        }.sortedByDescending { it.date }

        val rolloverNets = merchantNets.filter { net ->
            PlaidMiscSpending.isRolloverLenderName(net.label) && net.creditsOffsetCents > 0L
        }

        if (misc.isEmpty() && excluded.isEmpty() && merchantNets.isEmpty()) {
            appendLine("(none)")
            return
        }

        if (rolloverNets.isNotEmpty()) {
            appendLine("Borrow-app netting (repayment − re-borrow deposit, prior + current month):")
            rolloverNets.forEach { net ->
                appendLine(
                    "- ${net.label}: debits ${MoneyFormatter.format(net.debitsThisMonthCents)}" +
                        " − deposits ${MoneyFormatter.format(net.creditsOffsetCents)}" +
                        " = ${MoneyFormatter.format(net.countedCents)} counted"
                )
            }
            appendLine()
        }

        if (misc.isNotEmpty()) {
            misc.forEach { tx ->
                val accountName = accountNameByPlaidId[tx.plaidAccountId] ?: tx.plaidAccountId
                val key = PlaidMiscSpending.merchantKey(tx)
                val note = when {
                    PlaidMiscSpending.isInternalTransfer(key) -> " | internal transfer (ignored)"
                    PlaidMiscSpending.isRolloverLender(key) -> {
                        val net = merchantNets.find { it.label.equals(tx.name, true) || it.label == (tx.merchantName ?: tx.name) }
                        if (net != null && net.creditsOffsetCents > 0) " | see netting above" else ""
                    }
                    else -> ""
                }
                appendLine(
                    "- ${tx.date} | ${tx.name} | ${MoneyFormatter.format(tx.amountCents)} | account: $accountName$note"
                )
            }
            appendLine(
                "Total misc spending (net): ${MoneyFormatter.format(merchantNets.sumOf { it.countedCents })}"
            )
        } else {
            appendLine("(no counted misc spending)")
        }

        if (excluded.isNotEmpty()) {
            appendLine()
            appendLine("Excluded from free to spend (${excluded.size}):")
            excluded.forEach { tx ->
                val accountName = accountNameByPlaidId[tx.plaidAccountId] ?: tx.plaidAccountId
                appendLine(
                    "- ${tx.date} | ${tx.name} | ${MoneyFormatter.format(tx.amountCents)} | account: $accountName"
                )
            }
            appendLine(
                "Total excluded: ${MoneyFormatter.format(excluded.sumOf { it.amountCents })} " +
                    "(manual: loan rollovers, transfers, etc.)"
            )
        }
    }

    private fun StringBuilder.appendAccounts(
        accounts: List<AccountEntity>,
        banksById: Map<Long, com.family.bankapp.data.entity.BankEntity>
    ) {
        if (accounts.isEmpty()) {
            appendLine("(none)")
            return
        }
        accounts.forEach { account ->
            val bankName = banksById[account.bankId]?.name ?: "Bank ${account.bankId}"
            val included = when (account.accountType) {
                AccountType.CREDIT -> false
                AccountType.CHECKING -> true
                AccountType.SAVINGS -> account.includeInFreeToSpend
            }
            appendLine(
                "- $bankName · ${account.name} | ${account.accountType.name} | " +
                    "balance ${MoneyFormatter.format(account.balanceCents)} | " +
                    if (included) "included" else "excluded"
            )
        }
    }

    private fun billTracksMonth(bill: BillEntity, yearMonth: YearMonth): Boolean =
        !yearMonth.isBefore(FreeToSpendCalculator.trackingStartMonth(bill))
}
