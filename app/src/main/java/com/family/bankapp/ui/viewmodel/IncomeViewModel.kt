package com.family.bankapp.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.family.bankapp.BankAppApplication
import com.family.bankapp.data.entity.IncomeEntity
import com.family.bankapp.data.model.BillRecurrence
import com.family.bankapp.util.RecurrenceNormalizer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class IncomeListItem(
    val income: IncomeEntity,
    val monthlyEquivalentCents: Long,
    val linkedAccountLabel: String?
)

class IncomeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as BankAppApplication
    private val repository = app.repository

    val incomes = combine(
        repository.observeActiveIncomes(),
        repository.observeAccounts(),
        repository.observeBanks()
    ) { incomeList, accounts, banks ->
        incomeList.map { income ->
            val account = income.linkedAccountId?.let { id -> accounts.find { it.id == id } }
            val bank = account?.let { acc -> banks.find { it.id == acc.bankId } }
            val label = if (account != null && bank != null) {
                "${bank.name} · ${account.name}"
            } else {
                null
            }
            IncomeListItem(
                income = income,
                monthlyEquivalentCents = RecurrenceNormalizer.toMonthlyCents(
                    income.amountCents,
                    income.recurrence
                ),
                linkedAccountLabel = label
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun saveIncome(
        id: Long = 0,
        name: String,
        amountCents: Long,
        recurrence: BillRecurrence,
        dueDayOfMonth: Int,
        dueDateMillis: Long?,
        linkedAccountId: Long?,
        notes: String
    ) {
        viewModelScope.launch {
            val income = IncomeEntity(
                id = id,
                name = name.trim(),
                amountCents = amountCents,
                recurrence = recurrence,
                dueDayOfMonth = dueDayOfMonth,
                dueDateMillis = dueDateMillis,
                linkedAccountId = linkedAccountId,
                notes = notes.trim()
            )
            if (id == 0L) repository.addIncome(income) else repository.updateIncome(income)
        }
    }

    fun deleteIncome(income: IncomeEntity) {
        viewModelScope.launch { repository.deleteIncome(income) }
    }
}
