package android.template.butki

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class ButkiNotificationService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val packageName = sbn?.packageName ?: return
        val extras = sbn.notification.extras
        
        val title = extras.getString("android.title") ?: "Unknown"
        val text = extras.getCharSequence("android.text")?.toString() ?: ""

        // System notifications ko ignore karenge
        if (packageName != "android" && text.isNotEmpty()) {
            val intent = Intent("butki.NEW_NOTIFICATION")
            intent.putExtra("title", title)
            intent.putExtra("text", text)
            sendBroadcast(intent)
        }
    }
}
