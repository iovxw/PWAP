package net.iovxw.pwap.proxy

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import libcore.BoxInstance
import libcore.Libcore

object ProxyManager {
    private const val TAG = "ProxyManager"

    private var boxInstance: BoxInstance? = null

    private val _state = MutableStateFlow(ProxyState.IDLE)
    val state: StateFlow<ProxyState> = _state

    private val _currentPort = MutableStateFlow(0)
    val currentPort: StateFlow<Int> = _currentPort

    val isRunning: Boolean
        get() = _state.value == ProxyState.RUNNING

    suspend fun start(outboundJson: String, dnsServer: String = ""): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            stop()

            _state.value = ProxyState.STARTING

            // Find a free port
            val port = Libcore.findFreePort().toInt()
            val config = SingBoxConfig.buildConfig(outboundJson, port, dnsServer)
            Log.d(TAG, "Starting sing-box with config: $config")

            val instance = Libcore.newSingBoxInstance(config)
            instance.start()

            _currentPort.value = port
            boxInstance = instance

            _state.value = ProxyState.RUNNING
            Log.i(TAG, "Proxy started on 127.0.0.1:$port")
            Result.success(Unit)
        } catch (e: Exception) {
            _state.value = ProxyState.ERROR
            Log.e(TAG, "Failed to start proxy", e)
            Result.failure(e)
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        try {
            boxInstance?.close()
            boxInstance = null
            _currentPort.value = 0
            _state.value = ProxyState.IDLE
            Log.i(TAG, "Proxy stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping proxy", e)
        }
    }

    suspend fun testConnection(proxyAddr: String, url: String = "https://www.google.com/generate_204", timeout: Int = 5000): Result<Int> =
        withContext(Dispatchers.IO) {
            try {
                val latency = Libcore.urlTestViaProxy(proxyAddr, url, timeout)
                Result.success(latency)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    enum class ProxyState {
        IDLE, STARTING, RUNNING, ERROR
    }
}
