package com.jarvis.mobileagent.capabilities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper

class ClipboardSync(private val context: Context) {

    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val mainHandler = Handler(Looper.getMainLooper())

    fun getClipboardText(): String {
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text
            return text?.toString() ?: ""
        }
        return ""
    }

    fun setClipboardText(text: String, label: String = "JARVIS") {
        mainHandler.post {
            val clip = ClipData.newPlainText(label, text)
            clipboard.setPrimaryClip(clip)
        }
    }
}
