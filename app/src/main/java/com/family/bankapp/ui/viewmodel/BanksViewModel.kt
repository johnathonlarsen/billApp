package com.family.bankapp.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.family.bankapp.BankAppApplication
import com.family.bankapp.FamilyAppConfig
import com.family.bankapp.data.entity.AccountEntity
import com.family.bankapp.data.entity.BankEntity
import com.family.bankapp.data.model.AccountType
import com.family.bankapp.plaid.PlaidApiClient
import com.family.bankapp.plaid.PlaidConnectCheck
import com.family.bankapp.plaid.PlaidLimitGuard
import com.family.bankapp.plaid.PlaidBankSyncResult
import com.family.bankapp.plaid.PlaidSyncClient
import com.family.bankapp.sync.SupabaseSharedStateClient
import com.plaid.link.Plaid
import com.plaid.link.PlaidHandler
import com.plaid.link.configuration.LinkTokenConfiguration
import com.family.bankapp.util.BankColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BankWithAccounts(
    val bank: BankEntity,
    val accounts: List<AccountEntity>
)

class BanksViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as BankAppApplication
    private val repository = app.repository
    private val settings = app.settingsRepository

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

    fun checkBeforePlaidConnect(bank: BankEntity, onResult: (PlaidConnectCheck) -> Unit) {
        viewModelScope.launch {
            val limit = settings.plaidItemLimit.first()
            val localCount = repository.getPlaidConnectedCount()
            val supabase = FamilyAppConfig.supabaseConfig()
            val sharedUsage = SupabaseSharedStateClient.fetchPlaidUsage(supabase).getOrNull()
            onResult(
                PlaidLimitGuard.checkBeforeConnect(
                    serverUsage = sharedUsage,
                    localPlaidConnectedCount = localCount,
                    configuredLimit = limit,
                    bankName = bank.name,
                    isReplacingExisting = !bank.plaidItemId.isNullOrBlank()
                )
            )
        }
    }

    fun preparePlaidLink(
        bank: BankEntity,
        onReady: (PlaidHandler) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            PlaidApiClient.createLinkToken(bank.plaidItemId)
                .onSuccess { result ->
                    val config = LinkTokenConfiguration.Builder()
                        .token(result.linkToken)
                        .build()
                    val handler = Plaid.create(getApplication(), config)
                    onReady(handler)
                }
                .onFailure { e ->
                    onError(e.message ?: "Could not start Plaid Link")
                }
        }
    }

    fun completePlaidLink(
        bankId: Long,
        publicToken: String,
        replacingItemId: String?,
        onResult: (Result<PlaidBankSyncResult>) -> Unit
    ) {
        viewModelScope.launch {
            PlaidApiClient.exchangePublicToken(publicToken, replacingItemId)
                .onSuccess { result ->
                    repository.savePlaidConnection(bankId, result.itemId)
                    PlaidSyncClient.syncBank(repository, bankId, result.itemId, resetTransactionCursor = true)
                        .onSuccess { sync -> onResult(Result.success(sync)) }
                        .onFailure { e -> onResult(Result.failure(e)) }
                }
                .onFailure { e ->
                    onResult(Result.failure(e))
                }
        }
    }

    fun syncPlaidBank(bankId: Long, onResult: (Result<PlaidBankSyncResult>) -> Unit) {
        viewModelScope.launch {
            val bank = repository.getBank(bankId)
            val itemId = bank?.plaidItemId
            if (itemId.isNullOrBlank()) {
                onResult(Result.failure(IllegalStateException("Bank is not connected via Plaid")))
                return@launch
            }
            PlaidSyncClient.syncBank(repository, bankId, itemId)
                .onSuccess { onResult(Result.success(it)) }
                .onFailure { e -> onResult(Result.failure(e)) }
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

    fun observePlaidTransactions(bankId: Long): Flow<List<com.family.bankapp.data.entity.PlaidTransactionEntity>> =
        repository.observePlaidTransactionsByBank(bankId)
}
