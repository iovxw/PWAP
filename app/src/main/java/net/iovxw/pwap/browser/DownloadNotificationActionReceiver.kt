package net.iovxw.pwap.browser

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class DownloadNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_CANCEL_DOWNLOAD) {
            return
        }
        val downloadId = intent.getStringExtra(EXTRA_DOWNLOAD_ID).orEmpty()
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        if (downloadId.isBlank()) {
            if (notificationId > 0) {
                PageFetchDownloadStore.dismissOrphanNotification(
                    context.applicationContext,
                    notificationId
                )
            }
            return
        }
        Log.d(DOWNLOAD_LOG_TAG, "notification cancel action downloadId=$downloadId")
        val canceled = PageFetchDownloadStore.cancelDownload(context.applicationContext, downloadId)
        if (!canceled && notificationId > 0) {
            PageFetchDownloadStore.dismissOrphanNotification(
                context.applicationContext,
                notificationId
            )
        }
    }

    companion object {
        internal const val ACTION_CANCEL_DOWNLOAD = "net.iovxw.pwap.action.CANCEL_DOWNLOAD"
        internal const val EXTRA_DOWNLOAD_ID = "download_id"
        internal const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
