package net.iovxw.pwap.browser

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

class WebDownloadService : Service() {
    private val notificationManager by lazy(LazyThreadSafetyMode.NONE) {
        getSystemService(NotificationManager::class.java)
    }

    private var isForeground = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopForegroundService()
            else -> syncForegroundService()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun syncForegroundService() {
        val notification = PageFetchDownloadStore.buildForegroundNotification(this)
        if (notification == null) {
            stopForegroundService()
            return
        }

        if (!isForeground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    FOREGROUND_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(FOREGROUND_NOTIFICATION_ID, notification)
            }
            isForeground = true
        } else {
            notificationManager.notify(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundService() {
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        stopSelf()
    }

    companion object {
        internal const val FOREGROUND_NOTIFICATION_ID = 1001

        private const val ACTION_SYNC = "net.iovxw.pwap.action.SYNC_DOWNLOAD_FOREGROUND"
        private const val ACTION_STOP = "net.iovxw.pwap.action.STOP_DOWNLOAD_FOREGROUND"

        internal fun ensureForeground(context: Context) {
            val intent = Intent(context, WebDownloadService::class.java).apply {
                action = ACTION_SYNC
            }
            ContextCompat.startForegroundService(context, intent)
        }

        internal fun stop(context: Context) {
            val intent = Intent(context, WebDownloadService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
