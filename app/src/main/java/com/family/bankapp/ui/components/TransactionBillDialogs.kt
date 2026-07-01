package com.family.bankapp.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.family.bankapp.data.entity.BillEntity
import com.family.bankapp.data.entity.PlaidTransactionEntity
import com.family.bankapp.util.MoneyFormatter

enum class TransactionBillAction {
    NEW_BILL,
    LINK_EXISTING,
    UPDATE_EXISTING,
    UNLINK
}

@Composable
fun TransactionBillActionDialog(
    transaction: PlaidTransactionEntity,
    linkedBillName: String? = null,
    onDismiss: () -> Unit,
    onSelect: (TransactionBillAction) -> Unit
) {
    val label = transaction.merchantName?.takeIf { it.isNotBlank() } ?: transaction.name
    val isLinked = !linkedBillName.isNullOrBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isLinked) "Linked transaction" else "Bill from transaction") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, fontWeight = FontWeight.SemiBold)
                Text(
                    "${MoneyFormatter.format(transaction.amountCents)} · ${transaction.date}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isLinked) {
                    Text(
                        "Linked to $linkedBillName",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Change the link or remove it. The bill itself stays on your list.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(
                        onClick = { onSelect(TransactionBillAction.UNLINK) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Unlink from bill")
                    }
                    TextButton(
                        onClick = { onSelect(TransactionBillAction.LINK_EXISTING) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Link to a different bill")
                    }
                    TextButton(
                        onClick = { onSelect(TransactionBillAction.UPDATE_EXISTING) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Update bill from this")
                    }
                } else {
                    Text(
                        "Choose how to use this transaction for bill tracking.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(
                        onClick = { onSelect(TransactionBillAction.NEW_BILL) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Add as new bill")
                    }
                    TextButton(
                        onClick = { onSelect(TransactionBillAction.LINK_EXISTING) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Link to existing bill")
                    }
                    TextButton(
                        onClick = { onSelect(TransactionBillAction.UPDATE_EXISTING) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Update existing bill from this")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun ConfirmUnlinkBillFromTransactionDialog(
    billName: String,
    transaction: PlaidTransactionEntity,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Unlink from $billName?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Removes the link between this transaction and the bill.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "If this transaction marked the bill paid, that payment will be undone too.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Unlink") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun PickBillForTransactionDialog(
    title: String,
    description: String,
    bills: List<BillEntity>,
    onDismiss: () -> Unit,
    onPick: (BillEntity) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                if (bills.isEmpty()) {
                    Text("No active bills yet — add one first or choose Add as new bill.")
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(bills, key = { it.id }) { bill ->
                            Text(
                                "${bill.name} · ${MoneyFormatter.format(bill.amountCents)}",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(bill) }
                                    .padding(vertical = 10.dp),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun ConfirmUpdateBillFromTransactionDialog(
    bill: BillEntity,
    transaction: PlaidTransactionEntity,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val newName = transaction.merchantName?.takeIf { it.isNotBlank() } ?: transaction.name
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update bill from transaction?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Updates ${bill.name} for all future months and sets up auto-matching from Plaid.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text("Name: $newName", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Amount: ${MoneyFormatter.format(transaction.amountCents)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Marks this month's cycle paid if not already.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Update bill") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun ConfirmLinkBillToTransactionDialog(
    bill: BillEntity,
    transaction: PlaidTransactionEntity,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val pattern = transaction.merchantName?.takeIf { it.isNotBlank() } ?: transaction.name
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link to ${bill.name}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Future Plaid transactions matching \"$pattern\" (similar amount) will auto-mark this bill paid.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "This transaction will mark the matching bill cycle paid now.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Link bill") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
