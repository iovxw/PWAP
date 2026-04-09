package net.iovxw.pwap.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Message
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.iovxw.pwap.R
import net.iovxw.pwap.data.AppPreferences
import net.iovxw.pwap.proxy.ProxyManager

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
                Toast.makeText(this, "所选文件无法访问", Toast.LENGTH_SHORT).show()
            }
            callback.onReceiveValue(acceptedUris)
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
            WebViewProxySession.acquire(preferences.proxyConfig, preferences.dnsServer)
        }
        observeProxyAndLoad()
    }

    override fun onDestroy() {
        pendingFileChooserCallback?.onReceiveValue(null)
        pendingFileChooserCallback = null
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        lifecycleScope.launch {
            WebViewProxySession.release()
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

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.isVisible = newProgress < 100
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
                        "系统未找到可用的文件选择器",
                        Toast.LENGTH_SHORT
                    ).show()
                    false
                }
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

private object WebViewProxySession {
    private val mutex = Mutex()

    private var activeHosts = 0
    private var currentConfig: Pair<String, String>? = null

    suspend fun acquire(proxyConfig: String, dnsServer: String) {
        val requestedConfig = proxyConfig to dnsServer
        mutex.withLock {
            activeHosts += 1

            val sameConfig = currentConfig == requestedConfig
            val state = ProxyManager.state.value
            if (sameConfig && (state == ProxyManager.ProxyState.STARTING || ProxyManager.isRunning)) {
                return@withLock
            }

            currentConfig = requestedConfig
            ProxyManager.start(proxyConfig, dnsServer)
        }
    }

    suspend fun release() {
        mutex.withLock {
            if (activeHosts > 0) {
                activeHosts -= 1
            }
            if (activeHosts != 0) {
                return@withLock
            }

            currentConfig = null
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                try {
                    ProxyController.getInstance().clearProxyOverride(
                        Executor { it.run() },
                        {}
                    )
                } catch (_: Exception) {
                }
            }
            ProxyManager.stop()
        }
    }
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
