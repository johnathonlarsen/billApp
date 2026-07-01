package com.family.bankapp.util

import com.family.bankapp.data.entity.BillEntity
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

object PlaidBillCycle {

    enum class Choice(val monthOffset: Int, val label: String) {
        CURRENT(0, "Current cycle"),
        NEXT(1, "Next cycle")
    }

    fun dueDateForOffset(bill: BillEntity, txDate: LocalDate, monthOffset: Int): LocalDate {
        val month = YearMonth.from(txDate).plusMonths(monthOffset.toLong())
        return BillSchedule.dueDateForYearMonth(bill, month)
    }

    fun describeChoice(bill: BillEntity, txDate: LocalDate, choice: Choice): String {
        val due = dueDateForOffset(bill, txDate, choice.monthOffset)
        val dueLabel = due.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
        return "${choice.label} · due $dueLabel"
    }
}
