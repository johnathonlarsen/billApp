package com.family.bankapp.ui.screens

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.family.bankapp.ui.components.MoneyText
import com.family.bankapp.ui.viewmodel.IncomeViewModel
import com.family.bankapp.util.MoneyFormatter

@Composable
fun IncomeScreen(
    padding: PaddingValues,
    onAddIncome: () -> Unit,
    onEditIncome: (Long) -> Unit
) {
    val vm: IncomeViewModel = viewModel()
    val incomes by vm.incomes.collectAsState()
    val monthlyTotal = incomes.sumOf { it.monthlyEquivalentCents }

    Box(Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(
            contentPadding = PaddingValues(bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Column(Modifier.padding(16.dp)) {
                    Text("Income", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Paychecks and other deposits — used for free-to-spend on Home",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (incomes.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Estimated monthly income", fontWeight = FontWeight.SemiBold)
                            MoneyText(
                                monthlyTotal,
                                style = MaterialTheme.typography.headlineSmall
                            )
                            Text(
                                "From ${incomes.size} source(s)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    "Income sources",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (incomes.isEmpty()) {
                item {
                    Text(
                        "No income yet. Tap + to add a paycheck or other recurring deposit.",
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(incomes, key = { it.income.id }) { item ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clickable { onEditIncome(item.income.id) }
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(item.income.name, fontWeight = FontWeight.SemiBold)
                                MoneyText(item.income.amountCents)
                            }
                            Text(
                                "${item.income.recurrence.label} · ~${MoneyFormatter.format(item.monthlyEquivalentCents)}/mo",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            item.linkedAccountLabel?.let { label ->
                                Text(
                                    "Deposits to: $label",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            OutlinedButton(onClick = { onEditIncome(item.income.id) }) {
                                Text("Edit")
                            }
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onAddIncome,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add income")
        }
    }
}
