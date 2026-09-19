package com.jarvis.mobileagent.capabilities

import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log

class AudioVolumeManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var alarmRingtone: Ringtone? = null

    // ── Volume ───────────────────────────────────────────────────────────────

    fun getVolume(streamType: Int = AudioManager.STREAM_MUSIC): Map<String, Int> {
        val current = audioManager.getStreamVolume(streamType)
        val max = audioManager.getStreamMaxVolume(streamType)
        val pct = if (max > 0) (current * 100) / max else 0
        return mapOf("level" to pct, "raw" to current, "max" to max)
    }

    fun setVolume(percent: Int, streamType: Int = AudioManager.STREAM_MUSIC) {
        val max = audioManager.getStreamMaxVolume(streamType)
        val target = ((percent.coerceIn(0, 100) * max) / 100)
        audioManager.setStreamVolume(streamType, target, AudioManager.FLAG_SHOW_UI)
    }

    fun adjustVolume(direction: Int, streamType: Int = AudioManager.STREAM_MUSIC) {
        audioManager.adjustStreamVolume(streamType, direction, AudioManager.FLAG_SHOW_UI)
    }

    fun mute(mute: Boolean, streamType: Int = AudioManager.STREAM_MUSIC) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val dir = if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
            audioManager.adjustStreamVolume(streamType, dir, AudioManager.FLAG_SHOW_UI)
        } else {
            @Suppress("DEPRECATION")
            audioManager.setStreamMute(streamType, mute)
        }
    }

    // ── Flashlight ───────────────────────────────────────────────────────────

    fun setFlashlight(enabled: Boolean): Boolean {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cm.cameraIdList[0]
            cm.setTorchMode(cameraId, enabled)
            true
        } catch (e: Exception) {
            Log.e("AudioVolume", "Flashlight error: ${e.message}")
            false
        }
    }

    // ── Find My Phone Ring / Alarm ───────────────────────────────────────────

    fun startRinging() {
        try {
            stopRinging()
            // Set alarm volume to maximum so user can find phone
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)

            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            alarmRingtone = RingtoneManager.getRingtone(context, uri).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                }
                play()
            }

            // Vibrate phone
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500), 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 500, 200, 500), 0)
            }
        } catch (e: Exception) {
            Log.e("AudioVolume", "Failed to ring phone: ${e.message}")
        }
    }

    fun stopRinging() {
        try {
            alarmRingtone?.stop()
            alarmRingtone = null
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator.cancel()
        } catch (_: Exception) {}
    }
}
