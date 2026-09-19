package com.jarvis.mobileagent.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class JarvisNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "JarvisNotif"
        var instance: JarvisNotificationListener? = null
            private set

        var onNotificationReceived: ((Map<String, Any?>) -> Unit)? = null

        fun isRunning(): Boolean = instance != null
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.d(TAG, "Jarvis Notification Listener connected!")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance == this) instance = null
        Log.d(TAG, "Jarvis Notification Listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val extras = sbn.notification.extras ?: return
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val pkg = sbn.packageName ?: ""

        // Skip our own app notifications
        if (pkg == packageName) return

        val payload = mapOf(
            "package" to pkg,
            "title" to title,
            "text" to text,
            "post_time" to sbn.postTime,
            "id" to sbn.id
        )

        Log.d(TAG, "Notification received from $pkg: $title — $text")
        onNotificationReceived?.invoke(payload)
    }

    fun getActiveNotificationList(): List<Map<String, Any?>> {
        val list = mutableListOf<Map<String, Any?>>()
        try {
            val active = activeNotifications ?: return list
            for (sbn in active) {
                if (sbn.packageName == packageName) continue
                val extras = sbn.notification.extras ?: continue
                val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
                val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
                list.add(mapOf(
                    "package" to sbn.packageName,
                    "title" to title,
                    "text" to text,
                    "post_time" to sbn.postTime,
                    "id" to sbn.id
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error listing active notifications: ${e.message}")
        }
        return list
    }
}
