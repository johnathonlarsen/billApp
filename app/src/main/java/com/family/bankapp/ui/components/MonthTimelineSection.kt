package com.family.bankapp.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.family.bankapp.util.MoneyFormatter
import com.family.bankapp.util.MonthBillEntry
import com.family.bankapp.util.MonthOverview
import com.family.bankapp.util.MonthPillStatus
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val PillGrey = Color(0xFFBDBDBD)
private val PillGreyText = Color(0xFF616161)
private val PillGreen = Color(0xFF43A047)
private val PillGreenContainer = Color(0xFFC8E6C9)
private val PillYellow = Color(0xFFFFB300)
private val PillYellowContainer = Color(0xFFFFF3C4)

@Composable
fun MonthTimelineSection(
    months: List<MonthOverview>,
    expandedMonth: YearMonth?,
    onMonthClick: (YearMonth) -> Unit,
    modifier: Modifier = Modifier,
    onMarkBillPaid: ((MonthBillEntry) -> Unit)? = null,
    onEditBillPayment: ((MonthBillEntry) -> Unit)? = null,
    onRemoveBillFromMonth: ((MonthBillEntry) -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        Text(
            "Month overview",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        Text(
            "Grey = no data · Green = all paid · Yellow = bills outstanding",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 0.dp)
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(months, key = { it.yearMonth.toString() }) { month ->
                MonthPill(
                    month = month,
                    selected = expandedMonth == month.yearMonth,
                    onClick = { onMonthClick(month.yearMonth) }
                )
            }
        }

        expandedMonth?.let { selected ->
            months.find { it.yearMonth == selected }?.let { overview ->
                MonthDetailCard(
                    overview = overview,
                    onMarkBillPaid = onMarkBillPaid,
                    onEditBillPayment = onEditBillPayment,
                    onRemoveBillFromMonth = onRemoveBillFromMonth
                )
            }
        }
    }
}

@Composable
private fun MonthPill(
    month: MonthOverview,
    selected: Boolean,
    onClick: () -> Unit
) {
    val (background, textColor) = when (month.status) {
        MonthPillStatus.EMPTY -> PillGrey to PillGreyText
        MonthPillStatus.ALL_PAID -> PillGreen to Color.White
        MonthPillStatus.PARTIAL -> PillYellow to Color(0xFF5D4037)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(background)
            .then(
                if (selected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp))
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                month.label,
                color = textColor,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            if (!month.isPaddingMonth && month.totalCount > 0) {
                Text(
                    "${month.paidCount}/${month.totalCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.9f)
                )
            }
        }
    }
}

@Composable
private fun MonthDetailCard(
    overview: MonthOverview,
    onMarkBillPaid: ((MonthBillEntry) -> Unit)?,
    onEditBillPayment: ((MonthBillEntry) -> Unit)?,
    onRemoveBillFromMonth: ((MonthBillEntry) -> Unit)?
) {
    val dateFormatter = DateTimeFormatter.ofPattern("MMM d")
    val containerColor = when (overview.status) {
        MonthPillStatus.EMPTY -> MaterialTheme.colorScheme.surfaceVariant
        MonthPillStatus.ALL_PAID -> PillGreenContainer
        MonthPillStatus.PARTIAL -> PillYellowContainer
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(overview.fullLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            when {
                overview.isPaddingMonth || overview.bills.isEmpty() -> {
                    Text(
                        "No bill data for this month.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${overview.paidCount} of ${overview.totalCount} paid")
                        Text(
                            MoneyFormatter.format(overview.totalPaidCents) +
                                " / " + MoneyFormatter.format(overview.totalDueCents),
                            fontWeight = FontWeight.Medium
                        )
                    }

                    overview.bills.forEach { entry ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = if (entry.isPaid) Icons.Default.Check else Icons.Default.Schedule,
                                    contentDescription = null,
                                    tint = if (entry.isPaid) PillGreen else PillYellow
                                )
                                Column {
                                    Text(entry.bill.name, fontWeight = FontWeight.Medium)
                                    Text(
                                        "Due ${entry.dueDate.format(dateFormatter)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                val paidAmount = entry.payment?.amountCents ?: entry.bill.amountCents
                                Text(
                                    MoneyFormatter.format(paidAmount),
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (entry.isPaid &&
                                    entry.payment != null &&
                                    entry.payment.amountCents > entry.bill.amountCents
                                ) {
                                    Text(
                                        "Usual ${MoneyFormatter.format(entry.bill.amountCents)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (!entry.isPaid && onRemoveBillFromMonth != null) {
                                        IconButton(onClick = { onRemoveBillFromMonth(entry) }) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Remove from this month"
                                            )
                                        }
                                    }
                                    if (entry.isPaid) {
                                        if (onEditBillPayment != null) {
                                            IconButton(onClick = { onEditBillPayment(entry) }) {
                                                Icon(
                                                    Icons.Default.Edit,
                                                    contentDescription = "Edit payment"
                                                )
                                            }
                                        }
                                        Text(
                                            "Paid",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = PillGreen
                                        )
                                    } else if (onMarkBillPaid != null) {
                                        Button(
                                            onClick = { onMarkBillPaid(entry) },
                                            modifier = Modifier.padding(top = 4.dp)
                                        ) {
                                            Text("Mark paid", style = MaterialTheme.typography.labelSmall)
                                        }
                                    } else {
                                        Text(
                                            "Unpaid",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = PillYellow
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (overview.status == MonthPillStatus.ALL_PAID) {
                        Text(
                            "All bills paid for this month.",
                            style = MaterialTheme.typography.bodySmall,
                            color = PillGreen
                        )
                    }
                }
            }
        }
    }
}
