package com.v2ray.ang.ipfinder

import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession

/** Assistant minimal dédié au test IP Finder : aucun accès à l'audio ou au contenu assisté. */
class DamsVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        setDisabledShowContext(
            VoiceInteractionSession.SHOW_WITH_ASSIST or
                VoiceInteractionSession.SHOW_WITH_SCREENSHOT
        )
    }
}
