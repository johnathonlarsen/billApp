package com.family.bankapp.util

import com.family.bankapp.data.entity.AccountEntity
import com.family.bankapp.data.entity.BillEntity
import com.family.bankapp.data.entity.PaymentRecordEntity
import com.family.bankapp.data.model.AccountType
import com.family.bankapp.data.model.BillRecurrence
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

object MoneyFormatter {
    private val formatter: NumberFormat = NumberFormat.getCurrencyInstance(Locale.US)

    fun format(cents: Long): String = formatter.format(cents / 100.0)

    fun parse(input: String): Long? {
        val cleaned = input.replace(Regex("[^0-9.-]"), "")
        if (cleaned.isBlank()) return null
        return try {
            (cleaned.toDouble() * 100).toLong()
        } catch (_: NumberFormatException) {
            null
        }
    }
}

object BalanceCalculator {
    fun netBalance(accounts: List<AccountEntity>): Long =
        accounts.sumOf { account ->
            if (account.accountType == AccountType.CREDIT) -account.balanceCents else account.balanceCents
        }

    fun liquidBalance(accounts: List<AccountEntity>): Long =
        accounts.filter { it.accountType != AccountType.CREDIT }.sumOf { it.balanceCents }

    fun creditUsed(accounts: List<AccountEntity>): Long =
        accounts.filter { it.accountType == AccountType.CREDIT }.sumOf { it.balanceCents }
}

data class BillDueInfo(
    val bill: BillEntity,
    val dueDate: LocalDate,
    val daysUntilDue: Long,
    val isOverdue: Boolean,
    val isPaidThisCycle: Boolean,
    val cyclePayment: PaymentRecordEntity? = null
)

object BillSchedule {
    private val monthYearFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")
    private val fullDateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")

    fun toCycleMillis(date: LocalDate): Long =
        date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun fromCycleMillis(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

    fun defaultPaymentYearMonth(bill: BillEntity, today: LocalDate = LocalDate.now()): YearMonth =
        when (bill.recurrence) {
            BillRecurrence.YEARLY -> YearMonth.of(today.year, 1)
            else -> YearMonth.from(today)
        }

    fun dueDateForYearMonth(bill: BillEntity, yearMonth: YearMonth): LocalDate {
        return when (bill.recurrence) {
            BillRecurrence.MONTHLY -> {
                val day = bill.dueDayOfMonth.coerceIn(1, 28)
                    .coerceAtMost(yearMonth.lengthOfMonth())
                yearMonth.atDay(day)
            }
            BillRecurrence.YEARLY -> {
                val anchor = bill.dueDateMillis?.let {
                    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                }
                val month = anchor?.month ?: java.time.Month.JANUARY
                val day = anchor?.dayOfMonth ?: bill.dueDayOfMonth.coerceIn(1, 28)
                LocalDate.of(yearMonth.year, month, day.coerceAtMost(YearMonth.of(yearMonth.year, month).lengthOfMonth()))
            }
            BillRecurrence.WEEKLY -> {
                val anchor = bill.dueDateMillis?.let {
                    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                } ?: yearMonth.atDay(1)
                var candidate = anchor
                while (candidate.year < yearMonth.year ||
                    (candidate.year == yearMonth.year && candidate.monthValue < yearMonth.monthValue)
                ) {
                    candidate = candidate.plusWeeks(1)
                }
                if (candidate.year > yearMonth.year ||
                    (candidate.year == yearMonth.year && candidate.monthValue > yearMonth.monthValue)
                ) {
                    candidate = candidate.minusWeeks(1)
                }
                candidate
            }
            BillRecurrence.BIWEEKLY -> {
                val anchor = bill.dueDateMillis?.let {
                    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                } ?: yearMonth.atDay(1)
                var candidate = anchor
                while (candidate.year < yearMonth.year ||
                    (candidate.year == yearMonth.year && candidate.monthValue < yearMonth.monthValue)
                ) {
                    candidate = candidate.plusWeeks(2)
                }
                if (candidate.year > yearMonth.year ||
                    (candidate.year == yearMonth.year && candidate.monthValue > yearMonth.monthValue)
                ) {
                    candidate = candidate.minusWeeks(2)
                }
                candidate
            }
            BillRecurrence.ONE_TIME -> bill.dueDateMillis?.let {
                Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
            } ?: yearMonth.atDay(1)
        }
    }

    fun formatCycleLabel(bill: BillEntity, cycleDueDate: LocalDate): String = when (bill.recurrence) {
        BillRecurrence.MONTHLY -> cycleDueDate.format(monthYearFormatter)
        BillRecurrence.YEARLY -> cycleDueDate.year.toString()
        BillRecurrence.WEEKLY -> "Week of ${cycleDueDate.format(fullDateFormatter)}"
        BillRecurrence.BIWEEKLY -> "Pay period ${cycleDueDate.format(fullDateFormatter)}"
        BillRecurrence.ONE_TIME -> cycleDueDate.format(fullDateFormatter)
    }

    fun paymentForCycle(
        payments: List<PaymentRecordEntity>,
        billId: Long,
        cycleDueDate: LocalDate
    ): PaymentRecordEntity? {
        val cycleMillis = toCycleMillis(cycleDueDate)
        return payments.filter { it.billId == billId }.find { it.cycleDueDateMillis == cycleMillis }
    }
    fun nextDueDate(bill: BillEntity, today: LocalDate = LocalDate.now()): LocalDate {
        return when (bill.recurrence) {
            BillRecurrence.ONE_TIME -> {
                bill.dueDateMillis?.let {
                    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                } ?: today
            }
            BillRecurrence.WEEKLY -> {
                val anchor = bill.dueDateMillis?.let {
                    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                } ?: today
                var candidate = anchor
                while (candidate.isBefore(today)) {
                    candidate = candidate.plusWeeks(1)
                }
                candidate
            }
            BillRecurrence.BIWEEKLY -> {
                val anchor = bill.dueDateMillis?.let {
                    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                } ?: today
                var candidate = anchor
                while (candidate.isBefore(today)) {
                    candidate = candidate.plusWeeks(2)
                }
                candidate
            }
            BillRecurrence.YEARLY -> {
                val anchor = bill.dueDateMillis?.let {
                    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                } ?: LocalDate.of(today.year, 1, bill.dueDayOfMonth.coerceIn(1, 28))
                var candidate = anchor.withYear(today.year)
                if (candidate.isBefore(today)) candidate = candidate.plusYears(1)
                candidate
            }
            BillRecurrence.MONTHLY -> {
                val day = bill.dueDayOfMonth.coerceIn(1, 28)
                var candidate = LocalDate.of(today.year, today.month, day.coerceAtMost(today.lengthOfMonth()))
                if (candidate.isBefore(today)) {
                    val nextMonth = today.plusMonths(1)
                    candidate = LocalDate.of(
                        nextMonth.year,
                        nextMonth.month,
                        day.coerceAtMost(nextMonth.lengthOfMonth())
                    )
                }
                candidate
            }
        }
    }

    fun isPaidThisCycle(
        bill: BillEntity,
        dueDate: LocalDate,
        payments: List<PaymentRecordEntity> = emptyList()
    ): Boolean {
        if (payments.isNotEmpty()) {
            return paymentForCycle(payments, bill.id, dueDate) != null
        }
        val lastPaid = bill.lastPaidAt ?: return false
        val paidDate = Instant.ofEpochMilli(lastPaid).atZone(ZoneId.systemDefault()).toLocalDate()
        return when (bill.recurrence) {
            BillRecurrence.MONTHLY -> paidDate.year == dueDate.year && paidDate.month == dueDate.month
            BillRecurrence.WEEKLY -> ChronoUnit.WEEKS.between(paidDate, dueDate) == 0L ||
                (paidDate.isBefore(dueDate) && paidDate.plusWeeks(1).isAfter(dueDate))
            BillRecurrence.BIWEEKLY -> ChronoUnit.WEEKS.between(paidDate, dueDate).let { weeks ->
                weeks == 0L || (weeks == 1L && paidDate.plusWeeks(2).isAfter(dueDate))
            } || (paidDate.isBefore(dueDate) && !paidDate.plusWeeks(2).isBefore(dueDate))
            BillRecurrence.YEARLY -> paidDate.year == dueDate.year
            BillRecurrence.ONE_TIME -> true
        }
    }

    fun enrich(
        bill: BillEntity,
        payments: List<PaymentRecordEntity> = emptyList(),
        today: LocalDate = LocalDate.now()
    ): BillDueInfo {
        val dueDate = nextDueDate(bill, today)
        val daysUntil = ChronoUnit.DAYS.between(today, dueDate)
        val cyclePayment = paymentForCycle(payments, bill.id, dueDate)
        val paidThisCycle = cyclePayment != null || isPaidThisCycle(bill, dueDate, payments)
        return BillDueInfo(
            bill = bill,
            dueDate = dueDate,
            daysUntilDue = daysUntil,
            isOverdue = daysUntil < 0 && !paidThisCycle,
            isPaidThisCycle = paidThisCycle,
            cyclePayment = cyclePayment
        )
    }

    fun upcomingBills(
        bills: List<BillEntity>,
        withinDays: Int,
        payments: List<PaymentRecordEntity> = emptyList(),
        today: LocalDate = LocalDate.now()
    ): List<BillDueInfo> =
        bills.map { enrich(it, payments, today) }
            .filter { !it.isPaidThisCycle && it.daysUntilDue in 0..withinDays.toLong() }
            .sortedBy { it.dueDate }

    fun dueSoonTotal(
        bills: List<BillEntity>,
        withinDays: Int,
        payments: List<PaymentRecordEntity> = emptyList()
    ): Long =
        upcomingBills(bills, withinDays, payments).sumOf { it.bill.amountCents }

    fun accountCoverageWarning(
        account: AccountEntity,
        linkedBills: List<BillEntity>,
        withinDays: Int,
        payments: List<PaymentRecordEntity> = emptyList()
    ): Long? {
        val upcoming = upcomingBills(linkedBills, withinDays, payments)
            .filter { it.bill.linkedAccountId == account.id }
        if (upcoming.isEmpty()) return null
        val needed = upcoming.sumOf { it.bill.amountCents }
        val shortfall = needed - account.balanceCents
        return if (shortfall > 0) shortfall else null
    }
}

object BankColors {
    val palette = listOf(
        "#1565C0", "#2E7D32", "#6A1B9A", "#C62828", "#EF6C00", "#00838F"
    )

    fun colorForIndex(index: Int): String = palette[index % palette.size]
}
