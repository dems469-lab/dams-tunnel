package com.v2ray.ang.ipfinder

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil

/** Assistant minimal dédié au test IP Finder : aucun accès à l'audio ou au contenu assisté. */
class DamsVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        setDisabledShowContext(
            VoiceInteractionSession.SHOW_WITH_ASSIST or
                VoiceInteractionSession.SHOW_WITH_SCREENSHOT
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != IpFinderVoiceContract.ACTION_REQUEST_AIRPLANE_MODE) {
            return START_NOT_STICKY
        }

        val requestId = intent.getStringExtra(IpFinderVoiceContract.EXTRA_REQUEST_ID)
        val preferences = getSharedPreferences(IpFinderVoiceContract.PREFS, MODE_PRIVATE)
        if (requestId.isNullOrBlank()) return START_NOT_STICKY

        val arguments = Bundle().apply {
            putBoolean(IpFinderVoiceContract.EXTRA_SET_AIRPLANE_MODE, true)
            putString(IpFinderVoiceContract.EXTRA_REQUEST_ID, requestId)
            putBoolean(
                IpFinderVoiceContract.EXTRA_AIRPLANE_ENABLED,
                intent.getBooleanExtra(IpFinderVoiceContract.EXTRA_AIRPLANE_ENABLED, false)
            )
        }
        return try {
            // The selected assistant may open a system voice-action activity while Dams is
            // backgrounded. Zero flags explicitly withhold assist text and screenshots.
            showSession(arguments, 0)
            preferences.edit().putString("$requestId:dispatch", "ok").apply()
            START_NOT_STICKY
        } catch (exception: RuntimeException) {
            LogUtil.e(AppConfig.TAG, "IP Finder assistant: failed to show airplane-mode session request=$requestId", exception)
            preferences.edit().putString("$requestId:dispatch", "error").apply()
            START_NOT_STICKY
        }
    }
}
