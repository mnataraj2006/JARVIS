package com.jarvis.mobileagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.mobileagent.communication.PairingManager
import com.jarvis.mobileagent.service.JarvisAgentService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    pairingManager: PairingManager,
    service: JarvisAgentService?,
    onOpenPermissions: () -> Unit,
    onConnect: (String, Int, String?) -> Unit,
    onDisconnect: () -> Unit
) {
    var hostText by remember { mutableStateOf(pairingManager.serverHost) }
    var portText by remember { mutableStateOf(pairingManager.serverPort.toString()) }
    var pinText by remember { mutableStateOf("") }

    val status = service?.connectionStatus ?: "Service Inactive"
    val isConnected = status == "Connected"

    val batteryInfo = service?.deviceInfoManager?.getBatteryInfo()
    val storageInfo = service?.deviceInfoManager?.getStorageInfo()
    val networkInfo = service?.deviceInfoManager?.getNetworkInfo()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0D14))
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "JARVIS",
                    color = Color(0xFF00E5FF),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp
                )
                Text(
                    text = "Mobile Control Agent",
                    color = Color(0xFF64748B),
                    fontSize = 12.sp
                )
            }

            // Connection Badge
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isConnected) Color(0xFF0F392B) else Color(0xFF271B1B)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                color = if (isConnected) Color(0xFF10B981) else Color(0xFFEF4444),
                                shape = CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = status,
                        color = if (isConnected) Color(0xFF10B981) else Color(0xFFEF4444),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Connection Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131822))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "DESKTOP LINK",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = hostText,
                    onValueChange = { hostText = it },
                    label = { Text("PC IP Address (e.g. 192.168.1.100)") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF00E5FF),
                        unfocusedBorderColor = Color(0xFF1E293B),
                        focusedTextColor = Color(0xFFE2E8F0),
                        unfocusedTextColor = Color(0xFFE2E8F0)
                    ),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it },
                        label = { Text("Port") },
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF00E5FF),
                            unfocusedBorderColor = Color(0xFF1E293B),
                            focusedTextColor = Color(0xFFE2E8F0),
                            unfocusedTextColor = Color(0xFFE2E8F0)
                        ),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    OutlinedTextField(
                        value = pinText,
                        onValueChange = { pinText = it },
                        label = { Text("6-Digit PIN") },
                        modifier = Modifier.weight(1.5f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF00E5FF),
                            unfocusedBorderColor = Color(0xFF1E293B),
                            focusedTextColor = Color(0xFFE2E8F0),
                            unfocusedTextColor = Color(0xFFE2E8F0)
                        ),
                        singleLine = true
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            val port = portText.toIntOrNull() ?: 8000
                            onConnect(hostText, port, pinText.ifBlank { null })
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0077FF)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(if (isConnected) "Reconnect" else "Connect to PC", fontWeight = FontWeight.Bold)
                    }

                    if (isConnected) {
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = onDisconnect,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444))
                        ) {
                            Text("Disconnect")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Device Telemetry
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131822))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "DEVICE TELEMETRY",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val batLvl = batteryInfo?.first ?: -1
                    val chg = batteryInfo?.second ?: false
                    StatItem("Battery", if (batLvl >= 0) "$batLvl%${if (chg) " ⚡" else ""}" else "N/A")
                    StatItem("Storage Used", "${storageInfo?.get("used_percent") ?: 0}%")
                    StatItem("Network", networkInfo?.get("type")?.toString() ?: "Offline")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Action Buttons
        Button(
            onClick = onOpenPermissions,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("Configure Permissions & Services", color = Color(0xFF00E5FF))
        }
    }
}

@Composable
fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, color = Color(0xFFE2E8F0), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = label, color = Color(0xFF64748B), fontSize = 11.sp)
    }
}
