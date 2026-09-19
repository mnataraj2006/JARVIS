package com.jarvis.mobileagent.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jarvis.mobileagent.JarvisApp
import com.jarvis.mobileagent.capabilities.*
import com.jarvis.mobileagent.communication.*
import com.jarvis.mobileagent.ui.MainActivity
import kotlinx.coroutines.*

class JarvisAgentService : Service() {

    companion object {
        private const val TAG = "JarvisService"
        private const val NOTIFICATION_ID = 1001
        var instance: JarvisAgentService? = null
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var heartbeatJob: Job? = null

    lateinit var pairingManager: PairingManager
        private set
    lateinit var wsManager: WebSocketManager
        private set

    lateinit var appManager: AppManager
        private set
    lateinit var screenCaptureManager: ScreenCaptureManager
        private set
    lateinit var deviceInfoManager: DeviceInfoManager
        private set
    lateinit var audioVolumeManager: AudioVolumeManager
        private set
    lateinit var clipboardSync: ClipboardSync
        private set
    lateinit var fileTransferManager: FileTransferManager
        private set

    var connectionStatus: String = "Idle"
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "Starting JarvisAgentService...")

        pairingManager = PairingManager(this)
        appManager = AppManager(this)
        screenCaptureManager = ScreenCaptureManager(this)
        deviceInfoManager = DeviceInfoManager(this)
        audioVolumeManager = AudioVolumeManager(this)
        clipboardSync = ClipboardSync(this)
        fileTransferManager = FileTransferManager(this)

        wsManager = WebSocketManager(
            pairingManager = pairingManager,
            onCommandReceived = { cmd -> handleCommand(cmd) },
            onConnectionStateChanged = { connected, statusText ->
                connectionStatus = statusText ?: if (connected) "Connected" else "Disconnected"
                updateNotification(connectionStatus)
            }
        )

        // Wire notification listener events to push to desktop
        JarvisNotificationListener.onNotificationReceived = { payload ->
            wsManager.sendEvent("notification", payload)
        }

        startForeground(NOTIFICATION_ID, buildNotification("Initializing..."))
        startHeartbeat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pin = intent?.getStringExtra("pairing_pin")
        val shouldConnect = intent?.getBooleanExtra("connect", false) ?: false

        if (shouldConnect || pairingManager.isPaired()) {
            wsManager.connect(pin)
        }
        return START_STICKY
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(10_000)
                try {
                    val (level, charging) = deviceInfoManager.getBatteryInfo()
                    val net = deviceInfoManager.getNetworkInfo()["type"]?.toString() ?: "wifi"
                    wsManager.sendHeartbeat(level, charging, net)
                } catch (e: Exception) {
                    Log.w(TAG, "Heartbeat tick error: ${e.message}")
                }
            }
        }
    }

    private fun handleCommand(cmd: CommandFrame) {
        serviceScope.launch(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            var success = true
            var result: Any? = null
            var error: String? = null

            try {
                when (cmd.action) {
                    "launch_app" -> {
                        val appName = cmd.parameters["app_name"]?.toString()
                            ?: cmd.parameters["package_name"]?.toString() ?: ""
                        val (ok, msg) = appManager.launchApp(appName)
                        success = ok
                        if (ok) result = mapOf("message" to msg) else error = msg
                    }

                    "list_apps" -> {
                        result = appManager.listApps()
                    }

                    "tap" -> {
                        val a11y = JarvisAccessibilityService.instance
                        if (a11y == null) {
                            success = false
                            error = "Accessibility service is not active. Enable it in Android Settings."
                        } else {
                            val x = (cmd.parameters["x"] as? Number)?.toFloat() ?: 0f
                            val y = (cmd.parameters["y"] as? Number)?.toFloat() ?: 0f
                            success = a11y.clickCoordinate(x, y)
                            result = if (success) "Tapped at ($x, $y)" else "Tap failed"
                        }
                    }

                    "swipe" -> {
                        val a11y = JarvisAccessibilityService.instance
                        if (a11y == null) {
                            success = false
                            error = "Accessibility service is not active."
                        } else {
                            val x1 = (cmd.parameters["x1"] as? Number)?.toFloat() ?: 0f
                            val y1 = (cmd.parameters["y1"] as? Number)?.toFloat() ?: 0f
                            val x2 = (cmd.parameters["x2"] as? Number)?.toFloat() ?: 0f
                            val y2 = (cmd.parameters["y2"] as? Number)?.toFloat() ?: 0f
                            val dur = (cmd.parameters["duration_ms"] as? Number)?.toLong() ?: 300L
                            success = a11y.swipe(x1, y1, x2, y2, dur)
                            result = if (success) "Swiped successfully" else "Swipe failed"
                        }
                    }

                    "click_text" -> {
                        val a11y = JarvisAccessibilityService.instance
                        if (a11y == null) {
                            success = false
                            error = "Accessibility service is not active."
                        } else {
                            val text = cmd.parameters["text"]?.toString() ?: ""
                            success = a11y.clickByText(text)
                            result = if (success) "Clicked '$text'" else "Could not find clickable element with text '$text'"
                        }
                    }

                    "type" -> {
                        val a11y = JarvisAccessibilityService.instance
                        if (a11y == null) {
                            success = false
                            error = "Accessibility service is not active."
                        } else {
                            val text = cmd.parameters["text"]?.toString() ?: ""
                            success = a11y.typeText(text)
                            result = if (success) "Typed: $text" else "No active editable input field found"
                        }
                    }

                    "home" -> {
                        val a11y = JarvisAccessibilityService.instance
                        success = a11y?.pressHome() ?: false
                        if (!success && a11y == null) error = "Accessibility service is not active."
                    }

                    "back" -> {
                        val a11y = JarvisAccessibilityService.instance
                        success = a11y?.pressBack() ?: false
                        if (!success && a11y == null) error = "Accessibility service is not active."
                    }

                    "recents" -> {
                        val a11y = JarvisAccessibilityService.instance
                        success = a11y?.pressRecents() ?: false
                        if (!success && a11y == null) error = "Accessibility service is not active."
                    }

                    "lock" -> {
                        val a11y = JarvisAccessibilityService.instance
                        success = a11y?.pressLock() ?: false
                        if (!success && a11y == null) error = "Accessibility service is not active."
                    }

                    "ui_hierarchy" -> {
                        val a11y = JarvisAccessibilityService.instance
                        if (a11y == null) {
                            success = false
                            error = "Accessibility service is not active."
                        } else {
                            result = a11y.getUiHierarchy()
                        }
                    }

                    "screenshot" -> {
                        val quality = (cmd.parameters["quality"] as? Number)?.toInt() ?: 80
                        val (ok, data) = screenCaptureManager.captureScreenshot(quality)
                        success = ok
                        if (ok) result = mapOf("image_base64" to data) else error = data
                    }

                    "start_stream" -> {
                        val fps = (cmd.parameters["fps"] as? Number)?.toInt() ?: 8
                        success = screenCaptureManager.startScreenStream(fps) { frameBytes ->
                            wsManager.sendBinaryFrame(frameBytes)
                        }
                        if (!success) error = "Screen capture permission required. Start stream from phone UI."
                        else result = "Screen streaming active"
                    }

                    "stop_stream" -> {
                        screenCaptureManager.stopScreenStream()
                        result = "Screen stream stopped"
                    }

                    "battery" -> {
                        val (level, charging) = deviceInfoManager.getBatteryInfo()
                        result = mapOf("level" to level, "charging" to charging)
                    }

                    "device_info" -> {
                        result = deviceInfoManager.getFullDeviceInfo()
                    }

                    "volume" -> {
                        val act = cmd.parameters["action"]?.toString() ?: "get"
                        when (act) {
                            "up" -> audioVolumeManager.adjustVolume(1)
                            "down" -> audioVolumeManager.adjustVolume(-1)
                            "mute" -> audioVolumeManager.mute(true)
                            "unmute" -> audioVolumeManager.mute(false)
                            "set" -> {
                                val v = (cmd.parameters["value"] as? Number)?.toInt() ?: 50
                                audioVolumeManager.setVolume(v)
                            }
                        }
                        result = audioVolumeManager.getVolume()
                    }

                    "flashlight" -> {
                        val enabled = (cmd.parameters["enabled"] as? Boolean)
                            ?: (cmd.parameters["value"]?.toString()?.lowercase() == "on")
                        success = audioVolumeManager.setFlashlight(enabled)
                        result = mapOf("flashlight" to enabled)
                    }

                    "ring" -> {
                        audioVolumeManager.startRinging()
                        result = "Ringing phone"
                    }

                    "stop_ring" -> {
                        audioVolumeManager.stopRinging()
                        result = "Stopped ringing"
                    }

                    "notifications" -> {
                        val notifListener = JarvisNotificationListener.instance
                        if (notifListener == null) {
                            success = false
                            error = "Notification Listener service is not enabled in Android Settings."
                        } else {
                            result = notifListener.getActiveNotificationList()
                        }
                    }

                    "clipboard_get" -> {
                        result = mapOf("text" to clipboardSync.getClipboardText())
                    }

                    "clipboard_set" -> {
                        val text = cmd.parameters["text"]?.toString() ?: ""
                        clipboardSync.setClipboardText(text)
                        result = "Clipboard updated"
                    }

                    "send_file" -> {
                        val fileName = cmd.parameters["file_name"]?.toString() ?: "file_${System.currentTimeMillis()}"
                        val content = cmd.parameters["content_base64"]?.toString() ?: ""
                        val (ok, pathOrErr) = fileTransferManager.saveFile(fileName, content)
                        success = ok
                        if (ok) result = mapOf("saved_to" to pathOrErr) else error = pathOrErr
                    }

                    else -> {
                        success = false
                        error = "Unknown action: ${cmd.action}"
                    }
                }
            } catch (e: Exception) {
                success = false
                error = "Execution error: ${e.message}"
                Log.e(TAG, "Command execution failed: ${e.message}", e)
            }

            val duration = (System.currentTimeMillis() - t0).toDouble()
            val response = ResponseFrame(
                id = cmd.id,
                deviceId = pairingManager.deviceId,
                success = success,
                result = result,
                error = error,
                executionTimeMs = duration
            )
            wsManager.sendResponse(response)
        }
    }

    private fun buildNotification(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, JarvisApp.CHANNEL_ID)
            .setContentTitle("JARVIS Mobile Agent")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(status: String) {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(status))
    }

    override fun onDestroy() {
        super.onDestroy()
        heartbeatJob?.cancel()
        wsManager.disconnect()
        serviceScope.cancel()
        if (instance == this) instance = null
        Log.d(TAG, "JarvisAgentService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
