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
import com.family.bankapp.data.entity.PlaidTransactionEntity
import com.family.bankapp.data.model.AccountType
import com.family.bankapp.util.MoneyFormatter
import com.family.bankapp.data.repository.BankRepository
import com.family.bankapp.plaid.PlaidNameMatcher
import com.family.bankapp.plaid.PlaidRestorableItem
import com.family.bankapp.ui.components.PlaidUsageTrackerCard
import com.family.bankapp.ui.components.SectionHeader
import com.family.bankapp.ui.components.parseColorHex
import com.family.bankapp.ui.viewmodel.BankWithAccounts
import com.family.bankapp.ui.viewmodel.BanksViewModel
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

    val plaidTransactions by vm.observePlaidTransactions(bankId).collectAsState(initial = emptyList())

    val isPlaidConnected = !item?.bank?.plaidItemId.isNullOrBlank()
    val hasUnrestoredLinks = restorableItems.isNotEmpty()
    val matchingRestore = remember(item?.bank?.name, restorableItems) {
        val bankName = item?.bank?.name ?: return@remember null
        restorableItems.firstOrNull { PlaidNameMatcher.likelySameBank(bankName, it.institutionName) }
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
                                enabled = !plaidSyncBusy && !plaidLinkBusy,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (plaidSyncBusy) "Syncing…" else "Sync from Plaid")
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
                                restorableItems.forEach { link ->
                                    OutlinedButton(
                                        onClick = {
                                            restoreBusyItemId = link.itemId
                                            vm.restorePlaidLink(bankId, link.itemId) { result ->
                                                restoreBusyItemId = null
                                                result.onSuccess { sync ->
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
                                plaidConnectPending = true
                                vm.checkBeforePlaidConnect(item.bank) { check ->
                                    plaidConnectPending = false
                                    if (!check.allowed) {
                                        plaidBlockMessage = check.blockReason
                                    } else {
                                        plaidConfirmMessage = check.confirmMessage
                                    }
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

            if (isPlaidConnected) {
                item { SectionHeader("Recent transactions") }
                if (plaidTransactions.isEmpty()) {
                    item {
                        Text(
                            "No transactions yet — tap Sync from Plaid.",
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(plaidTransactions) { tx ->
                        PlaidTransactionRow(tx)
                    }
                }
            }
        }
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
            onConfirm = { name, type, notes ->
                vm.addAccount(bankId, name, type, notes)
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
            onDismiss = { editingAccount = null },
            onConfirm = { name, type, notes ->
                vm.updateAccount(
                    account.copy(
                        name = name,
                        accountType = type,
                        notes = notes
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
private fun PlaidTransactionRow(tx: PlaidTransactionEntity) {
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
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
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
    onDismiss: () -> Unit,
    onConfirm: (String, AccountType, String) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var name by remember { mutableStateOf(initialName) }
    var type by remember { mutableStateOf(initialType) }
    var notes by remember { mutableStateOf(initialNotes) }
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
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(name, type, notes) }, enabled = name.isNotBlank()) {
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
