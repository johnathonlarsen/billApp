package com.family.bankapp.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
import com.family.bankapp.data.dao.PlaidTransactionDao
import com.family.bankapp.data.entity.BillCycleSkipEntity
import com.family.bankapp.data.entity.IncomeEntity
import com.family.bankapp.data.entity.PlaidTransactionEntity
import com.family.bankapp.data.model.AccountType
import com.family.bankapp.data.model.BillCategory
import com.family.bankapp.data.model.BillRecurrence
import com.family.bankapp.data.model.ConnectionType

class Converters {
    @TypeConverter fun fromConnectionType(value: ConnectionType) = value.name
    @TypeConverter fun toConnectionType(value: String) = ConnectionType.valueOf(value)

    @TypeConverter fun fromAccountType(value: AccountType) = value.name
    @TypeConverter fun toAccountType(value: String) = AccountType.valueOf(value)

    @TypeConverter fun fromBillRecurrence(value: BillRecurrence) = value.name
    @TypeConverter fun toBillRecurrence(value: String) = BillRecurrence.valueOf(value)

    @TypeConverter fun fromBillCategory(value: BillCategory) = value.name
    @TypeConverter fun toBillCategory(value: String) = BillCategory.valueOf(value)
}

@Database(
    entities = [
        BankEntity::class,
        AccountEntity::class,
        BillEntity::class,
        PaymentRecordEntity::class,
        PlaidTransactionEntity::class,
        IncomeEntity::class,
        BillCycleSkipEntity::class
    ],
    version = 7,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bankDao(): BankDao
    abstract fun accountDao(): AccountDao
    abstract fun billDao(): BillDao
    abstract fun paymentRecordDao(): PaymentRecordDao
    abstract fun plaidTransactionDao(): PlaidTransactionDao
    abstract fun incomeDao(): IncomeDao
    abstract fun billCycleSkipDao(): BillCycleSkipDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE payment_records ADD COLUMN cycleDueDateMillis INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "UPDATE payment_records SET cycleDueDateMillis = paidAt WHERE cycleDueDateMillis = 0"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE banks ADD COLUMN plaidItemId TEXT")
                db.execSQL("ALTER TABLE banks ADD COLUMN plaidAccessToken TEXT")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN plaidAccountId TEXT")
                db.execSQL("ALTER TABLE banks ADD COLUMN plaidTransactionsCursor TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS plaid_transactions (
                        plaidTransactionId TEXT NOT NULL PRIMARY KEY,
                        bankId INTEGER NOT NULL,
                        plaidAccountId TEXT NOT NULL,
                        amountCents INTEGER NOT NULL,
                        date TEXT NOT NULL,
                        name TEXT NOT NULL,
                        merchantName TEXT,
                        pending INTEGER NOT NULL,
                        syncedAt INTEGER NOT NULL,
                        FOREIGN KEY(bankId) REFERENCES banks(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_plaid_transactions_bankId ON plaid_transactions(bankId)")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE accounts ADD COLUMN includeInFreeToSpend INTEGER NOT NULL DEFAULT 1"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS income_sources (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        amountCents INTEGER NOT NULL,
                        dueDayOfMonth INTEGER NOT NULL,
                        dueDateMillis INTEGER,
                        recurrence TEXT NOT NULL,
                        linkedAccountId INTEGER,
                        isActive INTEGER NOT NULL,
                        notes TEXT NOT NULL,
                        FOREIGN KEY(linkedAccountId) REFERENCES accounts(id) ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_income_sources_linkedAccountId ON income_sources(linkedAccountId)"
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val now = System.currentTimeMillis()
                db.execSQL(
                    "ALTER TABLE bills ADD COLUMN trackingStartMillis INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "UPDATE bills SET trackingStartMillis = $now WHERE trackingStartMillis = 0"
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS bill_cycle_skips (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        billId INTEGER NOT NULL,
                        cycleDueDateMillis INTEGER NOT NULL,
                        FOREIGN KEY(billId) REFERENCES bills(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_bill_cycle_skips_billId ON bill_cycle_skips(billId)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_bill_cycle_skips_billId_cycleDueDateMillis " +
                        "ON bill_cycle_skips(billId, cycleDueDateMillis)"
                )
            }
        }

        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "family_bank.db"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7
                    )
                    .build().also { instance = it }
            }
        }
    }
}
