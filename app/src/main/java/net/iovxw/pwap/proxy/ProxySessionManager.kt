package net.iovxw.pwap.proxy

import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.util.concurrent.Executor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object ProxySessionManager {
    private val mutex = Mutex()

    private var activeClients = 0
    private var currentConfig: Pair<String, String>? = null

    suspend fun acquire(proxyConfig: String, dnsServer: String) {
        val requestedConfig = proxyConfig to dnsServer
        mutex.withLock {
            activeClients += 1

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
            if (activeClients > 0) {
                activeClients -= 1
            }
            if (activeClients != 0) {
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
