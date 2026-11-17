package com.sajjad.jarvis

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat

/**
 * Android Digital Assistant Entrypoint for ACTION_ASSIST and ACTION_VOICE_COMMAND.
 * When the user triggers the device assistant gesture (long-press Home or swipe up),
 * this activity starts JarvisVoiceService with immediate listen intent and opens MainActivity.
 */
class JarvisAssistActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val serviceIntent = Intent(this, JarvisVoiceService::class.java).apply {
                action = "com.openjarvis.ACTION_TRIGGER_LISTEN"
            }
            ContextCompat.startForegroundService(this, serviceIntent)
        } catch (_: Throwable) {}

        try {
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("trigger_voice", true)
            }
            startActivity(mainIntent)
        } catch (_: Throwable) {}

        finish()
    }
}
