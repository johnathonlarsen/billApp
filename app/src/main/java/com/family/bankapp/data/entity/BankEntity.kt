package com.family.bankapp.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.family.bankapp.data.model.ConnectionType

@Entity(tableName = "banks")
data class BankEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorHex: String,
    val connectionType: ConnectionType = ConnectionType.MANUAL,
    val lastSyncedAt: Long? = null,
    val sortOrder: Int = 0,
    /** Plaid Item id — server holds access token. */
    val plaidItemId: String? = null,
    val plaidTransactionsCursor: String? = null,
    /** Deprecated: access tokens must not be stored on device (Plaid policy). Always null. */
    val plaidAccessToken: String? = null
)
