package com.movo.customer.parcel.account

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Presentation models deliberately independent from transport and persistence. */
data class AccountProfile(val name: String, val phone: String, val email: String = "")
data class AccountAddress(val id: String, val label: String, val address: String)
data class AccountNotice(val id: String, val title: String, val body: String, val createdAt: String = "", val read: Boolean = false)
data class AccountOrder(
    val id: String, val status: String, val pickup: String, val destination: String,
    val recipient: String = "", val phone: String = "", val createdAt: String = "", val price: String = "",
    val riderName: String = "", val riderPhone: String = "", val rating: Int? = null,
)
enum class AccountDestination { EDIT_PROFILE, SETTINGS, SUPPORT, ADDRESSES, SAFETY, INFORMATION, NOTIFICATIONS, ORDERS }

/** Never manufactures success; only the caller's completed Result can confirm a mutation. */
@Stable
internal class AccountMutation(private val scope: CoroutineScope) {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var succeeded by mutableStateOf(false)
        private set
    fun run(action: suspend () -> Result<Unit>, afterSuccess: () -> Unit = {}) {
        if (busy) return
        busy = true
        error = null
        succeeded = false
        scope.launch {
            try {
                action().getOrThrow()
                succeeded = true
                afterSuccess()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message?.take(240)?.takeIf { it.isNotBlank() } ?: "Please try again."
            } finally {
                busy = false
            }
        }
    }
}

@Composable
internal fun rememberAccountMutation(): AccountMutation {
    val scope = rememberCoroutineScope()
    return remember(scope) { AccountMutation(scope) }
}

@Composable
internal fun MutationNotice(mutation: AccountMutation, language: String, success: String) {
    if (mutation.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    mutation.error?.let {
        Text(accountText(language, "Not saved. ", "Ntibyabitswe. ", "Non enregistré. ") + it,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    if (mutation.succeeded) Text(success, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
internal fun AccountCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
internal fun AccountField(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean = true, multiline: Boolean = false) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, enabled = enabled,
        singleLine = !multiline, minLines = if (multiline) 3 else 1)
}

@Composable
internal fun DetailLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodyLarge)
    }
}
