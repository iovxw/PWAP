package net.iovxw.pwap.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import net.iovxw.pwap.MainActivity
import net.iovxw.pwap.data.AppPreferences
import net.iovxw.pwap.sensor.ProximitySensorManager

class PureWebViewActivity : BaseWebViewHostActivity() {

    private lateinit var proximitySensor: ProximitySensorManager
    private var keepSplashVisible = true
    private var targetHost = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { keepSplashVisible }
        super.onCreate(savedInstanceState)
        if (isFinishing) {
            keepSplashVisible = false
            return
        }
        proximitySensor = ProximitySensorManager(this) {
            runOnUiThread {
                startActivity(MainActivity.createConfigIntent(this))
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::proximitySensor.isInitialized) {
            proximitySensor.register()
        }
    }

    override fun onPause() {
        if (::proximitySensor.isInitialized) {
            proximitySensor.unregister()
        }
        super.onPause()
    }

    override fun resolveInitialUrl(preferences: AppPreferences): String? {
        if (!preferences.isConfigured) {
            keepSplashVisible = false
            startActivity(MainActivity.createConfigIntent(this))
            finish()
            return null
        }

        targetHost = Uri.parse(preferences.targetUrl).host.orEmpty()
        return preferences.targetUrl
    }

    override fun shouldRouteInCurrentWindow(url: Uri): Boolean {
        return isSameDomain(url.host.orEmpty(), targetHost)
    }

    override fun onInitialPageFinished() {
        keepSplashVisible = false
    }

    companion object {
        fun createIntent(context: Context): Intent {
            return Intent(context, PureWebViewActivity::class.java)
        }
    }
}

private fun isSameDomain(host1: String, host2: String): Boolean {
    if (host1 == host2) return true
    return host1.endsWith(".$host2") || host2.endsWith(".$host1")
}
