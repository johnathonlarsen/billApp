package com.family.bankapp.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.family.bankapp.BankAppApplication
import com.family.bankapp.data.entity.AccountEntity
import com.family.bankapp.data.entity.BankEntity
import com.family.bankapp.data.model.AccountType
import com.family.bankapp.util.BankColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BankWithAccounts(
    val bank: BankEntity,
    val accounts: List<AccountEntity>
)

class BanksViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as BankAppApplication
    private val repository = app.repository

    val banksWithAccounts = combine(
        repository.observeBanks(),
        repository.observeAccounts()
    ) { banks, accounts ->
        banks.map { bank ->
            BankWithAccounts(
                bank = bank,
                accounts = accounts.filter { it.bankId == bank.id }
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val bankCount = repository.observeBankCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun addBank(name: String, onResult: (Result<Long>) -> Unit) {
        viewModelScope.launch {
            val color = BankColors.colorForIndex(bankCount.value)
            onResult(repository.addBank(name, color))
        }
    }

    fun updateBank(bank: BankEntity) {
        viewModelScope.launch { repository.updateBank(bank) }
    }

    fun deleteBank(bank: BankEntity) {
        viewModelScope.launch { repository.deleteBank(bank) }
    }

    fun addAccount(
        bankId: Long,
        name: String,
        type: AccountType,
        notes: String = ""
    ) {
        viewModelScope.launch {
            repository.addAccount(bankId, name, type, notes = notes)
        }
    }

    fun updateAccount(account: AccountEntity) {
        viewModelScope.launch { repository.updateAccount(account) }
    }

    fun deleteAccount(account: AccountEntity) {
        viewModelScope.launch { repository.deleteAccount(account) }
    }
}
