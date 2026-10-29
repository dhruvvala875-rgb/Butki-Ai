package android.template.butki

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class ButkiNotificationService : NotificationListenerService() {

    companion object {
        var lastReplyAction: Notification.Action? = null
        
        // Yeh function Butki ke sune hue text ko direct WhatsApp par bhejega
        fun sendReply(context: Context, replyText: String): Boolean {
            val action = lastReplyAction ?: return false
            val remoteInputs = action.remoteInputs ?: return false
            
            val intent = Intent()
            val bundle = Bundle()
            
            for (remoteInput in remoteInputs) {
                bundle.putCharSequence(remoteInput.resultKey, replyText)
            }
            RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)
            
            return try {
                action.actionIntent.send(context, 0, intent)
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val packageName = sbn?.packageName ?: return
        val extras = sbn.notification.extras
        
        val title = extras.getString("android.title") ?: "Unknown"
        val text = extras.getCharSequence("android.text")?.toString() ?: ""

        // Sirf main apps ke notification lenge, system ke nahi
        if (packageName != "android" && text.isNotEmpty()) {
            
            lastReplyAction = null
            
            // Notification ke andar chhupe "Reply" button ko dhundhna
            sbn.notification.actions?.forEach { action ->
                if (action.remoteInputs != null) {
                    lastReplyAction = action
                }
            }

            val intent = Intent("butki.NEW_NOTIFICATION")
            intent.putExtra("title", title)
            intent.putExtra("text", text)
            // Agar Reply button mil gaya toh True, warna False
            intent.putExtra("canReply", lastReplyAction != null) 
            sendBroadcast(intent)
        }
    }
}
