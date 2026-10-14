package com.family.bankapp.util

import com.family.bankapp.data.entity.AccountEntity
import com.family.bankapp.data.entity.BankEntity
import com.family.bankapp.data.entity.BillEntity
import com.family.bankapp.data.entity.PaymentRecordEntity
import com.family.bankapp.data.entity.PlaidTransactionEntity
import com.family.bankapp.data.model.AccountType
import com.family.bankapp.data.model.ConnectionType
import com.family.bankapp.data.repository.OverviewData
import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeToSpendDebugReportTest {

    private val today = LocalDate.of(2026, 10, 14)

    @Test
    fun nearbyMonths_listPaidAndPendingAmounts() {
        val food = BillEntity(
            id = 1,
            name = "Food",
            amountCents = 40_000,
            dueDayOfMonth = 1,
            trackingStartMillis = BillSchedule.toCycleMillis(LocalDate.of(2026, 8, 1))
        )
        val septemberPaid = PaymentRecordEntity(
            billId = food.id,
            accountId = null,
            amountCents = 40_000,
            cycleDueDateMillis = BillSchedule.toCycleMillis(LocalDate.of(2026, 9, 1))
        )
        val octoberPartial = PaymentRecordEntity(
            billId = food.id,
            accountId = null,
            amountCents = 15_000,
            cycleDueDateMillis = BillSchedule.toCycleMillis(LocalDate.of(2026, 10, 1))
        )

        val report = report(
            bills = listOf(food),
            payments = listOf(septemberPaid, octoberPartial)
        )

        assertTrue(report.contains("--- September 2026 (previous) ---"))
        assertTrue(report.contains("--- October 2026 (current) ---"))
        assertTrue(report.contains("--- November 2026 (next) ---"))
        assertTrue(
            report.contains(
                "Food | Other | Monthly | due 2026-09-01 | PAID | bill $400.00 | paid $400.00 | remaining $0.00"
            )
        )
        assertTrue(
            report.contains(
                "Food | Other | Monthly | due 2026-10-01 | PARTIAL | bill $400.00 | paid $150.00 | remaining $250.00"
            )
        )
        assertTrue(
            report.contains(
                "Food | Other | Monthly | due 2026-11-01 | PENDING | bill $400.00 | paid $0.00 | remaining $400.00"
            )
        )
        assertTrue(report.contains("1 paid, 0 pending, 0 partial"))
        assertTrue(report.contains("0 paid, 0 pending, 1 partial"))
        assertTrue(report.contains("0 paid, 1 pending, 0 partial"))
        assertTrue(report.contains("upcoming, not in this month's free to spend"))
    }

    @Test
    fun capOneWithdrawals_listedAsIgnoredTransfersAndNotInSpendingTotal() {
        val account = AccountEntity(
            id = 1,
            bankId = 1,
            name = "360 Checking ···7098",
            accountType = AccountType.CHECKING,
            balanceCents = 2_812,
            plaidAccountId = "acct_checking"
        )
        val withdrawal = PlaidTransactionEntity(
            plaidTransactionId = "xfer",
            bankId = 1,
            plaidAccountId = "acct_checking",
            amountCents = 24_500,
            date = "2026-10-05",
            name = "Withdrawal to 360 Checking ***********",
            merchantName = "Capital One"
        )
        val coke = PlaidTransactionEntity(
            plaidTransactionId = "coke",
            bankId = 1,
            plaidAccountId = "acct_checking",
            amountCents = 285,
            date = "2026-10-10",
            name = "COCA COLA HATTIESBURG M"
        )

        val report = report(
            accounts = listOf(account),
            transactions = listOf(withdrawal, coke)
        )

        assertTrue(report.contains("Total misc spending (net): $2.85"))
        assertTrue(report.contains("Ignored transfers between your Capital One accounts (not counted):"))
        assertTrue(report.contains("Withdrawal to 360 Checking *********** | $245.00"))
        assertTrue(report.contains("Total ignored transfers: $245.00"))
        assertTrue(report.contains("merchant: Capital One"))
        assertFalse(report.contains("Total misc spending (net): $247.85"))
    }

    private fun report(
        bills: List<BillEntity> = emptyList(),
        payments: List<PaymentRecordEntity> = emptyList(),
        accounts: List<AccountEntity> = emptyList(),
        transactions: List<PlaidTransactionEntity> = emptyList()
    ): String {
        val snapshot = FreeToSpendCalculator.calculate(
            accounts = accounts,
            bills = bills,
            incomes = emptyList(),
            payments = payments,
            skips = emptyList(),
            plaidTransactions = transactions,
            linkedPlaidTransactionIds = emptySet(),
            includePriorOverdue = true,
            today = today
        )
        return FreeToSpendDebugReport.build(
            snapshot = snapshot,
            overview = OverviewData(
                banks = listOf(
                    BankEntity(id = 1, name = "CapitalOne", colorHex = "#000000", connectionType = ConnectionType.CONNECTED)
                ),
                accounts = accounts,
                bills = bills,
                payments = payments,
                plaidTransactions = transactions
            ),
            appVersion = "test",
            today = today
        )
    }
}
