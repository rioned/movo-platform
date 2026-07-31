package com.movo.design

import android.animation.ValueAnimator
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

enum class MovoTone { Positive, Warning, Critical, Neutral, Info }

@Composable
private fun toneColors(tone: MovoTone): Pair<Color, Color> {
    val status = MovoTheme.status
    return when (tone) {
        MovoTone.Positive -> status.positive to status.positiveContainer
        MovoTone.Warning -> status.warning to status.warningContainer
        MovoTone.Critical -> status.critical to status.criticalContainer
        MovoTone.Neutral -> status.neutral to status.neutralContainer
        MovoTone.Info -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.primaryContainer
    }
}

/** Small state chip: delivery status, rider availability, payment state. */
@Composable
fun StatusPill(text: String, tone: MovoTone = MovoTone.Neutral, modifier: Modifier = Modifier, showDot: Boolean = true) {
    val (foreground, background) = toneColors(tone)
    Surface(shape = CircleShape, color = background, modifier = modifier) {
        Row(
            Modifier.padding(horizontal = MovoSpacing.medium, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (showDot) Box(Modifier.size(7.dp).clip(CircleShape).background(foreground))
            Text(text, style = MaterialTheme.typography.labelMedium, color = foreground, maxLines = 1)
        }
    }
}

/** Inline banner for connectivity, warnings and recoverable errors. */
@Composable
fun MovoBanner(
    text: String,
    tone: MovoTone = MovoTone.Info,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    val (foreground, background) = toneColors(tone)
    Surface(modifier.fillMaxWidth(), shape = MovoShapes.small, color = background) {
        Row(
            Modifier.padding(horizontal = MovoSpacing.medium, vertical = MovoSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MovoSpacing.small)
        ) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = foreground, modifier = Modifier.weight(1f))
            action?.invoke()
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    illustration: (@Composable () -> Unit)? = null,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier.fillMaxWidth().padding(MovoSpacing.xlarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MovoSpacing.small)
    ) {
        illustration?.invoke()
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        action?.let { Spacer(Modifier.height(MovoSpacing.small)); it() }
    }
}

/**
 * Shimmering placeholder. Honours the system "remove animations" setting, so the
 * accessibility preference is respected instead of overridden.
 */
@Composable
fun ShimmerBlock(modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 18.dp) {
    val reducedMotion = remember { !ValueAnimator.areAnimatorsEnabled() }
    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight = MaterialTheme.colorScheme.outlineVariant
    if (reducedMotion) {
        Box(modifier.fillMaxWidth().height(height).clip(MovoShapes.extraSmall).background(base))
        return
    }
    val transition = rememberInfiniteTransition(label = "shimmer")
    val offset by transition.animateFloat(
        initialValue = -2f, targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "shimmerOffset"
    )
    Box(
        modifier.fillMaxWidth().height(height).clip(MovoShapes.extraSmall).background(
            Brush.horizontalGradient(
                colors = listOf(base, highlight, base),
                startX = offset * 200f,
                endX = offset * 200f + 400f
            )
        )
    )
}

@Composable
fun ShimmerCard(modifier: Modifier = Modifier) {
    MovoCard(modifier) {
        ShimmerBlock(height = 14.dp, modifier = Modifier.fillMaxWidth(0.4f))
        Spacer(Modifier.height(MovoSpacing.small))
        ShimmerBlock(height = 20.dp)
        Spacer(Modifier.height(MovoSpacing.small))
        ShimmerBlock(height = 14.dp, modifier = Modifier.fillMaxWidth(0.7f))
    }
}

/**
 * Countdown ring for time-boxed decisions — the rider offer timer (spec §7.4
 * "time allowed to accept"). Progress runs 1f → 0f as the window closes.
 */
@Composable
fun CountdownRing(
    progress: Float,
    secondsRemaining: Int,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 56.dp
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val urgent = MovoTheme.status.critical
    val calm = MaterialTheme.colorScheme.primary
    val sweep = progress.coerceIn(0f, 1f)
    val color = if (sweep < 0.34f) urgent else calm
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
            val inset = stroke.width / 2
            val arcSize = Size(this.size.width - stroke.width, this.size.height - stroke.width)
            drawArc(track, 0f, 360f, false, topLeft = androidx.compose.ui.geometry.Offset(inset, inset), size = arcSize, style = stroke)
            drawArc(color, -90f, 360f * sweep, false, topLeft = androidx.compose.ui.geometry.Offset(inset, inset), size = arcSize, style = stroke)
        }
        Text("$secondsRemaining", style = MaterialTheme.typography.titleMedium, color = color)
    }
}

/** Five-star rating display, optionally interactive. */
@Composable
fun RatingStars(rating: Double, modifier: Modifier = Modifier, onRate: ((Int) -> Unit)? = null, starSize: androidx.compose.ui.unit.Dp = 20.dp) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(MovoSpacing.tiny)) {
        for (star in 1..5) {
            val filled = rating >= star - 0.25
            Text(
                text = if (filled) "★" else "☆",
                style = MaterialTheme.typography.titleMedium,
                color = if (filled) MovoPalette.Amber else MaterialTheme.colorScheme.outline,
                modifier = if (onRate != null) {
                    Modifier.size(starSize + 10.dp).clip(CircleShape).clickable { onRate(star) }
                        .padding(MovoSpacing.tiny)
                } else Modifier
            )
        }
    }
}
