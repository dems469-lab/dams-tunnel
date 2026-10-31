package com.v2ray.ang.helper

import android.content.Context
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil

/**
 * User-selectable behaviour played on connection, disconnection and app notifications.
 *
 * Every event has its own preference so a user can keep notifications silent while still
 * getting a vibration when the tunnel drops. The default is [FeedbackMode.SILENT] so the
 * upgrade never introduces a sound the user did not ask for.
 */
enum class FeedbackMode(val value: String) {
    SILENT("silent"),
    VIBRATE("vibrate"),
    SOUND("sound");

    companion object {
        fun fromValue(value: String?): FeedbackMode =
            entries.firstOrNull { it.value == value } ?: SILENT
    }
}

object ConnectionFeedback {
    const val EVENT_CONNECT = "connect"
    const val EVENT_DISCONNECT = "disconnect"
    const val EVENT_NOTIFICATION = "notification"

    /** Settings key owning the mode of [event]. */
    fun prefKeyFor(event: String): String = when (event) {
        EVENT_CONNECT -> AppConfig.PREF_FEEDBACK_CONNECT_MODE
        EVENT_DISCONNECT -> AppConfig.PREF_FEEDBACK_DISCONNECT_MODE
        else -> AppConfig.PREF_FEEDBACK_NOTIFICATION_MODE
    }

    fun onConnected(context: Context?) = play(context, EVENT_CONNECT)

    fun onDisconnected(context: Context?) = play(context, EVENT_DISCONNECT)

    fun onNotification(context: Context?) = play(context, EVENT_NOTIFICATION)

    private fun play(context: Context?, event: String) {
        val ctx = context ?: return
        when (FeedbackMode.fromValue(MmkvManager.decodeSettingsString(prefKeyFor(event)))) {
            FeedbackMode.SILENT -> Unit
            FeedbackMode.VIBRATE -> vibrate(ctx)
            FeedbackMode.SOUND -> playSound(ctx)
        }
    }

    private fun playSound(context: Context) {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(context, uri)?.play()
        }.onFailure {
            LogUtil.w(AppConfig.TAG, "ConnectionFeedback: failed to play sound", it)
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate(context: Context) {
        runCatching {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                // Pre-O devices have no VibrationEffect; a plain duration is the only API.
                vibrator.vibrate(VIBRATION_MS)
            }
        }.onFailure {
            LogUtil.w(AppConfig.TAG, "ConnectionFeedback: failed to vibrate", it)
        }
    }

    private const val VIBRATION_MS = 150L
}
