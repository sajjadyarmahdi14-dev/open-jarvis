package com.sajjad.jarvis

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import androidx.core.content.ContextCompat

/**
 * Session service for Android Digital Assistant interactions.
 */
class JarvisVoiceInteractionSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return JarvisVoiceInteractionSession(this)
    }
}

class JarvisVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        try {
            val intent = Intent(context, JarvisVoiceService::class.java).apply {
                action = "com.openjarvis.ACTION_TRIGGER_LISTEN"
            }
            ContextCompat.startForegroundService(context, intent)
        } catch (_: Throwable) {}
        hide()
    }
}
