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
    /**
     * Spendable balance — Plaid `balance_available` when reported, otherwise
     * `balance_current`. Can be negative on an account with pending holds
     * larger than its current balance (e.g. 360 Checking ···7098 on
     * 2026-11-11: current $3.41, pending $21.51, available −$18.10).
     */
    val balanceCents: Long,
    /** Plaid `balance_current`, kept so the debug report can show pending = current − available. Null for manual accounts. */
    val currentBalanceCents: Long? = null,
    /** Set when imported from Plaid /accounts/get. */
    val plaidAccountId: String? = null,
    val lastUpdatedAt: Long = System.currentTimeMillis(),
    val notes: String = "",
    /** When false, savings balance is excluded from free-to-spend totals. Checking always counts. */
    val includeInFreeToSpend: Boolean = true
)
