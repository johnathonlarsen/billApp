package com.family.bankapp.plaid

import com.family.bankapp.data.entity.AccountEntity
import com.family.bankapp.data.entity.PlaidTransactionEntity
import com.family.bankapp.data.model.BillCategory
import com.family.bankapp.data.model.BillRecurrence
import java.time.LocalDate

data class PlaidTransactionBillDraft(
    val name: String,
    val amountCents: Long,
    val dueDayOfMonth: Int,
    val linkedAccountId: Long?,
    val category: BillCategory = BillCategory.OTHER,
    val recurrence: BillRecurrence = BillRecurrence.MONTHLY,
    val reminderDaysBefore: Int = 3,
    val notes: String = "",
    val cycleMonthOffset: Int = 1
)

fun PlaidTransactionEntity.toBillDraft(
    bankName: String,
    accounts: List<AccountEntity>
): PlaidTransactionBillDraft {
    val linked = accounts.find { it.plaidAccountId == plaidAccountId }
    val displayName = merchantName?.takeIf { it.isNotBlank() } ?: name
    val dueDay = runCatching { LocalDate.parse(date).dayOfMonth }.getOrDefault(1)
    val pendingNote = if (pending) " (pending when imported)" else ""
    return PlaidTransactionBillDraft(
        name = displayName,
        amountCents = amountCents,
        dueDayOfMonth = dueDay.coerceIn(1, 28),
        linkedAccountId = linked?.id,
        notes = buildString {
            append("From Plaid · $bankName · $date$pendingNote")
        }
    )
}

fun PlaidTransactionEntity.canAddAsBill(): Boolean = amountCents > 0
