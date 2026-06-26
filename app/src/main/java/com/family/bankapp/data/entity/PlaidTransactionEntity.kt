package com.family.bankapp.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "plaid_transactions",
    foreignKeys = [
        ForeignKey(
            entity = BankEntity::class,
            parentColumns = ["id"],
            childColumns = ["bankId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("bankId")
    ]
)
data class PlaidTransactionEntity(
    @PrimaryKey val plaidTransactionId: String,
    val bankId: Long,
    val plaidAccountId: String,
    /** Plaid convention: positive = outflow, negative = inflow (stored in cents). */
    val amountCents: Long,
    val date: String,
    val name: String,
    val merchantName: String? = null,
    val pending: Boolean = false,
    val syncedAt: Long = System.currentTimeMillis()
)
