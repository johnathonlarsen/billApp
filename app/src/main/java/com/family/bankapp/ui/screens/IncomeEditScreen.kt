package com.family.bankapp.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.family.bankapp.BankAppApplication
import com.family.bankapp.data.entity.IncomeEntity
import com.family.bankapp.data.model.BillRecurrence
import com.family.bankapp.ui.viewmodel.BanksViewModel
import com.family.bankapp.ui.viewmodel.IncomeViewModel
import com.family.bankapp.util.MoneyFormatter
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncomeEditScreen(
    incomeId: Long?,
    onBack: () -> Unit
) {
    val vm: IncomeViewModel = viewModel()
    val banksVm: BanksViewModel = viewModel()
    val banks by banksVm.banksWithAccounts.collectAsState()

    var name by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var recurrence by remember { mutableStateOf(BillRecurrence.MONTHLY) }
    var dueDay by remember { mutableStateOf("1") }
    var linkedAccountId by remember { mutableStateOf<Long?>(null) }
    var notes by remember { mutableStateOf("") }
    var recurrenceExpanded by remember { mutableStateOf(false) }
    var accountExpanded by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(incomeId == null) }
    var loadedIncome by remember { mutableStateOf<IncomeEntity?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (incomeId != null && !loaded) {
        LaunchedEffect(incomeId) {
            val app = banksVm.getApplication<BankAppApplication>()
            val income = app.repository.getIncome(incomeId)
            if (income != null) {
                loadedIncome = income
                name = income.name
                amountText = MoneyFormatter.format(income.amountCents)
                recurrence = income.recurrence
                dueDay = income.dueDayOfMonth.toString()
                linkedAccountId = income.linkedAccountId
                notes = income.notes
            }
            loaded = true
        }
    }

    val accountOptions = banks.flatMap { bwa ->
        bwa.accounts.map { acc -> "${bwa.bank.name} · ${acc.name}" to acc.id }
    }
    val linkedLabel = linkedAccountId?.let { id ->
        accountOptions.find { it.second == id }?.first
    } ?: "None"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (incomeId == null) "Add income" else "Edit income") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Cancel") }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Amount per pay") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                ExposedDropdownMenuBox(
                    expanded = recurrenceExpanded,
                    onExpandedChange = { recurrenceExpanded = it }
                ) {
                    OutlinedTextField(
                        value = recurrence.label,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("How often") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { recurrenceExpanded = true }
                    )
                    androidx.compose.material3.DropdownMenu(
                        expanded = recurrenceExpanded,
                        onDismissRequest = { recurrenceExpanded = false }
                    ) {
                        BillRecurrence.entries.filter { it != BillRecurrence.ONE_TIME }.forEach { option ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    recurrence = option
                                    recurrenceExpanded = false
                                }
                            )
                        }
                    }
                }
            }
            if (recurrence == BillRecurrence.MONTHLY) {
                item {
                    OutlinedTextField(
                        value = dueDay,
                        onValueChange = { dueDay = it.filter { ch -> ch.isDigit() }.take(2) },
                        label = { Text("Pay day of month (1–28)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = linkedLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Deposits to (optional)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { accountExpanded = !accountExpanded }
                )
                if (accountExpanded) {
                    Column {
                        TextButton(onClick = {
                            linkedAccountId = null
                            accountExpanded = false
                        }) { Text("None") }
                        accountOptions.forEach { (label, id) ->
                            TextButton(onClick = {
                                linkedAccountId = id
                                accountExpanded = false
                            }) { Text(label) }
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Button(
                    onClick = {
                        val amountCents = MoneyFormatter.parse(amountText) ?: return@Button
                        val day = dueDay.toIntOrNull()?.coerceIn(1, 28) ?: 1
                        val dueDateMillis = when (recurrence) {
                            BillRecurrence.MONTHLY -> null
                            else -> loadedIncome?.dueDateMillis?.takeIf { incomeId != null }
                                ?: LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        }
                        vm.saveIncome(
                            id = incomeId ?: 0L,
                            name = name,
                            amountCents = amountCents,
                            recurrence = recurrence,
                            dueDayOfMonth = day,
                            dueDateMillis = dueDateMillis,
                            linkedAccountId = linkedAccountId,
                            notes = notes
                        )
                        onBack()
                    },
                    enabled = name.isNotBlank() && MoneyFormatter.parse(amountText) != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save income")
                }
            }
            if (incomeId != null) {
                item {
                    OutlinedButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Delete income",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }

    if (showDeleteConfirm && loadedIncome != null) {
        val income = loadedIncome!!
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete ${income.name}?") },
            text = {
                Text(
                    "This permanently removes this income source. Free-to-spend on Home will no longer include it."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.deleteIncome(income)
                        showDeleteConfirm = false
                        onBack()
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}
