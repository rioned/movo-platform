package com.movo.customer.parcel.account

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.movo.customer.parcel.ui.Page
import com.movo.customer.parcel.ui.PrimaryButton
import com.movo.customer.parcel.ui.EmptyState
import java.util.UUID

@Composable
fun AddressesScreen(addresses: List<AccountAddress>, onSave: suspend (AccountAddress) -> Result<Unit>, onDelete: suspend (String) -> Result<Unit>, onBack: () -> Unit, language: String = "en") {
    var editing by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val mutation = rememberAccountMutation()
    Page(accountText(language, "Saved addresses", "Aderesi zabitswe", "Adresses enregistrées"), onBack) {
        Text(accountText(language, "Address notes saved on this device. These are not verified map locations; choose a map location when booking.", "Aderesi zibitswe kuri iyi telefoni. Si ahantu hemejwe ku ikarita; hitamo aho ipaki ijya igihe usaba kohereza.", "Adresses conservées sur cet appareil. Ce ne sont pas des lieux vérifiés : choisissez la position sur la carte lors de la commande."), color = MaterialTheme.colorScheme.onSurfaceVariant)
        MutationNotice(mutation, language, accountText(language, "Addresses updated on this device.", "Aderesi zahinduwe kuri iyi telefoni.", "Adresses mises à jour sur cet appareil."))
        if (addresses.isEmpty() && !editing) EmptyState(accountText(language, "No saved addresses yet.", "Nta aderesi irabikwa.", "Aucune adresse enregistrée."))
        addresses.forEach { item ->
            key(item.id) {
                AccountCard {
                    Text(item.label, style = MaterialTheme.typography.titleMedium)
                    Text(item.address)
                    Row {
                        TextButton(enabled = !mutation.busy, onClick = { editingId = item.id; label = item.label; address = item.address; editing = true }) { Text(accountText(language, "Edit", "Hindura", "Modifier")) }
                        TextButton(enabled = !mutation.busy, onClick = { deleteId = item.id }) { Text(accountText(language, "Delete", "Siba", "Supprimer"), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
        if (editing) {
            AccountCard {
                Text(accountText(language, "Address details", "Amakuru ya aderesi", "Détails de l’adresse"), style = MaterialTheme.typography.titleMedium)
                AccountField(label, { label = it.take(40) }, accountText(language, "Label (Home, Work…)", "Izina (Mu rugo, Ku kazi…)", "Nom (Domicile, Travail…)"), !mutation.busy)
                AccountField(address, { address = it.take(300) }, accountText(language, "Street, area and landmark", "Umuhanda, agace n'ikimenyetso", "Rue, quartier et point de repère"), !mutation.busy, multiline = true)
                PrimaryButton(accountText(language, "Save address", "Bika aderesi", "Enregistrer l’adresse"), !mutation.busy && AccountRules.validAddress(label, address)) {
                    val value = AccountAddress(editingId, label.trim(), address.trim())
                    mutation.run({ onSave(value) }) { editing = false }
                }
                TextButton(enabled = !mutation.busy, onClick = { editing = false }) { Text(accountText(language, "Cancel", "Reka", "Annuler")) }
            }
        } else PrimaryButton(accountText(language, "Add address", "Ongeramo aderesi", "Ajouter une adresse"), !mutation.busy) {
            editingId = UUID.randomUUID().toString(); label = ""; address = ""; editing = true
        }
    }
    deleteId?.let { id ->
        AlertDialog(onDismissRequest = { if (!mutation.busy) deleteId = null },
            title = { Text(accountText(language, "Delete address?", "Siba aderesi?", "Supprimer l’adresse ?")) },
            text = { Text(accountText(language, "This removes the saved note from this device.", "Ibi bisiba aderesi yabitswe kuri iyi telefoni.", "Cette adresse sera supprimée de cet appareil.")) },
            confirmButton = { TextButton(enabled = !mutation.busy, onClick = {
                deleteId = null
                mutation.run({ onDelete(id) }) { if (editingId == id) editing = false }
            }) { Text(accountText(language, "Delete", "Siba", "Supprimer")) } },
            dismissButton = { TextButton(enabled = !mutation.busy, onClick = { deleteId = null }) { Text(accountText(language, "Keep", "Bika", "Conserver")) } })
    }
}

@Composable
fun NotificationsScreen(notifications: List<AccountNotice>, loading: Boolean, error: String?, onRefresh: () -> Unit, onMarkRead: suspend (String) -> Result<Unit>, onBack: () -> Unit, language: String = "en") {
    val mutation = rememberAccountMutation()
    Page(accountText(language, "Notifications", "Imenyesha", "Notifications"), onBack) {
        TextButton(onClick = onRefresh, enabled = !loading) { Text(accountText(language, "Refresh", "Ongera urebe", "Actualiser")) }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
        if (!loading && error == null && notifications.isEmpty()) EmptyState(accountText(language, "You're all caught up. New notifications will appear here.", "Nta menyesha rishya. Irishya rizagaragara hano.", "Vous êtes à jour. Les nouvelles notifications apparaîtront ici."))
        MutationNotice(mutation, language, accountText(language, "Marked as read.", "Byashyizwe ku byasomwe.", "Marquée comme lue."))
        notifications.forEach { notice ->
            key(notice.id) {
                AccountCard {
                    if (!notice.read) Text(accountText(language, "NEW", "GISHYA", "NOUVEAU"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(notice.title, style = MaterialTheme.typography.titleMedium)
                    Text(notice.body)
                    if (notice.createdAt.isNotBlank()) Text(notice.createdAt, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!notice.read) TextButton(enabled = !mutation.busy, onClick = { mutation.run({ onMarkRead(notice.id) }) }) { Text(accountText(language, "Mark as read", "Shyira ku byasomwe", "Marquer comme lue")) }
                }
            }
        }
    }
}

/** Recipient details ("who receives the package") saved on-device so the customer can
 * quickly reuse a household member — e.g. sending forgotten keys back home. */
@Composable
fun RecipientsScreen(recipients: List<com.movo.customer.parcel.domain.SavedRecipient>, onSave: suspend (com.movo.customer.parcel.domain.SavedRecipient) -> Result<Unit>, onDelete: suspend (String) -> Result<Unit>, onBack: () -> Unit, language: String = "en") {
    var editing by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val mutation = rememberAccountMutation()
    Page(accountText(language, "Recipient details", "Amakuru y'uwakira", "Détails du destinataire"), onBack) {
        Text(accountText(language, "Save people who often receive packages from you — like someone at home who can collect keys you forgot.", "Bika abantu bakunda kwakira ibintu uva ubohereza — nk'umuntu wo mu rugo wakira urufunguzo wibagiwe.", "Enregistrez les personnes qui reçoivent souvent vos colis — comme quelqu'un à la maison qui récupère vos clés oubliées."), color = MaterialTheme.colorScheme.onSurfaceVariant)
        MutationNotice(mutation, language, accountText(language, "Recipient saved on this device.", "Uwakira yabitswe kuri iyi telefoni.", "Destinataire enregistré sur cet appareil."))
        if (recipients.isEmpty() && !editing) EmptyState(accountText(language, "No saved recipients yet.", "Nta wakira wabitswe.", "Aucun destinataire enregistré."))
        recipients.forEach { item ->
            key(item.id) {
                AccountCard {
                    Text(item.name, style = MaterialTheme.typography.titleMedium)
                    Text(item.phone)
                    if (item.note.isNotBlank()) Text(item.note, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row {
                        TextButton(enabled = !mutation.busy, onClick = { editingId = item.id; name = item.name; phone = item.phone; note = item.note; editing = true }) { Text(accountText(language, "Edit", "Hindura", "Modifier")) }
                        TextButton(enabled = !mutation.busy, onClick = { deleteId = item.id }) { Text(accountText(language, "Delete", "Siba", "Supprimer"), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
        if (editing) {
            AccountCard {
                Text(accountText(language, "Recipient details", "Amakuru y'uwakira", "Détails du destinataire"), style = MaterialTheme.typography.titleMedium)
                AccountField(name, { name = it.take(100) }, accountText(language, "Full name", "Amazina yose", "Nom complet"), !mutation.busy)
                AccountField(phone, { phone = it.take(20) }, accountText(language, "Phone number", "Nimero ya telefoni", "Numéro de téléphone"), !mutation.busy)
                AccountField(note, { note = it.take(300) }, accountText(language, "Note (e.g. gate code, landmark)", "Inyandiko (urugero: umubare w'irembo)", "Note (ex. code du portail, repère)"), !mutation.busy, multiline = true)
                PrimaryButton(accountText(language, "Save recipient", "Bika uwakira", "Enregistrer le destinataire"), !mutation.busy && AccountRules.validContact(name, phone)) {
                    val value = com.movo.customer.parcel.domain.SavedRecipient(editingId, name.trim(), phone.trim(), note.trim())
                    mutation.run({ onSave(value) }) { editing = false }
                }
                TextButton(enabled = !mutation.busy, onClick = { editing = false }) { Text(accountText(language, "Cancel", "Reka", "Annuler")) }
            }
        } else PrimaryButton(accountText(language, "Add recipient", "Ongeramo uwakira", "Ajouter un destinataire"), !mutation.busy) {
            editingId = UUID.randomUUID().toString(); name = ""; phone = ""; note = ""; editing = true
        }
    }
    deleteId?.let { id ->
        AlertDialog(onDismissRequest = { if (!mutation.busy) deleteId = null },
            title = { Text(accountText(language, "Delete recipient?", "Siba uwakira?", "Supprimer le destinataire ?")) },
            text = { Text(accountText(language, "This removes the saved recipient from this device.", "Ibi bisiba uwakira wabitswe kuri iyi telefoni.", "Ce destinataire sera supprimé de cet appareil.")) },
            confirmButton = { TextButton(enabled = !mutation.busy, onClick = {
                deleteId = null
                mutation.run({ onDelete(id) }) { if (editingId == id) editing = false }
            }) { Text(accountText(language, "Delete", "Siba", "Supprimer")) } },
            dismissButton = { TextButton(enabled = !mutation.busy, onClick = { deleteId = null }) { Text(accountText(language, "Keep", "Bika", "Conserver")) } })
    }
}
