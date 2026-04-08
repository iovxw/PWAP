package net.iovxw.pwap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        preferences = AppPreferences(this)

        // Show config on first launch or if not configured
        showConfig = preferences.isFirstLaunch || !preferences.isConfigured

        proximitySensor = ProximitySensorManager(this) {
            runOnUiThread { showConfig = true }
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
                        targetUrl = initialUrl,
                        onUrlChanged = { url -> preferences.lastVisitedUrl = url }
                    )
                }
            }
        }
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