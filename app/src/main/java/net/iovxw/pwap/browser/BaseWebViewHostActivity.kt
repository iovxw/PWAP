package net.iovxw.pwap.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.Executor
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import net.iovxw.pwap.R
import net.iovxw.pwap.data.AppPreferences
import net.iovxw.pwap.proxy.ProxyManager
import net.iovxw.pwap.proxy.ProxySessionManager
import org.json.JSONObject

abstract class BaseWebViewHostActivity : AppCompatActivity() {
    protected lateinit var preferences: AppPreferences
    protected lateinit var webView: WebView

    private lateinit var hostRoot: FrameLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var initialUrl: String

    private var appliedProxyPort = 0
    private var initialUrlLoaded = false
    private var initialPageFinished = false
    private var pendingFileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingWebPermission: PendingWebPermissionRequest? = null
    private var pendingDownloadRequest: BridgeDownloadRequest? = null
    private var permissionPromptDialog: AlertDialog? = null
    private var contextActionDialog: AlertDialog? = null
    private val blobHookSecret = UUID.randomUUID().toString()
    private val pageDownloadBridge by lazy(LazyThreadSafetyMode.NONE) {
        PageFetchDownloadBridge(applicationContext, blobHookSecret)
    }

    private val fallbackSystemBarColor: Int by lazy(LazyThreadSafetyMode.NONE) {
        ContextCompat.getColor(this, R.color.splash_background)
    }
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = pendingFileChooserCallback ?: return@registerForActivityResult
            pendingFileChooserCallback = null

            val uris = extractUploadUris(
                result.resultCode,
                result.data
            )
            val acceptedUris = uris
                ?.mapNotNull { sanitizeUploadUri(result.data, it) }
                ?.distinct()
                ?.takeIf { it.isNotEmpty() }
                ?.toTypedArray()

            if (result.resultCode == Activity.RESULT_OK && acceptedUris == null) {
                Toast.makeText(this, getString(R.string.selected_file_inaccessible), Toast.LENGTH_SHORT)
                    .show()
            }
            callback.onReceiveValue(acceptedUris)
        }
    private val webPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val pendingRequest = pendingWebPermission ?: return@registerForActivityResult
            pendingWebPermission = null
            val deniedPermissions = pendingRequest.androidPermissions.filter { permission ->
                result[permission] != true &&
                    ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
            }
            if (deniedPermissions.isEmpty()) {
                pendingRequest.request.grant(pendingRequest.resources.toTypedArray())
            } else {
                pendingRequest.request.deny()
                Toast.makeText(this, getString(R.string.system_permission_denied), Toast.LENGTH_SHORT)
                    .show()
            }
        }
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val pendingRequest = pendingDownloadRequest ?: return@registerForActivityResult
            pendingDownloadRequest = null
            if (!granted) {
                Toast.makeText(
                    this,
                    getString(R.string.notification_permission_denied),
                    Toast.LENGTH_SHORT
                ).show()
            }
            startPendingDownload(pendingRequest)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WebView.setWebContentsDebuggingEnabled(
            applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        )

        preferences = AppPreferences(this)
        initialUrl = resolveInitialUrl(preferences) ?: run {
            onInvalidLaunch()
            return
        }

        setContentView(R.layout.activity_pure_webview)
        hostRoot = findViewById(R.id.webViewHostRoot)
        progressBar = findViewById(R.id.pageLoadProgress)
        webView = findViewById(R.id.webView)

        hostRoot.setBackgroundColor(fallbackSystemBarColor)
        applySystemBarColor(fallbackSystemBarColor)
        progressBar.max = 100
        progressBar.progress = 0
        progressBar.isVisible = false

        webView.applyBrowserSettings(fallbackSystemBarColor, supportsMultipleWindows = true)
        webView.addJavascriptInterface(pageDownloadBridge, JS_DOWNLOAD_BRIDGE_NAME)
        installBlobDownloadHook()
        bindWebView()

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        finish()
                    }
                }
            }
        )

        lifecycleScope.launch {
            ProxySessionManager.acquire(preferences.proxyConfig, preferences.dnsServer)
        }
        observeProxyAndLoad()
    }

    override fun onDestroy() {
        pendingFileChooserCallback?.onReceiveValue(null)
        pendingFileChooserCallback = null
        permissionPromptDialog?.dismiss()
        permissionPromptDialog = null
        contextActionDialog?.dismiss()
        contextActionDialog = null
        pendingWebPermission?.request?.deny()
        pendingWebPermission = null
        pendingDownloadRequest = null
        pageDownloadBridge.abortAll(getString(R.string.page_closed_download_cancelled))
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        lifecycleScope.launch {
            ProxySessionManager.release()
        }
        super.onDestroy()
    }

    protected abstract fun resolveInitialUrl(preferences: AppPreferences): String?

    protected abstract fun shouldRouteInCurrentWindow(url: Uri): Boolean

    protected open fun openUrlInNewActivity(url: String) {
        startActivity(ExternalWebViewActivity.createIntent(this, url))
    }

    protected open fun onInitialPageFinished() {}

    protected open fun onInvalidLaunch() {
        finish()
    }

    private fun observeProxyAndLoad() {
        lifecycleScope.launch {
            combine(ProxyManager.state, ProxyManager.currentPort) { state, port ->
                state to port
            }.collectLatest { (state, port) ->
                if (state != ProxyManager.ProxyState.RUNNING || port <= 0) {
                    return@collectLatest
                }

                if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                    if (!initialUrlLoaded) {
                        webView.loadUrl(initialUrl)
                        initialUrlLoaded = true
                    }
                    return@collectLatest
                }

                if (appliedProxyPort == port && initialUrlLoaded) {
                    return@collectLatest
                }

                val proxyConfig = ProxyConfig.Builder()
                    .addProxyRule("http://127.0.0.1:$port")
                    .build()
                ProxyController.getInstance().setProxyOverride(
                    proxyConfig,
                    Executor { it.run() }
                ) {
                    appliedProxyPort = port
                    if (!initialUrlLoaded || webView.url != initialUrl) {
                        webView.loadUrl(initialUrl)
                        initialUrlLoaded = true
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun bindWebView() {
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                if (request != null && !request.isForMainFrame) {
                    return false
                }
                val url = request?.url ?: return false
                if (shouldRouteInCurrentWindow(url)) {
                    return false
                }
                openUrlInNewActivity(url.toString())
                return true
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                if (view == null) {
                    return
                }
                view.post {
                    view.readThemeColor { resolvedColor ->
                        applyResolvedThemeColor(resolvedColor)
                    }
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progressBar.progress = 0
                progressBar.isVisible = true
                applyResolvedThemeColor(null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                notifyInitialPageFinished()
                view?.readThemeColor { resolvedColor ->
                    applyResolvedThemeColor(resolvedColor)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    notifyInitialPageFinished()
                }
            }
        }
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            runOnUiThread {
                handleDownloadRequest(
                    url = url,
                    userAgent = userAgent,
                    contentDisposition = contentDisposition,
                    mimeType = mimeType,
                    contentLength = contentLength
                )
            }
        }
        webView.setOnLongClickListener {
            showContextActionsForCurrentHit()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            webView.setOnContextClickListener {
                showContextActionsForCurrentHit()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.isVisible = newProgress < 100
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                val safeRequest = request ?: return
                runOnUiThread {
                    handleWebPermissionRequest(safeRequest)
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest?) {
                val safeRequest = request ?: return
                runOnUiThread {
                    if (pendingWebPermission?.request == safeRequest) {
                        permissionPromptDialog?.dismiss()
                        permissionPromptDialog = null
                        pendingWebPermission = null
                    }
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (filePathCallback == null || fileChooserParams == null) {
                    return false
                }

                pendingFileChooserCallback?.onReceiveValue(null)
                pendingFileChooserCallback = filePathCallback

                return try {
                    fileChooserLauncher.launch(fileChooserParams.createIntent())
                    true
                } catch (_: ActivityNotFoundException) {
                    pendingFileChooserCallback = null
                    filePathCallback.onReceiveValue(null)
                    Toast.makeText(
                        this@BaseWebViewHostActivity,
                        getString(R.string.file_chooser_unavailable),
                        Toast.LENGTH_SHORT
                    ).show()
                    false
                }
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                val message = consoleMessage?.message().orEmpty()
                if (
                    message.contains("PWAPDownload", ignoreCase = true) ||
                    message.contains("CORS", ignoreCase = true) ||
                    message.contains("Access to fetch at", ignoreCase = true) ||
                    message.contains("blocked by", ignoreCase = true)
                ) {
                    Log.w(
                        DOWNLOAD_LOG_TAG,
                        "console ${consoleMessage?.messageLevel()} " +
                            "${consoleMessage?.sourceId()}:${consoleMessage?.lineNumber()} $message"
                    )
                }
                return super.onConsoleMessage(consoleMessage)
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val popupWebView = WebView(view?.context ?: this@BaseWebViewHostActivity).apply {
                    applyBrowserSettings(fallbackSystemBarColor, supportsMultipleWindows = false)
                    webViewClient = object : WebViewClient() {
                        private fun forwardToNewWindow(url: Uri?): Boolean {
                            val value = url?.toString()?.takeIf { it.isNotBlank() } ?: return false
                            openUrlInNewActivity(value)
                            destroy()
                            return true
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean = forwardToNewWindow(request?.url)

                        override fun onPageStarted(
                            view: WebView?,
                            url: String?,
                            favicon: Bitmap?
                        ) {
                            if (forwardToNewWindow(url?.let(Uri::parse))) {
                                view?.stopLoading()
                            }
                        }
                    }
                }
                transport.webView = popupWebView
                resultMsg.sendToTarget()
                return true
            }
        }
    }

    private fun handleWebPermissionRequest(request: PermissionRequest) {
        val resources = request.resources
            .filter { it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
            .distinct()

        if (resources.isEmpty()) {
            request.deny()
            return
        }

        permissionPromptDialog?.dismiss()
        pendingWebPermission?.request?.deny()

        val pendingRequest = PendingWebPermissionRequest(
            request = request,
            origin = request.origin,
            resources = resources,
            androidPermissions = resources.mapNotNull(::toAndroidPermission).distinct()
        )

        if (isTrustedSiteOrigin(request.origin)) {
            continueGrantWebPermission(pendingRequest)
            return
        }

        pendingWebPermission = pendingRequest
        permissionPromptDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.website_requests_permission_title))
            .setMessage(buildPermissionRequestMessage(pendingRequest))
            .setPositiveButton(getString(R.string.allow)) { _, _ ->
                permissionPromptDialog = null
                continueGrantWebPermission(pendingRequest)
            }
            .setNegativeButton(getString(R.string.deny)) { _, _ ->
                permissionPromptDialog = null
                pendingWebPermission = null
                request.deny()
            }
            .setOnCancelListener {
                permissionPromptDialog = null
                pendingWebPermission = null
                request.deny()
            }
            .show()
    }

    private fun continueGrantWebPermission(pendingRequest: PendingWebPermissionRequest) {
        pendingWebPermission = pendingRequest
        val missingPermissions = pendingRequest.androidPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isEmpty()) {
            pendingWebPermission = null
            pendingRequest.request.grant(pendingRequest.resources.toTypedArray())
            return
        }

        webPermissionLauncher.launch(missingPermissions.toTypedArray())
    }

    private fun isTrustedSiteOrigin(origin: Uri): Boolean {
        val originHost = origin.host.orEmpty()
        val configuredHost = Uri.parse(preferences.targetUrl).host.orEmpty()
        if (originHost.isBlank() || configuredHost.isBlank()) {
            return false
        }
        return isSameDomain(originHost, configuredHost)
    }

    private fun buildPermissionRequestMessage(pendingRequest: PendingWebPermissionRequest): String {
        val originLabel = pendingRequest.origin.host?.takeIf { it.isNotBlank() }
            ?: pendingRequest.origin.toString()
        val requestedFeatures = pendingRequest.resources.joinToString(getString(R.string.list_separator)) { resource ->
            when (resource) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> getString(R.string.permission_feature_camera)
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> getString(R.string.permission_feature_microphone)
                else -> resource
            }
        }
        return getString(R.string.permission_request_message, originLabel, requestedFeatures)
    }

    private fun handleDownloadRequest(
        url: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long
    ) {
        val downloadUrl = url?.trim().orEmpty()
        val scheme = runCatching { Uri.parse(downloadUrl).scheme.orEmpty().lowercase() }.getOrNull()
        Log.d(
            DOWNLOAD_LOG_TAG,
            "download request url=$downloadUrl scheme=$scheme mimeType=${mimeType.orEmpty()} " +
                "contentDisposition=${contentDisposition.orEmpty()} contentLength=$contentLength"
        )
        if (scheme !in setOf("http", "https", "blob", "data")) {
            Log.w(DOWNLOAD_LOG_TAG, "unsupported download scheme: $scheme")
            Toast.makeText(this, getString(R.string.download_type_unsupported), Toast.LENGTH_SHORT)
                .show()
            return
        }

        val pendingRequest = BridgeDownloadRequest(
            url = downloadUrl,
            contentDisposition = contentDisposition.orEmpty(),
            mimeType = mimeType.orEmpty(),
            contentLength = contentLength,
            suggestedFileName = URLUtil.guessFileName(
                downloadUrl,
                contentDisposition?.takeIf { it.isNotBlank() },
                mimeType?.takeIf { it.isNotBlank() }
            )
        )

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownloadRequest = pendingRequest
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        startPendingDownload(pendingRequest)
    }

    private fun showContextActionsForCurrentHit(): Boolean {
        val hitResult = webView.hitTestResult ?: return false
        if (!hitResult.isImageContextTarget()) {
            return false
        }
        resolveContextImageTarget(hitResult) { target ->
            if (target != null) {
                showContextActionDialog(target)
            }
        }
        return true
    }

    private fun resolveContextImageTarget(
        hitResult: WebView.HitTestResult,
        onResolved: (ContextImageTarget?) -> Unit
    ) {
        val fallbackUrl = hitResult.extra?.trim().orEmpty()
        // requestFocusNodeHref gives us src/url for image links, which is more
        // reliable than relying on hitTestResult.extra alone.
        val message = Handler(Looper.getMainLooper()) { resolved ->
            val data = resolved.data
            val imageUrl = data.getString("src").orEmpty()
                .ifBlank { fallbackUrl }
                .takeIf { it.isNotBlank() }
            val linkUrl = data.getString("url").orEmpty().takeIf { it.isNotBlank() }
            onResolved(imageUrl?.let { ContextImageTarget(it, linkUrl) })
            true
        }.obtainMessage()
        webView.requestFocusNodeHref(message)
    }

    private fun showContextActionDialog(target: ContextImageTarget) {
        contextActionDialog?.dismiss()
        contextActionDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.image_actions_title))
            .setItems(arrayOf(getString(R.string.download_image))) { _, which ->
                if (which == 0) {
                    startContextImageDownload(target)
                }
            }
            .setOnDismissListener {
                contextActionDialog = null
            }
            .show()
    }

    private fun startContextImageDownload(target: ContextImageTarget) {
        val scheme = runCatching { Uri.parse(target.imageUrl).scheme.orEmpty().lowercase() }
            .getOrDefault("")
        if (scheme == "http" || scheme == "https") {
            val request = BridgeDownloadRequest(
                url = target.imageUrl,
                contentDisposition = "",
                mimeType = "",
                contentLength = -1L,
                suggestedFileName = URLUtil.guessFileName(target.imageUrl, null, null)
            )
            startNativeHttpDownload(request, webView.url.orEmpty())
            return
        }
        handleDownloadRequest(
            url = target.imageUrl,
            userAgent = webView.settings.userAgentString,
            contentDisposition = "",
            mimeType = "",
            contentLength = -1L
        )
    }

    private fun startNativeHttpDownload(request: BridgeDownloadRequest, referer: String) {
        val proxyPort = appliedProxyPort.takeIf { it > 0 } ?: ProxyManager.currentPort.value
        if (proxyPort <= 0) {
            Toast.makeText(this, getString(R.string.proxy_not_ready_download), Toast.LENGTH_SHORT)
                .show()
            return
        }

        val cookieHeader = CookieManager.getInstance().getCookie(request.url).orEmpty()
        val userAgent = webView.settings.userAgentString.orEmpty()

        lifecycleScope.launch(Dispatchers.IO) {
            executeNativeHttpDownload(
                request = request,
                proxyPort = proxyPort,
                userAgent = userAgent,
                cookieHeader = cookieHeader,
                referer = referer
            )
        }
        runOnUiThread {
            Toast.makeText(this, getString(R.string.download_started), Toast.LENGTH_SHORT).show()
        }
    }

    private fun executeNativeHttpDownload(
        request: BridgeDownloadRequest,
        proxyPort: Int,
        userAgent: String,
        cookieHeader: String,
        referer: String
    ) {
        var connection: HttpURLConnection? = null
        var downloadId = ""
        try {
            connection = (URL(request.url).openConnection(
                Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort))
            ) as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15000
                readTimeout = 30000
                requestMethod = "GET"
                if (userAgent.isNotBlank()) {
                    setRequestProperty("User-Agent", userAgent)
                }
                if (cookieHeader.isNotBlank()) {
                    setRequestProperty("Cookie", cookieHeader)
                }
                if (referer.isNotBlank()) {
                    setRequestProperty("Referer", referer)
                }
                setRequestProperty("Accept", "*/*")
                connect()
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException(
                    "HTTP $responseCode ${connection.responseMessage.orEmpty()}".trim()
                )
            }

            val metadataJson = JSONObject().apply {
                put("url", request.url)
                put("finalUrl", connection.url.toString())
                put(
                    "contentDisposition",
                    connection.getHeaderField("Content-Disposition").orEmpty()
                )
                put("mimeType", connection.contentType?.substringBefore(';').orEmpty())
                put(
                    "contentLength",
                    connection.contentLengthLong.takeIf { it > 0 } ?: request.contentLength
                )
                put("suggestedFileName", request.suggestedFileName)
            }.toString()

            downloadId = PageFetchDownloadStore.beginDownload(
                applicationContext,
                request,
                metadataJson
            ) ?: throw IOException(getString(R.string.cannot_create_download_task))

            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    if (read == 0) {
                        continue
                    }
                    if (!PageFetchDownloadStore.appendBytes(applicationContext, downloadId, buffer, read)) {
                        throw IOException(getString(R.string.write_download_content_failed))
                    }
                }
            }

            if (!PageFetchDownloadStore.finishDownload(applicationContext, downloadId)) {
                throw IOException(getString(R.string.cannot_complete_download))
            }
        } catch (error: Exception) {
            Log.e(DOWNLOAD_LOG_TAG, "native http download failed url=${request.url}", error)
            if (downloadId.isNotBlank()) {
                PageFetchDownloadStore.failDownload(
                    applicationContext,
                    downloadId,
                    error.message ?: getString(R.string.download_failed)
                )
            } else {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        error.message ?: getString(R.string.download_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } finally {
            connection?.disconnect()
        }
    }

    private fun startPendingDownload(request: BridgeDownloadRequest) {
        val scheme = runCatching { Uri.parse(request.url).scheme.orEmpty().lowercase() }.getOrDefault("")
        when (scheme) {
            "blob" -> startBlobResolveDownload(request)
            "http", "https" -> startNativeHttpDownload(request, webView.url.orEmpty())
            else -> startJavascriptDownload(request)
        }
    }

    private fun startJavascriptDownload(request: BridgeDownloadRequest) {
        val requestToken = pageDownloadBridge.prepareRequest(request)
        val script = buildFetchDownloadScript(request, requestToken)
        webView.evaluateJavascript(script, null)
        Toast.makeText(this, getString(R.string.download_started), Toast.LENGTH_SHORT).show()
    }

    private fun startBlobResolveDownload(request: BridgeDownloadRequest) {
        val requestId = pageDownloadBridge.prepareBlobResolveRequest(request)
        val script = buildBlobResolveDispatchScript(request, requestId, blobHookSecret)
        webView.evaluateJavascript(script) { rawValue ->
            if (rawValue != "true") {
                pageDownloadBridge.failPendingBlobResolveRequest(
                    requestId,
                    getString(R.string.blob_download_bridge_missing)
                )
            }
        }
        Toast.makeText(this, getString(R.string.download_started), Toast.LENGTH_SHORT).show()
    }

    private fun installBlobDownloadHook() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            Log.w(DOWNLOAD_LOG_TAG, "DOCUMENT_START_SCRIPT not supported")
            return
        }
        WebViewCompat.addDocumentStartJavaScript(
            webView,
            buildBlobInterceptScript(blobHookSecret),
            setOf("*")
        )
    }

    private fun notifyInitialPageFinished() {
        if (initialPageFinished) {
            return
        }
        initialPageFinished = true
        onInitialPageFinished()
    }

    private fun applyResolvedThemeColor(themeColor: Int?) {
        val resolvedColor = themeColor ?: fallbackSystemBarColor
        hostRoot.setBackgroundColor(resolvedColor)
        applySystemBarColor(resolvedColor)
    }
}

private data class PendingWebPermissionRequest(
    val request: PermissionRequest,
    val origin: Uri,
    val resources: List<String>,
    val androidPermissions: List<String>,
)

private data class ContextImageTarget(
    val imageUrl: String,
    val linkUrl: String?,
)

private fun toAndroidPermission(resource: String): String? {
    return when (resource) {
        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
        else -> null
    }
}

private fun WebView.HitTestResult.isImageContextTarget(): Boolean {
    return type == WebView.HitTestResult.IMAGE_TYPE ||
        type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
}

private fun WebView.applyBrowserSettings(backgroundColor: Int, supportsMultipleWindows: Boolean) {
    setBackgroundColor(backgroundColor)
    settings.javaScriptEnabled = true
    settings.javaScriptCanOpenWindowsAutomatically = true
    settings.domStorageEnabled = true
    settings.setGeolocationEnabled(true)
    settings.layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
    settings.setSupportZoom(false)
    settings.builtInZoomControls = false
    settings.displayZoomControls = false
    settings.loadWithOverviewMode = false
    settings.useWideViewPort = false
    settings.saveFormData = false
    settings.mediaPlaybackRequiresUserGesture = false
    settings.setSupportMultipleWindows(supportsMultipleWindows)
}

private fun WebView.readThemeColor(onResolved: (Int?) -> Unit) {
    evaluateJavascript(THEME_COLOR_SCRIPT) { rawValue ->
        val normalizedColor = rawValue
            ?.takeUnless { it == "null" }
            ?.removeSurrounding("\"")
            ?.takeIf { it.isNotBlank() }
        val parsedColor = runCatching {
            normalizedColor?.let(Color::parseColor)
        }.getOrNull()
        onResolved(parsedColor)
    }
}

private fun AppCompatActivity.applySystemBarColor(color: Int) {
    window.statusBarColor = color
    window.navigationBarColor = color
    window.isNavigationBarContrastEnforced = false
    val controller = WindowInsetsControllerCompat(window, window.decorView)
    val useDarkIcons = ColorUtils.calculateLuminance(color) > 0.5
    controller.isAppearanceLightStatusBars = useDarkIcons
    controller.isAppearanceLightNavigationBars = useDarkIcons
}

internal fun isSameDomain(host1: String, host2: String): Boolean {
    if (host1 == host2) return true
    return host1.endsWith(".$host2") || host2.endsWith(".$host1")
}

private fun extractUploadUris(resultCode: Int, data: Intent?): Array<Uri>? {
    if (resultCode != Activity.RESULT_OK) {
        return null
    }

    val uris = buildList {
        val clipData = data?.clipData
        if (clipData != null) {
            for (index in 0 until clipData.itemCount) {
                clipData.getItemAt(index).uri?.let(::add)
            }
        }
        data?.data?.let(::add)
        if (isEmpty()) {
            addAll(WebChromeClient.FileChooserParams.parseResult(resultCode, data).orEmpty())
        }
    }

    return uris.takeIf { it.isNotEmpty() }?.toTypedArray()
}

private fun AppCompatActivity.sanitizeUploadUri(resultData: Intent?, uri: Uri): Uri? {
    return when (uri.scheme) {
        "content" -> {
            if (uri.authority.isNullOrBlank()) {
                return null
            }
            maybeTakePersistableReadPermission(resultData, uri)
            uri
        }
        "file" -> {
            val path = uri.path?.takeIf { it.isNotBlank() } ?: return null
            val file = java.io.File(path)
            if (file.isFile && file.canRead()) uri else null
        }
        else -> null
    }
}

private fun AppCompatActivity.maybeTakePersistableReadPermission(resultData: Intent?, uri: Uri) {
    val flags = resultData?.flags ?: return
    val hasReadPermission = flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
    val hasPersistablePermission = flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0
    if (!hasReadPermission || !hasPersistablePermission) {
        return
    }

    runCatching {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private fun buildFetchDownloadScript(request: BridgeDownloadRequest, requestToken: String): String {
    return """
        (async () => {
          const request = {
            url: ${request.url.toJsString()},
            contentDisposition: ${request.contentDisposition.toJsString()},
            mimeType: ${request.mimeType.toJsString()},
            contentLength: ${request.contentLength},
            suggestedFileName: ${request.suggestedFileName.toJsString()}
          };
          const requestToken = ${requestToken.toJsString()};
          const bridge = window.${JS_DOWNLOAD_BRIDGE_NAME};
          let downloadId = '';
          const targetOrigin = (() => {
            try {
              return new URL(request.url, location.href).origin;
            } catch (error) {
              return 'unknown';
            }
          })();

          function bytesToBase64(bytes) {
            let binary = '';
            const chunkSize = 0x8000;
            for (let offset = 0; offset < bytes.length; offset += chunkSize) {
              const chunk = bytes.subarray(offset, offset + chunkSize);
              binary += String.fromCharCode(...chunk);
            }
            return btoa(binary);
          }

          try {
            const response = await fetch(request.url, {
              credentials: 'include',
              cache: 'force-cache'
            });
            if (!response.ok) {
              throw new Error(`HTTP ${'$'}{response.status} ${'$'}{response.statusText}`.trim());
            }

            downloadId = bridge.beginDownload(requestToken, JSON.stringify({
              url: request.url,
              finalUrl: response.url || request.url,
              contentDisposition: response.headers.get('Content-Disposition') || request.contentDisposition,
              mimeType: response.headers.get('Content-Type') || request.mimeType,
              contentLength: Number(response.headers.get('Content-Length') || '') || request.contentLength || -1,
              suggestedFileName: request.suggestedFileName
            }));
            if (!downloadId) {
              throw new Error('native beginDownload failed');
            }

            const reader = response.body && response.body.getReader ? response.body.getReader() : null;
            if (reader) {
              while (true) {
                const { done, value } = await reader.read();
                if (done) break;
                if (value && value.length) {
                  if (!bridge.appendChunk(downloadId, bytesToBase64(value))) {
                    throw new Error('native appendChunk failed');
                  }
                }
              }
            } else {
              const buffer = await response.arrayBuffer();
              const bytes = new Uint8Array(buffer);
              for (let offset = 0; offset < bytes.length; offset += 65536) {
                const chunk = bytes.subarray(offset, offset + 65536);
                if (!bridge.appendChunk(downloadId, bytesToBase64(chunk))) {
                  throw new Error('native appendChunk failed');
                }
              }
            }

            if (!bridge.finishDownload(downloadId)) {
              throw new Error('native finishDownload failed');
            }
          } catch (error) {
            const errorName = error && error.name ? error.name : 'Error';
            const message = error && error.message ? error.message : String(error);
            const detailedMessage =
              `${'$'}{errorName}: ${'$'}{message}; frameOrigin=${'$'}{location.origin}; targetOrigin=${'$'}{targetOrigin}; target=${'$'}{request.url}`;
            if (typeof console !== 'undefined' && console.error) {
              console.error(`PWAPDownload fetch failed ${'$'}{detailedMessage}`);
            }
            if (downloadId) {
              bridge.failDownload(downloadId, detailedMessage);
            } else {
              bridge.reportError(requestToken, detailedMessage);
            }
          }
        })();
    """.trimIndent()
}

private fun buildBlobResolveDispatchScript(
    request: BridgeDownloadRequest,
    requestId: String,
    blobHookSecret: String
): String {
    return """
        (() => {
          const dispatcher = window.__pwapDispatchBlobResolve;
          if (typeof dispatcher !== 'function') {
            return false;
          }
          return !!dispatcher(JSON.stringify({
            secret: ${blobHookSecret.toJsString()},
            type: 'resolve-blob-download',
            requestId: ${requestId.toJsString()},
            url: ${request.url.toJsString()},
            contentDisposition: ${request.contentDisposition.toJsString()},
            mimeType: ${request.mimeType.toJsString()},
            contentLength: ${request.contentLength},
            suggestedFileName: ${request.suggestedFileName.toJsString()}
          }));
        })();
    """.trimIndent()
}

private fun buildBlobInterceptScript(blobHookSecret: String): String {
    return """
        (() => {
          if (window.__pwapBlobInterceptInstalled) return;
          window.__pwapBlobInterceptInstalled = true;

          const secret = ${blobHookSecret.toJsString()};
          const bridge = window.${JS_DOWNLOAD_BRIDGE_NAME};
          const objectUrls = new Map();
          const handledResolveRequests = new Set();
          let lastTrustedEventAt = 0;

          bridge.debugIntercept(secret, `installed origin=${'$'}{location.origin} href=${'$'}{location.href}`);

          const markTrustedEvent = (event) => {
            if (event && event.isTrusted) {
              lastTrustedEventAt = Date.now();
            }
          };

          document.addEventListener('pointerdown', markTrustedEvent, true);
          document.addEventListener('click', markTrustedEvent, true);
          document.addEventListener('keydown', markTrustedEvent, true);

          const originalCreateObjectURL = URL.createObjectURL.bind(URL);
          const originalRevokeObjectURL = URL.revokeObjectURL.bind(URL);
          const originalAnchorClick = HTMLAnchorElement.prototype.click;
          const originalWindowOpen = typeof window.open === 'function' ? window.open.bind(window) : null;
          const originalLocationAssign = typeof Location !== 'undefined' && Location.prototype.assign ? Location.prototype.assign : null;
          const originalLocationReplace = typeof Location !== 'undefined' && Location.prototype.replace ? Location.prototype.replace : null;

          URL.createObjectURL = function(value) {
            const url = originalCreateObjectURL(value);
            try {
              if (value instanceof Blob) {
                // Preferred path is to resolve Blob downloads on demand from the
                // owning frame. A fallback would be to stream every createObjectURL()
                // Blob to native immediately, but that stays only as a reserve option.
                objectUrls.set(url, value);
                bridge.debugIntercept(secret, `createObjectURL url=${'$'}{url} size=${'$'}{value.size || -1} type=${'$'}{value.type || ''} origin=${'$'}{location.origin}`);
              }
            } catch (error) {
            }
            return url;
          };

          URL.revokeObjectURL = function(url) {
            const revoke = () => {
              objectUrls.delete(url);
              originalRevokeObjectURL(url);
            };
            if (objectUrls.has(url)) {
              setTimeout(revoke, 30000);
            } else {
              revoke();
            }
          };

          function bytesToBase64(bytes) {
            let binary = '';
            const chunkSize = 0x8000;
            for (let offset = 0; offset < bytes.length; offset += chunkSize) {
              const chunk = bytes.subarray(offset, offset + chunkSize);
              binary += String.fromCharCode(...chunk);
            }
            return btoa(binary);
          }

          function findAnchor(event) {
            if (event.composedPath) {
              const path = event.composedPath();
              for (const item of path) {
                if (item && item.tagName === 'A' && item.href) {
                  return item;
                }
              }
            }
            let current = event.target;
            while (current && current.nodeType === 1) {
              if (current.tagName === 'A' && current.href) {
                return current;
              }
              current = current.parentElement;
            }
            return null;
          }

          function rememberResolveRequest(requestId) {
            if (!requestId) return;
            handledResolveRequests.add(requestId);
            setTimeout(() => {
              handledResolveRequests.delete(requestId);
            }, 60000);
          }

          function forwardResolveRequest(payload) {
            if (!window.frames || !window.frames.length) {
              return;
            }
            for (let index = 0; index < window.frames.length; index += 1) {
              try {
                window.frames[index].postMessage({ __pwapBlobResolve: payload }, '*');
              } catch (error) {
                bridge.debugIntercept(
                  secret,
                  `resolve-forward-failed requestId=${'$'}{payload.requestId || ''} index=${'$'}{index} message=${'$'}{error && error.message ? error.message : String(error)} origin=${'$'}{location.origin}`
                );
              }
            }
          }

          async function streamBlob(href, blob, suggestedFileName, requestId, sourceLabel) {
            let downloadId = '';
            try {
              bridge.debugIntercept(
                secret,
                `stream start requestId=${'$'}{requestId || ''} source=${'$'}{sourceLabel} href=${'$'}{href} size=${'$'}{typeof blob.size === 'number' ? blob.size : -1} origin=${'$'}{location.origin}`
              );
              downloadId = bridge.beginInterceptedBlobDownload(secret, requestId || '', JSON.stringify({
                url: href,
                finalUrl: href,
                contentDisposition: '',
                mimeType: blob.type || '',
                contentLength: typeof blob.size === 'number' ? blob.size : -1,
                suggestedFileName: suggestedFileName || ''
              }));
              if (downloadId === ${BLOB_RESOLVE_MISSING_SENTINEL.toJsString()}) {
                bridge.debugIntercept(
                  secret,
                  `stream skipped requestId=${'$'}{requestId || ''} source=${'$'}{sourceLabel} href=${'$'}{href} origin=${'$'}{location.origin}`
                );
                return;
              }
              if (!downloadId) {
                throw new Error('native beginDownload failed');
              }

              const reader = blob.stream && blob.stream().getReader ? blob.stream().getReader() : null;
              if (reader) {
                while (true) {
                  const { done, value } = await reader.read();
                  if (done) break;
                  if (value && value.length) {
                    if (!bridge.appendChunk(downloadId, bytesToBase64(value))) {
                      throw new Error('native appendChunk failed');
                    }
                  }
                }
              } else {
                const bytes = new Uint8Array(await blob.arrayBuffer());
                for (let offset = 0; offset < bytes.length; offset += 65536) {
                  const chunk = bytes.subarray(offset, offset + 65536);
                  if (!bridge.appendChunk(downloadId, bytesToBase64(chunk))) {
                    throw new Error('native appendChunk failed');
                  }
                }
              }

              if (!bridge.finishDownload(downloadId)) {
                throw new Error('native finishDownload failed');
              }
              bridge.debugIntercept(
                secret,
                `stream finish requestId=${'$'}{requestId || ''} source=${'$'}{sourceLabel} href=${'$'}{href} origin=${'$'}{location.origin}`
              );
            } catch (error) {
              const message = error && error.message ? error.message : String(error);
              if (downloadId) {
                bridge.failDownload(downloadId, message);
              } else {
                bridge.reportInterceptedError(secret, message);
              }
            }
          }

          function handleResolveRequest(payload, sourceLabel) {
            if (!payload || payload.secret !== secret || payload.type !== 'resolve-blob-download') {
              return false;
            }
            const requestId = typeof payload.requestId === 'string' ? payload.requestId : '';
            if (requestId && handledResolveRequests.has(requestId)) {
              return false;
            }
            rememberResolveRequest(requestId);

            const href = typeof payload.url === 'string' ? payload.url : '';
            const knownBlob = objectUrls.get(href);
            bridge.debugIntercept(
              secret,
              `resolve requestId=${'$'}{requestId} source=${'$'}{sourceLabel} href=${'$'}{href} known=${'$'}{!!knownBlob} origin=${'$'}{location.origin}`
            );
            if (knownBlob) {
              streamBlob(href, knownBlob, payload.suggestedFileName || '', requestId, sourceLabel);
            }
            forwardResolveRequest(payload);
            return true;
          }

          function maybeStreamKnownBlob(href, suggestedFileName, sourceLabel) {
            if (typeof href !== 'string') return false;
            if (!href.startsWith('blob:')) return false;
            if (Date.now() - lastTrustedEventAt > 1500) return false;

            const knownBlob = objectUrls.get(href);
            bridge.debugIntercept(secret, `${'$'}{sourceLabel} href=${'$'}{href} known=${'$'}{!!knownBlob} origin=${'$'}{location.origin}`);
            if (!knownBlob) return false;

            streamBlob(href, knownBlob, suggestedFileName || '', '', sourceLabel);
            return true;
          }

          window.addEventListener('message', (event) => {
            const payload =
              event &&
              event.data &&
              typeof event.data === 'object' &&
              event.data.__pwapBlobResolve
                ? event.data.__pwapBlobResolve
                : null;
            if (!payload) {
              return;
            }
            handleResolveRequest(payload, 'frame-message');
          }, false);

          window.__pwapDispatchBlobResolve = function(payloadJson) {
            try {
              const payload = typeof payloadJson === 'string'
                ? JSON.parse(payloadJson)
                : payloadJson;
              return handleResolveRequest(payload, 'native-dispatch');
            } catch (error) {
              bridge.debugIntercept(
                secret,
                `resolve-dispatch-failed ${'$'}{error && error.message ? error.message : String(error)}`
              );
              return false;
            }
          };

          document.addEventListener('click', (event) => {
            const anchor = findAnchor(event);
            if (!anchor) return;
            const href = anchor.href || '';
            if (!href.startsWith('blob:')) return;
            if (!maybeStreamKnownBlob(href, anchor.getAttribute('download') || '', 'blob-click')) return;

            event.preventDefault();
            event.stopImmediatePropagation();
          }, true);

          HTMLAnchorElement.prototype.click = function() {
            const href = this.href || '';
            if (maybeStreamKnownBlob(href, this.getAttribute('download') || '', 'anchor.click')) {
              return;
            }
            return originalAnchorClick.call(this);
          };

          if (originalWindowOpen) {
            window.open = function(url, ...args) {
              const href = typeof url === 'string' ? url : String(url ?? '');
              if (maybeStreamKnownBlob(href, '', 'window.open')) {
                return null;
              }
              return originalWindowOpen(url, ...args);
            };
          }

          if (originalLocationAssign) {
            Location.prototype.assign = function(url) {
              const href = typeof url === 'string' ? url : String(url ?? '');
              if (maybeStreamKnownBlob(href, '', 'location.assign')) {
                return;
              }
              return originalLocationAssign.call(this, url);
            };
          }

          if (originalLocationReplace) {
            Location.prototype.replace = function(url) {
              const href = typeof url === 'string' ? url : String(url ?? '');
              if (maybeStreamKnownBlob(href, '', 'location.replace')) {
                return;
              }
              return originalLocationReplace.call(this, url);
            };
          }

          try {
            const locationPrototype = Object.getPrototypeOf(window.location);
            const hrefDescriptor = locationPrototype
              ? Object.getOwnPropertyDescriptor(locationPrototype, 'href')
              : null;
            if (hrefDescriptor && hrefDescriptor.get && hrefDescriptor.set) {
              Object.defineProperty(locationPrototype, 'href', {
                configurable: true,
                enumerable: hrefDescriptor.enumerable,
                get() {
                  return hrefDescriptor.get.call(this);
                },
                set(value) {
                  const href = typeof value === 'string' ? value : String(value ?? '');
                  if (maybeStreamKnownBlob(href, '', 'location.href')) {
                    return href;
                  }
                  return hrefDescriptor.set.call(this, value);
                }
              });
            }
          } catch (error) {
            bridge.debugIntercept(secret, `location.href-hook-failed ${'$'}{error && error.message ? error.message : String(error)}`);
          }
        })();
    """.trimIndent()
}

private fun String.toJsString(): String = JSONObject.quote(this)

private const val THEME_COLOR_SCRIPT = """
(() => {
  const metas = Array.from(document.querySelectorAll('meta[name="theme-color"]'));
  if (!metas.length) return '';
  const themedMatch = metas.filter(meta => {
    const media = meta.getAttribute('media');
    if (!media) return false;
    try {
      return window.matchMedia(media).matches;
    } catch (error) {
      return false;
    }
  });
  const fallback = metas.find(meta => !meta.getAttribute('media'));
  const selected = themedMatch[0] || fallback || metas[0];
  const content = selected?.getAttribute('content')?.trim();
  if (!content) return '';
  const canvas = document.createElement('canvas');
  const context = canvas.getContext('2d');
  if (!context) return '';
  context.fillStyle = content;
  const normalized = context.fillStyle;
  return normalized.startsWith('#') ? normalized : '';
})();
"""
