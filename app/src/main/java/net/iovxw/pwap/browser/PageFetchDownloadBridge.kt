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
            Toast.makeText(appContext, "页面未提供可下载的 Blob 数据", Toast.LENGTH_SHORT).show()
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
            message?.takeIf { it.isNotBlank() } ?: "下载失败"
        )
    }

    @JavascriptInterface
    fun reportError(token: String, message: String?): Boolean {
        synchronized(lock) {
            pendingRequests.remove(token)
        }
        val errorMessage = message?.takeIf { it.isNotBlank() } ?: "下载失败"
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
        val errorMessage = message?.takeIf { it.isNotBlank() } ?: "下载失败"
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
                ?: throw IOException("无法写入下载文件")
            val downloadId = UUID.randomUUID().toString()
            val notificationId = nextNotificationId.incrementAndGet()

            synchronized(lock) {
                sessions[downloadId] = ActiveBridgeDownload(
                    notificationId = notificationId,
                    fileName = metadata.fileName,
                    mimeType = metadata.mimeType,
                    totalBytes = metadata.contentLength,
                    outputUri = outputUri,
                    outputStream = outputStream,
                    downloadedBytes = 0L,
                    lastNotifiedPercent = -1,
                    lastNotificationAt = 0L
                )
            }

            WebDownloadService.ensureForeground(context)
            updateForegroundNotification(context)

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
            val shouldNotify = synchronized(lock) {
                val session = sessions[downloadId] ?: return false
                val bytes = Base64.decode(base64Chunk, Base64.DEFAULT)
                session.outputStream.write(bytes)
                session.downloadedBytes += bytes.size

                val percent = if (session.totalBytes > 0) {
                    ((session.downloadedBytes * 100) / session.totalBytes).toInt().coerceIn(0, 100)
                } else {
                    -1
                }
                val now = SystemClock.elapsedRealtime()
                val notify = percent != session.lastNotifiedPercent ||
                    now - session.lastNotificationAt >= PROGRESS_UPDATE_INTERVAL_MS
                if (notify) {
                    session.lastNotifiedPercent = percent
                    session.lastNotificationAt = now
                }
                notify
            }

            if (shouldNotify) {
                updateForegroundNotification(context)
            }
            true
        }.getOrElse { error ->
            Log.e(DOWNLOAD_LOG_TAG, "append chunk failed for $downloadId", error)
            failDownload(context, downloadId, error.message ?: "写入下载内容失败")
            false
        }
    }

    fun finishDownload(context: Context, downloadId: String): Boolean {
        val session = synchronized(lock) {
            sessions.remove(downloadId)
        } ?: return false

        return runCatching {
            session.outputStream.flush()
            session.outputStream.close()
            finalizeDownloadUri(context, session.outputUri)
            postCompletionNotification(context, session)
            Log.d(DOWNLOAD_LOG_TAG, "finish downloadId=$downloadId uri=${session.outputUri}")
            onSessionFinished(context)
            true
        }.getOrElse { error ->
            Log.e(DOWNLOAD_LOG_TAG, "finish failed for $downloadId", error)
            deleteDownloadUri(context, session.outputUri)
            postFailureNotification(context, session.notificationId, error.message ?: "下载失败")
            onSessionFinished(context)
            false
        }
    }

    fun failDownload(context: Context, downloadId: String, message: String): Boolean {
        val session = synchronized(lock) {
            sessions.remove(downloadId)
        } ?: return false

        return runCatching {
            session.outputStream.close()
        }.also {
            deleteDownloadUri(context, session.outputUri)
            postFailureNotification(context, session.notificationId, message)
            Log.w(DOWNLOAD_LOG_TAG, "fail downloadId=$downloadId message=$message")
            onSessionFinished(context)
        }.isSuccess
    }

    fun buildForegroundNotification(context: Context): Notification? {
        val snapshot = synchronized(lock) {
            sessions.values.map {
                ActiveBridgeDownloadSnapshot(
                    fileName = it.fileName,
                    downloadedBytes = it.downloadedBytes,
                    totalBytes = it.totalBytes
                )
            }
        }
        if (snapshot.isEmpty()) {
            return null
        }

        val builder = NotificationCompat.Builder(context, ACTIVE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        createAppPendingIntent(context)?.let(builder::setContentIntent)

        val current = snapshot.first()
        if (snapshot.size == 1) {
            builder.setContentTitle(current.fileName)
                .setContentText(buildProgressText(context, current))
            if (current.totalBytes > 0) {
                val percent = ((current.downloadedBytes * 100) / current.totalBytes)
                    .toInt()
                    .coerceIn(0, 100)
                builder.setProgress(100, percent, false)
            } else {
                builder.setProgress(0, 0, true)
            }
        } else {
            builder.setContentTitle("正在下载 ${snapshot.size} 个文件")
                .setContentText(snapshot.joinToString(limit = 2, truncated = "等") { it.fileName })
                .setProgress(0, 0, true)
        }

        return builder.build()
    }

    private fun onSessionFinished(context: Context) {
        synchronized(lock) {
            if (sessions.isEmpty()) {
                WebDownloadService.stop(context)
                return
            }
        }
        updateForegroundNotification(context)
    }

    private fun updateForegroundNotification(context: Context) {
        val notification = buildForegroundNotification(context) ?: return
        context.getSystemService(NotificationManager::class.java)
            .notify(WebDownloadService.FOREGROUND_NOTIFICATION_ID, notification)
    }

    private fun ensureNotificationChannels(context: Context) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val activeChannel = NotificationChannel(
            ACTIVE_CHANNEL_ID,
            "后台下载",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "显示正在进行的下载任务"
        }
        val completeChannel = NotificationChannel(
            COMPLETE_CHANNEL_ID,
            "下载完成",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "显示下载完成或失败通知"
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
            ?: throw IOException("无法创建下载目标")
    }

    private fun finalizeDownloadUri(context: Context, uri: Uri) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        val updatedRows = context.contentResolver.update(uri, values, null, null)
        if (updatedRows <= 0) {
            throw IOException("无法完成下载文件写入")
        }
    }

    private fun deleteDownloadUri(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.delete(uri, null, null)
        }
    }

    private fun postCompletionNotification(context: Context, session: ActiveBridgeDownload) {
        val openIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(session.outputUri, session.mimeType.ifBlank { DEFAULT_MIME_TYPE })
            clipData = ClipData.newRawUri(session.fileName, session.outputUri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooserIntent = Intent.createChooser(openIntent, "打开下载文件").apply {
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
            .setContentText("下载完成，已保存到 Downloads")
            .addAction(android.R.drawable.ic_menu_view, "打开", openPendingIntent)

        context.getSystemService(NotificationManager::class.java)
            .notify(session.notificationId, builder.build())
    }

    private fun postFailureNotification(context: Context, notificationId: Int, message: String) {
        val builder = NotificationCompat.Builder(context, COMPLETE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .setContentTitle("下载失败")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
        createAppPendingIntent(context)?.let(builder::setContentIntent)
        context.getSystemService(NotificationManager::class.java)
            .notify(notificationId, builder.build())
    }

    private fun buildProgressText(context: Context, download: ActiveBridgeDownloadSnapshot): String {
        return if (download.totalBytes > 0) {
            "${Formatter.formatShortFileSize(context, download.downloadedBytes)} / " +
                Formatter.formatShortFileSize(context, download.totalBytes)
        } else {
            "已下载 ${Formatter.formatShortFileSize(context, download.downloadedBytes)}"
        }
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
    val notificationId: Int,
    var fileName: String,
    var mimeType: String,
    var totalBytes: Long,
    val outputUri: Uri,
    val outputStream: OutputStream,
    var downloadedBytes: Long,
    var lastNotifiedPercent: Int,
    var lastNotificationAt: Long,
)

private data class ActiveBridgeDownloadSnapshot(
    val fileName: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
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
private const val APP_PENDING_INTENT_REQUEST_CODE = 1002

private val nextNotificationId = AtomicInteger(2_000)
