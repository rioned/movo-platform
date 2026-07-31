package com.movo.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

enum class MovoButtonTone { Primary, Accent, Danger }

/**
 * The single call-to-action used across MOVO.
 *
 * Full-width and 56 dp tall by default: the spec asks for large action buttons
 * (§18) because riders tap while wearing gloves and customers while walking.
 */
@Composable
fun MovoButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    tone: MovoButtonTone = MovoButtonTone.Primary,
    leading: @Composable (() -> Unit)? = null
) {
    val container = when (tone) {
        MovoButtonTone.Primary -> MaterialTheme.colorScheme.primary
        MovoButtonTone.Accent -> MaterialTheme.colorScheme.secondary
        MovoButtonTone.Danger -> MaterialTheme.colorScheme.error
    }
    val content = when (tone) {
        MovoButtonTone.Primary -> MaterialTheme.colorScheme.onPrimary
        MovoButtonTone.Accent -> MaterialTheme.colorScheme.onSecondary
        MovoButtonTone.Danger -> MaterialTheme.colorScheme.onError
    }
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.fillMaxWidth().heightIn(min = MovoSpacing.actionHeight),
        shape = MovoShapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(22.dp), color = content, strokeWidth = 2.dp)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                leading?.let { it(); Spacer(Modifier.width(MovoSpacing.small)) }
                Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
fun MovoSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: Color = MaterialTheme.colorScheme.primary,
    leading: @Composable (() -> Unit)? = null
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = MovoShapes.medium,
        border = BorderStroke(1.5.dp, if (enabled) tone else MaterialTheme.colorScheme.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = tone)
    ) {
        leading?.let { it(); Spacer(Modifier.width(MovoSpacing.small)) }
        Text(text, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
fun MovoTextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}
