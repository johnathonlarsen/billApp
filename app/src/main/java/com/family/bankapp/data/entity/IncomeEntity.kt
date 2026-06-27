package com.family.bankapp.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.family.bankapp.data.model.BillRecurrence

@Entity(
    tableName = "income_sources",
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
data class IncomeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val amountCents: Long,
    val dueDayOfMonth: Int = 1,
    val dueDateMillis: Long? = null,
    val recurrence: BillRecurrence = BillRecurrence.MONTHLY,
    val linkedAccountId: Long? = null,
    val isActive: Boolean = true,
    val notes: String = ""
)
