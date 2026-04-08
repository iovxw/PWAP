package net.iovxw.pwap.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import net.iovxw.pwap.proxy.ProxyManager
import java.util.concurrent.Executor

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewScreen(targetUrl: String, onUrlChanged: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val proxyState by ProxyManager.state.collectAsState()
    val proxyPort by ProxyManager.currentPort.collectAsState()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loadingProgress by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }

    // Handle back button for WebView navigation
    BackHandler(enabled = webView?.canGoBack() == true) {
        webView?.goBack()
    }

    // Set up proxy when proxy is running
    LaunchedEffect(proxyState, proxyPort) {
        if (proxyState == ProxyManager.ProxyState.RUNNING && proxyPort > 0) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                val proxyConfig = ProxyConfig.Builder()
                    .addProxyRule("http://127.0.0.1:$proxyPort")
                    .build()
                ProxyController.getInstance().setProxyOverride(
                    proxyConfig,
                    Executor { it.run() },
                    { webView?.loadUrl(targetUrl) }
                )
            }
        }
    }

    // Clean up proxy override on dispose
    DisposableEffect(Unit) {
        onDispose {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                try {
                    ProxyController.getInstance().clearProxyOverride(
                        Executor { it.run() },
                        {}
                    )
                } catch (_: Exception) {}
            }
        }
    }

    when (proxyState) {
        ProxyManager.ProxyState.RUNNING -> {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                if (isLoading) {
                    LinearProgressIndicator(
                        progress = { loadingProgress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.setSupportZoom(true)
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true

                            val targetHost = Uri.parse(targetUrl).host ?: ""

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val url = request?.url ?: return false
                                    val requestHost = url.host ?: return false

                                    // Same domain (including subdomains) - load in WebView
                                    if (isSameDomain(requestHost, targetHost)) {
                                        return false
                                    }

                                    // Different domain - open in external browser (PWA behavior)
                                    try {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, url)
                                        )
                                    } catch (_: Exception) {}
                                    return true
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    isLoading = true
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    isLoading = false
                                    url?.let { onUrlChanged?.invoke(it) }
                                }
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    loadingProgress = newProgress
                                    if (newProgress >= 100) isLoading = false
                                }
                            }

                            webView = this
                        }
                    }
                )
            }
            } // Surface
        }

        ProxyManager.ProxyState.STARTING -> {
            Box(modifier = Modifier.fillMaxSize().systemBarsPadding(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LinearProgressIndicator()
                    Text(
                        text = "正在连接代理...",
                        modifier = Modifier.padding(top = 16.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }

        else -> {
            Box(modifier = Modifier.fillMaxSize().systemBarsPadding(), contentAlignment = Alignment.Center) {
                Text(
                    text = "代理未连接",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

private fun isSameDomain(host1: String, host2: String): Boolean {
    if (host1 == host2) return true
    // Check if one is subdomain of the other
    return host1.endsWith(".$host2") || host2.endsWith(".$host1")
}
