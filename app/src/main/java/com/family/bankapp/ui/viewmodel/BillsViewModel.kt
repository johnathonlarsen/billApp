package com.family.bankapp.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.family.bankapp.BankAppApplication
import com.family.bankapp.data.entity.BillEntity
import com.family.bankapp.data.entity.PaymentRecordEntity
import com.family.bankapp.data.model.BillCategory
import com.family.bankapp.data.model.BillRecurrence
import com.family.bankapp.util.BillCsvImportResult
import com.family.bankapp.util.BillCsvImporter
import com.family.bankapp.util.BillDueInfo
import com.family.bankapp.util.BillSchedule
import com.family.bankapp.util.MonthOverview
import com.family.bankapp.util.MonthTimeline
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BillListItem(
    val dueInfo: BillDueInfo,
    val linkedAccountLabel: String?,
    val billPayments: List<PaymentRecordEntity> = emptyList()
)

class BillsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as BankAppApplication
    private val repository = app.repository

    val bills = combine(
        repository.observeActiveBills(),
        repository.observeAccounts(),
        repository.observeBanks(),
        repository.observeAllPayments()
    ) { bills, accounts, banks, payments ->
        bills.map { bill ->
            val account = bill.linkedAccountId?.let { id -> accounts.find { it.id == id } }
            val bank = account?.let { acc -> banks.find { it.id == acc.bankId } }
            val label = if (account != null && bank != null) {
                "${bank.name} · ${account.name}"
            } else null
            val billPayments = payments.filter { it.billId == bill.id }
            BillListItem(
                dueInfo = BillSchedule.enrich(bill, billPayments),
                linkedAccountLabel = label,
                billPayments = billPayments
            )
        }.sortedWith(
            compareBy<BillListItem> { it.dueInfo.isPaidThisCycle }
                .thenBy { it.dueInfo.dueDate }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val monthTimeline = combine(
        repository.observeActiveBills(),
        repository.observeAllPayments(),
        repository.observeBillCycleSkips()
    ) { bills, payments, skips ->
        MonthTimeline.build(bills, payments, skips)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun billListItemFor(billId: Long, billItems: List<BillListItem>): BillListItem? =
        billItems.find { it.dueInfo.bill.id == billId }

    fun saveBill(
        id: Long = 0,
        name: String,
        amountCents: Long,
        recurrence: BillRecurrence,
        category: BillCategory,
        dueDayOfMonth: Int,
        dueDateMillis: Long?,
        linkedAccountId: Long?,
        reminderDaysBefore: Int,
        notes: String
    ) {
        viewModelScope.launch {
            val existing = if (id != 0L) repository.getBill(id) else null
            val bill = BillEntity(
                id = id,
                name = name.trim(),
                amountCents = amountCents,
                recurrence = recurrence,
                category = category,
                dueDayOfMonth = dueDayOfMonth,
                dueDateMillis = dueDateMillis,
                linkedAccountId = linkedAccountId,
                reminderDaysBefore = reminderDaysBefore,
                notes = notes.trim(),
                trackingStartMillis = existing?.trackingStartMillis ?: System.currentTimeMillis()
            )
            if (id == 0L) repository.addBill(bill) else repository.updateBill(bill)
        }
    }

    fun markPaid(bill: BillEntity, accountId: Long?, cycleDueDate: LocalDate) {
        viewModelScope.launch {
            repository.markBillPaid(bill, accountId, cycleDueDate)
        }
    }

    fun undoPayment(paymentId: Long) {
        viewModelScope.launch {
            repository.undoBillPayment(paymentId)
        }
    }

    fun deleteBill(bill: BillEntity) {
        viewModelScope.launch { repository.deleteBill(bill) }
    }

    fun skipBillCycle(billId: Long, cycleDueDate: LocalDate) {
        viewModelScope.launch {
            repository.skipBillCycle(billId, cycleDueDate)
        }
    }

    fun deactivateBill(bill: BillEntity) {
        viewModelScope.launch { repository.updateBill(bill.copy(isActive = false)) }
    }

    fun importFromCsv(
        csvText: String,
        onResult: (BillCsvImportResult) -> Unit
    ) {
        viewModelScope.launch {
            val banks = repository.observeBanks().first()
            val accounts = repository.observeAccounts().first()
            val result = BillCsvImporter.parse(csvText, banks, accounts)
            if (result.imported.isNotEmpty()) {
                repository.addBills(result.imported)
            }
            onResult(result)
        }
    }

    fun exportToCsv(onReady: (String) -> Unit) {
        viewModelScope.launch {
            val bills = repository.observeActiveBills().first()
            val banks = repository.observeBanks().first()
            val accounts = repository.observeAccounts().first()
            onReady(BillCsvImporter.export(bills, banks, accounts))
        }
    }
}
