package com.family.bankapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.family.bankapp.FamilyAppConfig
import com.family.bankapp.data.entity.AccountEntity
import com.family.bankapp.data.entity.PlaidTransactionEntity
import com.family.bankapp.data.model.AccountType
import com.family.bankapp.data.model.BillRecurrence
import com.family.bankapp.util.MoneyFormatter
import com.family.bankapp.data.repository.BankRepository
import com.family.bankapp.plaid.PlaidNameMatcher
import com.family.bankapp.plaid.PlaidRestorableItem
import com.family.bankapp.ui.components.AddBillFromTransactionDialog
import com.family.bankapp.ui.components.PlaidUsageTrackerCard
import com.family.bankapp.ui.components.SectionHeader
import com.family.bankapp.ui.components.parseColorHex
import com.family.bankapp.plaid.canAddAsBill
import com.family.bankapp.ui.viewmodel.BillsViewModel
import com.family.bankapp.ui.viewmodel.BankWithAccounts
import com.family.bankapp.ui.viewmodel.BanksViewModel
import java.time.LocalDate
import java.time.ZoneId
import com.plaid.link.FastOpenPlaidLink
import com.plaid.link.result.LinkExit
import com.plaid.link.result.LinkSuccess

@Composable
fun BanksScreen(padding: PaddingValues, onOpenBank: (Long) -> Unit) {
    val vm: BanksViewModel = viewModel()
    val banks by vm.banksWithAccounts.collectAsState()
    val bankCount by vm.bankCount.collectAsState()
    var showAddBank by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(
            contentPadding = PaddingValues(bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Column(Modifier.padding(16.dp)) {
                    Text("Your banks", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "$bankCount / ${BankRepository.MAX_BANKS} banks · accounts label where bills are paid from",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (banks.isEmpty()) {
                item {
                    Text(
                        "Add banks and accounts so bills can show “pay from Capital One · Checking”, etc.",
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            items(banks) { item ->
                BankCard(item = item, onClick = { onOpenBank(item.bank.id) })
            }
        }

        if (bankCount < BankRepository.MAX_BANKS) {
            FloatingActionButton(
                onClick = { showAddBank = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add bank")
            }
        }
    }

    if (showAddBank) {
        AddBankDialog(
            onDismiss = { showAddBank = false },
            onConfirm = { name, onError ->
                vm.addBank(name) { result ->
                    result.onSuccess { id ->
                        showAddBank = false
                        onOpenBank(id)
                    }.onFailure { e ->
                        onError(e.message ?: "Could not add bank")
                    }
                }
            }
        )
    }
}

@Composable
private fun BankCard(item: BankWithAccounts, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(parseColorHex(item.bank.colorHex))
            )
            Column {
                Text(item.bank.name, fontWeight = FontWeight.Bold)
                Text(
                    "${item.accounts.size} account(s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BankDetailScreen(
    bankId: Long,
    onBack: () -> Unit
) {
    val vm: BanksViewModel = viewModel()
    val billsVm: BillsViewModel = viewModel()
    val banks by vm.banksWithAccounts.collectAsState()
    val item = banks.find { it.bank.id == bankId }

    var showAddAccount by remember { mutableStateOf(false) }
    var editingAccount by remember { mutableStateOf<com.family.bankapp.data.entity.AccountEntity?>(null) }
    var plaidBlockMessage by remember { mutableStateOf<String?>(null) }
    var plaidConfirmMessage by remember { mutableStateOf<String?>(null) }
    var plaidConnectPending by remember { mutableStateOf(false) }
    var plaidLinkBusy by remember { mutableStateOf(false) }
    var plaidSyncBusy by remember { mutableStateOf(false) }
    var plaidStatusMessage by remember { mutableStateOf<String?>(null) }
    var restorableItems by remember { mutableStateOf<List<PlaidRestorableItem>>(emptyList()) }
    var restorableLoading by remember { mutableStateOf(false) }
    var restoreBusyItemId by remember { mutableStateOf<String?>(null) }
    var preferredRestoreItemId by remember { mutableStateOf<String?>(null) }
    var showDisconnectConfirm by remember { mutableStateOf(false) }
    var disconnectBusy by remember { mutableStateOf(false) }
    var showReconnectPasswordDialog by remember { mutableStateOf(false) }
    var reconnectPassword by remember { mutableStateOf("") }
    var reconnectPasswordError by remember { mutableStateOf<String?>(null) }
    var billFromTransaction by remember { mutableStateOf<PlaidTransactionEntity?>(null) }

    val accountOptions = remember(banks) {
        banks.flatMap { bwa ->
            bwa.accounts.map { acc -> "${bwa.bank.name} · ${acc.name}" to acc.id }
        }
    }

    fun beginPlaidConnect() {
        plaidConnectPending = true
        vm.checkBeforePlaidConnect(item?.bank ?: return) { check ->
            plaidConnectPending = false
            if (!check.allowed) {
                plaidBlockMessage = check.blockReason
            } else {
                plaidConfirmMessage = check.confirmMessage
            }
        }
    }

    val plaidTransactions by vm.observePlaidTransactions(bankId).collectAsState(initial = emptyList())
    val groupedPlaidTransactions = remember(plaidTransactions, item?.accounts) {
        groupPlaidTransactionsByAccount(plaidTransactions, item?.accounts.orEmpty())
    }
    val isPlaidConnected = !item?.bank?.plaidItemId.isNullOrBlank()
    val hasUnrestoredLinks = restorableItems.isNotEmpty()
    val matchingRestore = remember(item?.bank?.name, restorableItems, preferredRestoreItemId) {
        preferredRestoreItemId?.let { id ->
            restorableItems.firstOrNull { it.itemId == id }
        } ?: run {
            val bankName = item?.bank?.name ?: return@remember null
            restorableItems.firstOrNull { PlaidNameMatcher.likelySameBank(bankName, it.institutionName) }
        }
    }

    LaunchedEffect(bankId, isPlaidConnected) {
        if (!isPlaidConnected) {
            restorableLoading = true
            vm.fetchRestorablePlaidItems { result ->
                restorableLoading = false
                result.onSuccess { restorableItems = it }
            }
        } else {
            restorableItems = emptyList()
        }
    }

    val plaidLauncher = rememberLauncherForActivityResult(FastOpenPlaidLink()) { result ->
        when (result) {
            is LinkSuccess -> {
                plaidLinkBusy = true
                val replacingItemId = item?.bank?.plaidItemId
                vm.completePlaidLink(
                    bankId = bankId,
                    publicToken = result.publicToken,
                    replacingItemId = replacingItemId
                ) { linkResult ->
                    plaidLinkBusy = false
                    linkResult.onSuccess { sync ->
                        plaidStatusMessage = buildString {
                            append("${item?.bank?.name ?: "Bank"} connected via Plaid.\n\n")
                            append("Imported ${sync.accountsImported} account(s)")
                            append(" and ${sync.transactionsAdded} transaction(s).")
                            if (sync.hasMoreTransactions) {
                                append("\n\nMore transactions available — tap Sync from Plaid again.")
                            }
                        }
                    }.onFailure { e ->
                        plaidStatusMessage = e.message ?: "Could not save Plaid connection"
                    }
                }
            }
            is LinkExit -> {
                result.error?.let { err ->
                    plaidStatusMessage = err.displayMessage ?: "Plaid Link was cancelled"
                }
            }
        }
    }

    if (item == null) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            Text("Bank not found", modifier = Modifier.padding(16.dp))
            Button(onClick = onBack, modifier = Modifier.padding(16.dp)) { Text("Back") }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item.bank.name) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddAccount = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add account")
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 88.dp)
        ) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Plaid connection", fontWeight = FontWeight.Bold)
                        if (isPlaidConnected) {
                            Text(
                                "Connected — balances and transactions sync from Plaid Sandbox.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            OutlinedButton(
                                onClick = {
                                    plaidSyncBusy = true
                                    vm.syncPlaidBank(bankId) { result ->
                                        plaidSyncBusy = false
                                        result.onSuccess { sync ->
                                            plaidStatusMessage = buildString {
                                                append("Synced ${sync.accountsImported} account(s)")
                                                append(" and ${sync.transactionsAdded} new transaction(s).")
                                                if (sync.hasMoreTransactions) {
                                                    append("\n\nMore pages available — sync again if needed.")
                                                }
                                            }
                                        }.onFailure { e ->
                                            plaidStatusMessage = e.message ?: "Sync failed"
                                        }
                                    }
                                },
                                enabled = !plaidSyncBusy && !plaidLinkBusy && !disconnectBusy,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (plaidSyncBusy) "Syncing…" else "Sync from Plaid")
                            }
                            OutlinedButton(
                                onClick = { showDisconnectConfirm = true },
                                enabled = !plaidSyncBusy && !plaidLinkBusy && !disconnectBusy,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (disconnectBusy) "Disconnecting…" else "Disconnect Plaid")
                            }
                        } else {
                            Text(
                                "Optional. Connect only when ready — each bank uses one of your 10 lifetime Trial slots.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (restorableLoading) {
                                Text(
                                    "Checking for saved Plaid links…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else if (restorableItems.isNotEmpty()) {
                                Text(
                                    "Saved links on the server — restore instead of connecting again (no new Trial slot).",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                matchingRestore?.let { link ->
                                    Button(
                                        onClick = {
                                            restoreBusyItemId = link.itemId
                                            vm.restorePlaidLink(bankId, link.itemId) { result ->
                                                restoreBusyItemId = null
                                                result.onSuccess { sync ->
                                                    preferredRestoreItemId = null
                                                    plaidStatusMessage = buildString {
                                                        append("Restored ${link.institutionName}.\n\n")
                                                        append("Synced ${sync.accountsImported} account(s)")
                                                        append(" and ${sync.transactionsAdded} transaction(s).")
                                                    }
                                                }.onFailure { e ->
                                                    plaidStatusMessage = e.message ?: "Could not restore link"
                                                }
                                            }
                                        },
                                        enabled = restoreBusyItemId == null && !plaidLinkBusy,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            if (restoreBusyItemId == link.itemId) {
                                                "Restoring…"
                                            } else {
                                                "Restore saved link (${link.institutionName})"
                                            }
                                        )
                                    }
                                }
                                val otherRestorable = restorableItems.filter { it.itemId != matchingRestore?.itemId }
                                otherRestorable.forEach { link ->
                                    OutlinedButton(
                                        onClick = {
                                            restoreBusyItemId = link.itemId
                                            vm.restorePlaidLink(bankId, link.itemId) { result ->
                                                restoreBusyItemId = null
                                                result.onSuccess { sync ->
                                                    preferredRestoreItemId = null
                                                    plaidStatusMessage = buildString {
                                                        append("Restored ${link.institutionName}.\n\n")
                                                        append("Synced ${sync.accountsImported} account(s)")
                                                        append(" and ${sync.transactionsAdded} transaction(s).")
                                                    }
                                                }.onFailure { e ->
                                                    plaidStatusMessage = e.message ?: "Could not restore link"
                                                }
                                            }
                                        },
                                        enabled = restoreBusyItemId == null && !plaidLinkBusy,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            if (restoreBusyItemId == link.itemId) {
                                                "Restoring…"
                                            } else {
                                                "Restore ${link.institutionName}"
                                            }
                                        )
                                    }
                                }
                            }
                            when {
                                matchingRestore != null -> {
                                    Text(
                                        "Start with Restore ${matchingRestore.institutionName} on this bank.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                hasUnrestoredLinks -> {
                                    Text(
                                        "Connect is disabled until all saved links above are restored on this phone.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                        Button(
                            onClick = {
                                if (isPlaidConnected) {
                                    reconnectPassword = ""
                                    reconnectPasswordError = null
                                    showReconnectPasswordDialog = true
                                } else {
                                    beginPlaidConnect()
                                }
                            },
                            enabled = !plaidConnectPending &&
                                !plaidLinkBusy &&
                                !hasUnrestoredLinks &&
                                restoreBusyItemId == null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                when {
                                    plaidConnectPending -> "Checking limit…"
                                    plaidLinkBusy -> "Connecting…"
                                    isPlaidConnected -> "Reconnect via Plaid"
                                    else -> "Connect via Plaid"
                                }
                            )
                        }
                    }
                }
            }

            item {
                PlaidUsageTrackerCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    compact = true
                )
            }

            item {
                Text(
                    if (isPlaidConnected) {
                        "Plaid accounts show live Sandbox balances. Manual accounts are labels only."
                    } else {
                        "Add checking, savings, or credit accounts you pay bills from."
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item { SectionHeader("Accounts") }

            if (item.accounts.isEmpty()) {
                item {
                    Text(
                        if (isPlaidConnected) {
                            "Tap Sync from Plaid to import Sandbox accounts."
                        } else {
                            "No accounts yet."
                        },
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            items(item.accounts) { account ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(account.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                account.accountType.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                MoneyFormatter.format(account.balanceCents),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            if (!account.plaidAccountId.isNullOrBlank()) {
                                Text(
                                    "From Plaid",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (account.accountType == AccountType.SAVINGS && !account.includeInFreeToSpend) {
                                Text(
                                    "Excluded from free to spend",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (account.notes.isNotBlank()) {
                                Text(
                                    account.notes,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(onClick = { editingAccount = account }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit")
                        }
                    }
                }
            }

            if (isPlaidConnected || plaidTransactions.isNotEmpty()) {
                item { SectionHeader("Recent transactions") }
                if (!isPlaidConnected && plaidTransactions.isNotEmpty()) {
                    item {
                        Text(
                            "Cached from your last Plaid sync — restore the saved link to refresh.",
                            modifier = Modifier.padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (plaidTransactions.isEmpty()) {
                    item {
                        Text(
                            "No transactions yet — tap Sync from Plaid.",
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    groupedPlaidTransactions.forEach { group ->
                        item {
                            PlaidAccountTransactionHeader(
                                accountLabel = group.accountLabel,
                                transactionCount = group.transactions.size
                            )
                        }
                        items(group.transactions, key = { it.plaidTransactionId }) { tx ->
                            PlaidTransactionRow(
                                tx = tx,
                                onAddAsBill = if (tx.canAddAsBill()) {
                                    { billFromTransaction = tx }
                                } else {
                                    null
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    billFromTransaction?.let { tx ->
        AddBillFromTransactionDialog(
            transaction = tx,
            bankName = item.bank.name,
            accounts = item.accounts,
            accountOptions = accountOptions,
            onDismiss = { billFromTransaction = null },
            onConfirm = { draft ->
                val txDate = runCatching { LocalDate.parse(tx.date) }.getOrNull()
                val dueDateMillis = when (draft.recurrence) {
                    BillRecurrence.ONE_TIME,
                    BillRecurrence.WEEKLY,
                    BillRecurrence.YEARLY -> txDate
                        ?.atStartOfDay(ZoneId.systemDefault())
                        ?.toInstant()
                        ?.toEpochMilli()
                    else -> null
                }
                billsVm.saveBill(
                    name = draft.name,
                    amountCents = draft.amountCents,
                    recurrence = draft.recurrence,
                    category = draft.category,
                    dueDayOfMonth = draft.dueDayOfMonth,
                    dueDateMillis = dueDateMillis,
                    linkedAccountId = draft.linkedAccountId,
                    reminderDaysBefore = draft.reminderDaysBefore,
                    notes = draft.notes
                )
                billFromTransaction = null
                plaidStatusMessage = "\"${draft.name}\" added to your bills."
            }
        )
    }

    if (showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { if (!disconnectBusy) showDisconnectConfirm = false },
            title = { Text("Disconnect Plaid?") },
            text = {
                Text(
                    "This unlinks Plaid on this phone only. Accounts, balances, and transactions stay here. " +
                        "Your saved link remains on the server — use Restore saved link to reconnect without a new Trial slot."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        disconnectBusy = true
                        vm.disconnectPlaidLink(bankId) { result ->
                            disconnectBusy = false
                            showDisconnectConfirm = false
                            result.onSuccess { itemId ->
                                preferredRestoreItemId = itemId
                                plaidStatusMessage =
                                    "Plaid disconnected. Accounts and transactions are still on this phone.\n\n" +
                                    "Tap Restore saved link below to reconnect without relinking."
                            }.onFailure { e ->
                                plaidStatusMessage = e.message ?: "Could not disconnect Plaid"
                            }
                        }
                    },
                    enabled = !disconnectBusy
                ) {
                    Text(if (disconnectBusy) "Disconnecting…" else "Disconnect")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDisconnectConfirm = false },
                    enabled = !disconnectBusy
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    plaidBlockMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { plaidBlockMessage = null },
            title = { Text("Cannot connect") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { plaidBlockMessage = null }) { Text("OK") }
            }
        )
    }

    if (showReconnectPasswordDialog) {
        AlertDialog(
            onDismissRequest = {
                showReconnectPasswordDialog = false
                reconnectPassword = ""
                reconnectPasswordError = null
            },
            title = { Text("Reconnect via Plaid") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Enter the family reconnect password. Reconnecting may use a Plaid Trial slot if the saved link cannot be repaired.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = reconnectPassword,
                        onValueChange = {
                            reconnectPassword = it
                            reconnectPasswordError = null
                        },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    reconnectPasswordError?.let { error ->
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (reconnectPassword == FamilyAppConfig.PLAID_RECONNECT_PASSWORD) {
                            showReconnectPasswordDialog = false
                            reconnectPassword = ""
                            reconnectPasswordError = null
                            beginPlaidConnect()
                        } else {
                            reconnectPasswordError = "Incorrect password"
                        }
                    }
                ) {
                    Text("Continue")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showReconnectPasswordDialog = false
                        reconnectPassword = ""
                        reconnectPasswordError = null
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    plaidConfirmMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { plaidConfirmMessage = null },
            title = { Text("Confirm Plaid connection") },
            text = { Text(message) },
            confirmButton = {
                Button(onClick = {
                    val bank = item.bank
                    plaidConfirmMessage = null
                    plaidLinkBusy = true
                    vm.preparePlaidLink(
                        bank = bank,
                        onReady = { handler ->
                            plaidLinkBusy = false
                            plaidLauncher.launch(handler)
                        },
                        onError = { message ->
                            plaidLinkBusy = false
                            plaidBlockMessage = message
                        }
                    )
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { plaidConfirmMessage = null }) { Text("Cancel") }
            }
        )
    }

    plaidStatusMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { plaidStatusMessage = null },
            title = { Text("Plaid") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { plaidStatusMessage = null }) { Text("OK") }
            }
        )
    }

    if (showAddAccount) {
        AccountDialog(
            title = "Add account",
            onDismiss = { showAddAccount = false },
            onConfirm = { name, type, notes, includeInFreeToSpend ->
                vm.addAccount(bankId, name, type, notes, includeInFreeToSpend)
                showAddAccount = false
            }
        )
    }

    editingAccount?.let { account ->
        AccountDialog(
            title = "Edit account",
            initialName = account.name,
            initialType = account.accountType,
            initialNotes = account.notes,
            initialIncludeInFreeToSpend = account.includeInFreeToSpend,
            onDismiss = { editingAccount = null },
            onConfirm = { name, type, notes, includeInFreeToSpend ->
                vm.updateAccount(
                    account.copy(
                        name = name,
                        accountType = type,
                        notes = notes,
                        includeInFreeToSpend = includeInFreeToSpend
                    )
                )
                editingAccount = null
            },
            onDelete = {
                vm.deleteAccount(account)
                editingAccount = null
            }
        )
    }
}

@Composable
private fun PlaidAccountTransactionHeader(
    accountLabel: String,
    transactionCount: Int
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
    ) {
        Text(
            accountLabel,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            "$transactionCount transaction(s)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private data class PlaidAccountTransactionGroup(
    val accountLabel: String,
    val sortOrder: Int,
    val transactions: List<PlaidTransactionEntity>
)

private fun groupPlaidTransactionsByAccount(
    transactions: List<PlaidTransactionEntity>,
    accounts: List<AccountEntity>
): List<PlaidAccountTransactionGroup> {
    val accountByPlaidId = accounts.mapNotNull { account ->
        account.plaidAccountId?.let { it to account }
    }.toMap()

    return transactions
        .groupBy { it.plaidAccountId }
        .map { (plaidAccountId, txs) ->
            val account = accountByPlaidId[plaidAccountId]
            val label = account?.name ?: "Plaid account ···${plaidAccountId.takeLast(4)}"
            val sortOrder = account?.let { acc ->
                accounts.indexOfFirst { it.id == acc.id }.takeIf { it >= 0 }
            } ?: Int.MAX_VALUE
            PlaidAccountTransactionGroup(
                accountLabel = label,
                sortOrder = sortOrder,
                transactions = txs.sortedWith(
                    compareByDescending<PlaidTransactionEntity> { it.date }
                        .thenByDescending { it.plaidTransactionId }
                )
            )
        }
        .sortedWith(compareBy({ it.sortOrder }, { it.accountLabel }))
}

@Composable
private fun PlaidTransactionRow(
    tx: PlaidTransactionEntity,
    onAddAsBill: (() -> Unit)? = null
) {
    val amountLabel = if (tx.amountCents >= 0) {
        "-${MoneyFormatter.format(tx.amountCents)}"
    } else {
        "+${MoneyFormatter.format(-tx.amountCents)}"
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(tx.name, fontWeight = FontWeight.SemiBold)
                Text(
                    tx.date + if (tx.pending) " · pending" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                amountLabel,
                fontWeight = FontWeight.Medium,
                color = if (tx.amountCents >= 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )
            if (onAddAsBill != null) {
                IconButton(onClick = onAddAsBill) {
                    Icon(Icons.Default.Add, contentDescription = "Add as bill")
                }
            }
        }
    }
}

@Composable
private fun AddBankDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, (String) -> Unit) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add bank") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        error = null
                    },
                    label = { Text("Bank name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name) { message -> error = message } },
                enabled = name.isNotBlank()
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun AccountDialog(
    title: String,
    initialName: String = "",
    initialType: AccountType = AccountType.CHECKING,
    initialNotes: String = "",
    initialIncludeInFreeToSpend: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (String, AccountType, String, Boolean) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var name by remember { mutableStateOf(initialName) }
    var type by remember { mutableStateOf(initialType) }
    var notes by remember { mutableStateOf(initialNotes) }
    var includeInFreeToSpend by remember { mutableStateOf(initialIncludeInFreeToSpend) }
    var typeExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Account name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = type.label,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Type") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { typeExpanded = !typeExpanded }
                )
                if (typeExpanded) {
                    AccountType.entries.forEach { option ->
                        TextButton(onClick = {
                            type = option
                            typeExpanded = false
                        }) {
                            Text(option.label)
                        }
                    }
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
                if (type == AccountType.SAVINGS) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Count in free to spend")
                            Text(
                                "Turn off for savings you treat as off-limits",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = includeInFreeToSpend,
                            onCheckedChange = { includeInFreeToSpend = it }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, type, notes, includeInFreeToSpend) },
                enabled = name.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                onDelete?.let {
                    OutlinedButton(onClick = it) { Text("Delete") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
