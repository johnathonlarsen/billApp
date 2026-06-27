package com.family.bankapp.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.family.bankapp.data.entity.AccountEntity
import com.family.bankapp.data.entity.BankEntity
import com.family.bankapp.data.entity.BillEntity
import com.family.bankapp.data.entity.BillCycleSkipEntity
import com.family.bankapp.data.entity.IncomeEntity
import com.family.bankapp.data.entity.PaymentRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BankDao {
    @Query("SELECT * FROM banks ORDER BY sortOrder ASC, name ASC")
    fun observeAll(): Flow<List<BankEntity>>

    @Query("SELECT COUNT(*) FROM banks")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM banks")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM banks WHERE plaidItemId IS NOT NULL")
    fun observePlaidConnectedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM banks WHERE plaidItemId IS NOT NULL")
    suspend fun getPlaidConnectedCount(): Int

    @Query("SELECT * FROM banks WHERE id = :id")
    suspend fun getById(id: Long): BankEntity?

    @Query("SELECT * FROM banks WHERE LOWER(TRIM(name)) = LOWER(TRIM(:name)) LIMIT 1")
    suspend fun getByNameIgnoreCase(name: String): BankEntity?

    @Query("SELECT * FROM banks WHERE plaidItemId = :itemId LIMIT 1")
    suspend fun getByPlaidItemId(itemId: String): BankEntity?

    @Query("SELECT plaidItemId FROM banks WHERE plaidItemId IS NOT NULL")
    suspend fun getAllPlaidItemIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bank: BankEntity): Long

    @Update
    suspend fun update(bank: BankEntity)

    @Delete
    suspend fun delete(bank: BankEntity)
}

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY bankId ASC, name ASC")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE bankId = :bankId ORDER BY name ASC")
    fun observeByBank(bankId: Long): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE bankId = :bankId ORDER BY name ASC")
    suspend fun getByBankSync(bankId: Long): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE bankId = :bankId AND plaidAccountId = :plaidAccountId LIMIT 1")
    suspend fun getByPlaidAccountId(bankId: Long, plaidAccountId: String): AccountEntity?

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: Long): AccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(account: AccountEntity): Long

    @Update
    suspend fun update(account: AccountEntity)

    @Delete
    suspend fun delete(account: AccountEntity)
}

@Dao
interface BillDao {
    @Query("SELECT * FROM bills WHERE isActive = 1 ORDER BY name ASC")
    fun observeActive(): Flow<List<BillEntity>>

    @Query("SELECT * FROM bills ORDER BY name ASC")
    fun observeAll(): Flow<List<BillEntity>>

    @Query("SELECT * FROM bills WHERE isActive = 1 ORDER BY name ASC")
    suspend fun getActiveSync(): List<BillEntity>

    @Query("SELECT * FROM bills WHERE id = :id")
    suspend fun getById(id: Long): BillEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bill: BillEntity): Long

    @Update
    suspend fun update(bill: BillEntity)

    @Delete
    suspend fun delete(bill: BillEntity)
}

@Dao
interface PaymentRecordDao {
    @Query("SELECT * FROM payment_records ORDER BY paidAt DESC")
    fun observeAll(): Flow<List<PaymentRecordEntity>>

    @Query("SELECT * FROM payment_records WHERE billId = :billId ORDER BY paidAt DESC")
    fun observeByBill(billId: Long): Flow<List<PaymentRecordEntity>>

    @Query("SELECT * FROM payment_records WHERE id = :id")
    suspend fun getById(id: Long): PaymentRecordEntity?

    @Query("SELECT * FROM payment_records WHERE billId = :billId AND cycleDueDateMillis = :cycleDueDateMillis LIMIT 1")
    suspend fun getForCycle(billId: Long, cycleDueDateMillis: Long): PaymentRecordEntity?

    @Query("SELECT * FROM payment_records WHERE billId = :billId ORDER BY paidAt DESC LIMIT 1")
    suspend fun getLatestForBill(billId: Long): PaymentRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: PaymentRecordEntity): Long

    @Delete
    suspend fun delete(record: PaymentRecordEntity)
}

@Dao
interface BillCycleSkipDao {
    @Query("SELECT * FROM bill_cycle_skips ORDER BY cycleDueDateMillis DESC")
    fun observeAll(): Flow<List<BillCycleSkipEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(skip: BillCycleSkipEntity): Long

    @Query("DELETE FROM bill_cycle_skips WHERE billId = :billId AND cycleDueDateMillis = :cycleDueDateMillis")
    suspend fun deleteForCycle(billId: Long, cycleDueDateMillis: Long)
}

@Dao
interface IncomeDao {
    @Query("SELECT * FROM income_sources WHERE isActive = 1 ORDER BY name ASC")
    fun observeActive(): Flow<List<IncomeEntity>>

    @Query("SELECT * FROM income_sources WHERE id = :id")
    suspend fun getById(id: Long): IncomeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(income: IncomeEntity): Long

    @Update
    suspend fun update(income: IncomeEntity)

    @Delete
    suspend fun delete(income: IncomeEntity)
}

@Dao
interface PlaidTransactionDao {
    @Query(
        "SELECT * FROM plaid_transactions WHERE bankId = :bankId ORDER BY date DESC, plaidTransactionId DESC LIMIT :limit"
    )
    fun observeByBank(bankId: Long, limit: Int = 50): Flow<List<com.family.bankapp.data.entity.PlaidTransactionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(transactions: List<com.family.bankapp.data.entity.PlaidTransactionEntity>)

    @Query("DELETE FROM plaid_transactions WHERE plaidTransactionId IN (:ids)")
    suspend fun deleteByPlaidIds(ids: List<String>)
}
