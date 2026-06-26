package com.family.bankapp.plaid

import com.family.bankapp.data.repository.BankRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PlaidSyncClient {

    private const val MAX_TRANSACTION_PAGES = 5

    suspend fun syncBank(
        repository: BankRepository,
        bankId: Long,
        itemId: String,
        resetTransactionCursor: Boolean = false
    ): Result<PlaidBankSyncResult> = withContext(Dispatchers.IO) {
        runCatching {
            if (resetTransactionCursor) {
                repository.getBank(bankId)?.let { bank ->
                    repository.updateBank(bank.copy(plaidTransactionsCursor = null))
                }
            }

            val accountsJson = PlaidApiClient.syncAccounts(itemId).getOrThrow()
            val accountsImported = repository.upsertPlaidAccounts(
                bankId,
                accountsJson.toPlaidAccountsSyncResult().accounts
            )

            var cursor = repository.getPlaidTransactionsCursor(bankId)
            var totalAdded = 0
            var totalRemoved = 0
            var hasMore = false
            var pages = 0

            do {
                val txJson = PlaidApiClient.syncTransactions(itemId, cursor).getOrThrow()
                val txResult = txJson.toPlaidTransactionsSyncResult()
                totalAdded += repository.applyPlaidTransactions(
                    bankId = bankId,
                    added = txResult.added,
                    removedIds = txResult.removedIds,
                    nextCursor = txResult.nextCursor
                )
                totalRemoved += txResult.removedIds.size
                cursor = txResult.nextCursor
                hasMore = txResult.hasMore
                pages++
            } while (hasMore && pages < MAX_TRANSACTION_PAGES)

            PlaidBankSyncResult(
                accountsImported = accountsImported,
                transactionsAdded = totalAdded,
                transactionsRemoved = totalRemoved,
                hasMoreTransactions = hasMore
            )
        }
    }
}
