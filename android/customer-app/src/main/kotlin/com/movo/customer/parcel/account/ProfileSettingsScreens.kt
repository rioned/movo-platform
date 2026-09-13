package com.movo.customer.parcel.account

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.movo.customer.parcel.ui.Page
import com.movo.customer.parcel.ui.PrimaryButton
import com.movo.customer.parcel.ui.MenuRow
import androidx.compose.ui.platform.LocalContext
import com.movo.customer.parcel.push.PushPreferences
import com.movo.customer.parcel.push.PushCategory
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen(profile: AccountProfile, onNavigate: (AccountDestination) -> Unit, onSignOut: () -> Unit,
                  language: String = "en", onBack: () -> Unit = {}) {
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    Page(accountText(language, "Your account", "Konti yawe", "Votre compte"), onBack) {
        AccountCard {
            Text(profile.name.ifBlank { accountText(language, "MOVO customer", "Umukiriya wa MOVO", "Client MOVO") }, style = MaterialTheme.typography.headlineSmall)
            Text(profile.phone, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (profile.email.isNotBlank()) Text(profile.email)
            TextButton(onClick = { onNavigate(AccountDestination.EDIT_PROFILE) }) {
                Text(accountText(language, "Edit profile", "Hindura umwirondoro", "Modifier le profil"))
            }
        }
        val rows = listOf(
            AccountDestination.ORDERS to accountText(language, "My orders", "Ibyo nohereje", "Mes commandes"),
            AccountDestination.ADDRESSES to accountText(language, "Saved addresses", "Aderesi zabitswe", "Adresses enregistrées"),
            AccountDestination.NOTIFICATIONS to accountText(language, "Notifications", "Imenyesha", "Notifications"),
            AccountDestination.SETTINGS to accountText(language, "Settings", "Igenamiterere", "Paramètres"),
            AccountDestination.SUPPORT to accountText(language, "Help & support", "Ubufasha", "Aide et assistance"),
            AccountDestination.SAFETY to accountText(language, "Safety", "Umutekano", "Sécurité"),
            AccountDestination.INFORMATION to accountText(language, "About MOVO", "Ibyerekeye MOVO", "À propos de MOVO"),
        )
        rows.forEach { (route, title) -> MenuRow(title = title, onClick = { onNavigate(route) }) }
        TextButton(onClick = { confirmSignOut = true }) {
            Text(accountText(language, "Sign out", "Sohoka", "Se déconnecter"), color = MaterialTheme.colorScheme.error)
        }
    }
    if (confirmSignOut) AlertDialog(onDismissRequest = { confirmSignOut = false },
        title = { Text(accountText(language, "Sign out?", "Urasohoka?", "Se déconnecter ?")) },
        text = { Text(accountText(language, "You will need to sign in again to access your deliveries.", "Uzongera kwinjira kugira ngo urebe ibyo wohereje.", "Vous devrez vous reconnecter pour accéder à vos livraisons.")) },
        confirmButton = { TextButton(onClick = { confirmSignOut = false; onSignOut() }) { Text(accountText(language, "Sign out", "Sohoka", "Se déconnecter")) } },
        dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(accountText(language, "Stay", "Guma", "Rester")) } })
}

@Composable
fun EditProfileScreen(profile: AccountProfile, onSave: suspend (String, String) -> Result<Unit>, onBack: () -> Unit, language: String = "en") {
    var name by rememberSaveable(profile.name) { mutableStateOf(profile.name) }
    var email by rememberSaveable(profile.email) { mutableStateOf(profile.email) }
    val mutation = rememberAccountMutation()
    Page(accountText(language, "Edit profile", "Hindura umwirondoro", "Modifier le profil"), onBack) {
        AccountField(name, { name = it.take(100) }, accountText(language, "Full name", "Amazina yose", "Nom complet"), !mutation.busy)
        AccountField(email, { email = it.take(254) }, accountText(language, "Email (optional)", "Imeyili (si ngombwa)", "E-mail (facultatif)"), !mutation.busy)
        AccountCard {
            DetailLine(accountText(language, "Phone number", "Nimero ya telefoni", "Numéro de téléphone"), profile.phone)
            Text(accountText(language, "Phone changes require support verification.", "Guhindura nimero bisaba kugenzurwa n'abashinzwe ubufasha.", "Contactez l’assistance pour faire vérifier un changement de numéro."), style = MaterialTheme.typography.bodySmall)
        }
        MutationNotice(mutation, language, accountText(language, "Profile saved.", "Umwirondoro wabitswe.", "Profil enregistré."))
        PrimaryButton(accountText(language, "Save changes", "Bika impinduka", "Enregistrer"), !mutation.busy && AccountRules.validProfile(name, email)) {
            mutation.run({ onSave(name.trim(), email.trim()) })
        }
    }
}

@Composable
fun SettingsScreen(language: String, onLanguageChange: suspend (String) -> Result<Unit>, onBack: () -> Unit, onSystemNotifications: () -> Unit) {
    val mutation = rememberAccountMutation()
    val context = LocalContext.current
    val store = remember { AccountPreferences(context) }
    val options by store.options.collectAsState(initial = AccountOptions())
    val push = remember { PushPreferences(context) }
    val categories by push.categories.collectAsState(initial = emptyMap())
    val scope = rememberCoroutineScope()
    Page(accountText(language, "Settings", "Igenamiterere", "Paramètres"), onBack) {
        Row { Text("Display traffic", Modifier.weight(1f)); Switch(options.traffic, { scope.launch { store.traffic(it) } }) }
        Row { Text("Don't call the Rider", Modifier.weight(1f)); Switch(options.preferMessages, { scope.launch { store.preferMessages(it) } }) }
        Text("We'll include a request to message instead unless it's urgent in the delivery instructions.")
        Row { Text("Share my live location with Rider", Modifier.weight(1f)); Switch(false, {}, enabled = false) }
        Text("Not available on the current API. Your pickup pin is shared; your device is not tracked in the background.")
        Text("Notifications", style = MaterialTheme.typography.titleLarge)
        listOf(PushCategory.DELIVERY to "Order updates", PushCategory.PROMOTIONS to "Promos").forEach { (category, label) ->
            Row { Text(label, Modifier.weight(1f)); Switch(categories[category] ?: (category == PushCategory.DELIVERY), { scope.launch { push.setEnabled(category, it) } }) }
        }
        Text(accountText(language, "App language", "Ururimi rwa porogaramu", "Langue de l’application"), style = MaterialTheme.typography.titleLarge)
        listOf("en" to "English", "rw" to "Kinyarwanda", "fr" to "Français").forEach { (code, title) ->
            OutlinedButton(onClick = { mutation.run({ onLanguageChange(code) }) }, enabled = !mutation.busy, modifier = Modifier.fillMaxWidth()) {
                RadioButton(selected = language == code, onClick = null)
                Spacer(Modifier.width(12.dp))
                Text(title, Modifier.weight(1f))
            }
        }
        MutationNotice(mutation, language, accountText(language, "Language saved on this device.", "Ururimi rwabitswe kuri iyi telefoni.", "Langue enregistrée sur cet appareil."))
        MenuRow(accountText(language, "Notification permissions", "Uburenganzira bw'imenyesha", "Autorisations de notification"),
            accountText(language, "Manage in Android settings", "Bigenzure mu igenamiterere rya Android", "Gérer dans les paramètres Android"), onSystemNotifications)
        Text(accountText(language, "Push alerts depend on device permission and service availability. Your delivery status remains available in My orders.", "Imenyesha riterwa n'uburenganzira bwa telefoni na serivisi. Reba aho ibyo wohereje bigeze mu byo wohereje.", "Les alertes dépendent des autorisations et du service. Consultez le suivi dans Mes commandes."), style = MaterialTheme.typography.bodyMedium)
    }
}
