package net.iovxw.pwap

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.iovxw.pwap.data.AppPreferences
import net.iovxw.pwap.browser.PureWebViewActivity
import net.iovxw.pwap.sensor.ProximitySensorManager
import net.iovxw.pwap.ui.ConfigScreen
import net.iovxw.pwap.ui.theme.PWAPTheme

class MainActivity : ComponentActivity() {

    private lateinit var preferences: AppPreferences
    private lateinit var proximitySensor: ProximitySensorManager
    private var showConfig by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        preferences = AppPreferences(this)
        val forceConfig = intent.getBooleanExtra(EXTRA_FORCE_CONFIG, false)

        showConfig = forceConfig || preferences.isFirstLaunch || !preferences.isConfigured

        proximitySensor = ProximitySensorManager(this) {
            runOnUiThread { showConfig = true }
        }

        if (!showConfig) {
            launchPureHost()
            return
        }

        setContent {
            PWAPTheme {
                if (showConfig) {
                    ConfigScreen(
                        preferences = preferences,
                        onSave = {
                            launchPureHost()
                        }
                    )
                }
            }
        }
    }

    private fun launchPureHost() {
        startActivity(PureWebViewActivity.createIntent(this))
        finish()
    }

    override fun onResume() {
        super.onResume()
        if (showConfig) {
            proximitySensor.register()
        }
    }

    override fun onPause() {
        super.onPause()
        if (showConfig) {
            proximitySensor.unregister()
        }
    }

    companion object {
        private const val EXTRA_FORCE_CONFIG = "extra_force_config"

        fun createConfigIntent(context: Context): Intent {
            return Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_FORCE_CONFIG, true)
        }
    }
}
