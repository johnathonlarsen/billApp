package com.family.bankapp.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.family.bankapp.data.model.AccountType

@Entity(
    tableName = "accounts",
    foreignKeys = [
        ForeignKey(
            entity = BankEntity::class,
            parentColumns = ["id"],
            childColumns = ["bankId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bankId")]
)
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bankId: Long,
    val name: String,
    val accountType: AccountType,
    val balanceCents: Long,
    /** Set when imported from Plaid /accounts/get. */
    val plaidAccountId: String? = null,
    val lastUpdatedAt: Long = System.currentTimeMillis(),
    val notes: String = "",
    /** When false, savings balance is excluded from free-to-spend totals. Checking always counts. */
    val includeInFreeToSpend: Boolean = true
)
