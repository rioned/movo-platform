package com.movo.customer.mode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.movo.design.ModeChoiceCard
import com.movo.design.MovoButton
import com.movo.design.MovoServiceMode
import com.movo.design.MovoSpacing
import com.movo.design.MovoTextAction

/**
 * The first decision of a session: which MOVO product the customer wants.
 *
 * Shown after sign-in rather than buried in a menu, because ride and delivery are
 * genuinely different jobs and the app's whole shape follows from the answer. The
 * previous choice is pre-selected so a returning customer confirms rather than
 * re-decides, and the switcher in the app bar means this is never a one-way door.
 */
@Composable
fun ModeSelectScreen(
    customerName: String,
    initialMode: MovoServiceMode?,
    onConfirm: (MovoServiceMode) -> Unit,
    onSignOut: (() -> Unit)? = null
) {
    var selected by rememberSaveable(stateSaver = ModeSaver) {
        mutableStateOf(initialMode ?: MovoServiceMode.Ride)
    }
    val greeting = remember(customerName) { customerName.trim().split(' ').firstOrNull()?.takeIf(String::isNotBlank) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Column(Modifier.padding(horizontal = MovoSpacing.large, vertical = MovoSpacing.xlarge)) {
                Text(
                    if (greeting != null) "Hello, $greeting" else "Welcome to MOVO",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(MovoSpacing.tiny))
                Text(
                    "What do you need today?",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = MovoSpacing.large),
                verticalArrangement = Arrangement.spacedBy(MovoSpacing.default)
            ) {
                ModeChoiceCard(
                    mode = MovoServiceMode.Ride,
                    selected = selected == MovoServiceMode.Ride,
                    onSelect = { selected = MovoServiceMode.Ride },
                    highlights = listOf(
                        "A verified rider picks you up",
                        "Fare agreed before you book",
                        "Helmet provided, trip tracked end to end"
                    )
                )
                ModeChoiceCard(
                    mode = MovoServiceMode.Delivery,
                    selected = selected == MovoServiceMode.Delivery,
                    onSelect = { selected = MovoServiceMode.Delivery },
                    highlights = listOf(
                        "Parcels and documents across Kigali",
                        "Handover codes at pickup and delivery",
                        "Your receiver is kept updated by SMS"
                    )
                )
                Spacer(Modifier.height(MovoSpacing.small))
                Text(
                    "You can switch at any time from the top of the screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp) {
                Column(Modifier.padding(MovoSpacing.default).navigationBarsPadding()) {
                    MovoButton("Continue to ${selected.action.lowercase()}", { onConfirm(selected) })
                    onSignOut?.let {
                        MovoTextAction("Sign out", it, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

/** Survives process death so a chooser left on screen does not reset its selection. */
private val ModeSaver = androidx.compose.runtime.saveable.Saver<MovoServiceMode, String>(
    save = { it.apiValue },
    restore = { MovoServiceMode.from(it) }
)
