package com.jarvis.mobileagent.capabilities

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.net.Inet4Address
import java.net.NetworkInterface

class DeviceInfoManager(private val context: Context) {

    fun getBatteryInfo(): Pair<Int, Boolean> {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        return Pair(pct, isCharging)
    }

    fun getStorageInfo(): Map<String, Any> {
        val stat = StatFs(Environment.getDataDirectory().path)
        val totalBytes = stat.totalBytes
        val availableBytes = stat.availableBytes
        val usedBytes = totalBytes - availableBytes

        return mapOf(
            "total_gb" to String.format("%.2f", totalBytes / (1024.0 * 1024.0 * 1024.0)),
            "available_gb" to String.format("%.2f", availableBytes / (1024.0 * 1024.0 * 1024.0)),
            "used_gb" to String.format("%.2f", usedBytes / (1024.0 * 1024.0 * 1024.0)),
            "used_percent" to if (totalBytes > 0) ((usedBytes * 100) / totalBytes).toInt() else 0
        )
    }

    fun getRamInfo(): Map<String, Any> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memoryInfo)

        val total = memoryInfo.totalMem
        val avail = memoryInfo.availMem
        val used = total - avail

        return mapOf(
            "total_gb" to String.format("%.2f", total / (1024.0 * 1024.0 * 1024.0)),
            "used_gb" to String.format("%.2f", used / (1024.0 * 1024.0 * 1024.0)),
            "available_gb" to String.format("%.2f", avail / (1024.0 * 1024.0 * 1024.0)),
            "used_percent" to if (total > 0) ((used * 100) / total).toInt() else 0
        )
    }

    fun getNetworkInfo(): Map<String, Any> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(active)

        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val typeStr = when {
            isWifi -> "WiFi"
            isCellular -> "Cellular"
            else -> "Offline"
        }

        var ip = "127.0.0.1"
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        ip = addr.hostAddress ?: ""
                        break
                    }
                }
            }
        } catch (_: Exception) {}

        return mapOf(
            "type" to typeStr,
            "ip" to ip,
            "connected" to (caps != null)
        )
    }

    fun getFullDeviceInfo(): Map<String, Any> {
        val (batteryLevel, isCharging) = getBatteryInfo()
        return mapOf(
            "model" to Build.MODEL,
            "manufacturer" to Build.MANUFACTURER,
            "brand" to Build.BRAND,
            "android_version" to Build.VERSION.RELEASE,
            "sdk_int" to Build.VERSION.SDK_INT,
            "battery_level" to batteryLevel,
            "is_charging" to isCharging,
            "storage" to getStorageInfo(),
            "ram" to getRamInfo(),
            "network" to getNetworkInfo()
        )
    }
}
