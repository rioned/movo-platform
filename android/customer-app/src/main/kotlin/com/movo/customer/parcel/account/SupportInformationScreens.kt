package com.movo.customer.parcel.account

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.movo.customer.parcel.ui.Page
import com.movo.customer.parcel.ui.PrimaryButton
import com.movo.customer.parcel.ui.MenuRow
import com.movo.customer.parcel.JourneyStore
import kotlinx.coroutines.launch

@Composable
fun SupportScreen(onSubmit: suspend (String, String, String) -> Result<Unit>, onBack: () -> Unit, language: String = "en") {
    var category by rememberSaveable { mutableStateOf("delivery") }
    var subject by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    val mutation = rememberAccountMutation()
    Page(accountText(language, "Help & support", "Ubufasha", "Aide et assistance"), onBack) {
        Text(accountText(language, "How can we help?", "Twagufasha iki?", "Comment vous aider ?"), style = MaterialTheme.typography.headlineSmall)
        Text(accountText(language, "Send a support request. Include the order reference, but never your password or verification code.", "Ohereza ikibazo. Shyiramo nimero y'icyo wohereje, ariko ntutange ijambo ry'ibanga cyangwa kode yo kwemeza.", "Envoyez une demande avec la référence de commande, jamais votre mot de passe ou code de vérification."))
        listOf(
            "delivery" to accountText(language, "Delivery", "Kohereza", "Livraison"),
            "payment" to accountText(language, "Payment", "Kwishyura", "Paiement"),
            "other" to accountText(language, "Other", "Ikindi", "Autre")
        ).forEach { (code, title) ->
            FilterChip(selected = category == code, onClick = { category = code }, enabled = !mutation.busy, label = { Text(title) })
        }
        AccountField(subject, { subject = it.take(120) }, accountText(language, "Subject", "Umutwe w'ikibazo", "Objet"), !mutation.busy)
        AccountField(description, { description = it.take(1000) }, accountText(language, "Describe the issue", "Sobanura ikibazo", "Décrivez le problème"), !mutation.busy, multiline = true)
        Text("${description.length}/1000", style = MaterialTheme.typography.labelSmall)
        MutationNotice(mutation, language, accountText(language, "Support request sent.", "Ikibazo cyoherejwe.", "Demande envoyée."))
        PrimaryButton(accountText(language, "Send request", "Ohereza ikibazo", "Envoyer la demande"), !mutation.busy && AccountRules.validSupport(category, subject, description)) {
            mutation.run({ onSubmit(category, subject.trim(), description.trim()) }) { subject = ""; description = "" }
        }
        Text(accountText(language, "Not an emergency service. For immediate danger, open Safety.", "Iyi si serivisi y'ubutabazi bwihuse. Mu kaga kihutirwa, jya ku Mutekano.", "Ce service ne traite pas les urgences. En cas de danger immédiat, consultez Sécurité."), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SafetyScreen(onSupport: () -> Unit, onBack: () -> Unit, language: String = "en") {
    val context = LocalContext.current
    var dialError by remember { mutableStateOf(false) }
    val store = remember { AccountPreferences(context) }
    val options by store.options.collectAsState(initial = AccountOptions())
    val scope = rememberCoroutineScope()
    var contactName by rememberSaveable { mutableStateOf("") }
    var contactPhone by rememberSaveable { mutableStateOf("") }
    Page(accountText(language, "Safety", "Umutekano", "Sécurité"), onBack) {
        AccountCard {
            Text(accountText(language, "Need urgent help?", "Ukeneye ubufasha bwihuse?", "Besoin d’aide urgente ?"), style = MaterialTheme.typography.titleLarge)
            Text(accountText(language, "If you are in immediate danger in Rwanda, dial 112. MOVO does not automatically alert emergency services or share your location.", "Niba uri mu kaga kihutirwa mu Rwanda, hamagara 112. MOVO ntiyohereza ubutabazi cyangwa aho uri mu buryo bwikora.", "En cas de danger immédiat au Rwanda, composez le 112. MOVO n’alerte pas automatiquement les secours et ne partage pas votre position."))
            PrimaryButton(accountText(language, "Open dialer · 112", "Fungura telefoni · 112", "Ouvrir le téléphone · 112")) {
                try { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112"))) }
                catch (_: ActivityNotFoundException) { dialError = true }
            }
            if (dialError) Text(accountText(language, "No phone app found. Dial 112 from another phone.", "Nta porogaramu yo guhamagara. Hamagara 112 ukoresheje indi telefoni.", "Application téléphone introuvable. Composez le 112 sur un autre téléphone."), color = MaterialTheme.colorScheme.error)
        }
        AccountCard {
            Text(accountText(language, "Before handing over a parcel", "Mbere yo gutanga ipaki", "Avant de remettre un colis"), style = MaterialTheme.typography.titleMedium)
            Text(accountText(language, "Check the rider and order details. Package fragile items securely. Keep verification codes private until the correct handover step.", "Genzura umushoferi n'amakuru y'ipaki. Pfunyika neza ibimeneka. Bika kode mu ibanga kugeza igihe gikwiye cyo gutanga ipaki.", "Vérifiez le livreur et la commande. Emballez les objets fragiles. Gardez les codes privés jusqu’à l’étape de remise appropriée."))
        }
        Text("Trusted contacts", style = MaterialTheme.typography.titleLarge)
        options.contacts.forEach { contact ->
            val parts = contact.split('|', limit = 2)
            MenuRow(parts.first(), parts.last()) { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${parts.last()}"))) }
            TextButton(onClick = { scope.launch { store.deleteContact(contact) } }) { Text("Remove contact") }
        }
        AccountField(contactName, { contactName = it.replace("|", "").take(100) }, "Contact name")
        AccountField(contactPhone, { contactPhone = it.take(16) }, "Phone (+250)")
        PrimaryButton("Save trusted contact", AccountRules.validContact(contactName, contactPhone)) { scope.launch { store.addContact(contactName, contactPhone); contactName = ""; contactPhone = "" } }
        TextButton(onClick = { scope.launch {
            val place = JourneyStore(context).lastLocation()
            val text = place?.let { "My last known MOVO pickup location: https://maps.google.com/?q=${it.latitude},${it.longitude}. This is a saved location, not live tracking." } ?: "I need help with my MOVO delivery. Please contact me."
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share with a trusted contact"))
        } }) { Text("Share last known location") }
        MenuRow(accountText(language, "Report a delivery concern", "Menyesha ikibazo cy'ipaki", "Signaler un problème de livraison"), onClick = onSupport)
    }
}

@Composable
fun InformationScreen(onBack: () -> Unit, language: String = "en") {
    val context = LocalContext.current
    Page(accountText(language, "About MOVO", "Ibyerekeye MOVO", "À propos de MOVO"), onBack) {
        Text("MOVO", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
        Text("Version ${com.movo.customer.BuildConfig.VERSION_NAME}")
        MenuRow("Visit MOVO", "movo-vervice.tech") { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://movo-vervice.tech/"))) }
        MenuRow("Rate the app", "Opens its Google Play listing if published") { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${context.packageName}"))) }
        Text(accountText(language, "Parcels, from door to door.", "Amapaki, kuva ku rugi kugera ku rundi.", "Vos colis, de porte à porte."), style = MaterialTheme.typography.titleLarge)
        AccountCard {
            Text(accountText(language, "How delivery works", "Uko kohereza bikora", "Comment fonctionne la livraison"), style = MaterialTheme.typography.titleMedium)
            Text(accountText(language, "Choose pickup and destination, enter recipient details, review the quote, and confirm. Track progress in My orders. Prices are confirmed by the service.", "Hitamo aho ipaki iva n'aho ijya, andika amakuru y'uyakira, reba igiciro hanyuma wemeze. Kurikirana mu byo wohereje. Ibiciro byemezwa na serivisi.", "Choisissez le départ et la destination, renseignez le destinataire, vérifiez le devis puis confirmez. Suivez la livraison dans Mes commandes. Les prix sont confirmés par le service."))
        }
        AccountCard {
            Text(accountText(language, "Your information", "Amakuru yawe", "Vos informations"), style = MaterialTheme.typography.titleMedium)
            Text(accountText(language, "Delivery requests send contact and location details to MOVO to arrange delivery. Saved address labels and language preferences are stored on this device. Do not include passwords, payment PINs, or verification codes in support messages.", "Gusaba kohereza byohereza amakuru yo kuvugana n'aho ipaki iri kuri MOVO. Aderesi zabitswe n'ururimi bibikwa kuri iyi telefoni. Ntushyire amagambo y'ibanga cyangwa kode mu butumwa bw'ubufasha.", "Les demandes transmettent à MOVO les coordonnées et lieux nécessaires à la livraison. Les adresses enregistrées et la langue sont conservées sur cet appareil. N’incluez aucun mot de passe, code de paiement ou de vérification dans vos messages."))
            Text(accountText(language, "Full legal terms and privacy policy are not available in this app yet. Contact support to request them.", "Amategeko yuzuye n'amabwiriza y'ibanga ntaraboneka muri iyi porogaramu. Yabisabe abashinzwe ubufasha.", "Les conditions complètes et la politique de confidentialité ne sont pas encore disponibles dans l’application. Demandez-les à l’assistance."), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
