package com.sajjad.jarvis

import android.content.Intent
import android.service.voice.VoiceInteractionService
import androidx.core.content.ContextCompat

/**
 * Official Android System Digital Assistant Service.
 * Registers Jarvis with the Android OS so users can choose Jarvis as their Default Assistant
 * in Android Settings -> Apps -> Default Apps -> Assist app.
 * Holding system assistant role grants high-priority process status and protects from background killing.
 */
class JarvisVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        try {
            val serviceIntent = Intent(this, JarvisVoiceService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
        } catch (_: Throwable) {}
    }
}
