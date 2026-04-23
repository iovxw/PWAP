package net.iovxw.pwap.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.iovxw.pwap.R
import net.iovxw.pwap.data.AppPreferences
import net.iovxw.pwap.proxy.ProxyManager
import net.iovxw.pwap.proxy.SingBoxConfig
import net.iovxw.pwap.scanner.ScannerActivity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    preferences: AppPreferences,
    onSave: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var targetUrl by remember { mutableStateOf(preferences.targetUrl) }
    var proxyConfig by remember { mutableStateOf(preferences.proxyConfig) }
    var dnsServer by remember { mutableStateOf(preferences.dnsServer) }
    var testResult by remember { mutableStateOf<ProxyTestResult?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    var showConfigDialog by remember { mutableStateOf(false) }

    val scannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanText = result.data?.getStringExtra(ScannerActivity.EXTRA_SCAN_RESULT)
                ?: return@rememberLauncherForActivityResult
            val parsed = SingBoxConfig.parseProxyLink(scanText)
            if (parsed != null) {
                proxyConfig = parsed
                Toast.makeText(
                    context,
                    context.getString(R.string.proxy_config_recognized),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    context,
                    context.getString(R.string.proxy_config_unrecognized),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.config_title)) })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = targetUrl,
                onValueChange = { targetUrl = it },
                label = { Text(stringResource(R.string.target_url_label)) },
                placeholder = { Text("https://example.com") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.proxy_config_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (proxyConfig.isNotBlank()) {
                        Text(
                            text = getProxyDescription(context, proxyConfig),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.proxy_config_not_set),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = {
                            val intent = Intent(context, ScannerActivity::class.java)
                            scannerLauncher.launch(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.proxy_config_scan_button))
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.dns_settings_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.dns_settings_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = dnsServer,
                        onValueChange = { dnsServer = it },
                        label = { Text(stringResource(R.string.dns_server_label)) },
                        placeholder = { Text("8.8.8.8") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = {
                            Text(stringResource(R.string.dns_server_supporting_text))
                        }
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.proxy_test_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    testResult?.let {
                        Text(
                            text = it.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = when (it.status) {
                                ProxyTestResultStatus.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                                ProxyTestResultStatus.SUCCESS -> MaterialTheme.colorScheme.primary
                                ProxyTestResultStatus.ERROR -> MaterialTheme.colorScheme.error
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    OutlinedButton(
                        onClick = {
                            if (proxyConfig.isBlank()) {
                                testResult = ProxyTestResult(
                                    context.getString(R.string.proxy_test_missing_proxy),
                                    ProxyTestResultStatus.ERROR
                                )
                                return@OutlinedButton
                            }
                            isTesting = true
                            testResult = ProxyTestResult(
                                context.getString(R.string.proxy_test_in_progress),
                                ProxyTestResultStatus.INFO
                            )

                            scope.launch {
                                val startResult = ProxyManager.start(proxyConfig, dnsServer)
                                if (startResult.isFailure) {
                                    testResult = ProxyTestResult(
                                        context.getString(
                                            R.string.proxy_test_start_failed,
                                            startResult.exceptionOrNull()?.localizedMessage
                                                ?.takeIf { it.isNotBlank() }
                                                ?: context.getString(R.string.unknown_error)
                                        ),
                                        ProxyTestResultStatus.ERROR
                                    )
                                    isTesting = false
                                    return@launch
                                }

                                val port = ProxyManager.currentPort.value
                                val result = ProxyManager.testConnection("127.0.0.1:$port")
                                result.onSuccess { latency ->
                                    testResult = ProxyTestResult(
                                        context.getString(R.string.proxy_test_success, latency),
                                        ProxyTestResultStatus.SUCCESS
                                    )
                                }.onFailure { error ->
                                    testResult = ProxyTestResult(
                                        context.getString(
                                            R.string.proxy_test_failure,
                                            error.localizedMessage?.takeIf { it.isNotBlank() }
                                                ?: context.getString(R.string.unknown_error)
                                        ),
                                        ProxyTestResultStatus.ERROR
                                    )
                                }
                                isTesting = false
                            }
                        },
                        enabled = !isTesting,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.NetworkCheck, contentDescription = null)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.proxy_test_button))
                    }
                }
            }

            OutlinedButton(
                onClick = { showConfigDialog = true },
                enabled = proxyConfig.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Code, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.view_generated_config))
            }

            Button(
                onClick = {
                    var url = targetUrl.trim()
                    if (url.isNotBlank() && !url.contains("://")) {
                        url = "https://$url"
                    }
                    preferences.targetUrl = url
                    preferences.proxyConfig = proxyConfig
                    preferences.dnsServer = dnsServer
                    preferences.isFirstLaunch = false
                    onSave()
                },
                enabled = targetUrl.isNotBlank() && proxyConfig.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.save_and_launch))
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showConfigDialog) {
        val configJson = try {
            SingBoxConfig.buildConfig(proxyConfig, 0, dnsServer)
        } catch (error: Exception) {
            context.getString(
                R.string.config_generation_failed,
                error.localizedMessage?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.unknown_error)
            )
        }
        AlertDialog(
            onDismissRequest = { showConfigDialog = false },
            title = { Text(stringResource(R.string.sing_box_config_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                ) {
                    Text(
                        text = configJson,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showConfigDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }
}

private fun getProxyDescription(context: Context, config: String): String {
    return try {
        val json = org.json.JSONObject(config)
        val type = json.optString("type", context.getString(R.string.proxy_type_unknown))
        val server = json.optString("server", "")
        val port = json.optInt("server_port", 0)
        "$type → $server:$port"
    } catch (_: Exception) {
        context.getString(R.string.proxy_configured)
    }
}

private data class ProxyTestResult(
    val message: String,
    val status: ProxyTestResultStatus,
)

private enum class ProxyTestResultStatus {
    INFO,
    SUCCESS,
    ERROR,
}
