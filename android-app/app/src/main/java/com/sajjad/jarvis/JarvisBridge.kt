package com.sajjad.jarvis

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import android.widget.Toast
import java.util.Locale

class JarvisBridge(
    private val activity: MainActivity,
    private val onStartVoice: () -> Unit
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(activity, this)
    private var isTtsReady = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
            isTtsReady = true
        }
    }

    @JavascriptInterface
    fun isNativeApp(): Boolean = true

    @JavascriptInterface
    fun speak(text: String) {
        activity.runOnUiThread {
            if (isTtsReady && text.isNotBlank()) {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "JARVIS_TTS")
            }
        }
    }

    @JavascriptInterface
    fun startVoiceRecognition() {
        activity.runOnUiThread {
            onStartVoice()
        }
    }

    @JavascriptInterface
    fun showToast(message: String) {
        activity.runOnUiThread {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun startTermuxServer() {
        activity.runOnUiThread {
            activity.launchTermuxServer()
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}
