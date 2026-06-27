package com.family.bankapp.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import com.family.bankapp.ui.components.AppUpdateSettingsCard
import com.family.bankapp.ui.components.PlaidUsageTrackerCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.family.bankapp.BankAppApplication
import com.family.bankapp.FamilyAppConfig
import com.family.bankapp.data.model.BillCategory
import com.family.bankapp.data.model.BillRecurrence
import com.family.bankapp.ui.viewmodel.BanksViewModel
import com.family.bankapp.ui.components.EnumDropdownField
import com.family.bankapp.ui.viewmodel.BillsViewModel
import com.family.bankapp.ui.viewmodel.AppUpdateViewModel
import com.family.bankapp.ui.viewmodel.SettingsViewModel
import com.family.bankapp.util.MoneyFormatter
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillEditScreen(
    billId: Long?,
    onBack: () -> Unit
) {
    val vm: BillsViewModel = viewModel()
    val banksVm: BanksViewModel = viewModel()
    val banks by banksVm.banksWithAccounts.collectAsState()

    var name by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var recurrence by remember { mutableStateOf(BillRecurrence.MONTHLY) }
    var category by remember { mutableStateOf(BillCategory.OTHER) }
    var dueDay by remember { mutableStateOf("1") }
    var linkedAccountId by remember { mutableStateOf<Long?>(null) }
    var reminderDays by remember { mutableStateOf("3") }
    var notes by remember { mutableStateOf("") }
    var recurrenceExpanded by remember { mutableStateOf(false) }
    var categoryExpanded by remember { mutableStateOf(false) }
    var accountExpanded by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(billId == null) }

    if (billId != null && !loaded) {
        LaunchedEffect(billId) {
            val app = banksVm.getApplication<BankAppApplication>()
            val bill = app.repository.getBill(billId)
            if (bill != null) {
                name = bill.name
                amountText = MoneyFormatter.format(bill.amountCents)
                recurrence = bill.recurrence
                category = bill.category
                dueDay = bill.dueDayOfMonth.toString()
                linkedAccountId = bill.linkedAccountId
                reminderDays = bill.reminderDaysBefore.toString()
                notes = bill.notes
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
                title = { Text(if (billId == null) "Add bill" else "Edit bill") },
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
                    label = { Text("Bill name") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Amount") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
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
            }
            item {
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
            }
            if (recurrence == BillRecurrence.MONTHLY) {
                item {
                    OutlinedTextField(
                        value = dueDay,
                        onValueChange = { dueDay = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("Due day of month (1-28)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            item {
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
                if (accountOptions.isEmpty()) {
                    Text(
                        "Add a bank account on the Banks tab first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = reminderDays,
                    onValueChange = { reminderDays = it.filter { c -> c.isDigit() }.take(2) },
                    label = { Text("Remind days before due") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Button(
                    onClick = {
                        val amount = MoneyFormatter.parse(amountText) ?: 0L
                        val day = dueDay.toIntOrNull()?.coerceIn(1, 28) ?: 1
                        val remind = reminderDays.toIntOrNull()?.coerceIn(0, 14) ?: 3
                        val dueDateMillis = if (recurrence == BillRecurrence.ONE_TIME ||
                            recurrence == BillRecurrence.WEEKLY ||
                            recurrence == BillRecurrence.YEARLY
                        ) {
                            LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        } else null

                        vm.saveBill(
                            id = billId ?: 0L,
                            name = name,
                            amountCents = amount,
                            recurrence = recurrence,
                            category = category,
                            dueDayOfMonth = day,
                            dueDateMillis = dueDateMillis,
                            linkedAccountId = linkedAccountId,
                            reminderDaysBefore = remind,
                            notes = notes
                        )
                        onBack()
                    },
                    enabled = name.isNotBlank() && MoneyFormatter.parse(amountText) != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save bill")
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    padding: PaddingValues,
    onOpenPrivacyPolicy: () -> Unit = {},
    updateVm: AppUpdateViewModel
) {
    val vm: SettingsViewModel = viewModel()
    val reminderDays by vm.defaultReminderDays.collectAsState()
    val forecastDays by vm.forecastDays.collectAsState()
    val includePriorOverdue by vm.includePriorOverdueBills.collectAsState()
    var showSampleConfirm by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Each phone keeps its own bills and banks — data stays on this device",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("CSV bill import", fontWeight = FontWeight.Bold)
                    Text(
                        "Columns: name, amount, due_day, category, recurrence, pay_from, reminder_days, notes",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "pay_from uses Bank · Account (add banks first). " +
                            "Use Import on the Bills tab to pick a .csv file.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Template: bills_import_template.csv in the project folder.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            AppUpdateSettingsCard(vm = updateVm)
        }

        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenPrivacyPolicy)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Privacy policy", fontWeight = FontWeight.Bold)
                    Text(
                        "Required by Plaid before connecting real banks in Production. " +
                            "Also available at the URL shown inside the policy (for Plaid Dashboard).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "View policy →",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        item {
            PlaidUsageTrackerCard(
                modifier = Modifier.padding(horizontal = 16.dp),
                compact = false
            )
        }

        item {
            PlaidTrialSettingsCard()
        }

        item {
            SettingStepper(
                title = "Default bill reminder",
                subtitle = "Days before due date",
                value = reminderDays,
                range = 0..14,
                onChange = vm::setReminderDays
            )
        }

        item {
            SettingStepper(
                title = "Dashboard forecast window",
                subtitle = "Days ahead for upcoming bills",
                value = forecastDays,
                range = 7..60,
                onChange = vm::setForecastDays
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Include prior-month overdue bills", fontWeight = FontWeight.Bold)
                        Text(
                            "When on, unpaid bills from earlier months reduce free-to-spend. " +
                                "Current month only — never next month.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = includePriorOverdue,
                        onCheckedChange = vm::setIncludePriorOverdueBills
                    )
                }
            }
        }

        item {
            Button(onClick = { showSampleConfirm = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Load sample family data")
            }
            Text(
                "Adds Keesler, Capital One, Hancock Whitney and sample bills.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showSampleConfirm) {
        AlertDialog(
            onDismissRequest = { showSampleConfirm = false },
            title = { Text("Load sample data?") },
            text = { Text("This adds demo banks and bills. It won't remove existing data.") },
            confirmButton = {
                Button(onClick = {
                    com.family.bankapp.data.SampleDataLoader.load(
                        (context.applicationContext as com.family.bankapp.BankAppApplication).repository
                    )
                    showSampleConfirm = false
                }) { Text("Load") }
            },
            dismissButton = {
                TextButton(onClick = { showSampleConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun PlaidTrialSettingsCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Plaid Sandbox", fontWeight = FontWeight.Bold)
            Text(
                "Safe testing with fake banks. ${FamilyAppConfig.PLAID_EXPECTED_ITEMS} household Items " +
                    "(one login per institution). Production Trial slots are tracked above.",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Daily transaction sync per bank (~${FamilyAppConfig.PLAID_EXPECTED_ITEMS * 30} API calls/mo).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingStepper(
    title: String,
    subtitle: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit
) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = value.toString(),
                onValueChange = { text ->
                    text.toIntOrNull()?.let { onChange(it.coerceIn(range)) }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
