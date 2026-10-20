package com.v2ray.ang.ipfinder

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** Crée une session sans interface et sans traitement des données d'assistance. */
class DamsVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        VoiceInteractionSession(this)
}
