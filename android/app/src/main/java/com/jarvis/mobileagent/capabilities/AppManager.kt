package com.jarvis.mobileagent.capabilities

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

class AppManager(private val context: Context) {

    private val commonApps = mapOf(
        "whatsapp" to "com.whatsapp",
        "youtube" to "com.google.android.youtube",
        "chrome" to "com.android.chrome",
        "maps" to "com.google.android.apps.maps",
        "spotify" to "com.spotify.music",
        "camera" to "com.android.camera",
        "settings" to "com.android.settings",
        "calculator" to "com.google.android.calculator",
        "clock" to "com.google.android.deskclock",
        "instagram" to "com.instagram.android",
        "telegram" to "org.telegram.messenger",
        "gmail" to "com.google.android.gm",
        "phone" to "com.google.android.dialer",
        "messages" to "com.google.android.apps.messaging"
    )

    fun launchApp(appNameOrPackage: String): Pair<Boolean, String> {
        val query = appNameOrPackage.trim().lowercase()
        val pm = context.packageManager

        // 1. Direct package check
        var targetPkg: String? = if (query.contains(".")) query else commonApps[query]

        // 2. Search installed applications by display label
        if (targetPkg == null) {
            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in installed) {
                if ((app.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && pm.getLaunchIntentForPackage(app.packageName) == null) {
                    continue
                }
                val label = pm.getApplicationLabel(app).toString().lowercase()
                if (label == query || label.contains(query)) {
                    targetPkg = app.packageName
                    break
                }
            }
        }

        if (targetPkg == null) {
            return Pair(false, "App '$appNameOrPackage' not found on phone.")
        }

        val launchIntent = pm.getLaunchIntentForPackage(targetPkg)
        return if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            context.startActivity(launchIntent)
            Pair(true, "Launched $targetPkg on phone.")
        } else {
            Pair(false, "App $targetPkg has no launcher activity.")
        }
    }

    fun listApps(): List<Map<String, String>> {
        val pm = context.packageManager
        val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val result = mutableListOf<Map<String, String>>()

        for (app in installed) {
            if (pm.getLaunchIntentForPackage(app.packageName) != null) {
                val label = pm.getApplicationLabel(app).toString()
                result.add(mapOf("name" to label, "package" to app.packageName))
            }
        }
        return result
    }

    fun openAppDetails(packageName: String): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
