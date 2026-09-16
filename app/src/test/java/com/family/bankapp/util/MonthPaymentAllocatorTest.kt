package com.family.bankapp.util

import com.family.bankapp.data.entity.BillEntity
import com.family.bankapp.data.entity.IncomeEntity
import com.family.bankapp.data.entity.PaymentRecordEntity
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
    fun freeToSpend_partialStillReservesUsualBillAmount() {
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
        assertEquals(300_000L, snapshot.freeToSpendCents)
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

    private fun BillEntity.toOutstanding(): MonthBillEntry {
        val due = BillSchedule.dueDateForYearMonth(this, september)
        return MonthBillEntry(
            bill = this,
            dueDate = due,
            isPaid = false,
            payment = null
        )
    }

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
