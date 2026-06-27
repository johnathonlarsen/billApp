package com.family.bankapp.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.family.bankapp.data.entity.BillEntity
import com.family.bankapp.data.entity.PaymentRecordEntity
import com.family.bankapp.data.model.BillRecurrence
import com.family.bankapp.ui.components.EnumDropdownField
import com.family.bankapp.ui.components.MoneyText
import com.family.bankapp.ui.components.MonthTimelineSection
import com.family.bankapp.ui.viewmodel.BillListItem
import com.family.bankapp.ui.viewmodel.BillsViewModel
import com.family.bankapp.util.BillCsvImportResult
import com.family.bankapp.util.BillSchedule
import com.family.bankapp.util.MonthBillEntry
import com.family.bankapp.util.MoneyFormatter
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun BillsScreen(
    padding: PaddingValues,
    onAddBill: () -> Unit,
    onEditBill: (Long) -> Unit
) {
    val vm: BillsViewModel = viewModel()
    val bills by vm.bills.collectAsState()
    val monthTimeline by vm.monthTimeline.collectAsState()
    var expandedMonth by remember { mutableStateOf<YearMonth?>(YearMonth.now()) }
    var payTarget by remember { mutableStateOf<BillListItem?>(null) }
    var payCycleDate by remember { mutableStateOf<LocalDate?>(null) }
    var undoTarget by remember { mutableStateOf<BillListItem?>(null) }
    var skipTarget by remember { mutableStateOf<MonthBillEntry?>(null) }
    var importResult by remember { mutableStateOf<BillCsvImportResult?>(null) }
    val context = LocalContext.current

    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val text = stream.bufferedReader().readText()
                vm.importFromCsv(text) { result ->
                    importResult = result
                }
            } ?: run {
                importResult = BillCsvImportResult(emptyList(), listOf("Could not read file"))
            }
        } catch (e: Exception) {
            importResult = BillCsvImportResult(emptyList(), listOf(e.message ?: "Import failed"))
        }
    }

    Box(
        Modifier.fillMaxSize().padding(padding)
    ) {
        LazyColumn(
            contentPadding = PaddingValues(bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Column(Modifier.padding(16.dp)) {
                    Text("Bills", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Add bills manually or import from a CSV file",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = { csvLauncher.launch(arrayOf("text/*", "text/csv", "application/csv", "*/*")) },
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Icon(Icons.Default.Upload, contentDescription = null)
                        Text("Import bills from CSV")
                    }
                }
            }

            item {
                MonthTimelineSection(
                    months = monthTimeline,
                    expandedMonth = expandedMonth,
                    onMonthClick = { month ->
                        expandedMonth = if (expandedMonth == month) null else month
                    },
                    onMarkBillPaid = { entry ->
                        vm.billListItemFor(entry.bill.id, bills)?.let { item ->
                            payTarget = item
                            payCycleDate = entry.dueDate
                        }
                    },
                    onRemoveBillFromMonth = { skipTarget = it }
                )
            }

            item {
                Text(
                    "All bills",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (bills.isEmpty()) {
                item {
                    Text(
                        "No bills yet. Tap + to add rent, utilities, subscriptions, and more.",
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            items(bills) { item ->
                BillCard(
                    item = item,
                    onEdit = { onEditBill(item.dueInfo.bill.id) },
                    onMarkPaid = {
                        payTarget = item
                        payCycleDate = null
                    },
                    onUndoPaid = { undoTarget = item }
                )
            }
        }

        FloatingActionButton(
            onClick = onAddBill,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add bill")
        }
    }

    payTarget?.let { target ->
        MarkPaidDialog(
            item = target,
            initialYearMonth = payCycleDate?.let { YearMonth.from(it) },
            onDismiss = {
                payTarget = null
                payCycleDate = null
            },
            onConfirm = { cycleDueDate ->
                vm.markPaid(target.dueInfo.bill, target.dueInfo.bill.linkedAccountId, cycleDueDate)
                payTarget = null
                payCycleDate = null
            }
        )
    }

    undoTarget?.let { target ->
        val payment = target.dueInfo.cyclePayment
        if (payment != null) {
            UndoPaidDialog(
                bill = target.dueInfo.bill,
                payment = payment,
                onDismiss = { undoTarget = null },
                onConfirm = {
                    vm.undoPayment(payment.id)
                    undoTarget = null
                }
            )
        }
    }

    skipTarget?.let { entry ->
        val cycleLabel = BillSchedule.formatCycleLabel(entry.bill, entry.dueDate)
        AlertDialog(
            onDismissRequest = { skipTarget = null },
            title = { Text("Remove from $cycleLabel?") },
            text = {
                Text(
                    "Remove \"${entry.bill.name}\" from $cycleLabel only? " +
                        "The bill stays on your list and will still appear in other months."
                )
            },
            confirmButton = {
                Button(onClick = {
                    vm.skipBillCycle(entry.bill.id, entry.dueDate)
                    skipTarget = null
                }) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { skipTarget = null }) { Text("Cancel") }
            }
        )
    }

    importResult?.let { result ->
        AlertDialog(
            onDismissRequest = { importResult = null },
            title = {
                Text(
                    if (result.importedCount > 0) "Imported ${result.importedCount} bill(s)"
                    else "Import failed"
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (result.importedCount > 0) {
                        Text("Bills were added to this device.")
                    }
                    if (result.errors.isNotEmpty()) {
                        Text(
                            result.errors.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { importResult = null }) { Text("OK") }
            }
        )
    }
}

@Composable
private fun BillCard(
    item: BillListItem,
    onEdit: () -> Unit,
    onMarkPaid: () -> Unit,
    onUndoPaid: () -> Unit
) {
    val dueInfo = item.dueInfo
    val formatter = DateTimeFormatter.ofPattern("MMM d")
    val statusColor = when {
        dueInfo.isPaidThisCycle -> MaterialTheme.colorScheme.primary
        dueInfo.isOverdue -> MaterialTheme.colorScheme.error
        dueInfo.daysUntilDue <= 3 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val paidCycleLabel = dueInfo.cyclePayment?.let { payment ->
        BillSchedule.formatCycleLabel(
            dueInfo.bill,
            BillSchedule.fromCycleMillis(payment.cycleDueDateMillis)
        )
    }
    val statusText = when {
        dueInfo.isPaidThisCycle && paidCycleLabel != null -> "Paid for $paidCycleLabel"
        dueInfo.isPaidThisCycle -> "Paid this cycle"
        dueInfo.isOverdue -> "Overdue"
        dueInfo.daysUntilDue == 0L -> "Due today"
        else -> "Due ${dueInfo.dueDate.format(formatter)}"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = if (dueInfo.isPaidThisCycle) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(dueInfo.bill.name, fontWeight = FontWeight.Bold)
                    Text(
                        "${dueInfo.bill.category.label} · ${dueInfo.bill.recurrence.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    item.linkedAccountLabel?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                MoneyText(dueInfo.bill.amountCents, color = statusColor)
            }
            Text(statusText, color = statusColor, style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onEdit) { Text("Edit") }
                if (dueInfo.isPaidThisCycle && dueInfo.cyclePayment != null) {
                    OutlinedButton(onClick = onUndoPaid) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null)
                        Text("Undo")
                    }
                } else {
                    Button(onClick = onMarkPaid) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Text("Mark paid")
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkPaidDialog(
    item: BillListItem,
    initialYearMonth: YearMonth? = null,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit
) {
    val bill = item.dueInfo.bill
    val defaultYearMonth = initialYearMonth ?: BillSchedule.defaultPaymentYearMonth(bill)
    var selectedMonth by remember(initialYearMonth) { mutableIntStateOf(defaultYearMonth.monthValue) }
    var selectedYear by remember(initialYearMonth) { mutableIntStateOf(defaultYearMonth.year) }
    var monthExpanded by remember { mutableStateOf(false) }
    var yearExpanded by remember { mutableStateOf(false) }

    val yearMonth = YearMonth.of(selectedYear, selectedMonth)
    val cycleDueDate = BillSchedule.dueDateForYearMonth(bill, yearMonth)
    val cycleLabel = BillSchedule.formatCycleLabel(bill, cycleDueDate)
    val existingPayment = BillSchedule.paymentForCycle(item.billPayments, bill.id, cycleDueDate)

    val monthLabel = Month.of(selectedMonth).getDisplayName(TextStyle.FULL, Locale.getDefault())
    val yearOptions = remember {
        val current = LocalDate.now().year
        (current - 2..current + 1).toList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mark ${bill.name} paid?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Confirm payment for the billing period below. You can change the month or year if needed.",
                    style = MaterialTheme.typography.bodyMedium
                )

                if (bill.recurrence == BillRecurrence.MONTHLY || bill.recurrence == BillRecurrence.YEARLY) {
                    EnumDropdownField(
                        label = "Month",
                        value = monthLabel,
                        expanded = monthExpanded,
                        onExpandedChange = { monthExpanded = it },
                        enabled = bill.recurrence == BillRecurrence.MONTHLY
                    ) {
                        Month.entries.forEach { month ->
                            DropdownMenuItem(
                                text = { Text(month.getDisplayName(TextStyle.FULL, Locale.getDefault())) },
                                onClick = {
                                    selectedMonth = month.value
                                    monthExpanded = false
                                }
                            )
                        }
                    }

                    EnumDropdownField(
                        label = "Year",
                        value = selectedYear.toString(),
                        expanded = yearExpanded,
                        onExpandedChange = { yearExpanded = it }
                    ) {
                        yearOptions.forEach { year ->
                            DropdownMenuItem(
                                text = { Text(year.toString()) },
                                onClick = {
                                    selectedYear = year
                                    yearExpanded = false
                                }
                            )
                        }
                    }
                }

                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Billing period", style = MaterialTheme.typography.labelMedium)
                        Text(cycleLabel, fontWeight = FontWeight.Bold)
                        Text(
                            "Amount: ${MoneyFormatter.format(bill.amountCents)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        bill.linkedAccountId?.let {
                            Text(
                                "Pay from linked account",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (existingPayment != null) {
                    Text(
                        "This period is already marked paid. Confirming will replace that payment.",
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(cycleDueDate) }) {
                Text("Confirm paid")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun UndoPaidDialog(
    bill: BillEntity,
    payment: PaymentRecordEntity,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val cycleLabel = BillSchedule.formatCycleLabel(
        bill,
        BillSchedule.fromCycleMillis(payment.cycleDueDateMillis)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Undo payment?") },
        text = {
            Text(
                "Remove the paid status for ${bill.name} ($cycleLabel)?"
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text("Undo payment") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Keep paid") }
        }
    )
}
