package com.family.bankapp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.family.bankapp.FamilyAppConfig
import com.family.bankapp.plaid.PlaidApiBudget
import com.family.bankapp.plaid.PlaidUsage
import com.family.bankapp.ui.util.findComponentActivity
import com.family.bankapp.ui.viewmodel.PlaidTrackerViewModel

@Composable
fun PlaidUsageTrackerCard(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    vm: PlaidTrackerViewModel? = null
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findComponentActivity() }
    val trackerVm = vm ?: viewModel(activity)
    val itemUsage by trackerVm.itemUsage.collectAsState()
    val apiBudget by trackerVm.apiBudget.collectAsState()
    val error by trackerVm.error.collectAsState()
    val loading by trackerVm.loading.collectAsState()
    val localCount by trackerVm.localPlaidCount.collectAsState()

    PlaidUsageTrackerContent(
        modifier = modifier,
        compact = compact,
        itemUsage = itemUsage,
        apiBudget = apiBudget,
        localPlaidCount = localCount,
        error = error,
        loading = loading,
        onRefresh = trackerVm::refresh
    )
}

@Composable
fun PlaidUsageTrackerContent(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    itemUsage: PlaidUsage?,
    apiBudget: PlaidApiBudget?,
    localPlaidCount: Int,
    error: String?,
    loading: Boolean,
    onRefresh: () -> Unit
) {
    val slotsUsed = itemUsage?.used ?: localPlaidCount
    val slotsLimit = itemUsage?.limit ?: 10
    val slotsRemaining = itemUsage?.remaining ?: (slotsLimit - localPlaidCount).coerceAtLeast(0)

    val budgetUsed = apiBudget?.used ?: 0
    val budgetLimit = apiBudget?.limit ?: FamilyAppConfig.PLAID_API_MONTHLY_LIMIT
    val budgetRemaining = apiBudget?.remaining ?: budgetLimit
    val budgetProgress = if (budgetLimit > 0) budgetUsed.toFloat() / budgetLimit else 0f

    val budgetContainerColor = when {
        apiBudget?.atLimit == true -> MaterialTheme.colorScheme.errorContainer
        budgetRemaining <= 20 -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = budgetContainerColor)
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Plaid usage (shared)", fontWeight = FontWeight.Bold)

            Text(
                if (compact) {
                    "Bank connections: $slotsUsed / $slotsLimit · $slotsRemaining left"
                } else {
                    "Bank connections: $slotsUsed / $slotsLimit used · $slotsRemaining left"
                },
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                if (compact) {
                    "API calls (month): $budgetUsed / $budgetLimit · $budgetRemaining left"
                } else {
                    "API calls this month: $budgetUsed / $budgetLimit used · $budgetRemaining left"
                },
                style = MaterialTheme.typography.bodyMedium
            )

            LinearProgressIndicator(
                progress = { budgetProgress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )

            apiBudget?.periodMonth?.let { month ->
                Text(
                    "Resets monthly (UTC). Period: $month",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!compact) {
                Text(
                    "Each transaction sync page = 1 call. Daily sync for 3 banks ≈ 90 calls/mo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            OutlinedButton(
                onClick = onRefresh,
                enabled = !loading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (loading) "Refreshing…" else "Refresh usage")
            }
        }
    }
}
