package com.family.bankapp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.family.bankapp.data.entity.PlaidTransactionEntity
import com.family.bankapp.data.model.BillCategory
import com.family.bankapp.data.model.BillRecurrence
import com.family.bankapp.plaid.PlaidTransactionBillDraft
import com.family.bankapp.plaid.toBillDraft
import com.family.bankapp.util.MoneyFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBillFromTransactionDialog(
    transaction: PlaidTransactionEntity,
    bankName: String,
    accounts: List<com.family.bankapp.data.entity.AccountEntity>,
    accountOptions: List<Pair<String, Long>>,
    onDismiss: () -> Unit,
    onConfirm: (PlaidTransactionBillDraft) -> Unit
) {
    val initial = remember(transaction.plaidTransactionId) {
        transaction.toBillDraft(bankName, accounts)
    }

    var name by remember(initial) { mutableStateOf(initial.name) }
    var amountText by remember(initial) { mutableStateOf(MoneyFormatter.format(initial.amountCents)) }
    var dueDay by remember(initial) { mutableStateOf(initial.dueDayOfMonth.toString()) }
    var category by remember(initial) { mutableStateOf(initial.category) }
    var recurrence by remember(initial) { mutableStateOf(initial.recurrence) }
    var linkedAccountId by remember(initial) { mutableStateOf(initial.linkedAccountId) }
    var notes by remember(initial) { mutableStateOf(initial.notes) }
    var categoryExpanded by remember { mutableStateOf(false) }
    var recurrenceExpanded by remember { mutableStateOf(false) }
    var accountExpanded by remember { mutableStateOf(false) }

    val linkedLabel = linkedAccountId?.let { id ->
        accountOptions.find { it.second == id }?.first
    } ?: "None"

    val amountValid = MoneyFormatter.parse(amountText) != null
    val dayValid = dueDay.toIntOrNull()?.let { it in 1..28 } == true

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add as bill") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (transaction.pending) {
                    Text(
                        "This transaction is still pending at the bank.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    "Review and edit before saving.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Bill name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Amount") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = dueDay,
                    onValueChange = { dueDay = it.filter { c -> c.isDigit() }.take(2) },
                    label = { Text("Due day of month (1–28)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                EnumDropdownField(
                    label = "Category",
                    value = category.label,
                    expanded = categoryExpanded,
                    onExpandedChange = { categoryExpanded = it }
                ) {
                    BillCategory.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                category = option
                                categoryExpanded = false
                            }
                        )
                    }
                }
                EnumDropdownField(
                    label = "Recurrence",
                    value = recurrence.label,
                    expanded = recurrenceExpanded,
                    onExpandedChange = { recurrenceExpanded = it }
                ) {
                    BillRecurrence.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                recurrence = option
                                recurrenceExpanded = false
                            }
                        )
                    }
                }
                EnumDropdownField(
                    label = "Pay from account",
                    value = linkedLabel,
                    expanded = accountExpanded,
                    onExpandedChange = { accountExpanded = it },
                    enabled = accountOptions.isNotEmpty()
                ) {
                    DropdownMenuItem(
                        text = { Text("None") },
                        onClick = {
                            linkedAccountId = null
                            accountExpanded = false
                        }
                    )
                    accountOptions.forEach { (label, id) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                linkedAccountId = id
                                accountExpanded = false
                            }
                        )
                    }
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amount = MoneyFormatter.parse(amountText) ?: return@Button
                    val day = dueDay.toIntOrNull()?.coerceIn(1, 28) ?: return@Button
                    onConfirm(
                        PlaidTransactionBillDraft(
                            name = name.trim(),
                            amountCents = amount,
                            dueDayOfMonth = day,
                            linkedAccountId = linkedAccountId,
                            category = category,
                            recurrence = recurrence,
                            reminderDaysBefore = initial.reminderDaysBefore,
                            notes = notes.trim()
                        )
                    )
                },
                enabled = name.isNotBlank() && amountValid && dayValid
            ) {
                Text("Add bill")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
