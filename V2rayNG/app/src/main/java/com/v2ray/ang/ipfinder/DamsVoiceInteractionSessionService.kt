package com.v2ray.ang.ipfinder

import android.os.Bundle
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

object IpFinderVoiceContract {
    const val EXTRA_SET_AIRPLANE_MODE = "com.v2ray.ang.ipfinder.SET_AIRPLANE_MODE"
    const val EXTRA_REQUEST_ID = "com.v2ray.ang.ipfinder.REQUEST_ID"
    const val EXTRA_AIRPLANE_ENABLED = "com.v2ray.ang.ipfinder.AIRPLANE_ENABLED"
    const val PREFS = "ip_finder_voice_requests"
}

/** Crée une session sans interface et sans traitement des données d'assistance. */
class DamsVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        DamsVoiceInteractionSession(this)
}

private class DamsVoiceInteractionSession(context: android.content.Context) : VoiceInteractionSession(context) {
    private val appContext = context.applicationContext
    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (args?.getBoolean(IpFinderVoiceContract.EXTRA_SET_AIRPLANE_MODE) != true) return

        val requestId = args.getString(IpFinderVoiceContract.EXTRA_REQUEST_ID) ?: return
        val enabled = args.getBoolean(IpFinderVoiceContract.EXTRA_AIRPLANE_ENABLED)
        val preferences = appContext.getSharedPreferences(IpFinderVoiceContract.PREFS, android.content.Context.MODE_PRIVATE)
        preferences.edit().putString("latest_request_id", requestId).apply()
        try {
            startVoiceActivity(
                Intent(Settings.ACTION_VOICE_CONTROL_AIRPLANE_MODE)
                    .putExtra(Settings.EXTRA_AIRPLANE_MODE_ENABLED, enabled)
            )
            preferences.edit().putString("$requestId:dispatch", "ok").apply()
        } catch (_: Exception) {
            preferences.edit().putString("$requestId:dispatch", "error").apply()
        }
    }

    override fun onTaskStarted(intent: Intent, taskId: Int) {
        super.onTaskStarted(intent, taskId)
        val preferences = appContext.getSharedPreferences(IpFinderVoiceContract.PREFS, android.content.Context.MODE_PRIVATE)
        val requestId = preferences
            .getString("latest_request_id", null) ?: return
        preferences.edit().putBoolean("$requestId:task_started", true).apply()
    }

    override fun onTaskFinished(intent: Intent, taskId: Int) {
        super.onTaskFinished(intent, taskId)
        finish()
    }
}
