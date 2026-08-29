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
        name: String
    ) = PlaidTransactionEntity(
        plaidTransactionId = id,
        bankId = bankId,
        plaidAccountId = accountId,
        amountCents = amountCents,
        date = date,
        name = name
    )
}
