package net.iovxw.pwap.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import net.iovxw.pwap.data.AppPreferences

class ExternalWebViewActivity : BaseWebViewHostActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun resolveInitialUrl(preferences: AppPreferences): String? {
        if (!preferences.isConfigured) {
            return null
        }
        return intent.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }
    }

    override fun shouldRouteInCurrentWindow(url: Uri): Boolean = true

    companion object {
        private const val EXTRA_URL = "extra_url"

        fun createIntent(context: Context, url: String): Intent {
            return Intent(context, ExternalWebViewActivity::class.java)
                .putExtra(EXTRA_URL, url)
        }
    }
}
