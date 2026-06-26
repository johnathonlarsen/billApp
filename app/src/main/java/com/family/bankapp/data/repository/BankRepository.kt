package com.family.bankapp.data.repository

import com.family.bankapp.data.dao.AccountDao
import com.family.bankapp.data.dao.BankDao
import com.family.bankapp.data.dao.BillDao
import com.family.bankapp.data.dao.PaymentRecordDao
import com.family.bankapp.data.entity.AccountEntity
import com.family.bankapp.data.entity.BankEntity
import com.family.bankapp.data.entity.BillEntity
import com.family.bankapp.data.entity.PaymentRecordEntity
import com.family.bankapp.data.dao.PlaidTransactionDao
import com.family.bankapp.data.entity.PlaidTransactionEntity
import com.family.bankapp.data.model.ConnectionType
import com.family.bankapp.plaid.PlaidAccountSnapshot
import com.family.bankapp.plaid.PlaidTransactionSnapshot
import com.family.bankapp.plaid.mapPlaidAccountType
import com.family.bankapp.util.BillSchedule
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class BankRepository(
    private val bankDao: BankDao,
    private val accountDao: AccountDao,
    private val billDao: BillDao,
    private val paymentRecordDao: PaymentRecordDao,
    private val plaidTransactionDao: PlaidTransactionDao
) {
    companion object {
        const val MAX_BANKS = 3
    }

    fun observeBanks(): Flow<List<BankEntity>> = bankDao.observeAll()
    fun observeBankCount(): Flow<Int> = bankDao.observeCount()
    fun observePlaidConnectedCount(): Flow<Int> = bankDao.observePlaidConnectedCount()
    suspend fun getPlaidConnectedCount(): Int = bankDao.getPlaidConnectedCount()
    fun observeAccounts(): Flow<List<AccountEntity>> = accountDao.observeAll()
    fun observeAccountsByBank(bankId: Long): Flow<List<AccountEntity>> = accountDao.observeByBank(bankId)
    fun observePlaidTransactionsByBank(bankId: Long, limit: Int = 50): Flow<List<PlaidTransactionEntity>> =
        plaidTransactionDao.observeByBank(bankId, limit)
    fun observeActiveBills(): Flow<List<BillEntity>> = billDao.observeActive()
    fun observePaymentHistory(billId: Long): Flow<List<PaymentRecordEntity>> =
        paymentRecordDao.observeByBill(billId)

    fun observeAllPayments(): Flow<List<PaymentRecordEntity>> = paymentRecordDao.observeAll()

    fun observeOverview(): Flow<OverviewData> = combine(
        bankDao.observeAll(),
        accountDao.observeAll(),
        billDao.observeActive(),
        paymentRecordDao.observeAll()
    ) { banks, accounts, bills, payments ->
        OverviewData(banks, accounts, bills, payments)
    }

    suspend fun getBank(id: Long): BankEntity? = bankDao.getById(id)
    suspend fun getAccount(id: Long): AccountEntity? = accountDao.getById(id)
    suspend fun getBill(id: Long): BillEntity? = billDao.getById(id)

    suspend fun getBankCount(): Int = bankDao.getCount()

    suspend fun addBank(name: String, colorHex: String): Result<Long> {
        return try {
            val currentCount = bankDao.getCount()
            if (currentCount >= MAX_BANKS) {
                Result.failure(IllegalStateException("Maximum of $MAX_BANKS banks allowed"))
            } else {
                val id = bankDao.insert(
                    BankEntity(
                        name = name.trim(),
                        colorHex = colorHex,
                        sortOrder = currentCount
                    )
                )
                Result.success(id)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateBank(bank: BankEntity) = bankDao.update(bank)

    suspend fun savePlaidConnection(bankId: Long, itemId: String) {
        val bank = bankDao.getById(bankId) ?: return
        bankDao.update(
            bank.copy(
                plaidItemId = itemId,
                plaidAccessToken = null,
                connectionType = ConnectionType.CONNECTED,
                lastSyncedAt = System.currentTimeMillis()
            )
        )
    }
    suspend fun deleteBank(bank: BankEntity) = bankDao.delete(bank)

    suspend fun markBankConnected(bankId: Long) {
        val bank = bankDao.getById(bankId) ?: return
        bankDao.update(
            bank.copy(
                connectionType = ConnectionType.CONNECTED,
                lastSyncedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun upsertPlaidAccounts(bankId: Long, snapshots: List<PlaidAccountSnapshot>): Int {
        var count = 0
        val now = System.currentTimeMillis()
        snapshots.forEach { snapshot ->
            val existing = accountDao.getByPlaidAccountId(bankId, snapshot.accountId)
            if (existing != null) {
                accountDao.update(
                    existing.copy(
                        name = snapshot.name,
                        accountType = mapPlaidAccountType(snapshot.subtype, snapshot.type),
                        balanceCents = snapshot.balanceCents,
                        lastUpdatedAt = now
                    )
                )
            } else {
                accountDao.insert(
                    AccountEntity(
                        bankId = bankId,
                        name = snapshot.name,
                        accountType = mapPlaidAccountType(snapshot.subtype, snapshot.type),
                        balanceCents = snapshot.balanceCents,
                        plaidAccountId = snapshot.accountId,
                        lastUpdatedAt = now
                    )
                )
            }
            count++
        }
        val bank = bankDao.getById(bankId) ?: return count
        bankDao.update(bank.copy(lastSyncedAt = now))
        return count
    }

    suspend fun applyPlaidTransactions(
        bankId: Long,
        added: List<PlaidTransactionSnapshot>,
        removedIds: List<String>,
        nextCursor: String?
    ): Int {
        val now = System.currentTimeMillis()
        if (removedIds.isNotEmpty()) {
            plaidTransactionDao.deleteByPlaidIds(removedIds)
        }
        if (added.isNotEmpty()) {
            plaidTransactionDao.upsertAll(
                added.map { tx ->
                    PlaidTransactionEntity(
                        plaidTransactionId = tx.transactionId,
                        bankId = bankId,
                        plaidAccountId = tx.accountId,
                        amountCents = tx.amountCents,
                        date = tx.date,
                        name = tx.name,
                        merchantName = tx.merchantName,
                        pending = tx.pending,
                        syncedAt = now
                    )
                }
            )
        }
        val bank = bankDao.getById(bankId) ?: return added.size
        bankDao.update(
            bank.copy(
                plaidTransactionsCursor = nextCursor,
                lastSyncedAt = now
            )
        )
        return added.size
    }

    suspend fun getPlaidTransactionsCursor(bankId: Long): String? =
        bankDao.getById(bankId)?.plaidTransactionsCursor

    suspend fun syncBankBalances(bankId: Long, accountUpdates: Map<Long, Long>) {
        accountUpdates.forEach { (accountId, balanceCents) ->
            val account = accountDao.getById(accountId) ?: return@forEach
            if (account.bankId == bankId) {
                accountDao.update(
                    account.copy(
                        balanceCents = balanceCents,
                        lastUpdatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
        val bank = bankDao.getById(bankId) ?: return
        bankDao.update(bank.copy(lastSyncedAt = System.currentTimeMillis()))
    }

    suspend fun addAccount(
        bankId: Long,
        name: String,
        accountType: com.family.bankapp.data.model.AccountType,
        notes: String = ""
    ): Long = accountDao.insert(
        AccountEntity(
            bankId = bankId,
            name = name.trim(),
            accountType = accountType,
            balanceCents = 0,
            notes = notes.trim()
        )
    )

    suspend fun updateAccount(account: AccountEntity) = accountDao.update(account)
    suspend fun deleteAccount(account: AccountEntity) = accountDao.delete(account)

    suspend fun addBill(bill: BillEntity): Long = billDao.insert(bill)
    suspend fun addBills(bills: List<BillEntity>): Int {
        bills.forEach { billDao.insert(it) }
        return bills.size
    }
    suspend fun updateBill(bill: BillEntity) = billDao.update(bill)
    suspend fun deleteBill(bill: BillEntity) = billDao.delete(bill)

    suspend fun markBillPaid(
        bill: BillEntity,
        accountId: Long?,
        cycleDueDate: LocalDate,
        amountCents: Long = bill.amountCents,
        note: String = ""
    ) {
        val cycleMillis = BillSchedule.toCycleMillis(cycleDueDate)
        paymentRecordDao.getForCycle(bill.id, cycleMillis)?.let { existing ->
            undoPaymentRecord(existing)
        }

        val now = System.currentTimeMillis()
        paymentRecordDao.insert(
            PaymentRecordEntity(
                billId = bill.id,
                accountId = accountId,
                amountCents = amountCents,
                paidAt = now,
                cycleDueDateMillis = cycleMillis,
                note = note
            )
        )
        billDao.update(bill.copy(lastPaidAt = now))
    }

    suspend fun undoBillPayment(paymentId: Long) {
        val record = paymentRecordDao.getById(paymentId) ?: return
        paymentRecordDao.delete(record)
        val bill = billDao.getById(record.billId) ?: return
        val latest = paymentRecordDao.getLatestForBill(bill.id)
        billDao.update(bill.copy(lastPaidAt = latest?.paidAt))
    }

    private suspend fun undoPaymentRecord(record: PaymentRecordEntity) {
        paymentRecordDao.delete(record)
    }
}

data class OverviewData(
    val banks: List<BankEntity>,
    val accounts: List<AccountEntity>,
    val bills: List<BillEntity>,
    val payments: List<PaymentRecordEntity> = emptyList()
)
