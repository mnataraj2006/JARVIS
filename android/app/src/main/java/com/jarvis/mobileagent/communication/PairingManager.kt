package com.jarvis.mobileagent.communication

import android.content.Context
import android.os.Build
import java.util.UUID

class PairingManager(context: Context) {

    private val prefs = context.getSharedPreferences("jarvis_mobile_agent_prefs", Context.MODE_PRIVATE)

    var serverHost: String
        get() = prefs.getString("server_host", "192.168.31.43") ?: "192.168.31.43"
        set(value) = prefs.edit().putString("server_host", value.trim()).apply()

    var serverPort: Int
        get() = prefs.getInt("server_port", 8000)
        set(value) = prefs.edit().putInt("server_port", value).apply()

    var useTls: Boolean
        get() = prefs.getBoolean("use_tls", true)
        set(value) = prefs.edit().putBoolean("use_tls", value).apply()

    var lastPin: String?
        get() = prefs.getString("last_pin", null)
        set(value) = prefs.edit().putString("last_pin", value?.trim()?.uppercase()).apply()

    var deviceToken: String?
        get() = prefs.getString("device_token", null)
        set(value) = prefs.edit().putString("device_token", value).apply()

    var authToken: String?
        get() = prefs.getString("auth_token", null)
        set(value) = prefs.edit().putString("auth_token", value).apply()

    val deviceId: String
        get() {
            var id = prefs.getString("device_id", null)
            if (id == null) {
                id = "phone-${UUID.randomUUID().toString().substring(0, 8)}"
                prefs.edit().putString("device_id", id).apply()
            }
            return id
        }

    var deviceName: String
        get() = prefs.getString("device_name", "${Build.MANUFACTURER} ${Build.MODEL}") ?: "${Build.MANUFACTURER} ${Build.MODEL}"
        set(value) = prefs.edit().putString("device_name", value).apply()

    fun getWsUrl(): String {
        val proto = if (useTls) "wss" else "ws"
        return "$proto://$serverHost:$serverPort/ws/mobile-agent"
    }

    fun isPaired(): Boolean {
        return !deviceToken.isNullOrBlank()
    }

    fun clearPairing() {
        prefs.edit().remove("device_token").remove("auth_token").apply()
    }
}
