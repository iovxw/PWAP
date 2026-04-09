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
    private var currentForegroundNotificationId: Int? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopForegroundService()
            else -> syncForegroundService()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun syncForegroundService() {
        val foregroundNotification = PageFetchDownloadStore.buildForegroundNotification(this)
        if (foregroundNotification == null) {
            stopForegroundService()
            return
        }

        if (!isForeground || currentForegroundNotificationId != foregroundNotification.notificationId) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    foregroundNotification.notificationId,
                    foregroundNotification.notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(
                    foregroundNotification.notificationId,
                    foregroundNotification.notification
                )
            }
            isForeground = true
            currentForegroundNotificationId = foregroundNotification.notificationId
        } else {
            notificationManager.notify(
                foregroundNotification.notificationId,
                foregroundNotification.notification
            )
        }
        PageFetchDownloadStore.notifyActiveNotifications(this)
    }

    private fun stopForegroundService() {
        if (isForeground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_DETACH)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(false)
            }
            isForeground = false
            currentForegroundNotificationId = null
        }
        stopSelf()
    }

    companion object {
        private const val ACTION_SYNC = "net.iovxw.pwap.action.SYNC_DOWNLOAD_FOREGROUND"
        private const val ACTION_STOP = "net.iovxw.pwap.action.STOP_DOWNLOAD_FOREGROUND"

        internal fun ensureForeground(context: Context) {
            val intent = Intent(context, WebDownloadService::class.java).apply {
                action = ACTION_SYNC
            }
            ContextCompat.startForegroundService(context, intent)
        }

        internal fun createCancelPendingIntent(
            context: Context,
            downloadId: String,
            requestCode: Int
        ) = android.app.PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, DownloadNotificationActionReceiver::class.java).apply {
                action = DownloadNotificationActionReceiver.ACTION_CANCEL_DOWNLOAD
                putExtra(DownloadNotificationActionReceiver.EXTRA_DOWNLOAD_ID, downloadId)
                putExtra(DownloadNotificationActionReceiver.EXTRA_NOTIFICATION_ID, requestCode)
            },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        internal fun stop(context: Context) {
            val intent = Intent(context, WebDownloadService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
