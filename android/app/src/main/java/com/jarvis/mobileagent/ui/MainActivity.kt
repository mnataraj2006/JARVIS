package com.jarvis.mobileagent.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.jarvis.mobileagent.capabilities.ScreenCaptureManager
import com.jarvis.mobileagent.communication.PairingManager
import com.jarvis.mobileagent.service.JarvisAgentService

class MainActivity : ComponentActivity() {

    private lateinit var pairingManager: PairingManager
    private var hasMediaProjection by mutableStateOf(false)

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            ScreenCaptureManager.mediaProjection = mpm.getMediaProjection(result.resultCode, result.data!!)
            hasMediaProjection = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pairingManager = PairingManager(this)
        hasMediaProjection = ScreenCaptureManager.mediaProjection != null

        // Auto-start background agent service
        startAgentService()

        setContent {
            var currentScreen by remember { mutableStateOf("dashboard") }

            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0A0D14)
                ) {
                    if (currentScreen == "dashboard") {
                        DashboardScreen(
                            pairingManager = pairingManager,
                            service = JarvisAgentService.instance,
                            onOpenPermissions = { currentScreen = "permissions" },
                            onConnect = { host, port, pin ->
                                pairingManager.serverHost = host
                                pairingManager.serverPort = port
                                startAgentService(pin, connect = true)
                            },
                            onDisconnect = {
                                JarvisAgentService.instance?.wsManager?.disconnect()
                            }
                        )
                    } else {
                        PermissionSetupScreen(
                            context = this,
                            hasMediaProjection = hasMediaProjection,
                            onRequestMediaProjection = { requestScreenCapture() }
                        )
                    }
                }
            }
        }
    }

    private fun startAgentService(pin: String? = null, connect: Boolean = false) {
        val intent = Intent(this, JarvisAgentService::class.java).apply {
            if (!pin.isNullOrBlank()) putExtra("pairing_pin", pin)
            putExtra("connect", connect)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }

    private fun requestScreenCapture() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
}
