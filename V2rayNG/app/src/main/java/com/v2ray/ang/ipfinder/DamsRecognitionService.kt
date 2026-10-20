package com.v2ray.ang.ipfinder

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/** Service structurel pour le rôle Assistant, sans capture audio ni permission microphone. */
class DamsRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent, callback: Callback) {
        callback.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
    }

    override fun onStopListening(callback: Callback) {
        callback.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(callback: Callback) = Unit
}
