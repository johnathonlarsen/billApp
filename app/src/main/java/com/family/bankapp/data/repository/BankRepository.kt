package com.family.bankapp.data.repository

import com.family.bankapp.data.dao.AccountDao
import com.family.bankapp.data.dao.BankDao
import com.family.bankapp.data.dao.BillDao
import com.family.bankapp.data.dao.PaymentRecordDao
import com.family.bankapp.data.entity.AccountEntity
import com.family.bankapp.data.entity.BankEntity
import com.family.bankapp.data.entity.BillEntity
import com.family.bankapp.data.entity.PaymentRecordEntity
import com.family.bankapp.data.dao.BillCycleSkipDao
import com.family.bankapp.data.dao.IncomeDao
import com.family.bankapp.data.dao.PlaidPaymentLinkDao
import com.family.bankapp.data.dao.PlaidTransactionDao
import com.family.bankapp.data.entity.BillCycleSkipEntity
import com.family.bankapp.data.entity.IncomeEntity
import com.family.bankapp.data.entity.PlaidPaymentLinkEntity
import com.family.bankapp.data.entity.PlaidTransactionEntity
import com.family.bankapp.data.model.ConnectionType
import com.family.bankapp.plaid.PlaidAccountSnapshot
import com.family.bankapp.plaid.PlaidTransactionSnapshot
import com.family.bankapp.plaid.mapPlaidAccountType
import com.family.bankapp.util.BillSchedule
import com.family.bankapp.util.BillTransactionMatcher
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class BankRepository(
    private val bankDao: BankDao,
    private val accountDao: AccountDao,
    private val billDao: BillDao,
    private val paymentRecordDao: PaymentRecordDao,
    private val plaidTransactionDao: PlaidTransactionDao,
    private val incomeDao: IncomeDao,
    private val billCycleSkipDao: BillCycleSkipDao,
    private val plaidPaymentLinkDao: PlaidPaymentLinkDao
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
    fun observePlaidTransactionsByBank(bankId: Long): Flow<List<PlaidTransactionEntity>> =
        plaidTransactionDao.observeByBank(bankId)

    fun observePlaidPaymentLinks(): Flow<List<PlaidPaymentLinkEntity>> =
        plaidPaymentLinkDao.observeAll()

    fun observeActiveBills(): Flow<List<BillEntity>> = billDao.observeActive()
    fun observePaymentHistory(billId: Long): Flow<List<PaymentRecordEntity>> =
        paymentRecordDao.observeByBill(billId)

    fun observeAllPayments(): Flow<List<PaymentRecordEntity>> = paymentRecordDao.observeAll()
    fun observeActiveIncomes(): Flow<List<IncomeEntity>> = incomeDao.observeActive()
    fun observeBillCycleSkips(): Flow<List<BillCycleSkipEntity>> = billCycleSkipDao.observeAll()

    fun observeOverview(): Flow<OverviewData> = combine(
        combine(
            bankDao.observeAll(),
            accountDao.observeAll(),
            billDao.observeActive()
        ) { banks, accounts, bills -> Triple(banks, accounts, bills) },
        combine(
            paymentRecordDao.observeAll(),
            incomeDao.observeActive(),
            billCycleSkipDao.observeAll()
        ) { payments, incomes, skips -> Triple(payments, incomes, skips) },
        combine(
            plaidTransactionDao.observeAll(),
            plaidPaymentLinkDao.observeLinkedTransactionIds()
        ) { transactions, linkedIds -> Pair(transactions, linkedIds.toSet()) }
    ) { (banks, accounts, bills), (payments, incomes, skips), (transactions, linkedIds) ->
        OverviewData(
            banks = banks,
            accounts = accounts,
            bills = bills,
            payments = payments,
            incomes = incomes,
            billCycleSkips = skips,
            plaidTransactions = transactions,
            linkedPlaidTransactionIds = linkedIds
        )
    }

    suspend fun getBank(id: Long): BankEntity? = bankDao.getById(id)
    suspend fun getBankByName(name: String): BankEntity? = bankDao.getByNameIgnoreCase(name.trim())
    suspend fun getBankByPlaidItemId(itemId: String): BankEntity? = bankDao.getByPlaidItemId(itemId)
    suspend fun getLocalPlaidItemIds(): List<String> = bankDao.getAllPlaidItemIds()
    suspend fun getAccount(id: Long): AccountEntity? = accountDao.getById(id)
    suspend fun getBill(id: Long): BillEntity? = billDao.getById(id)
    suspend fun getIncome(id: Long): IncomeEntity? = incomeDao.getById(id)

    suspend fun getBankCount(): Int = bankDao.getCount()

    suspend fun addBank(name: String, colorHex: String): Result<Long> {
        return try {
            val trimmed = name.trim()
            if (trimmed.isBlank()) {
                return Result.failure(IllegalStateException("Bank name is required"))
            }
            bankDao.getByNameIgnoreCase(trimmed)?.let { existing ->
                return Result.failure(
                    IllegalStateException(
                        "\"${existing.name}\" is already in your bank list. Open it and use Restore saved link if Plaid was disconnected."
                    )
                )
            }
            val currentCount = bankDao.getCount()
            if (currentCount >= MAX_BANKS) {
                Result.failure(IllegalStateException("Maximum of $MAX_BANKS banks allowed"))
            } else {
                val id = bankDao.insert(
                    BankEntity(
                        name = trimmed,
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
        getBankByPlaidItemId(itemId)?.let { existing ->
            if (existing.id != bankId) {
                error("This Plaid link is already assigned to ${existing.name}")
            }
        }
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

    /** Unlinks Plaid on this phone after the server connection is removed. */
    suspend fun unlinkPlaidLocally(bankId: Long): String? {
        val bank = bankDao.getById(bankId) ?: return null
        val itemId = bank.plaidItemId ?: return null
        bankDao.update(
            bank.copy(
                plaidItemId = null,
                plaidTransactionsCursor = null,
                connectionType = ConnectionType.MANUAL
            )
        )
        return itemId
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
        notes: String = "",
        includeInFreeToSpend: Boolean = true
    ): Long = accountDao.insert(
        AccountEntity(
            bankId = bankId,
            name = name.trim(),
            accountType = accountType,
            balanceCents = 0,
            notes = notes.trim(),
            includeInFreeToSpend = if (accountType == com.family.bankapp.data.model.AccountType.SAVINGS) {
                includeInFreeToSpend
            } else {
                true
            }
        )
    )

    suspend fun updateAccount(account: AccountEntity) = accountDao.update(account)
    suspend fun deleteAccount(account: AccountEntity) = accountDao.delete(account)

    suspend fun addBill(bill: BillEntity): Long = billDao.insert(bill)

    suspend fun createBillFromPlaidTransaction(
        bill: BillEntity,
        tx: PlaidTransactionEntity,
        cycleMonthOffset: Int
    ): Long {
        val pattern = BillTransactionMatcher.matchPatternFromTransaction(tx)
        val id = billDao.insert(
            bill.copy(
                plaidMatchPattern = pattern,
                plaidAutoMarkPaid = true,
                plaidCycleMonthOffset = cycleMonthOffset
            )
        )
        val saved = billDao.getById(id) ?: return id
        if (!tx.pending) {
            markBillPaidFromTransaction(saved, tx, accountDao.getAllSync())
        } else {
            plaidPaymentLinkDao.insert(
                PlaidPaymentLinkEntity(
                    plaidTransactionId = tx.plaidTransactionId,
                    billId = id
                )
            )
        }
        return id
    }

    suspend fun addBills(bills: List<BillEntity>): Int {
        bills.forEach { billDao.insert(it) }
        return bills.size
    }
    suspend fun updateBill(bill: BillEntity) = billDao.update(bill)
    suspend fun deleteBill(bill: BillEntity) = billDao.delete(bill)

    suspend fun skipBillCycle(billId: Long, cycleDueDate: LocalDate) {
        billCycleSkipDao.insert(
            BillCycleSkipEntity(
                billId = billId,
                cycleDueDateMillis = BillSchedule.toCycleMillis(cycleDueDate)
            )
        )
    }

    suspend fun addIncome(income: IncomeEntity): Long = incomeDao.insert(income)
    suspend fun updateIncome(income: IncomeEntity) = incomeDao.update(income)
    suspend fun deleteIncome(income: IncomeEntity) = incomeDao.delete(income)

    suspend fun linkBillToTransaction(
        bill: BillEntity,
        tx: PlaidTransactionEntity,
        cycleMonthOffset: Int,
        markThisCyclePaid: Boolean = true
    ): BillEntity {
        val accounts = accountDao.getAllSync()
        val pattern = BillTransactionMatcher.matchPatternFromTransaction(tx)
        val linkedAccount = accounts.find { it.plaidAccountId == tx.plaidAccountId }?.id
        val updated = bill.copy(
            plaidMatchPattern = pattern,
            plaidAutoMarkPaid = true,
            plaidCycleMonthOffset = cycleMonthOffset,
            linkedAccountId = linkedAccount ?: bill.linkedAccountId
        )
        billDao.update(updated)
        if (markThisCyclePaid && !tx.pending) {
            markBillPaidFromTransaction(updated, tx, accounts)
        } else {
            plaidPaymentLinkDao.insert(
                PlaidPaymentLinkEntity(
                    plaidTransactionId = tx.plaidTransactionId,
                    billId = bill.id
                )
            )
        }
        return updated
    }

    suspend fun updateBillFromTransaction(
        bill: BillEntity,
        tx: PlaidTransactionEntity,
        cycleMonthOffset: Int
    ): BillEntity {
        val accounts = accountDao.getAllSync()
        val dueDay = runCatching { LocalDate.parse(tx.date).dayOfMonth }.getOrDefault(bill.dueDayOfMonth)
        val linkedAccount = accounts.find { it.plaidAccountId == tx.plaidAccountId }?.id
        val displayName = tx.merchantName?.takeIf { it.isNotBlank() } ?: tx.name
        val updated = bill.copy(
            name = displayName,
            amountCents = tx.amountCents,
            dueDayOfMonth = dueDay.coerceIn(1, 28),
            linkedAccountId = linkedAccount ?: bill.linkedAccountId,
            plaidMatchPattern = BillTransactionMatcher.matchPatternFromTransaction(tx),
            plaidAutoMarkPaid = true,
            plaidCycleMonthOffset = cycleMonthOffset
        )
        billDao.update(updated)
        if (!tx.pending) {
            markBillPaidFromTransaction(updated, tx, accounts)
        } else {
            plaidPaymentLinkDao.insert(
                PlaidPaymentLinkEntity(
                    plaidTransactionId = tx.plaidTransactionId,
                    billId = bill.id
                )
            )
        }
        return updated
    }

    suspend fun unlinkBillFromTransaction(tx: PlaidTransactionEntity) {
        val link = plaidPaymentLinkDao.getByTransactionId(tx.plaidTransactionId)
        val paymentId = link?.paymentRecordId
            ?: paymentRecordDao.getByPlaidTransactionId(tx.plaidTransactionId)?.id
        if (paymentId != null) {
            undoBillPayment(paymentId)
        }
        plaidPaymentLinkDao.deleteByTransactionId(tx.plaidTransactionId)
    }

    suspend fun applyAutoBillPaymentsForBank(bankId: Long): Int {
        val bills = billDao.getActiveSync()
            .filter {
                it.plaidAutoMarkPaid &&
                    !it.plaidMatchPattern.isNullOrBlank() &&
                    it.plaidCycleMonthOffset != null
            }
        if (bills.isEmpty()) return 0

        val accounts = accountDao.getAllSync()
        val payments = paymentRecordDao.getAllSync()
        val skips = billCycleSkipDao.getAllSync()
        val linkedIds = plaidPaymentLinkDao.getAllLinkedTransactionIds().toSet()
        val transactions = plaidTransactionDao.getByBankSync(bankId)
            .filter { it.amountCents > 0 && !it.pending && it.plaidTransactionId !in linkedIds }

        var applied = 0
        for (tx in transactions) {
            val matches = bills.filter {
                BillTransactionMatcher.transactionMatchesBill(tx, it, accounts, payments, skips)
            }
            if (matches.size != 1) continue
            markBillPaidFromTransaction(matches.single(), tx, accounts)
            applied++
        }
        return applied
    }

    private suspend fun markBillPaidFromTransaction(
        bill: BillEntity,
        tx: PlaidTransactionEntity,
        accounts: List<AccountEntity>
    ) {
        val existingLink = plaidPaymentLinkDao.getByTransactionId(tx.plaidTransactionId)
        if (existingLink?.paymentRecordId != null) return

        val txDate = BillTransactionMatcher.transactionDate(tx) ?: LocalDate.now()
        val skips = billCycleSkipDao.getAllSync()
        val payments = paymentRecordDao.getAllSync()
        val cycleDue = BillTransactionMatcher.resolvePaymentCycle(bill, txDate, skips, payments)
            ?: return
        val cycleMillis = BillSchedule.toCycleMillis(cycleDue)
        if (paymentRecordDao.getForCycle(bill.id, cycleMillis) != null) {
            plaidPaymentLinkDao.insert(
                PlaidPaymentLinkEntity(
                    plaidTransactionId = tx.plaidTransactionId,
                    billId = bill.id
                )
            )
            return
        }

        val accountId = accounts.find { it.plaidAccountId == tx.plaidAccountId }?.id ?: bill.linkedAccountId
        val paidAt = BillTransactionMatcher.paidAtMillisFromTransaction(tx)
        val recordId = paymentRecordDao.insert(
            PaymentRecordEntity(
                billId = bill.id,
                accountId = accountId,
                amountCents = tx.amountCents,
                paidAt = paidAt,
                cycleDueDateMillis = cycleMillis,
                note = "Auto · Plaid: ${tx.name}",
                plaidTransactionId = tx.plaidTransactionId
            )
        )
        billDao.update(bill.copy(lastPaidAt = paidAt))
        plaidPaymentLinkDao.insert(
            PlaidPaymentLinkEntity(
                plaidTransactionId = tx.plaidTransactionId,
                billId = bill.id,
                paymentRecordId = recordId
            )
        )
    }

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

    suspend fun updateBillPayment(
        paymentId: Long,
        accountId: Long?,
        amountCents: Long,
        paidAtMillis: Long
    ) {
        val existing = paymentRecordDao.getById(paymentId) ?: return
        paymentRecordDao.update(
            existing.copy(
                accountId = accountId,
                amountCents = amountCents,
                paidAt = paidAtMillis
            )
        )
        val bill = billDao.getById(existing.billId) ?: return
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
    val payments: List<PaymentRecordEntity> = emptyList(),
    val incomes: List<IncomeEntity> = emptyList(),
    val billCycleSkips: List<BillCycleSkipEntity> = emptyList(),
    val plaidTransactions: List<PlaidTransactionEntity> = emptyList(),
    val linkedPlaidTransactionIds: Set<String> = emptySet()
)
