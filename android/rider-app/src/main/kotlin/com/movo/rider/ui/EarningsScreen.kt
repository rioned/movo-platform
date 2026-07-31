package com.movo.rider.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.movo.design.EmptyState
import com.movo.design.MovoBanner
import com.movo.design.MovoCard
import com.movo.design.MovoSpacing
import com.movo.design.MovoTone
import com.movo.design.SectionHeader
import com.movo.design.SegmentOption
import com.movo.design.SegmentedChoice
import com.movo.design.ShimmerCard
import com.movo.design.StatTile
import com.movo.design.formatRwf
import com.movo.rider.model.EarningsSummary
import com.movo.rider.model.PerformanceStats

/**
 * Earnings and performance in one place (spec §7.8/§7.9) — what the rider made,
 * and the behaviour that keeps them eligible for the best offers.
 */
@Composable
fun EarningsScreen(
    summary: EarningsSummary?,
    performance: PerformanceStats?,
    loading: Boolean,
    error: String?,
    onPeriodChange: (String) -> Unit
) {
    var period by remember { mutableStateOf("today") }

    Column(Modifier.fillMaxSize().padding(horizontal = MovoSpacing.default)) {
        Text("Earnings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = MovoSpacing.medium))
        SegmentedChoice(
            options = listOf(
                SegmentOption("today", "Today"),
                SegmentOption("week", "This week"),
                SegmentOption("month", "This month")
            ),
            selected = period,
            onSelect = { period = it; onPeriodChange(it) }
        )
        Spacer(Modifier.height(MovoSpacing.medium))
        error?.let { MovoBanner(it, MovoTone.Critical); Spacer(Modifier.height(MovoSpacing.small)) }

        if (loading && summary == null) {
            ShimmerCard(); Spacer(Modifier.height(MovoSpacing.small)); ShimmerCard()
            return@Column
        }

        MovoCard(color = MaterialTheme.colorScheme.primaryContainer, elevation = 0.dp) {
            Text("Net earnings", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                formatRwf(summary?.total ?: 0.0),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                "${summary?.count ?: 0} completed deliveries • ${formatRwf(summary?.platformFees ?: 0.0)} platform fees",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        Spacer(Modifier.height(MovoSpacing.medium))
        performance?.let { stats ->
            MovoCard {
                SectionHeader("Performance")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    StatTile("Deliveries", "${stats.totalDeliveries}")
                    StatTile("Acceptance", "${stats.acceptanceRate}%")
                    StatTile("Cancellation", "${stats.cancellationRate}%")
                    StatTile("Rating", if (stats.ratingCount > 0) String.format("%.1f", stats.rating) else "—")
                }
            }
            Spacer(Modifier.height(MovoSpacing.medium))
        }

        SectionHeader("Recent settlements")
        if (summary == null || summary.entries.isEmpty()) {
            EmptyState(
                title = "No completed deliveries in this period",
                message = "Go online to start receiving delivery offers near you."
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(MovoSpacing.small),
                contentPadding = PaddingValues(bottom = MovoSpacing.section)
            ) {
                items(summary.entries) { entry ->
                    MovoCard {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.orderNo, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    entry.route,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2
                                )
                                entry.completedAt?.let {
                                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Text(formatRwf(entry.amount), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
