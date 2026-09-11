package com.movo.rider.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.movo.design.EmptyState
import com.movo.design.MovoBanner
import com.movo.design.MovoCard
import com.movo.design.MovoPalette
import com.movo.design.MovoSpacing
import com.movo.design.MovoTone
import com.movo.design.SectionHeader
import com.movo.design.SegmentOption
import com.movo.design.SegmentedChoice
import com.movo.design.ShimmerCard
import com.movo.design.StatTile
import com.movo.design.formatRwf
import com.movo.design.formatTimestamp
import com.movo.design.plural
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

    val currentSummary = summary?.takeIf { summary?.period == period }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(MovoSpacing.default),
        verticalArrangement = Arrangement.spacedBy(MovoSpacing.medium)
    ) {
        item {
            Text("MOVO / EARNINGS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text("Your work, in numbers", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = MovoSpacing.small))
            Text("A clear view of every completed delivery.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
        SegmentedChoice(
            options = listOf(
                SegmentOption("today", "Today"),
                SegmentOption("week", "This week"),
                SegmentOption("month", "This month")
            ),
            selected = period,
            onSelect = { period = it; onPeriodChange(it) },
            enabled = !loading
        )
        }
        error?.let { item { MovoBanner(it, MovoTone.Critical) } }
        if (loading) {
            item { ShimmerCard() }
        } else if (currentSummary != null) {
        item {
        MovoCard(color = MovoPalette.ForestDeep, elevation = 0.dp) {
            Text("Net earnings / RWF", style = MaterialTheme.typography.labelMedium, color = MovoPalette.Lime)
            Spacer(Modifier.height(MovoSpacing.small))
            Text(
                formatRwf(currentSummary.total),
                style = MaterialTheme.typography.displaySmall,
                color = Color.White
            )
            Spacer(Modifier.height(MovoSpacing.medium))
            Text(
                "${plural(currentSummary.count, "completed delivery", "completed deliveries")} • ${formatRwf(currentSummary.platformFees)} platform fees",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f)
            )
        }
        }
        } else if (error == null) {
            item { EmptyState("Earnings not available yet", "Choose a period to load your delivery earnings.") }
        }
        performance?.let { stats ->
            item {
            MovoCard {
                SectionHeader("Performance")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MovoSpacing.small)) {
                    Column(Modifier.weight(1f)) { StatTile("Deliveries", "${stats.totalDeliveries}") }
                    Column(Modifier.weight(1f)) { StatTile("Rating", if (stats.ratingCount > 0) String.format(java.util.Locale.US, "%.1f", stats.rating) else "—") }
                }
                Spacer(Modifier.height(MovoSpacing.medium))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MovoSpacing.small)) {
                    Column(Modifier.weight(1f)) { StatTile("Acceptance", "${stats.acceptanceRate}%") }
                    Column(Modifier.weight(1f)) { StatTile("Cancellation", "${stats.cancellationRate}%") }
                }
            }
            }
        }
        item { SectionHeader("Recent settlements") }
        if (!loading && currentSummary != null && currentSummary.entries.isEmpty()) {
            item {
            EmptyState(
                title = "No completed deliveries in this period",
                message = "Completed deliveries will appear here with your earnings in RWF."
            )
            }
        } else if (!loading && currentSummary != null) {
                items(currentSummary.entries) { entry ->
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
                                    Text(formatTimestamp(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Text(formatRwf(entry.amount), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
        }
    }
}
