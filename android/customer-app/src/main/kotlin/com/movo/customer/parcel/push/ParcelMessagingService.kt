package com.movo.customer.parcel.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.movo.customer.MainActivity
import com.movo.customer.R
import kotlinx.coroutines.runBlocking

/** Firebase's resource options are optional at build time; missing configuration disables FCM. */
fun configureFirebase(context: Context): Boolean = runCatching {
    FirebaseApp.getApps(context).isNotEmpty() || FirebaseApp.initializeApp(context) != null
}.getOrDefault(false)

class ParcelMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) { PushTokenStore(this).save(token) }
    override fun onMessageReceived(message: RemoteMessage) {
        val category = PushRules.category(message.data) ?: return
        val route = PushRules.route(message.data)
        val preferences = PushPreferences(this)
        val permission = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val manager = NotificationManagerCompat.from(this)
        val key = PushRules.eventKey(message.messageId, message.data)
        val allowed = runBlocking { PushRules.shouldNotify(permission, manager.areNotificationsEnabled(), preferences.isEnabled(category)) && preferences.claim(key) }
        if (!allowed) return
        val channelId = "movo_${category.key}"
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channelId, if (category == PushCategory.DELIVERY) "Delivery updates" else "MOVO offers", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        route?.let { intent.putExtra("order_id", it.orderId); intent.putExtra("offer_id", it.offerId) }
        val pending = PendingIntent.getActivity(this, key.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notice = NotificationCompat.Builder(this, channelId).setSmallIcon(R.drawable.ic_movo_motorcycle)
            .setContentTitle(message.data["title"]?.take(120) ?: "MOVO delivery update")
            .setContentText(message.data["body"]?.take(240) ?: "Open MOVO to view the latest status.")
            .setContentIntent(pending).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        try { manager.notify(key.hashCode(), notice) } catch (_: SecurityException) { /* Permission may be revoked between checks. */ }
    }
}
