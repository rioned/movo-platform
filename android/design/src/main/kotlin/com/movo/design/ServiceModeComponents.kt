package com.movo.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** The icon that stands for a product wherever it appears in either app. */
val MovoServiceMode.icon: ImageVector
    get() = if (isRide) Icons.Filled.Person else Icons.Filled.MailOutline

/**
 * The accent a product carries. Rides take the amber that MOVO already uses for
 * movement and money; deliveries keep the brand forest. Two products a rider
 * must tell apart in a two-second glance need to differ by colour, not just text.
 */
@Composable
fun MovoServiceMode.accent(): Color =
    if (isRide) MovoTheme.status.warning else MaterialTheme.colorScheme.primary

@Composable
fun MovoServiceMode.accentContainer(): Color =
    if (isRide) MovoTheme.status.warningContainer else MaterialTheme.colorScheme.primaryContainer

/**
 * Compact product marker for lists, offer cards and history rows — the fastest
 * way for a rider to see that the job on screen carries a person, not a parcel.
 */
@Composable
fun ServiceModeBadge(
    mode: MovoServiceMode,
    modifier: Modifier = Modifier,
    label: String = if (mode.isRide) "Ride" else "Delivery"
) {
    val foreground = mode.accent()
    Surface(shape = CircleShape, color = mode.accentContainer(), modifier = modifier) {
        Row(
            Modifier.padding(horizontal = MovoSpacing.medium, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(mode.icon, contentDescription = null, tint = foreground, modifier = Modifier.size(14.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = foreground, maxLines = 1)
        }
    }
}

/**
 * A full-width product card for the chooser a customer sees after signing in.
 *
 * Deliberately large: this is the first decision of the session, it is taken
 * one-handed, and picking the wrong product costs a whole abandoned booking.
 */
@Composable
fun ModeChoiceCard(
    mode: MovoServiceMode,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    highlights: List<String> = emptyList()
) {
    val accent = mode.accent()
    val border by animateColorAsState(
        if (selected) accent else MaterialTheme.colorScheme.outlineVariant,
        label = "modeCardBorder"
    )
    val elevation by animateDpAsState(if (selected) 10.dp else 1.dp, label = "modeCardElevation")

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        shape = MovoShapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, border),
        shadowElevation = elevation
    ) {
        Column(Modifier.padding(MovoSpacing.large)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(52.dp).clip(MovoShapes.medium).background(mode.accentContainer()),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(mode.icon, contentDescription = null, tint = accent, modifier = Modifier.size(26.dp))
                }
                Column(Modifier.weight(1f).padding(horizontal = MovoSpacing.default)) {
                    Text(mode.title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        mode.tagline,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // The selected state is announced by `selectable`, so the tick is
                // decorative and must not be read out a second time.
                if (selected) {
                    Box(
                        Modifier.size(26.dp).clip(CircleShape).background(accent),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Check, contentDescription = null,
                            tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            if (highlights.isNotEmpty()) {
                Spacer(Modifier.height(MovoSpacing.medium))
                highlights.forEach { highlight ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MovoSpacing.small)
                    ) {
                        Box(Modifier.size(5.dp).clip(CircleShape).background(accent))
                        Text(
                            highlight,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * The in-app product switcher. Sits above the working area so changing product is
 * always one tap away — the chooser at sign-in sets a starting point, not a trap.
 */
@Composable
fun ModeSwitcher(
    selected: MovoServiceMode,
    onSelect: (MovoServiceMode) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 2.dp
    ) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MovoServiceMode.entries.forEach { mode ->
                val active = mode == selected
                val accent = mode.accent()
                Surface(
                    shape = CircleShape,
                    color = if (active) accent else Color.Transparent,
                    modifier = Modifier.selectable(
                        selected = active,
                        enabled = enabled,
                        role = Role.Tab,
                        onClick = { if (mode != selected) onSelect(mode) }
                    )
                ) {
                    Row(
                        Modifier.padding(horizontal = MovoSpacing.default, vertical = MovoSpacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            mode.icon, contentDescription = null,
                            tint = if (active) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            mode.action,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (active) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
