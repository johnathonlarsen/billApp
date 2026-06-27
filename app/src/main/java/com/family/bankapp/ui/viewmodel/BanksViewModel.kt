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
import com.family.bankapp.plaid.PlaidNameMatcher
import com.family.bankapp.plaid.PlaidRestorableItem
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
            val baseCheck = PlaidLimitGuard.checkBeforeConnect(
                serverUsage = sharedUsage,
                localPlaidConnectedCount = localCount,
                configuredLimit = limit,
                bankName = bank.name,
                isReplacingExisting = !bank.plaidItemId.isNullOrBlank()
            )
            if (!baseCheck.allowed || !bank.plaidItemId.isNullOrBlank()) {
                onResult(baseCheck)
                return@launch
            }

            val unrestored = filterUnclaimedRestorableItems(
                PlaidApiClient.listRestorableItems().getOrElse {
                    onResult(baseCheck)
                    return@launch
                }
            )
            if (unrestored.isNotEmpty()) {
                val names = unrestored.joinToString { it.institutionName }
                onResult(
                    baseCheck.copy(
                        allowed = false,
                        blockReason = buildString {
                            append("Saved Plaid link(s) on the server are not restored on this phone yet")
                            append(" ($names).\n\n")
                            append("Restore them first — Connect is blocked so you do not burn a Trial slot.")
                        }
                    )
                )
                return@launch
            }

            onResult(baseCheck)
        }
    }

    fun fetchRestorablePlaidItems(onResult: (Result<List<PlaidRestorableItem>>) -> Unit) {
        viewModelScope.launch {
            PlaidApiClient.listRestorableItems()
                .map { items -> filterUnclaimedRestorableItems(items) }
                .also { onResult(it) }
        }
    }

    fun restorePlaidLink(
        bankId: Long,
        itemId: String,
        onResult: (Result<PlaidBankSyncResult>) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val bank = repository.getBank(bankId)
                    ?: return@launch onResult(Result.failure(IllegalStateException("Bank not found")))

                if (!bank.plaidItemId.isNullOrBlank()) {
                    onResult(Result.failure(IllegalStateException("This bank is already connected via Plaid")))
                    return@launch
                }

                repository.getBankByPlaidItemId(itemId)?.let { existing ->
                    onResult(
                        Result.failure(
                            IllegalStateException("That link is already assigned to ${existing.name}")
                        )
                    )
                    return@launch
                }

                val restorable = filterUnclaimedRestorableItems(
                    PlaidApiClient.listRestorableItems().getOrElse { e ->
                        onResult(Result.failure(e))
                        return@launch
                    }
                )
                if (restorable.none { it.itemId == itemId }) {
                    onResult(Result.failure(IllegalStateException("Saved Plaid link not found or already in use")))
                    return@launch
                }

                runCatching { repository.savePlaidConnection(bankId, itemId) }
                    .onFailure { e ->
                        onResult(Result.failure(e))
                        return@launch
                    }

                PlaidSyncClient.syncBank(repository, bankId, itemId, resetTransactionCursor = false)
                    .onSuccess { onResult(Result.success(it)) }
                    .onFailure { e -> onResult(Result.failure(e)) }
            } finally {
                app.requestPlaidUsageRefresh()
            }
        }
    }

    private suspend fun filterUnclaimedRestorableItems(
        items: List<PlaidRestorableItem>
    ): List<PlaidRestorableItem> {
        val linkedIds = repository.getLocalPlaidItemIds().toSet()
        return items.filter { it.itemId !in linkedIds }
    }

    fun preparePlaidLink(
        bank: BankEntity,
        onReady: (PlaidHandler) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            val linkedItemIds = repository.getLocalPlaidItemIds()
            PlaidApiClient.createLinkToken(
                replacingItemId = bank.plaidItemId,
                linkedItemIds = linkedItemIds
            )
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
            try {
                val linkedItemIds = repository.getLocalPlaidItemIds()
                val result = PlaidApiClient.exchangePublicToken(
                    publicToken = publicToken,
                    replacingItemId = replacingItemId,
                    linkedItemIds = linkedItemIds
                ).fold(
                    onSuccess = { exchange ->
                        repository.getBankByPlaidItemId(exchange.itemId)?.let { existing ->
                            if (existing.id != bankId) {
                                return@fold Result.failure<PlaidBankSyncResult>(
                                    IllegalStateException(
                                        "This Plaid login is already linked to ${existing.name}. " +
                                            "Use Restore saved link on that bank instead."
                                    )
                                )
                            }
                        }
                        repository.savePlaidConnection(bankId, exchange.itemId)
                        PlaidSyncClient.syncBank(repository, bankId, exchange.itemId, resetTransactionCursor = true)
                    },
                    onFailure = { Result.failure(it) }
                )
                onResult(result)
            } finally {
                app.requestPlaidUsageRefresh()
            }
        }
    }

    fun removePlaidConnection(bankId: Long, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            try {
                val bank = repository.getBank(bankId)
                    ?: return@launch onResult(Result.failure(IllegalStateException("Bank not found")))
                val itemId = bank.plaidItemId
                    ?: return@launch onResult(
                        Result.failure(IllegalStateException("This bank is not connected via Plaid"))
                    )

                PlaidApiClient.removePlaidItem(itemId)
                    .onFailure { e ->
                        onResult(Result.failure(e))
                        return@launch
                    }

                repository.unlinkPlaidLocally(bankId)
                    ?: return@launch onResult(
                        Result.failure(IllegalStateException("Could not update bank on this phone"))
                    )
                onResult(Result.success(Unit))
            } finally {
                app.requestPlaidUsageRefresh()
            }
        }
    }

    fun syncPlaidBank(bankId: Long, onResult: (Result<PlaidBankSyncResult>) -> Unit) {
        viewModelScope.launch {
            try {
                val bank = repository.getBank(bankId)
                val itemId = bank?.plaidItemId
                if (itemId.isNullOrBlank()) {
                    onResult(Result.failure(IllegalStateException("Bank is not connected via Plaid")))
                    return@launch
                }
                PlaidSyncClient.syncBank(repository, bankId, itemId)
                    .onSuccess { onResult(Result.success(it)) }
                    .onFailure { e -> onResult(Result.failure(e)) }
            } finally {
                app.requestPlaidUsageRefresh()
            }
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
        notes: String = "",
        includeInFreeToSpend: Boolean = true
    ) {
        viewModelScope.launch {
            repository.addAccount(bankId, name, type, notes = notes, includeInFreeToSpend = includeInFreeToSpend)
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
