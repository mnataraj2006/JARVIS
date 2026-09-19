package com.jarvis.mobileagent.communication

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit

class WebSocketManager(
    private val pairingManager: PairingManager,
    private val onCommandReceived: (CommandFrame) -> Unit,
    private val onConnectionStateChanged: (Boolean, String?) -> Unit
) {
    private val TAG = "JarvisWebSocket"
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var isConnecting = false
    private var shouldReconnect = true
    private var reconnectAttempt = 0
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun connect(pin: String? = null) {
        if (isConnecting || isConnected) return
        isConnecting = true
        shouldReconnect = true

        val url = pairingManager.getWsUrl()
        Log.d(TAG, "Connecting to JARVIS desktop at $url...")
        onConnectionStateChanged(false, "Connecting...")

        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                isConnected = true
                isConnecting = false
                reconnectAttempt = 0
                Log.d(TAG, "Connected to JARVIS desktop!")
                onConnectionStateChanged(true, "Connected")

                // Send handshake / registration
                sendHandshake(ws, pin)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val root = JsonParser.parseString(text).asJsonObject
                    val type = root.get("type")?.asString

                    when (type) {
                        "handshake_ok" -> {
                            val token = root.get("token")?.asString
                            val devToken = root.get("device_token")?.asString
                            if (!token.isNullOrBlank()) pairingManager.authToken = token
                            if (!devToken.isNullOrBlank()) pairingManager.deviceToken = devToken
                            Log.d(TAG, "Handshake authenticated successfully")
                        }
                        "command" -> {
                            val cmd = gson.fromJson(text, CommandFrame::class.java)
                            onCommandReceived(cmd)
                        }
                        "registered" -> {
                            Log.d(TAG, "Device registration confirmed by desktop")
                        }
                        "error" -> {
                            val msg = root.get("message")?.asString ?: "Unknown error"
                            Log.e(TAG, "Desktop error: $msg")
                        }
                        else -> {
                            Log.d(TAG, "Received message: $text")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse incoming WS message: ${e.message}")
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Closing WS: $code / $reason")
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                isConnected = false
                isConnecting = false
                onConnectionStateChanged(false, "Disconnected ($reason)")
                scheduleReconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                isConnecting = false
                Log.w(TAG, "Connection failure: ${t.message}")
                onConnectionStateChanged(false, "Connection failed: ${t.message}")
                scheduleReconnect()
            }
        })
    }

    private fun sendHandshake(ws: WebSocket, pin: String?) {
        val devInfo = DeviceRegistration(
            id = pairingManager.deviceId,
            name = pairingManager.deviceName,
            osVersion = android.os.Build.VERSION.RELEASE,
            manufacturer = android.os.Build.MANUFACTURER,
            model = android.os.Build.MODEL,
            batteryLevel = null,
            isCharging = false,
            network = "wifi",
            ip = "",
            capabilities = listOf(
                "apps", "accessibility", "click", "swipe", "type",
                "screen_capture", "screen_stream", "volume", "flashlight",
                "notifications", "clipboard", "file_transfer"
            )
        )

        val handshake = HandshakeFrame(
            type = "handshake",
            token = pairingManager.authToken,
            deviceToken = pairingManager.deviceToken,
            pin = pin,
            device = devInfo
        )
        ws.send(gson.toJson(handshake))
    }

    fun sendResponse(response: ResponseFrame) {
        val json = gson.toJson(response)
        webSocket?.send(json)
    }

    fun sendHeartbeat(batteryLevel: Int, isCharging: Boolean, network: String) {
        if (!isConnected) return
        val hb = HeartbeatFrame(
            type = "heartbeat",
            battery = BatteryData(level = batteryLevel, charging = isCharging),
            network = network,
            timestamp = System.currentTimeMillis() / 1000.0
        )
        webSocket?.send(gson.toJson(hb))
    }

    fun sendEvent(eventName: String, data: Map<String, Any?>) {
        if (!isConnected) return
        val event = EventFrame(
            type = "event",
            event = eventName,
            data = data
        )
        webSocket?.send(gson.toJson(event))
    }

    fun sendBinaryFrame(bytes: ByteArray) {
        if (!isConnected) return
        webSocket?.send(ByteString.of(*bytes))
    }

    fun disconnect() {
        shouldReconnect = false
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        isConnected = false
        isConnecting = false
        onConnectionStateChanged(false, "Disconnected")
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        scope.launch {
            reconnectAttempt++
            val delaySeconds = (2 * reconnectAttempt).coerceAtMost(20)
            Log.d(TAG, "Reconnecting in ${delaySeconds}s (attempt #$reconnectAttempt)...")
            onConnectionStateChanged(false, "Reconnecting in ${delaySeconds}s...")
            delay(delaySeconds * 1000L)
            connect()
        }
    }
}
