package com.family.bankapp.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.family.bankapp.data.model.BillCategory
import com.family.bankapp.data.model.BillRecurrence

@Entity(
    tableName = "bills",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["linkedAccountId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("linkedAccountId")]
)
data class BillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val amountCents: Long,
    val dueDayOfMonth: Int = 1,
    val dueDateMillis: Long? = null,
    val recurrence: BillRecurrence = BillRecurrence.MONTHLY,
    val category: BillCategory = BillCategory.OTHER,
    val linkedAccountId: Long? = null,
    val isActive: Boolean = true,
    val reminderDaysBefore: Int = 3,
    val lastPaidAt: Long? = null,
    val notes: String = "",
    /** Only bill cycles on or after this month count toward free-to-spend (when added to the app). */
    val trackingStartMillis: Long = System.currentTimeMillis(),
    /** Normalized Plaid name/merchant substring for auto-matching transactions. */
    val plaidMatchPattern: String? = null,
    /** When true, matching Plaid debits auto-mark the bill paid for that cycle. */
    val plaidAutoMarkPaid: Boolean = true
)
