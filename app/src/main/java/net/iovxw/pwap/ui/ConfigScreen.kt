package net.iovxw.pwap.ui

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.iovxw.pwap.data.AppPreferences
import net.iovxw.pwap.proxy.ProxyManager
import net.iovxw.pwap.proxy.SingBoxConfig
import net.iovxw.pwap.scanner.ScannerActivity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    preferences: AppPreferences,
    onSave: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var targetUrl by remember { mutableStateOf(preferences.targetUrl) }
    var proxyConfig by remember { mutableStateOf(preferences.proxyConfig) }
    var dnsServer by remember { mutableStateOf(preferences.dnsServer) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    var showConfigDialog by remember { mutableStateOf(false) }

    val scannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanText = result.data?.getStringExtra(ScannerActivity.EXTRA_SCAN_RESULT) ?: return@rememberLauncherForActivityResult
            val parsed = SingBoxConfig.parseProxyLink(scanText)
            if (parsed != null) {
                proxyConfig = parsed
                Toast.makeText(context, "代理配置已识别", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "无法识别代理配置", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("PWAP 配置") })
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

            // Target URL
            OutlinedTextField(
                value = targetUrl,
                onValueChange = { targetUrl = it },
                label = { Text("目标网站 URL") },
                placeholder = { Text("https://example.com") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Proxy Config Section
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("代理配置", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))

                    if (proxyConfig.isNotBlank()) {
                        Text(
                            text = getProxyDescription(proxyConfig),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text(
                            text = "未配置，请扫码设置",
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
                        Text("扫码设置代理")
                    }
                }
            }

            // Proxy Test
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("DNS 设置", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "用于解析代理服务器域名（仅限此用途）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = dnsServer,
                        onValueChange = { dnsServer = it },
                        label = { Text("DNS 服务器") },
                        placeholder = { Text("8.8.8.8") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = {
                            Text("支持: IP / tls:// (DoT) / https:// (DoH) / quic:// (DoQ)")
                        }
                    )
                }
            }

            // Proxy Test
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("代理测试", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))

                    testResult?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (it.contains("ms")) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    OutlinedButton(
                        onClick = {
                            if (proxyConfig.isBlank()) {
                                testResult = "请先配置代理"
                                return@OutlinedButton
                            }
                            isTesting = true
                            testResult = "测试中..."

                            scope.launch {
                                // Start a temporary proxy for testing
                                val startResult = ProxyManager.start(proxyConfig, dnsServer)
                                if (startResult.isFailure) {
                                    testResult = "启动代理失败: ${startResult.exceptionOrNull()?.message}"
                                    isTesting = false
                                    return@launch
                                }

                                val port = ProxyManager.currentPort.value
                                val result = ProxyManager.testConnection("127.0.0.1:$port")
                                result.onSuccess { latency ->
                                    testResult = "连接成功: ${latency}ms"
                                }.onFailure { e ->
                                    testResult = "连接失败: ${e.message}"
                                }
                                isTesting = false
                            }
                        },
                        enabled = !isTesting,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.NetworkCheck, contentDescription = null)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("测试代理连接")
                    }
                }
            }

            // View Config Button
            OutlinedButton(
                onClick = { showConfigDialog = true },
                enabled = proxyConfig.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Code, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("查看生成配置")
            }

            // Save Button
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
                    preferences.lastVisitedUrl = ""
                    onSave()
                },
                enabled = targetUrl.isNotBlank() && proxyConfig.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("保存并启动")
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showConfigDialog) {
        val configJson = try {
            SingBoxConfig.buildConfig(proxyConfig, 0, dnsServer)
        } catch (e: Exception) {
            "配置生成失败: ${e.message}"
        }
        AlertDialog(
            onDismissRequest = { showConfigDialog = false },
            title = { Text("sing-box 配置") },
            text = {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                ) {
                    Text(
                        text = configJson,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showConfigDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }
}

private fun getProxyDescription(config: String): String {
    return try {
        val json = org.json.JSONObject(config)
        val type = json.optString("type", "unknown")
        val server = json.optString("server", "")
        val port = json.optInt("server_port", 0)
        "$type → $server:$port"
    } catch (_: Exception) {
        "已配置"
    }
}
