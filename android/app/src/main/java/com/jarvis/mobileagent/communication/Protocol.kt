package com.jarvis.mobileagent.communication

import com.google.gson.annotations.SerializedName

data class CommandFrame(
    val type: String = "command",
    val id: String,
    val action: String,
    val parameters: Map<String, Any?> = emptyMap(),
    val timestamp: Double = 0.0
)

data class ResponseFrame(
    val type: String = "response",
    val id: String,
    @SerializedName("device_id") val deviceId: String,
    val success: Boolean,
    val result: Any? = null,
    val error: String? = null,
    @SerializedName("execution_time_ms") val executionTimeMs: Double = 0.0
)

data class BatteryData(
    val level: Int,
    val charging: Boolean
)

data class HeartbeatFrame(
    val type: String = "heartbeat",
    val battery: BatteryData,
    val network: String,
    val timestamp: Double
)

data class EventFrame(
    val type: String = "event",
    val event: String,
    val data: Map<String, Any?>
)

data class DeviceRegistration(
    val id: String,
    val name: String,
    val type: String = "android",
    val os: String = "Android",
    @SerializedName("os_version") val osVersion: String,
    val manufacturer: String,
    val model: String,
    @SerializedName("battery_level") val batteryLevel: Int?,
    @SerializedName("is_charging") val isCharging: Boolean,
    val network: String,
    val ip: String,
    val capabilities: List<String>,
    @SerializedName("agent_version") val agentVersion: String = "1.0.0"
)

data class HandshakeFrame(
    val type: String = "handshake",
    val token: String? = null,
    @SerializedName("device_token") val deviceToken: String? = null,
    val pin: String? = null,
    val device: DeviceRegistration
)
