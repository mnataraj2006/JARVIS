package com.jarvis.mobileagent.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.mobileagent.service.JarvisAccessibilityService
import com.jarvis.mobileagent.service.JarvisNotificationListener

@Composable
fun PermissionSetupScreen(
    context: Context,
    hasMediaProjection: Boolean,
    onRequestMediaProjection: () -> Unit
) {
    val a11yActive = JarvisAccessibilityService.isRunning()
    val notifActive = JarvisNotificationListener.isRunning()

    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val isBatteryIgnored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } else true

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0D14))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "AGENT PERMISSIONS",
            color = Color(0xFF00E5FF),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 16.dp)
        )

        Text(
            text = "JARVIS requires these permissions to control phone apps, notifications, and screen capture on your behalf.",
            color = Color(0xFF94A3B8),
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // 1. Accessibility Service
        PermissionCard(
            title = "Accessibility Service",
            description = "Allows JARVIS to click, swipe, type, and automate UI actions",
            granted = a11yActive,
            onClick = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 2. Notification Listener
        PermissionCard(
            title = "Notification Gateway",
            description = "Allows JARVIS to read incoming WhatsApp messages and notifications",
            granted = notifActive,
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 3. Screen Capture / MediaProjection
        PermissionCard(
            title = "Screen Capture & Stream",
            description = "Enables desktop computer vision and live screen mirror",
            granted = hasMediaProjection,
            onClick = onRequestMediaProjection
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 4. Battery Optimization Exemption
        PermissionCard(
            title = "Background Execution",
            description = "Prevents Android from killing JARVIS when the screen is turned off",
            granted = isBatteryIgnored,
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isBatteryIgnored) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            }
        )
    }
}

@Composable
fun PermissionCard(
    title: String,
    description: String,
    granted: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF131822))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, color = Color(0xFFE2E8F0), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = description, color = Color(0xFF64748B), fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.width(12.dp))

            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (granted) Color(0xFF0F392B) else Color(0xFF0077FF),
                    contentColor = if (granted) Color(0xFF10B981) else Color.White
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = if (granted) "Active" else "Enable",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
