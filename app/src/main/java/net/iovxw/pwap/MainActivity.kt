package net.iovxw.pwap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.iovxw.pwap.data.AppPreferences
import net.iovxw.pwap.proxy.ProxyManager
import net.iovxw.pwap.sensor.ProximitySensorManager
import net.iovxw.pwap.ui.ConfigScreen
import net.iovxw.pwap.ui.WebViewScreen
import net.iovxw.pwap.ui.theme.PWAPTheme

class MainActivity : ComponentActivity() {

    private lateinit var preferences: AppPreferences
    private lateinit var proximitySensor: ProximitySensorManager
    private var showConfig by mutableStateOf(false)
    private var keepSplashVisible = true
    private var waitingForInitialPage = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { keepSplashVisible }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        preferences = AppPreferences(this)

        // Show config on first launch or if not configured
        showConfig = preferences.isFirstLaunch || !preferences.isConfigured
        waitingForInitialPage = preferences.isConfigured && !showConfig
        keepSplashVisible = waitingForInitialPage

        proximitySensor = ProximitySensorManager(this) {
            runOnUiThread { showConfig = true }
        }

        if (waitingForInitialPage) {
            lifecycleScope.launch {
                ProxyManager.state.collectLatest { state ->
                    if (waitingForInitialPage && state == ProxyManager.ProxyState.ERROR) {
                        releaseStartupSplash()
                    }
                }
            }
        }

        // Auto-start proxy if configured
        if (preferences.isConfigured && !showConfig) {
            startProxy()
        }

        setContent {
            PWAPTheme {
                if (showConfig) {
                    ConfigScreen(
                        preferences = preferences,
                        onSave = {
                            showConfig = false
                            startProxy()
                        }
                    )
                } else {
                    val initialUrl = preferences.lastVisitedUrl.ifBlank { preferences.targetUrl }
                    WebViewScreen(
                        targetUrl = preferences.targetUrl,
                        onInitialPageRendered = ::releaseStartupSplash
                    )
                }
            }
        }
    }

    private fun releaseStartupSplash() {
        keepSplashVisible = false
        waitingForInitialPage = false
    }

    private fun startProxy() {
        lifecycleScope.launch {
            ProxyManager.start(
                preferences.proxyConfig,
                preferences.dnsServer
            )
        }
    }

    override fun onResume() {
        super.onResume()
        proximitySensor.register()
    }

    override fun onPause() {
        super.onPause()
        proximitySensor.unregister()
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleScope.launch { ProxyManager.stop() }
    }
}
