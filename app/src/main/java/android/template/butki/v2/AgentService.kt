package android.template.butki.v2

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.template.butki.utilities.Logger

class AgentService : Service() {

    companion object {
        const val TAG = "AgentService"

        fun start(context: Context, command: String) {
            val intent = Intent(context, AgentService::class.java).apply {
                putExtra("command", command)
            }
            context.startForegroundService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = intent?.getStringExtra("command") ?: "No command"
        Logger.d(TAG, "Executing command: $command")
        
        // Background processing logic can be added here
        
        return START_NOT_STICKY
    }
}
