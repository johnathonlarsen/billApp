package com.family.bankapp.util

import com.family.bankapp.data.entity.BillEntity
import com.family.bankapp.data.entity.PaymentRecordEntity
import com.family.bankapp.data.model.BillRecurrence
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

enum class MonthPillStatus {
    EMPTY,
    ALL_PAID,
    PARTIAL
}

data class MonthBillEntry(
    val bill: BillEntity,
    val dueDate: LocalDate,
    val isPaid: Boolean,
    val payment: PaymentRecordEntity?
)

data class MonthOverview(
    val yearMonth: YearMonth,
    val status: MonthPillStatus,
    val isPaddingMonth: Boolean,
    val bills: List<MonthBillEntry>,
    val paidCount: Int,
    val totalCount: Int,
    val totalDueCents: Long,
    val totalPaidCents: Long
) {
    val label: String get() = yearMonth.format(DateTimeFormatter.ofPattern("MMM yy"))
    val fullLabel: String get() = yearMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
}

object MonthTimeline {
    fun build(
        bills: List<BillEntity>,
        payments: List<PaymentRecordEntity>,
        today: LocalDate = LocalDate.now()
    ): List<MonthOverview> {
        val current = YearMonth.from(today)
        if (bills.isEmpty()) {
            return listOf(
                current.minusMonths(1),
                current,
                current.plusMonths(1)
            ).map { month ->
                MonthOverview(
                    yearMonth = month,
                    status = MonthPillStatus.EMPTY,
                    isPaddingMonth = true,
                    bills = emptyList(),
                    paidCount = 0,
                    totalCount = 0,
                    totalDueCents = 0,
                    totalPaidCents = 0
                )
            }
        }

        val coreRange = coreDataRange(bills, payments, current)
        val visibleStart = minOf(
            coreRange.start.minusMonths(1),
            current.minusMonths(1)
        )
        val visibleEnd = maxOf(
            coreRange.endInclusive.plusMonths(1),
            current.plusMonths(1)
        )

        val months = mutableListOf<YearMonth>()
        var cursor = visibleStart
        while (!cursor.isAfter(visibleEnd)) {
            months.add(cursor)
            cursor = cursor.plusMonths(1)
        }

        return months.map { month ->
            buildMonthOverview(bills, payments, month, coreRange)
        }
    }

    private fun coreDataRange(
        bills: List<BillEntity>,
        payments: List<PaymentRecordEntity>,
        current: YearMonth
    ): ClosedRange<YearMonth> {
        val dataMonths = mutableSetOf<YearMonth>()

        dataMonths.add(current)
        payments.forEach { payment ->
            dataMonths.add(YearMonth.from(BillSchedule.fromCycleMillis(payment.cycleDueDateMillis)))
        }
        bills.forEach { bill ->
            if (bill.recurrence == BillRecurrence.ONE_TIME) {
                bill.dueDateMillis?.let {
                    dataMonths.add(YearMonth.from(BillSchedule.fromCycleMillis(it)))
                }
            }
        }

        val sorted = dataMonths.sorted()
        return sorted.first()..sorted.last()
    }

    private fun buildMonthOverview(
        bills: List<BillEntity>,
        payments: List<PaymentRecordEntity>,
        yearMonth: YearMonth,
        coreRange: ClosedRange<YearMonth>
    ): MonthOverview {
        val isPadding = yearMonth < coreRange.start || yearMonth > coreRange.endInclusive
        val applicableBills = bills.filter { billAppliesToMonth(it, yearMonth) }

        if (isPadding || applicableBills.isEmpty()) {
            return MonthOverview(
                yearMonth = yearMonth,
                status = MonthPillStatus.EMPTY,
                isPaddingMonth = isPadding || applicableBills.isEmpty(),
                bills = emptyList(),
                paidCount = 0,
                totalCount = 0,
                totalDueCents = 0,
                totalPaidCents = 0
            )
        }

        val entries = applicableBills.map { bill ->
            val dueDate = BillSchedule.dueDateForYearMonth(bill, yearMonth)
            val payment = BillSchedule.paymentForCycle(payments, bill.id, dueDate)
            MonthBillEntry(
                bill = bill,
                dueDate = dueDate,
                isPaid = payment != null,
                payment = payment
            )
        }.sortedBy { it.dueDate }

        val paidCount = entries.count { it.isPaid }
        val totalCount = entries.size
        val status = when {
            totalCount == 0 -> MonthPillStatus.EMPTY
            paidCount == totalCount -> MonthPillStatus.ALL_PAID
            else -> MonthPillStatus.PARTIAL
        }

        return MonthOverview(
            yearMonth = yearMonth,
            status = status,
            isPaddingMonth = false,
            bills = entries,
            paidCount = paidCount,
            totalCount = totalCount,
            totalDueCents = entries.sumOf { it.bill.amountCents },
            totalPaidCents = entries.filter { it.isPaid }.sumOf { it.bill.amountCents }
        )
    }

    fun billAppliesToMonth(bill: BillEntity, yearMonth: YearMonth): Boolean {
        return when (bill.recurrence) {
            BillRecurrence.MONTHLY -> true
            BillRecurrence.YEARLY -> {
                val due = BillSchedule.dueDateForYearMonth(bill, yearMonth)
                YearMonth.from(due) == yearMonth
            }
            BillRecurrence.WEEKLY -> {
                val due = BillSchedule.dueDateForYearMonth(bill, yearMonth)
                YearMonth.from(due) == yearMonth
            }
            BillRecurrence.BIWEEKLY -> {
                val due = BillSchedule.dueDateForYearMonth(bill, yearMonth)
                YearMonth.from(due) == yearMonth
            }
            BillRecurrence.ONE_TIME -> {
                bill.dueDateMillis?.let {
                    YearMonth.from(BillSchedule.fromCycleMillis(it)) == yearMonth
                } ?: false
            }
        }
    }
}
