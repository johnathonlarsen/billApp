package com.family.bankapp.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.family.bankapp.BankAppApplication
import com.family.bankapp.data.repository.OverviewData
import com.family.bankapp.data.settings.SettingsRepository
import com.family.bankapp.util.BillDueInfo
import com.family.bankapp.util.BillSchedule
import com.family.bankapp.util.MonthOverview
import com.family.bankapp.util.MonthTimeline
import java.time.YearMonth
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class BillDueWithLabel(
    val dueInfo: BillDueInfo,
    val payFromLabel: String?
)

data class DashboardState(
    val overview: OverviewData = OverviewData(emptyList(), emptyList(), emptyList(), emptyList()),
    val forecastDays: Int = 14,
    val upcomingBills: List<BillDueWithLabel> = emptyList(),
    val overdueBills: List<BillDueWithLabel> = emptyList(),
    val upcomingTotalCents: Long = 0,
    val overdueTotalCents: Long = 0,
    val currentMonth: MonthOverview? = null,
    val activeBillCount: Int = 0
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as BankAppApplication
    private val repository = app.repository
    private val settings = app.settingsRepository

    val state: StateFlow<DashboardState> = combine(
        repository.observeOverview(),
        settings.forecastDays
    ) { overview, forecastDays ->
        val bills = overview.bills
        val payments = overview.payments

        fun payFromLabel(bill: com.family.bankapp.data.entity.BillEntity): String? {
            val account = bill.linkedAccountId?.let { id -> overview.accounts.find { it.id == id } }
            val bank = account?.let { acc -> overview.banks.find { it.id == acc.bankId } }
            return if (account != null && bank != null) "${bank.name} · ${account.name}" else null
        }

        fun enrich(dueInfo: BillDueInfo) = BillDueWithLabel(dueInfo, payFromLabel(dueInfo.bill))

        val allDue = bills.map { BillSchedule.enrich(it, payments) }
        val upcoming = BillSchedule.upcomingBills(bills, forecastDays, payments).map { enrich(it) }
        val overdue = allDue.filter { it.isOverdue }.sortedBy { it.dueDate }.map { enrich(it) }

        val currentMonth = MonthTimeline.build(bills, payments)
            .find { it.yearMonth == YearMonth.now() }

        DashboardState(
            overview = overview,
            forecastDays = forecastDays,
            upcomingBills = upcoming,
            overdueBills = overdue,
            upcomingTotalCents = upcoming.sumOf { it.dueInfo.bill.amountCents },
            overdueTotalCents = overdue.sumOf { it.dueInfo.bill.amountCents },
            currentMonth = currentMonth,
            activeBillCount = bills.size
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardState())
}
