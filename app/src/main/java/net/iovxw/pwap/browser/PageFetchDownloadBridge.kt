package net.iovxw.pwap.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import net.iovxw.pwap.R
import org.json.JSONObject

internal data class BridgeDownloadRequest(
    val url: String,
    val contentDisposition: String,
    val mimeType: String,
    val contentLength: Long,
    val suggestedFileName: String,
)

internal class PageFetchDownloadBridge(
    private val appContext: Context,
    private val blobHookSecret: String,
) {
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingRequests = linkedMapOf<String, BridgeDownloadRequest>()
    private val pendingBlobResolveRequests = linkedMapOf<String, BridgeDownloadRequest>()
    private val pendingBlobResolveCallbacks = linkedMapOf<String, Runnable>()
    private val activeDownloadIds = linkedSetOf<String>()

    fun prepareRequest(request: BridgeDownloadRequest): String {
        val token = UUID.randomUUID().toString()
        synchronized(lock) {
            pendingRequests[token] = request
        }
        return token
    }

    fun abortAll(reason: String) {
        val pendingBlobCallbacks = synchronized(lock) {
            pendingBlobResolveCallbacks.values.toList().also {
                pendingBlobResolveCallbacks.clear()
                pendingBlobResolveRequests.clear()
            }
        }
        pendingBlobCallbacks.forEach(mainHandler::removeCallbacks)
        val pendingIds = synchronized(lock) {
            pendingRequests.clear()
            activeDownloadIds.toList().also { activeDownloadIds.clear() }
        }
        pendingIds.forEach { downloadId ->
            PageFetchDownloadStore.failDownload(appContext, downloadId, reason)
        }
    }

    fun prepareBlobResolveRequest(request: BridgeDownloadRequest): String {
        val requestId = UUID.randomUUID().toString()
        val timeout = Runnable {
            val expiredRequest = synchronized(lock) {
                pendingBlobResolveCallbacks.remove(requestId)
                pendingBlobResolveRequests.remove(requestId)
            } ?: return@Runnable

            Log.w(
                DOWNLOAD_LOG_TAG,
                "blob resolve timeout requestId=$requestId url=${expiredRequest.url}"
            )
            Toast.makeText(
                appContext,
                appContext.getString(R.string.blob_data_not_available),
                Toast.LENGTH_SHORT
            ).show()
        }
        synchronized(lock) {
            pendingBlobResolveRequests[requestId] = request
            pendingBlobResolveCallbacks[requestId] = timeout
        }
        mainHandler.postDelayed(timeout, BLOB_RESOLVE_TIMEOUT_MS)
        return requestId
    }

    fun failPendingBlobResolveRequest(requestId: String, message: String) {
        val callback = synchronized(lock) {
            pendingBlobResolveRequests.remove(requestId)
            pendingBlobResolveCallbacks.remove(requestId)
        } ?: return
        mainHandler.removeCallbacks(callback)
        Log.w(DOWNLOAD_LOG_TAG, "blob resolve failed requestId=$requestId message=$message")
        Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
    }

    @JavascriptInterface
    fun beginDownload(token: String, metadataJson: String): String {
        val request = synchronized(lock) {
            pendingRequests.remove(token)
        } ?: return ""

        val downloadId = PageFetchDownloadStore.beginDownload(appContext, request, metadataJson) ?: return ""
        synchronized(lock) {
            activeDownloadIds += downloadId
        }
        Log.d(DOWNLOAD_LOG_TAG, "bridge begin token=$token downloadId=$downloadId")
        return downloadId
    }

    @JavascriptInterface
    fun beginInterceptedBlobDownload(secret: String, requestId: String, metadataJson: String): String {
        if (secret != blobHookSecret) {
            return ""
        }
        val metadata = JSONObject(metadataJson)
        val pendingRequest = consumeBlobResolveRequest(requestId)
        if (requestId.isNotBlank() && pendingRequest == null) {
            Log.w(DOWNLOAD_LOG_TAG, "intercepted begin missing requestId=$requestId")
            return BLOB_RESOLVE_MISSING_SENTINEL
        }
        val request = mergeBridgeDownloadRequest(pendingRequest, metadata)
        val downloadId = PageFetchDownloadStore.beginDownload(appContext, request, metadataJson) ?: return ""
        synchronized(lock) {
            activeDownloadIds += downloadId
        }
        Log.d(
            DOWNLOAD_LOG_TAG,
            "intercepted begin requestId=${requestId.ifBlank { "-" }} downloadId=$downloadId"
        )
        return downloadId
    }

    @JavascriptInterface
    fun appendChunk(downloadId: String, base64Chunk: String): Boolean {
        return PageFetchDownloadStore.appendChunk(appContext, downloadId, base64Chunk)
    }

    @JavascriptInterface
    fun finishDownload(downloadId: String): Boolean {
        synchronized(lock) {
            activeDownloadIds.remove(downloadId)
        }
        return PageFetchDownloadStore.finishDownload(appContext, downloadId)
    }

    @JavascriptInterface
    fun failDownload(downloadId: String, message: String?): Boolean {
        synchronized(lock) {
            activeDownloadIds.remove(downloadId)
        }
        return PageFetchDownloadStore.failDownload(
            appContext,
            downloadId,
            message?.takeIf { it.isNotBlank() } ?: appContext.getString(R.string.download_failed)
        )
    }

    @JavascriptInterface
    fun reportError(token: String, message: String?): Boolean {
        synchronized(lock) {
            pendingRequests.remove(token)
        }
        val errorMessage = message?.takeIf { it.isNotBlank() }
            ?: appContext.getString(R.string.download_failed)
        Log.e(DOWNLOAD_LOG_TAG, "bridge reportError token=$token message=$errorMessage")
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(appContext, errorMessage, Toast.LENGTH_SHORT).show()
        }
        return false
    }

    @JavascriptInterface
    fun reportInterceptedError(secret: String, message: String?): Boolean {
        if (secret != blobHookSecret) {
            return false
        }
        val errorMessage = message?.takeIf { it.isNotBlank() }
            ?: appContext.getString(R.string.download_failed)
        Log.e(DOWNLOAD_LOG_TAG, "intercepted reportError message=$errorMessage")
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(appContext, errorMessage, Toast.LENGTH_SHORT).show()
        }
        return false
    }

    @JavascriptInterface
    fun debugIntercept(secret: String, message: String?): Boolean {
        if (secret != blobHookSecret) {
            return false
        }
        Log.d(DOWNLOAD_LOG_TAG, "blob-hook ${message.orEmpty()}")
        return true
    }

    private fun consumeBlobResolveRequest(requestId: String): BridgeDownloadRequest? {
        if (requestId.isBlank()) {
            return null
        }
        var callback: Runnable? = null
        val request = synchronized(lock) {
            callback = pendingBlobResolveCallbacks.remove(requestId)
            pendingBlobResolveRequests.remove(requestId)
        }
        callback?.let(mainHandler::removeCallbacks)
        return request
    }

    private fun mergeBridgeDownloadRequest(
        base: BridgeDownloadRequest?,
        metadata: JSONObject
    ): BridgeDownloadRequest {
        val metadataRequest = BridgeDownloadRequest(
            url = metadata.optString("url"),
            contentDisposition = metadata.optString("contentDisposition"),
            mimeType = metadata.optString("mimeType"),
            contentLength = metadata.optLong("contentLength", -1L),
            suggestedFileName = metadata.optString("suggestedFileName")
        )
        if (base == null) {
            return metadataRequest
        }
        return BridgeDownloadRequest(
            url = metadataRequest.url.ifBlank { base.url },
            contentDisposition = metadataRequest.contentDisposition.ifBlank { base.contentDisposition },
            mimeType = metadataRequest.mimeType.ifBlank { base.mimeType },
            contentLength = metadataRequest.contentLength.takeIf { it > 0 } ?: base.contentLength,
            suggestedFileName = metadataRequest.suggestedFileName.ifBlank { base.suggestedFileName }
        )
    }
}

internal object PageFetchDownloadStore {
    private val lock = Any()
    private val sessions = linkedMapOf<String, ActiveBridgeDownload>()
    // Workaround: canceled progress notifications can be re-posted by late notification updates,
    // so keep their ids suppressed briefly and cancel them again during subsequent refreshes.
    private val suppressedNotificationIds = linkedMapOf<Int, Long>()
    private val notificationCleanupHandler = Handler(Looper.getMainLooper())

    fun beginDownload(
        context: Context,
        request: BridgeDownloadRequest,
        metadataJson: String
    ): String? {
        return runCatching {
            ensureNotificationChannels(context)

            val metadata = BridgeDownloadMetadata.from(request, metadataJson)
            val outputUri = createPendingDownloadUri(context, metadata.fileName, metadata.mimeType)
            val outputStream = context.contentResolver.openOutputStream(outputUri)
                ?.buffered()
                ?: throw IOException(context.getString(R.string.cannot_write_download_file))
            val downloadId = UUID.randomUUID().toString()
            val notificationId = nextNotificationId.incrementAndGet()
            val now = SystemClock.elapsedRealtime()

            synchronized(lock) {
                sessions[downloadId] = ActiveBridgeDownload(
                    downloadId = downloadId,
                    notificationId = notificationId,
                    fileName = metadata.fileName,
                    mimeType = metadata.mimeType,
                    totalBytes = metadata.contentLength,
                    outputUri = outputUri,
                    outputStream = outputStream,
                    downloadedBytes = 0L,
                    startedAt = now,
                    lastSpeedSampleAt = now,
                    lastSpeedSampleBytes = 0L,
                    speedBytesPerSecond = 0L,
                    lastNotifiedPercent = -1,
                    lastNotificationAt = 0L
                )
            }

            WebDownloadService.ensureForeground(context)
            notifyActiveNotifications(context)

            Log.d(
                DOWNLOAD_LOG_TAG,
                "native begin downloadId=$downloadId fileName=${metadata.fileName} mimeType=${metadata.mimeType} total=${metadata.contentLength}"
            )
            downloadId
        }.getOrElse { error ->
            Log.e(DOWNLOAD_LOG_TAG, "native begin failed", error)
            null
        }
    }

    fun appendChunk(context: Context, downloadId: String, base64Chunk: String): Boolean {
        return runCatching {
            val session = synchronized(lock) {
                sessions[downloadId]
            } ?: return false
            val bytes = Base64.decode(base64Chunk, Base64.DEFAULT)
            val shouldNotify = synchronized(session.ioLock) {
                if (!isActiveSession(downloadId, session)) {
                    return false
                }
                session.outputStream.write(bytes)
                updateProgressState(session, bytes.size)
            }

            if (shouldNotify) {
                notifyActiveNotifications(context)
            }
            true
        }.getOrElse { error ->
            Log.e(DOWNLOAD_LOG_TAG, "append chunk failed for $downloadId", error)
            failDownload(
                context,
                downloadId,
                error.message ?: context.getString(R.string.write_download_content_failed)
            )
            false
        }
    }

    fun appendBytes(context: Context, downloadId: String, bytes: ByteArray, length: Int): Boolean {
        return runCatching {
            val session = synchronized(lock) {
                sessions[downloadId]
            } ?: return false
            val shouldNotify = synchronized(session.ioLock) {
                if (!isActiveSession(downloadId, session)) {
                    return false
                }
                session.outputStream.write(bytes, 0, length)
                updateProgressState(session, length)
            }

            if (shouldNotify) {
                notifyActiveNotifications(context)
            }
            true
        }.getOrElse { error ->
            Log.e(DOWNLOAD_LOG_TAG, "append bytes failed for $downloadId", error)
            failDownload(
                context,
                downloadId,
                error.message ?: context.getString(R.string.write_download_content_failed)
            )
            false
        }
    }

    fun finishDownload(context: Context, downloadId: String): Boolean {
        val session = synchronized(lock) {
            sessions.remove(downloadId)
        } ?: return false

        return runCatching {
            synchronized(session.ioLock) {
                session.outputStream.flush()
                session.outputStream.close()
            }
            finalizeDownloadUri(context, session.outputUri)
            postCompletionNotification(context, session)
            Log.d(DOWNLOAD_LOG_TAG, "finish downloadId=$downloadId uri=${session.outputUri}")
            onSessionFinished(context)
            true
        }.getOrElse { error ->
            Log.e(DOWNLOAD_LOG_TAG, "finish failed for $downloadId", error)
            deleteDownloadUri(context, session.outputUri)
            postFailureNotification(
                context,
                session.notificationId,
                error.message ?: context.getString(R.string.download_failed)
            )
            onSessionFinished(context)
            false
        }
    }

    fun failDownload(context: Context, downloadId: String, message: String): Boolean {
        val session = synchronized(lock) {
            sessions.remove(downloadId)
        } ?: return false

        return runCatching {
            synchronized(session.ioLock) {
                session.outputStream.close()
            }
        }.also {
            deleteDownloadUri(context, session.outputUri)
            postFailureNotification(context, session.notificationId, message)
            Log.w(DOWNLOAD_LOG_TAG, "fail downloadId=$downloadId message=$message")
            onSessionFinished(context)
        }.isSuccess
    }

    fun cancelDownload(context: Context, downloadId: String): Boolean {
        val session = synchronized(lock) {
            sessions.remove(downloadId)
        } ?: return false

        suppressNotification(context, session.notificationId)
        return runCatching {
            synchronized(session.ioLock) {
                session.outputStream.close()
            }
        }.also {
            deleteDownloadUri(context, session.outputUri)
            Log.i(DOWNLOAD_LOG_TAG, "cancel downloadId=$downloadId")
            val hasRemainingSessions = synchronized(lock) {
                sessions.isNotEmpty()
            }
            if (hasRemainingSessions) {
                WebDownloadService.ensureForeground(context)
                notifyActiveNotifications(context)
            } else {
                context.stopService(Intent(context, WebDownloadService::class.java))
            }
        }.isSuccess
    }

    fun dismissOrphanNotification(context: Context, notificationId: Int) {
        suppressNotification(context, notificationId)
    }

    fun buildForegroundNotification(context: Context): ActiveDownloadNotification? {
        val session = synchronized(lock) {
            sessions.values.firstOrNull()
        }
        val snapshot = session?.toSnapshot()
        if (snapshot == null || !hasActiveSession(snapshot.downloadId, snapshot.notificationId)) {
            return null
        }
        return ActiveDownloadNotification(
            notificationId = snapshot.notificationId,
            notification = buildActiveNotification(context, snapshot)
        )
    }

    fun notifyActiveNotifications(context: Context) {
        val activeSessions = synchronized(lock) {
            sessions.values.toList()
        }
        val snapshots = activeSessions.map(ActiveBridgeDownload::toSnapshot)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        cancelSuppressedNotifications(context)
        snapshots.forEach { snapshot ->
            if (!hasActiveSession(snapshot.downloadId, snapshot.notificationId)) {
                return@forEach
            }
            notificationManager.notify(
                snapshot.notificationId,
                buildActiveNotification(context, snapshot)
            )
        }
        cancelSuppressedNotifications(context)
    }

    private fun onSessionFinished(context: Context) {
        synchronized(lock) {
            if (sessions.isEmpty()) {
                WebDownloadService.stop(context)
                return
            }
        }
        WebDownloadService.ensureForeground(context)
        notifyActiveNotifications(context)
    }

    private fun ensureNotificationChannels(context: Context) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val activeChannel = NotificationChannel(
            ACTIVE_CHANNEL_ID,
            context.getString(R.string.background_download_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.background_download_channel_description)
        }
        val completeChannel = NotificationChannel(
            COMPLETE_CHANNEL_ID,
            context.getString(R.string.download_complete_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.download_complete_channel_description)
        }
        notificationManager.createNotificationChannel(activeChannel)
        notificationManager.createNotificationChannel(completeChannel)
    }

    private fun createPendingDownloadUri(context: Context, fileName: String, mimeType: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType.ifBlank { DEFAULT_MIME_TYPE })
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException(context.getString(R.string.cannot_create_download_target))
    }

    private fun finalizeDownloadUri(context: Context, uri: Uri) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        val updatedRows = context.contentResolver.update(uri, values, null, null)
        if (updatedRows <= 0) {
            throw IOException(context.getString(R.string.cannot_finalize_download_file))
        }
    }

    private fun deleteDownloadUri(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.delete(uri, null, null)
        }
    }

    private fun suppressNotification(context: Context, notificationId: Int) {
        val expiresAt = SystemClock.elapsedRealtime() + SUPPRESSED_NOTIFICATION_WINDOW_MS
        synchronized(lock) {
            suppressedNotificationIds[notificationId] = expiresAt
        }
        cancelSuppressedNotifications(context)
        SUPPRESSED_NOTIFICATION_CANCEL_DELAYS_MS.forEach { delayMs ->
            notificationCleanupHandler.postDelayed(
                { cancelSuppressedNotifications(context.applicationContext) },
                delayMs
            )
        }
    }

    private fun cancelSuppressedNotifications(context: Context) {
        val now = SystemClock.elapsedRealtime()
        val suppressedIds = synchronized(lock) {
            val iterator = suppressedNotificationIds.entries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().value <= now) {
                    iterator.remove()
                }
            }
            suppressedNotificationIds.keys.toList()
        }
        if (suppressedIds.isEmpty()) {
            return
        }
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        suppressedIds.forEach(notificationManager::cancel)
        notificationManager.cancel(AUTO_GROUP_SUMMARY_NOTIFICATION_ID)
    }

    private fun isActiveSession(downloadId: String, session: ActiveBridgeDownload): Boolean {
        return synchronized(lock) {
            sessions[downloadId] === session
        }
    }

    private fun hasActiveSession(downloadId: String, notificationId: Int): Boolean {
        return synchronized(lock) {
            sessions[downloadId]?.notificationId == notificationId
        }
    }

    private fun updateProgressState(session: ActiveBridgeDownload, bytesWritten: Int): Boolean {
        session.downloadedBytes += bytesWritten
        val now = SystemClock.elapsedRealtime()
        val elapsedSinceSample = now - session.lastSpeedSampleAt
        if (elapsedSinceSample >= SPEED_SAMPLE_INTERVAL_MS) {
            val deltaBytes = session.downloadedBytes - session.lastSpeedSampleBytes
            session.speedBytesPerSecond = if (elapsedSinceSample > 0) {
                (deltaBytes * 1000L) / elapsedSinceSample
            } else {
                0L
            }
            session.lastSpeedSampleAt = now
            session.lastSpeedSampleBytes = session.downloadedBytes
        } else {
            val totalElapsed = now - session.startedAt
            if (totalElapsed > 0) {
                session.speedBytesPerSecond = (session.downloadedBytes * 1000L) / totalElapsed
            }
        }
        val percent = if (session.totalBytes > 0) {
            ((session.downloadedBytes * 100) / session.totalBytes).toInt().coerceIn(0, 100)
        } else {
            -1
        }
        val notify = percent != session.lastNotifiedPercent ||
            now - session.lastNotificationAt >= PROGRESS_UPDATE_INTERVAL_MS
        if (notify) {
            session.lastNotifiedPercent = percent
            session.lastNotificationAt = now
        }
        return notify
    }

    private fun buildActiveNotification(
        context: Context,
        download: ActiveBridgeDownloadSnapshot
    ): Notification {
        val builder = NotificationCompat.Builder(context, ACTIVE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            // Workaround: give each active download a distinct group key to reduce framework
            // auto-grouping of our silent progress notifications on this device.
            .setGroup("$ACTIVE_NOTIFICATION_GROUP_PREFIX${download.downloadId}")
            .setContentTitle(download.fileName)
            .setContentText(buildProgressText(context, download))
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.cancel),
                WebDownloadService.createCancelPendingIntent(
                    context = context,
                    downloadId = download.downloadId,
                    requestCode = download.notificationId
                )
            )

        if (download.totalBytes > 0) {
            val percent = ((download.downloadedBytes * 100) / download.totalBytes)
                .toInt()
                .coerceIn(0, 100)
            builder.setProgress(100, percent, false)
        } else {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    private fun postCompletionNotification(context: Context, session: ActiveBridgeDownload) {
        val openIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(session.outputUri, session.mimeType.ifBlank { DEFAULT_MIME_TYPE })
            clipData = ClipData.newRawUri(session.fileName, session.outputUri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooserIntent = Intent.createChooser(
            openIntent,
            context.getString(R.string.open_download_file)
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri(session.fileName, session.outputUri)
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            session.notificationId,
            chooserIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, COMPLETE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentTitle(session.fileName)
            .setContentText(context.getString(R.string.download_complete_saved))
            .addAction(android.R.drawable.ic_menu_view, context.getString(R.string.open), openPendingIntent)

        context.getSystemService(NotificationManager::class.java)
            .notify(session.notificationId, builder.build())
    }

    private fun postFailureNotification(context: Context, notificationId: Int, message: String) {
        val builder = NotificationCompat.Builder(context, COMPLETE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .setContentTitle(context.getString(R.string.download_failed_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
        createAppPendingIntent(context)?.let(builder::setContentIntent)
        context.getSystemService(NotificationManager::class.java)
            .notify(notificationId, builder.build())
    }

    private fun buildProgressText(context: Context, download: ActiveBridgeDownloadSnapshot): String {
        val progressText = if (download.totalBytes > 0) {
            "${Formatter.formatShortFileSize(context, download.downloadedBytes)} / " +
                Formatter.formatShortFileSize(context, download.totalBytes)
        } else {
            context.getString(
                R.string.downloaded_size,
                Formatter.formatShortFileSize(context, download.downloadedBytes)
            )
        }
        val speedText = download.speedBytesPerSecond
            .takeIf { it > 0L }
            ?.let { "${Formatter.formatShortFileSize(context, it)}/s" }
        return listOfNotNull(progressText, speedText).joinToString(" · ")
    }
}

private data class BridgeDownloadMetadata(
    val fileName: String,
    val mimeType: String,
    val contentLength: Long,
) {
    companion object {
        fun from(request: BridgeDownloadRequest, metadataJson: String): BridgeDownloadMetadata {
            val json = JSONObject(metadataJson)
            val finalUrl = json.optString("finalUrl").ifBlank { request.url }
            val contentDisposition = json.optString("contentDisposition")
                .ifBlank { request.contentDisposition }
            val mimeType = normalizeMimeType(
                json.optString("mimeType"),
                request.mimeType
            )
            val contentLength = when {
                json.has("contentLength") -> json.optLong("contentLength", request.contentLength)
                else -> request.contentLength
            }
            val fileName = resolveDownloadFileName(
                finalUrl = finalUrl,
                contentDisposition = contentDisposition,
                mimeType = mimeType,
                suggestedFileName = request.suggestedFileName
            )
            return BridgeDownloadMetadata(
                fileName = fileName,
                mimeType = mimeType,
                contentLength = contentLength
            )
        }
    }
}

private data class ActiveBridgeDownload(
    val downloadId: String,
    val notificationId: Int,
    var fileName: String,
    var mimeType: String,
    var totalBytes: Long,
    val outputUri: Uri,
    val outputStream: OutputStream,
    var downloadedBytes: Long,
    val startedAt: Long,
    var lastSpeedSampleAt: Long,
    var lastSpeedSampleBytes: Long,
    var speedBytesPerSecond: Long,
    var lastNotifiedPercent: Int,
    var lastNotificationAt: Long,
    val ioLock: Any = Any(),
) {
    fun toSnapshot(): ActiveBridgeDownloadSnapshot {
        return synchronized(ioLock) {
            ActiveBridgeDownloadSnapshot(
                downloadId = downloadId,
                notificationId = notificationId,
                fileName = fileName,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                speedBytesPerSecond = speedBytesPerSecond
            )
        }
    }
}

private data class ActiveBridgeDownloadSnapshot(
    val downloadId: String,
    val notificationId: Int,
    val fileName: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val speedBytesPerSecond: Long,
)

internal data class ActiveDownloadNotification(
    val notificationId: Int,
    val notification: Notification,
)

private fun OutputStream.buffered(): OutputStream = BufferedOutputStream(this)

private fun createAppPendingIntent(context: Context): PendingIntent? {
    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    return PendingIntent.getActivity(
        context,
        APP_PENDING_INTENT_REQUEST_CODE,
        launchIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

private fun normalizeMimeType(contentType: String, fallback: String): String {
    val normalized = contentType.substringBefore(';').trim()
    return when {
        normalized.isNotBlank() -> normalized
        fallback.isNotBlank() -> fallback
        else -> DEFAULT_MIME_TYPE
    }
}

private fun resolveDownloadFileName(
    finalUrl: String,
    contentDisposition: String,
    mimeType: String,
    suggestedFileName: String
): String {
    val guessedName = URLUtil.guessFileName(
        finalUrl,
        contentDisposition.takeIf { it.isNotBlank() },
        mimeType.takeIf { it.isNotBlank() }
    )
    if (isMeaningfulFileName(guessedName)) {
        return guessedName
    }
    if (isMeaningfulFileName(suggestedFileName)) {
        return suggestedFileName
    }
    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
    return if (!extension.isNullOrBlank()) {
        "download.$extension"
    } else {
        "download.bin"
    }
}

private fun isMeaningfulFileName(fileName: String): Boolean {
    val normalized = fileName.trim()
    return normalized.isNotBlank() &&
        normalized.lowercase() !in setOf("downloadfile", "downloadfile.bin", "download.bin")
}

internal const val JS_DOWNLOAD_BRIDGE_NAME = "PWAPDownloadBridge"
internal const val DOWNLOAD_LOG_TAG = "PWAPDownload"
internal const val BLOB_RESOLVE_MISSING_SENTINEL = "__PWAP_BLOB_RESOLVE_MISSING__"

private const val ACTIVE_CHANNEL_ID = "download_active"
private const val COMPLETE_CHANNEL_ID = "download_complete"
private const val DEFAULT_MIME_TYPE = "application/octet-stream"
private const val BLOB_RESOLVE_TIMEOUT_MS = 5000L
private const val PROGRESS_UPDATE_INTERVAL_MS = 750L
private const val SPEED_SAMPLE_INTERVAL_MS = 1000L
private const val APP_PENDING_INTENT_REQUEST_CODE = 1002
private const val ACTIVE_NOTIFICATION_GROUP_PREFIX = "download:"
private const val AUTO_GROUP_SUMMARY_NOTIFICATION_ID = 1
private const val SUPPRESSED_NOTIFICATION_WINDOW_MS = 4000L
private val SUPPRESSED_NOTIFICATION_CANCEL_DELAYS_MS = longArrayOf(250L, 1000L, 2500L)

private val nextNotificationId = AtomicInteger(2_000)
