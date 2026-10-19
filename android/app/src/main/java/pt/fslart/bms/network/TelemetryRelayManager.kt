package pt.fslart.bms.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import pt.fslart.bms.model.BmsState
import pt.fslart.bms.model.CarProfile
import pt.fslart.bms.model.LiveJsonDecoder
import java.util.concurrent.TimeUnit

enum class RelayConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

class TelemetryRelayManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val TAG = "TelemetryRelay"
    private val PREFS_NAME = "bms_relay_prefs"
    private val KEY_SERVER_URL = "server_url"
    private val KEY_RELAY_ENABLED = "relay_enabled"
    val DEFAULT_SERVER_URL = "wss://bms.stocklart.pt/ws/telemetry"

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // 0 for infinite WebSocket read
        .retryOnConnectionFailure(true)
        .build()

    // Configuration states
    private val _serverUrl = MutableStateFlow(prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL)
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _isRelayEnabled = MutableStateFlow(prefs.getBoolean(KEY_RELAY_ENABLED, false))
    val isRelayEnabled: StateFlow<Boolean> = _isRelayEnabled.asStateFlow()

    // 1. Broadcaster / Gateway state (Phone BLE -> Server)
    private val _relayState = MutableStateFlow(RelayConnectionState.DISCONNECTED)
    val relayState: StateFlow<RelayConnectionState> = _relayState.asStateFlow()

    private val _packetsSent = MutableStateFlow(0L)
    val packetsSent: StateFlow<Long> = _packetsSent.asStateFlow()

    private var relayWebSocket: WebSocket? = null
    private var relayReconnectJob: Job? = null

    // 2. Viewer / Client state (Server -> Phone Wi-Fi/4G)
    private val _viewerState = MutableStateFlow(RelayConnectionState.DISCONNECTED)
    val viewerState: StateFlow<RelayConnectionState> = _viewerState.asStateFlow()

    private val _packetsReceived = MutableStateFlow(0L)
    val packetsReceived: StateFlow<Long> = _packetsReceived.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _incomingState = MutableSharedFlow<BmsState>(extraBufferCapacity = 64)
    val incomingState: SharedFlow<BmsState> = _incomingState.asSharedFlow()

    private val _rawLines = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val rawLines: SharedFlow<String> = _rawLines.asSharedFlow()

    private var viewerWebSocket: WebSocket? = null
    private var viewerReconnectJob: Job? = null
    private var isViewerWanted: Boolean = false

    var decoder = LiveJsonDecoder(CarProfile.TEK26E)

    fun setCar(car: CarProfile) {
        decoder.car = car
    }

    fun setServerUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isNotEmpty()) {
            _serverUrl.value = trimmed
            prefs.edit().putString(KEY_SERVER_URL, trimmed).apply()
        }
    }

    fun setRelayEnabled(enabled: Boolean) {
        _isRelayEnabled.value = enabled
        prefs.edit().putBoolean(KEY_RELAY_ENABLED, enabled).apply()
        if (!enabled) {
            disconnectRelay()
        }
    }

    fun normalizeWsUrl(input: String): String {
        var clean = input.trim()
        if (clean.isEmpty()) return DEFAULT_SERVER_URL

        if (clean.startsWith("ws://", ignoreCase = true) || clean.startsWith("wss://", ignoreCase = true)) {
            return clean
        }

        if (clean.startsWith("https://", ignoreCase = true)) {
            clean = "wss://" + clean.substring(8)
        } else if (clean.startsWith("http://", ignoreCase = true)) {
            clean = "ws://" + clean.substring(7)
        } else {
            // No scheme provided, e.g. "bms.stocklart.pt" or "stocklart.pt:8000"
            if (clean.contains("bms.stocklart.pt") || !clean.contains(":")) {
                clean = "wss://$clean"
            } else {
                clean = "ws://$clean"
            }
        }

        // Ensure endpoint path
        val uriPart = clean.substringAfter("://")
        if (!uriPart.contains("/")) {
            clean = "$clean/ws/telemetry"
        }
        return clean
    }

    // =========================================================================
    // BROADCASTER / GATEWAY (Forward BLE packets to Server)
    // =========================================================================

    private fun ensureRelayConnected() {
        if (!_isRelayEnabled.value) return
        if (relayWebSocket != null && _relayState.value == RelayConnectionState.CONNECTED) return
        if (_relayState.value == RelayConnectionState.CONNECTING) return

        val targetUrl = normalizeWsUrl(_serverUrl.value)
        _relayState.value = RelayConnectionState.CONNECTING
        Log.d(TAG, "Connecting Relay Broadcaster to $targetUrl")

        try {
            val request = Request.Builder().url(targetUrl).build()
            relayWebSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "Relay Broadcaster connected to server!")
                    _relayState.value = RelayConnectionState.CONNECTED
                    _lastError.value = null
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "Relay Broadcaster error: ${t.message}")
                    _relayState.value = RelayConnectionState.ERROR
                    _lastError.value = "Erro no servidor: ${t.localizedMessage ?: "Falha de rede"}"
                    relayWebSocket = null
                    scheduleRelayReconnect()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "Relay Broadcaster closed: $code / $reason")
                    _relayState.value = RelayConnectionState.DISCONNECTED
                    relayWebSocket = null
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error starting relay WebSocket: ${e.message}")
            _relayState.value = RelayConnectionState.ERROR
            _lastError.value = e.message
            scheduleRelayReconnect()
        }
    }

    private fun scheduleRelayReconnect() {
        if (!_isRelayEnabled.value) return
        relayReconnectJob?.cancel()
        relayReconnectJob = scope.launch {
            delay(3000L)
            if (_isRelayEnabled.value && _relayState.value != RelayConnectionState.CONNECTED) {
                ensureRelayConnected()
            }
        }
    }

    fun publishRawLine(line: String) {
        if (!_isRelayEnabled.value) return
        ensureRelayConnected()

        val ws = relayWebSocket
        if (ws != null && _relayState.value == RelayConnectionState.CONNECTED) {
            val sent = ws.send(line)
            if (sent) {
                _packetsSent.value += 1
            }
        }
    }

    fun disconnectRelay() {
        relayReconnectJob?.cancel()
        relayReconnectJob = null
        try {
            relayWebSocket?.close(1000, "App requested close")
        } catch (_: Exception) {}
        relayWebSocket = null
        _relayState.value = RelayConnectionState.DISCONNECTED
    }

    // =========================================================================
    // VIEWER / CLIENT (Receive telemetry from Server over Wi-Fi/4G)
    // =========================================================================

    fun startViewer(customUrl: String? = null) {
        isViewerWanted = true
        val targetUrl = normalizeWsUrl(customUrl ?: _serverUrl.value)
        setServerUrl(targetUrl)

        disconnectViewer()
        isViewerWanted = true
        _viewerState.value = RelayConnectionState.CONNECTING
        Log.i(TAG, "Connecting Viewer to server at $targetUrl")

        try {
            val request = Request.Builder().url(targetUrl).build()
            viewerWebSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "Viewer connected to server! Ready for telemetry.")
                    _viewerState.value = RelayConnectionState.CONNECTED
                    _lastError.value = null
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val trimmed = text.trim()
                    if (trimmed.isNotEmpty()) {
                        _packetsReceived.value += 1
                        _rawLines.tryEmit(trimmed)
                        processIncomingLine(trimmed)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "Viewer WebSocket error: ${t.message}")
                    _viewerState.value = RelayConnectionState.ERROR
                    _lastError.value = "Falha de ligação ao servidor: ${t.localizedMessage ?: "Erro de rede"}"
                    viewerWebSocket = null
                    scheduleViewerReconnect()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "Viewer WebSocket closed: $code / $reason")
                    _viewerState.value = RelayConnectionState.DISCONNECTED
                    viewerWebSocket = null
                    if (isViewerWanted) {
                        scheduleViewerReconnect()
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error starting viewer WebSocket: ${e.message}")
            _viewerState.value = RelayConnectionState.ERROR
            _lastError.value = e.message
            scheduleViewerReconnect()
        }
    }

    private fun scheduleViewerReconnect() {
        if (!isViewerWanted) return
        viewerReconnectJob?.cancel()
        viewerReconnectJob = scope.launch {
            delay(2500L)
            if (isViewerWanted && _viewerState.value != RelayConnectionState.CONNECTED) {
                startViewer()
            }
        }
    }

    private fun processIncomingLine(line: String) {
        scope.launch(Dispatchers.Default) {
            val state = decoder.decodeLine(line)
            if (state != null) {
                val enrichedState = state.copy(
                    connectionAddress = _serverUrl.value,
                    connectionDeviceName = "Nuvem (stocklart.pt)",
                    isConnected = true,
                    isDemo = false
                )
                _incomingState.emit(enrichedState)
            }
        }
    }

    fun disconnectViewer() {
        isViewerWanted = false
        viewerReconnectJob?.cancel()
        viewerReconnectJob = null
        try {
            viewerWebSocket?.close(1000, "User disconnected")
        } catch (_: Exception) {}
        viewerWebSocket = null
        _viewerState.value = RelayConnectionState.DISCONNECTED
    }

    fun disconnectAll() {
        disconnectRelay()
        disconnectViewer()
    }
}
