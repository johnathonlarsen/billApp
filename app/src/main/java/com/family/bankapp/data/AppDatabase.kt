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
import com.family.bankapp.data.dao.PlaidTransactionDao
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
        PlaidTransactionEntity::class
    ],
    version = 4,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bankDao(): BankDao
    abstract fun accountDao(): AccountDao
    abstract fun billDao(): BillDao
    abstract fun paymentRecordDao(): PaymentRecordDao
    abstract fun plaidTransactionDao(): PlaidTransactionDao

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

        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "family_bank.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build().also { instance = it }
            }
        }
    }
}
