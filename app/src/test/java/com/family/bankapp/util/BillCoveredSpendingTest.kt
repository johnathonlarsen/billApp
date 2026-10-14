package com.family.bankapp.util

import com.family.bankapp.data.entity.AccountEntity
import com.family.bankapp.data.entity.BillEntity
import com.family.bankapp.data.entity.PlaidTransactionEntity
import com.family.bankapp.data.model.AccountType
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BillCoveredSpendingTest {

    private val october = YearMonth.of(2026, 10)
    private val accountId = "acct_7098"

    @Test
    fun octoberDump_doesNotCountBillPaymentsOrManualFoodAndFuelAgain() {
        val bills = listOf(
            bill(1, "Coast Electric", 33_000),
            bill(2, "Spotify", 1_400),
            bill(3, "Geico", 6_000),
            bill(4, "Proton VPN", 1_000),
            bill(5, "Food", 48_000),
            bill(6, "Fuel", 12_000),
            bill(7, "Cursor", 2_000)
        )
        val transactions = listOf(
            tx("coast", "2026-10-03", 33_800, "MDC COAST ELECTRIC POWE", "Coast Electric Power Association"),
            tx("spotify", "2026-10-01", 1_390, "Spotify"),
            tx("geico", "2026-10-01", 6_188, "GEICO"),
            tx("proton", "2026-10-01", 999, "PROTON AG", "Proton"),
            tx("greer", "2026-10-04", 11_305, "GREER'S # 30", "Greer's"),
            tx("buffet", "2026-10-04", 1_889, "NEW BUFFET CITY"),
            tx("coke", "2026-10-14", 285, "COCA COLA HATTIESBURG M", "Coca-Cola"),
            tx("tree", "2026-10-04", 3_401, "DOLLARTREE", "Dollar Tree"),
            tx("venmo", "2026-10-08", 300, "Venmo"),
            tx("comfy", "2026-10-04", 2_000, "COMFY ORG", "Comfy"),
            tx("shell", "2026-10-06", 4_000, "SHELL OIL"),
            tx("xfer", "2026-10-05", 24_500, "Withdrawal to 360 Checking ***********", "Capital One")
        )

        val covers = BillCoveredSpending.covers(
            transactions = transactions,
            countedBills = bills,
            spendingPlaidAccountIds = setOf(accountId),
            linkedTransactionIds = emptySet(),
            yearMonth = october
        )
        val coveredIds = covers.map { it.transaction.plaidTransactionId }.toSet()

        assertEquals(
            setOf("coast", "spotify", "geico", "proton", "greer", "buffet", "coke", "shell"),
            coveredIds
        )
        assertTrue(covers.single { it.transaction.plaidTransactionId == "greer" }.reason.contains("Food"))
        assertTrue(covers.single { it.transaction.plaidTransactionId == "shell" }.reason.contains("Fuel"))
        assertTrue(covers.single { it.transaction.plaidTransactionId == "coast" }.reason.contains("Coast Electric"))

        val misc = PlaidMiscSpending.miscSpentCents(
            transactions = transactions,
            linkedTransactionIds = coveredIds,
            spendingPlaidAccountIds = setOf(accountId),
            yearMonth = october
        )
        // Dollar Tree $34.01 + Venmo $3.00 + Comfy $20.00. The 360 transfer is ignored.
        assertEquals(5_701L, misc)
    }

    @Test
    fun largeBorrowRepayment_doesNotMatchASmallFeeBill() {
        val bills = listOf(bill(1, "Empower/Cashapp Fee", 5_000))
        val transactions = listOf(tx("empower", "2026-10-02", 20_000, "EMPOWER"))
        val covers = BillCoveredSpending.covers(
            transactions = transactions,
            countedBills = bills,
            spendingPlaidAccountIds = setOf(accountId),
            linkedTransactionIds = emptySet(),
            yearMonth = october
        )
        assertTrue(covers.isEmpty())
    }

    @Test
    fun calculator_dropsCoveredChargesFromFreeToSpend() {
        val account = AccountEntity(
            id = 1,
            bankId = 1,
            name = "360 Checking",
            accountType = AccountType.CHECKING,
            balanceCents = 0,
            plaidAccountId = accountId
        )
        val food = bill(5, "Food", 48_000)
        val coast = bill(1, "Coast Electric", 33_000)
        val snapshot = FreeToSpendCalculator.calculate(
            accounts = listOf(account),
            bills = listOf(food, coast),
            incomes = emptyList(),
            payments = emptyList(),
            skips = emptyList(),
            plaidTransactions = listOf(
                tx("coast", "2026-10-03", 33_800, "MDC COAST ELECTRIC POWE", "Coast Electric Power Association"),
                tx("greer", "2026-10-04", 11_305, "GREER'S # 30", "Greer's"),
                tx("venmo", "2026-10-08", 300, "Venmo")
            ),
            linkedPlaidTransactionIds = emptySet(),
            includePriorOverdue = false,
            today = LocalDate.of(2026, 10, 14)
        )
        // Unpaid food $480 and electric $330 are still due. The bank is empty, so free to spend is that shortfall.
        // Venmo is already reflected only if it had left a balance; it is not subtracted again.
        assertEquals(81_000L, snapshot.allBillsThisMonthCents)
        assertEquals(81_000L, snapshot.remainingBillsThisMonthCents)
        assertEquals(300L, snapshot.plaidMiscSpentCents)
        assertEquals(-81_000L, snapshot.freeToSpendCents)
    }

    private fun bill(id: Long, name: String, amountCents: Long) = BillEntity(
        id = id,
        name = name,
        amountCents = amountCents,
        dueDayOfMonth = 1,
        trackingStartMillis = BillSchedule.toCycleMillis(LocalDate.of(2026, 1, 1))
    )

    private fun tx(
        id: String,
        date: String,
        amountCents: Long,
        name: String,
        merchantName: String? = null
    ) = PlaidTransactionEntity(
        plaidTransactionId = id,
        bankId = 1,
        plaidAccountId = accountId,
        amountCents = amountCents,
        date = date,
        name = name,
        merchantName = merchantName
    )
}
