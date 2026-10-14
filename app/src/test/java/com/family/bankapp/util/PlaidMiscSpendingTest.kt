package com.family.bankapp.util

import com.family.bankapp.data.entity.PlaidTransactionEntity
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaidMiscSpendingTest {

    private val accountId = "acct_checking"
    private val bankId = 1L
    private val august = YearMonth.of(2026, 8)

    @Test
    fun rolloverLender_netsRepaymentAgainstPriorMonthDeposit_countsOnlyFee() {
        val transactions = listOf(
            tx("tilt-deposit-jul", "2026-07-28", -19_000, "Tilt"),
            tx("tilt-repay-aug", "2026-08-27", 20_000, "Tilt")
        )

        val misc = PlaidMiscSpending.miscSpentCents(
            transactions = transactions,
            linkedTransactionIds = emptySet(),
            spendingPlaidAccountIds = setOf(accountId),
            yearMonth = august
        )

        // $200.00 repayment − $190.00 re-borrow deposit = $10.00 fee
        assertEquals(1_000L, misc)
    }

    @Test
    fun capOneTransfer_ignoredWhenBankIsMerchantAndNameIsWithdrawal() {
        val transactions = listOf(
            tx(
                id = "xfer-7098",
                date = "2026-10-05",
                amountCents = 24_500,
                name = "Withdrawal to 360 Checking ***********",
                merchantName = "Capital One"
            ),
            tx(
                id = "coke",
                date = "2026-10-10",
                amountCents = 285,
                name = "COCA COLA HATTIESBURG M",
                merchantName = "Coca-Cola"
            )
        )

        val misc = PlaidMiscSpending.miscSpentCents(
            transactions = transactions,
            linkedTransactionIds = emptySet(),
            spendingPlaidAccountIds = setOf(accountId),
            yearMonth = YearMonth.of(2026, 10)
        )

        assertEquals(285L, misc)
        assertEquals(
            "internal_transfer_360",
            PlaidMiscSpending.merchantKey(transactions.first())
        )
    }

    @Test
    fun atmWithdrawal_stillCountsAsSpending() {
        val transactions = listOf(
            tx(
                id = "atm",
                date = "2026-08-02",
                amountCents = 4_000,
                name = "ATM WITHDRAWAL",
                merchantName = "Capital One"
            )
        )

        val misc = PlaidMiscSpending.miscSpentCents(
            transactions = transactions,
            linkedTransactionIds = emptySet(),
            spendingPlaidAccountIds = setOf(accountId),
            yearMonth = august
        )

        assertEquals(4_000L, misc)
    }

    @Test
    fun internalTransfer_ignored() {
        val transactions = listOf(
            tx("xfer-out", "2026-08-15", 5_000, "Withdrawal to 360 Money Market ***********"),
            tx("xfer-in", "2026-08-23", -7_300, "Withdrawal to 360 Checking ***********")
        )

        val misc = PlaidMiscSpending.miscSpentCents(
            transactions = transactions,
            linkedTransactionIds = emptySet(),
            spendingPlaidAccountIds = setOf(accountId),
            yearMonth = august
        )

        assertEquals(0L, misc)
    }

    @Test
    fun regularPurchase_countsFullDebit() {
        val transactions = listOf(
            tx("coke", "2026-08-26", 285, "COCA COLA HATTIESBURG M")
        )

        val misc = PlaidMiscSpending.miscSpentCents(
            transactions = transactions,
            linkedTransactionIds = emptySet(),
            spendingPlaidAccountIds = setOf(accountId),
            yearMonth = august
        )

        assertEquals(285L, misc)
    }

    @Test
    fun mixedScenario_matchesUserStyleBudget() {
        val transactions = listOf(
            tx("tilt-deposit", "2026-08-20", -19_000, "Tilt"),
            tx("tilt-repay", "2026-08-22", 20_000, "Tilt"),
            tx("xfer", "2026-08-15", 5_000, "Withdrawal to 360 Money Market ***********"),
            tx("groceries", "2026-08-01", 9_342, "GREER'S # 30"),
            tx("coke", "2026-08-26", 285, "COCA COLA HATTIESBURG M")
        )

        val misc = PlaidMiscSpending.miscSpentCents(
            transactions = transactions,
            linkedTransactionIds = emptySet(),
            spendingPlaidAccountIds = setOf(accountId),
            yearMonth = august
        )

        // Tilt fee $10 + groceries $93.42 + coke $2.85 = $106.27
        assertEquals(10_627L, misc)
    }

    private fun tx(
        id: String,
        date: String,
        amountCents: Long,
        name: String,
        merchantName: String? = null
    ) = PlaidTransactionEntity(
        plaidTransactionId = id,
        bankId = bankId,
        plaidAccountId = accountId,
        amountCents = amountCents,
        date = date,
        name = name,
        merchantName = merchantName
    )
}
