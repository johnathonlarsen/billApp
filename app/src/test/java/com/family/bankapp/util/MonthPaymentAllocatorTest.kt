package com.family.bankapp.util

import com.family.bankapp.data.entity.AccountEntity
import com.family.bankapp.data.entity.BillEntity
import com.family.bankapp.data.entity.IncomeEntity
import com.family.bankapp.data.entity.PaymentRecordEntity
import com.family.bankapp.data.entity.PlaidTransactionEntity
import com.family.bankapp.data.model.AccountType
import com.family.bankapp.data.model.BillCategory
import com.family.bankapp.data.model.BillRecurrence
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonthPaymentAllocatorTest {

    private val september = YearMonth.of(2026, 9)
    private val today = LocalDate.of(2026, 9, 10)

    @Test
    fun remainingCents_treatsLowerAmountAsPartial() {
        val bill = bill(1, "Rent", 100_000)
        val payment = payment(1, 40_000, BillSchedule.dueDateForYearMonth(bill, september))

        assertEquals(60_000L, BillSchedule.remainingCents(bill, payment))
        assertTrue(BillSchedule.isPartiallyPaid(bill, payment))
        assertFalse(BillSchedule.isFullyPaid(bill, payment))
        assertEquals(100_000L, BillSchedule.reservedAmountForCycle(bill, payment))
    }

    @Test
    fun remainingCents_overpayIsFullyPaidAndRaisesReservedAmount() {
        val bill = bill(1, "Rent", 100_000)
        val payment = payment(1, 125_000, BillSchedule.dueDateForYearMonth(bill, september))

        assertEquals(0L, BillSchedule.remainingCents(bill, payment))
        assertTrue(BillSchedule.isFullyPaid(bill, payment))
        assertFalse(BillSchedule.isPartiallyPaid(bill, payment))
        assertEquals(125_000L, BillSchedule.reservedAmountForCycle(bill, payment))
    }

    @Test
    fun allocate_appliesInDueDateOrderAndLeavesLaterBillsUnpaid() {
        val rent = bill(1, "Rent", 100_000, dueDay = 1)
        val electric = bill(2, "Electric", 20_000, dueDay = 12)
        val internet = bill(3, "Internet", 8_000, dueDay = 18)
        val outstanding = listOf(rent, electric, internet).map { it.toOutstanding() }

        val allocations = MonthPaymentAllocator.allocate(outstanding, 110_000)
        val byName = allocations.associate { it.entry.bill.name to it.newAmountCents }

        assertEquals(2, allocations.size)
        assertEquals(100_000L, byName["Rent"])
        assertEquals(10_000L, byName["Electric"])
        assertFalse(byName.containsKey("Internet"))

        val preview = MonthPaymentAllocator.preview(outstanding, 110_000)
        assertEquals(1, preview.fullyPaidCount)
        assertEquals(1, preview.partialCount)
        assertEquals(1, preview.unpaidCount)
    }

    @Test
    fun allocate_fullRemainingMarksEveryBillPaidAtUsualAmount() {
        val rent = bill(1, "Rent", 100_000, dueDay = 1)
        val electric = bill(2, "Electric", 20_000, dueDay = 12)
        val outstanding = listOf(rent, electric).map { it.toOutstanding() }
        val remaining = outstanding.sumOf { it.remainingCents }

        val allocations = MonthPaymentAllocator.allocate(outstanding, remaining)
        assertEquals(2, allocations.size)
        assertTrue(allocations.all { it.newAmountCents == it.entry.bill.amountCents })
    }

    @Test
    fun allocate_addsOntoExistingPartialThenContinues() {
        val rent = bill(1, "Rent", 100_000, dueDay = 1)
        val electric = bill(2, "Electric", 20_000, dueDay = 12)
        val rentDue = BillSchedule.dueDateForYearMonth(rent, september)
        val outstanding = listOf(
            MonthBillEntry(
                bill = rent,
                dueDate = rentDue,
                isPaid = false,
                payment = payment(rent.id, 40_000, rentDue)
            ),
            electric.toOutstanding()
        )

        val allocations = MonthPaymentAllocator.allocate(outstanding, 70_000)
        val byName = allocations.associate { it.entry.bill.name to it.newAmountCents }

        assertEquals(100_000L, byName["Rent"])
        assertEquals(10_000L, byName["Electric"])
    }

    @Test
    fun allocate_extraBeyondMonthTotalGoesToLastBill() {
        val rent = bill(1, "Rent", 100_000, dueDay = 1)
        val electric = bill(2, "Electric", 20_000, dueDay = 12)
        val outstanding = listOf(rent, electric).map { it.toOutstanding() }

        val allocations = MonthPaymentAllocator.allocate(outstanding, 150_000)
        val byName = allocations.associate { it.entry.bill.name to it.newAmountCents }

        assertEquals(100_000L, byName["Rent"])
        assertEquals(50_000L, byName["Electric"])
    }

    @Test
    fun monthTimeline_partialPaymentDoesNotCountAsPaid() {
        val rent = bill(1, "Rent", 100_000, dueDay = 1)
        val electric = bill(2, "Electric", 20_000, dueDay = 12)
        val rentDue = BillSchedule.dueDateForYearMonth(rent, september)
        val overview = MonthTimeline.build(
            bills = listOf(rent, electric),
            payments = listOf(payment(rent.id, 40_000, rentDue)),
            today = today
        ).first { it.yearMonth == september }

        assertEquals(MonthPillStatus.PARTIAL, overview.status)
        assertEquals(0, overview.paidCount)
        assertEquals(2, overview.totalCount)
        assertEquals(40_000L, overview.totalPaidCents)
        assertEquals(120_000L, overview.totalDueCents)
        assertEquals(80_000L, overview.remainingDueCents)
        assertTrue(overview.bills.first { it.bill.name == "Rent" }.isPartial)
        assertFalse(overview.bills.first { it.bill.name == "Rent" }.isPaid)
        assertEquals(2, overview.outstandingBills.size)
    }

    @Test
    fun freeToSpend_isBankBalanceMinusBillsStillOwed() {
        val food = bill(1, "Food", 48_000, dueDay = 1)
        val fuel = bill(2, "Fuel", 12_000, dueDay = 1)
        val geico = bill(3, "Geico", 6_000, dueDay = 1)
        val october = YearMonth.of(2026, 10)
        val snapshot = FreeToSpendCalculator.calculate(
            accounts = listOf(
                checking(1, "360 Checking ···4440", 38_381),
                checking(2, "360 Checking ···7098", 4_754),
                AccountEntity(
                    id = 3,
                    bankId = 1,
                    name = "360 Money Market",
                    accountType = AccountType.SAVINGS,
                    balanceCents = 8,
                    includeInFreeToSpend = true
                )
            ),
            bills = listOf(food, fuel, geico),
            incomes = listOf(IncomeEntity(name = "Paycheck", amountCents = 150_000, dueDayOfMonth = 1)),
            payments = listOf(
                payment(food.id, 24_000, BillSchedule.dueDateForYearMonth(food, october)),
                payment(fuel.id, 6_000, BillSchedule.dueDateForYearMonth(fuel, october)),
                payment(geico.id, 6_000, BillSchedule.dueDateForYearMonth(geico, october))
            ),
            skips = emptyList(),
            plaidTransactions = emptyList(),
            linkedPlaidTransactionIds = emptySet(),
            includePriorOverdue = false,
            today = LocalDate.of(2026, 10, 14)
        )

        assertEquals(43_143L, snapshot.liquidBalanceCents)
        assertEquals(30_000L, snapshot.remainingBillsThisMonthCents)
        assertEquals(13_143L, snapshot.freeToSpendCents)
        assertEquals(0L, snapshot.pendingSubtractedCents)
    }

    @Test
    fun freeToSpend_subtractsPendingWhileBalanceIsStillCurrent() {
        // Reported 2026-11-11: stored balances are still Plaid current,
        // $475.51 together, with a $21.51 pending debit on ···7098 and
        // $300 of bills still due. Free to spend must be $154.00, and the
        // pending amount must be visible in the snapshot.
        val november = LocalDate.of(2026, 11, 11)
        val rent = bill(1, "Bills", 30_000, dueDay = 1)
        val pending = PlaidTransactionEntity(
            plaidTransactionId = "pending7098",
            bankId = 1,
            plaidAccountId = "acct_7098",
            amountCents = 2_151,
            date = "2026-11-11",
            name = "PENDING DEBIT",
            pending = true
        )
        val creditPending = PlaidTransactionEntity(
            plaidTransactionId = "pendingcard",
            bankId = 1,
            plaidAccountId = "acct_card",
            amountCents = 5_000,
            date = "2026-11-11",
            name = "CARD HOLD",
            pending = true
        )
        val snapshot = FreeToSpendCalculator.calculate(
            accounts = listOf(
                AccountEntity(
                    id = 1,
                    bankId = 1,
                    name = "Other cash",
                    accountType = AccountType.CHECKING,
                    balanceCents = 47_210,
                    currentBalanceCents = 47_210,
                    plaidAccountId = "acct_other"
                ),
                AccountEntity(
                    id = 2,
                    bankId = 1,
                    name = "360 Checking ···7098",
                    accountType = AccountType.CHECKING,
                    balanceCents = 341,
                    currentBalanceCents = 341,
                    plaidAccountId = "acct_7098"
                ),
                AccountEntity(
                    id = 3,
                    bankId = 1,
                    name = "Card",
                    accountType = AccountType.CREDIT,
                    balanceCents = 20_000,
                    currentBalanceCents = 20_000,
                    plaidAccountId = "acct_card"
                )
            ),
            bills = listOf(rent),
            incomes = emptyList(),
            payments = emptyList(),
            skips = emptyList(),
            plaidTransactions = listOf(pending, creditPending),
            linkedPlaidTransactionIds = emptySet(),
            includePriorOverdue = false,
            today = november
        )

        assertEquals(2_151L, snapshot.pendingHoldsCents)
        assertEquals(2_151L, snapshot.pendingSubtractedCents)
        assertEquals(45_400L, snapshot.liquidBalanceCents)
        assertEquals(30_000L, snapshot.remainingBillsThisMonthCents)
        assertEquals(15_400L, snapshot.freeToSpendCents)
    }

    @Test
    fun freeToSpend_doesNotSubtractPendingTwiceWhenBalanceIsAvailable() {
        val snapshot = FreeToSpendCalculator.calculate(
            accounts = listOf(
                AccountEntity(
                    id = 1,
                    bankId = 1,
                    name = "360 Checking ···7098",
                    accountType = AccountType.CHECKING,
                    balanceCents = -1_810,
                    currentBalanceCents = 341,
                    plaidAccountId = "acct_7098"
                )
            ),
            bills = emptyList(),
            incomes = emptyList(),
            payments = emptyList(),
            skips = emptyList(),
            plaidTransactions = listOf(
                PlaidTransactionEntity(
                    plaidTransactionId = "pending7098",
                    bankId = 1,
                    plaidAccountId = "acct_7098",
                    amountCents = 2_151,
                    date = "2026-11-11",
                    name = "PENDING DEBIT",
                    pending = true
                )
            ),
            linkedPlaidTransactionIds = emptySet(),
            includePriorOverdue = false,
            today = LocalDate.of(2026, 11, 11)
        )

        assertEquals(-1_810L, snapshot.liquidBalanceCents)
        assertEquals(-1_810L, snapshot.freeToSpendCents)
        assertEquals(0L, snapshot.pendingSubtractedCents)
        assertEquals(2_151L, snapshot.pendingHoldsCents)
    }

    @Test
    fun freeToSpend_partialLeavesOnlyTheUnpaidRemainder() {
        val rent = bill(1, "Rent", 100_000, dueDay = 1)
        val rentDue = BillSchedule.dueDateForYearMonth(rent, september)
        val snapshot = FreeToSpendCalculator.calculate(
            accounts = emptyList(),
            bills = listOf(rent),
            incomes = listOf(
                IncomeEntity(name = "Paycheck", amountCents = 400_000, dueDayOfMonth = 1)
            ),
            payments = listOf(payment(rent.id, 40_000, rentDue)),
            skips = emptyList(),
            plaidTransactions = emptyList(),
            linkedPlaidTransactionIds = emptySet(),
            includePriorOverdue = false,
            today = today
        )

        assertEquals(100_000L, snapshot.allBillsThisMonthCents)
        assertEquals(60_000L, snapshot.remainingBillsThisMonthCents)
        assertEquals(-60_000L, snapshot.freeToSpendCents)
    }

    @Test
    fun enrich_partialStaysUnpaidWithRemainingBalance() {
        val rent = bill(1, "Rent", 100_000, dueDay = 1)
        val due = BillSchedule.nextDueDate(rent, today)
        val dueInfo = BillSchedule.enrich(
            rent,
            listOf(payment(rent.id, 25_000, due)),
            today
        )

        assertFalse(dueInfo.isPaidThisCycle)
        assertTrue(dueInfo.isPartialThisCycle)
        assertEquals(75_000L, dueInfo.remainingCents)
        assertFalse(dueInfo.isOverdue)
    }

    @Test
    fun pendingDebitCents_isZeroWhenNoneAreDetected() {
        val checking = checking(1, "360 Checking ···4440", 38_381).copy(plaidAccountId = "acct_4440")
        val other = checking(2, "360 Checking ···7098", 341).copy(plaidAccountId = "acct_7098")
        val posted = PlaidTransactionEntity(
            plaidTransactionId = "posted",
            bankId = 1,
            plaidAccountId = "acct_4440",
            amountCents = 500,
            date = "2026-11-11",
            name = "STORE",
            pending = false
        )

        assertEquals(0L, FreeToSpendCalculator.pendingDebitCents(checking, listOf(posted)))
        assertEquals(0L, FreeToSpendCalculator.pendingDebitCents(other, listOf(posted)))
        assertEquals(
            2_151L,
            FreeToSpendCalculator.pendingDebitCents(
                other,
                listOf(
                    posted,
                    PlaidTransactionEntity(
                        plaidTransactionId = "hold",
                        bankId = 1,
                        plaidAccountId = "acct_7098",
                        amountCents = 2_151,
                        date = "2026-11-11",
                        name = "PENDING",
                        pending = true
                    )
                )
            )
        )
    }

    private fun BillEntity.toOutstanding(): MonthBillEntry {
        val due = BillSchedule.dueDateForYearMonth(this, september)
        return MonthBillEntry(
            bill = this,
            dueDate = due,
            isPaid = false,
            payment = null
        )
    }

    private fun checking(id: Long, name: String, balanceCents: Long) = AccountEntity(
        id = id,
        bankId = 1,
        name = name,
        accountType = AccountType.CHECKING,
        balanceCents = balanceCents
    )

    private fun bill(
        id: Long,
        name: String,
        amountCents: Long,
        dueDay: Int = 1
    ) = BillEntity(
        id = id,
        name = name,
        amountCents = amountCents,
        dueDayOfMonth = dueDay,
        recurrence = BillRecurrence.MONTHLY,
        category = BillCategory.HOUSING,
        trackingStartMillis = 0L
    )

    private fun payment(
        billId: Long,
        amountCents: Long,
        due: LocalDate
    ) = PaymentRecordEntity(
        id = billId,
        billId = billId,
        accountId = null,
        amountCents = amountCents,
        cycleDueDateMillis = BillSchedule.toCycleMillis(due)
    )
}
