package net.iovxw.pwap.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("pwap_prefs", Context.MODE_PRIVATE)

    var targetUrl: String
        get() = prefs.getString(KEY_TARGET_URL, "") ?: ""
        set(value) = prefs.edit { putString(KEY_TARGET_URL, value) }

    var proxyConfig: String
        get() = prefs.getString(KEY_PROXY_CONFIG, "") ?: ""
        set(value) = prefs.edit { putString(KEY_PROXY_CONFIG, value) }

    var isFirstLaunch: Boolean
        get() = prefs.getBoolean(KEY_FIRST_LAUNCH, true)
        set(value) = prefs.edit { putBoolean(KEY_FIRST_LAUNCH, value) }

    val isConfigured: Boolean
        get() = targetUrl.isNotBlank() && proxyConfig.isNotBlank()

    var lastVisitedUrl: String
        get() = prefs.getString(KEY_LAST_URL, "") ?: ""
        set(value) = prefs.edit { putString(KEY_LAST_URL, value) }

    var dnsServer: String
        get() = prefs.getString(KEY_DNS_SERVER, DEFAULT_DNS) ?: DEFAULT_DNS
        set(value) = prefs.edit { putString(KEY_DNS_SERVER, value) }

    companion object {
        private const val KEY_TARGET_URL = "target_url"
        private const val KEY_PROXY_CONFIG = "proxy_config"
        private const val KEY_FIRST_LAUNCH = "first_launch"
        private const val KEY_LAST_URL = "last_visited_url"
        private const val KEY_DNS_SERVER = "dns_server"
        private const val DEFAULT_DNS = "8.8.8.8"
    }
}
