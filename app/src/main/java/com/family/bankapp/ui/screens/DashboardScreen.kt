package com.family.bankapp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.lifecycle.viewmodel.compose.viewModel
import com.family.bankapp.ui.components.PlaidUsageTrackerCard
import com.family.bankapp.ui.components.MoneyText
import com.family.bankapp.ui.components.SectionHeader
import com.family.bankapp.ui.components.StatCard
import com.family.bankapp.ui.viewmodel.DashboardViewModel
import com.family.bankapp.util.FreeToSpendSnapshot
import com.family.bankapp.util.MoneyFormatter
import com.family.bankapp.util.MonthPillStatus
import java.time.format.DateTimeFormatter

private val StatusGreen = Color(0xFF43A047)
private val StatusYellow = Color(0xFFFFB300)

@Composable
fun DashboardScreen(padding: PaddingValues) {
    val vm: DashboardViewModel = viewModel()
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val formatter = DateTimeFormatter.ofPattern("MMM d")

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            PlaidUsageTrackerCard(
                modifier = Modifier.padding(horizontal = 16.dp),
                compact = true
            )
        }

        item {
            Column(Modifier.padding(16.dp)) {
                Text("Bill tracker", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${state.activeBillCount} active bill(s) · ${state.overview.banks.size} bank(s) for pay-from labels",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        state.freeToSpend?.let { fts ->
            item {
                FreeToSpendCard(
                    fts = fts,
                    onCopyDebug = {
                        vm.buildFreeToSpendDebugReport()?.let { text ->
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Free to spend debug", text))
                            Toast.makeText(context, "Debug info copied to clipboard", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }

        state.currentMonth?.let { month ->
            if (!month.isPaddingMonth && month.totalCount > 0) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = when (month.status) {
                                MonthPillStatus.ALL_PAID -> Color(0xFFC8E6C9)
                                MonthPillStatus.PARTIAL -> Color(0xFFFFF3C4)
                                MonthPillStatus.EMPTY -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("This month — ${month.fullLabel}", fontWeight = FontWeight.Bold)
                            Text(
                                when (month.status) {
                                    MonthPillStatus.ALL_PAID -> "All ${month.totalCount} bills paid"
                                    MonthPillStatus.PARTIAL ->
                                        "${month.paidCount} of ${month.totalCount} paid · " +
                                            MoneyFormatter.format(month.totalDueCents - month.totalPaidCents) +
                                            " still due"
                                    MonthPillStatus.EMPTY -> "No bills this month"
                                }
                            )
                        }
                    }
                }
            }
        }

        item {
            Row(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatCard(
                    title = "Due soon",
                    cents = state.upcomingTotalCents,
                    modifier = Modifier.weight(1f),
                    subtitle = "Next ${state.forecastDays} days"
                )
            }
        }

        if (state.overdueBills.isNotEmpty()) {
            item { SectionHeader("Overdue") }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Warning, contentDescription = null)
                        Column {
                            Text(
                                "${state.overdueBills.size} overdue · ${MoneyFormatter.format(state.overdueTotalCents)}",
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "See Bills tab to mark paid or update",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
            items(state.overdueBills) { item ->
                BillSummaryCard(
                    name = item.dueInfo.bill.name,
                    subtitle = "Due ${item.dueInfo.dueDate.format(formatter)} · overdue",
                    payFrom = item.payFromLabel,
                    amountCents = item.dueInfo.bill.amountCents,
                    accentColor = MaterialTheme.colorScheme.error
                )
            }
        }

        item {
            SectionHeader(
                title = "Coming up (${state.forecastDays} days)",
                action = {
                    Text(
                        MoneyFormatter.format(state.upcomingTotalCents),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            )
        }

        if (state.upcomingBills.isEmpty()) {
            item {
                Text(
                    "No unpaid bills in the next ${state.forecastDays} days.",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(state.upcomingBills) { item ->
                BillSummaryCard(
                    name = item.dueInfo.bill.name,
                    subtitle = "Due ${item.dueInfo.dueDate.format(formatter)} · ${item.dueInfo.bill.category.label}",
                    payFrom = item.payFromLabel,
                    amountCents = item.dueInfo.bill.amountCents,
                    accentColor = if (item.dueInfo.daysUntilDue <= 3) StatusYellow else MaterialTheme.colorScheme.onSurface
                )
            }
        }

        if (state.activeBillCount == 0) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Get started", fontWeight = FontWeight.Bold)
                        Text(
                            "Add banks and accounts on the Banks tab (optional — for “pay from” labels). " +
                                "Then add bills on the Bills tab to track due dates and mark them paid.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FreeToSpendCard(
    fts: FreeToSpendSnapshot,
    onCopyDebug: () -> Unit
) {
    val isShort = fts.freeToSpendCents < 0
    val headlineColor = if (isShort) MaterialTheme.colorScheme.error else StatusGreen
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (isShort) "Over budget" else "Free to spend",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onCopyDebug) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = "Copy formula debug info"
                    )
                }
            }
            MoneyText(
                fts.freeToSpendCents,
                color = headlineColor,
                style = MaterialTheme.typography.headlineMedium,
                showSign = isShort
            )
            Text(
                if (isShort) {
                    "${MoneyFormatter.format(-fts.freeToSpendCents)} over your ${fts.currentMonthLabel} budget"
                } else {
                    "Left from ${fts.currentMonthLabel} income after bills & misc spent"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                buildString {
                    append("Income ${MoneyFormatter.format(fts.monthlyIncomeCents)}")
                    append(" − bills ${MoneyFormatter.format(fts.fixedBillsThisMonthCents)}")
                    if (fts.miscPaidThisMonthCents > 0) {
                        append(" − misc ${MoneyFormatter.format(fts.miscPaidThisMonthCents)}")
                    }
                    if (fts.plaidMiscSpentCents > 0) {
                        append(" − spending ${MoneyFormatter.format(fts.plaidMiscSpentCents)}")
                    }
                    if (fts.priorOverdueUnpaidCents > 0) {
                        append(" − prior ${MoneyFormatter.format(fts.priorOverdueUnpaidCents)}")
                    }
                },
                style = MaterialTheme.typography.bodySmall
            )
            if (fts.miscUnpaidThisMonthCents > 0) {
                Text(
                    "Other bills not paid yet (${MoneyFormatter.format(fts.miscUnpaidThisMonthCents)}) " +
                        "don't reduce this until marked paid.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (fts.plaidMiscSpentCents > 0) {
                Text(
                    "Spending = Plaid debits this month not linked to a bill.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (fts.priorOverdueUnpaidCents > 0 && fts.includePriorOverdue) {
                Text(
                    "Prior unpaid bills count from when each bill was added — mark paid or turn off in Settings.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (fts.liquidBalanceCents > 0) {
                Text(
                    "Account balance (reference): ${MoneyFormatter.format(fts.liquidBalanceCents)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "Plan: ${MoneyFormatter.format(fts.monthlyIncomeCents)} income − " +
                    "${MoneyFormatter.format(fts.monthlyBillsCents)} bills = " +
                    "${MoneyFormatter.format(fts.plannedFreeMonthlyCents)}/mo free",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BillSummaryCard(
    name: String,
    subtitle: String,
    payFrom: String?,
    amountCents: Long,
    accentColor: Color
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(name, fontWeight = FontWeight.SemiBold)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    payFrom?.let {
                        Text(
                            "Pay from: $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                MoneyText(amountCents, color = accentColor, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
