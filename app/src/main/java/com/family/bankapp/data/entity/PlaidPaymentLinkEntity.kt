package com.family.bankapp.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "plaid_payment_links",
    primaryKeys = ["plaidTransactionId"],
    foreignKeys = [
        ForeignKey(
            entity = BillEntity::class,
            parentColumns = ["id"],
            childColumns = ["billId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("billId")]
)
data class PlaidPaymentLinkEntity(
    val plaidTransactionId: String,
    val billId: Long,
    val paymentRecordId: Long? = null,
    val linkedAt: Long = System.currentTimeMillis()
)
