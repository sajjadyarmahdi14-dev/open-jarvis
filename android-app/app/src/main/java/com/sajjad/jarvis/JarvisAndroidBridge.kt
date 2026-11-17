package com.sajjad.jarvis

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.JavascriptInterface
import android.widget.Toast
import java.util.Locale

class JarvisAndroidBridge(
    private val activity: MainActivity,
    private val onStartVoice: () -> Unit,
    private val onStopVoice: () -> Unit
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = try {
        TextToSpeech(activity.applicationContext, this)
    } catch (_: Throwable) {
        null
    }
    private var isTtsReady = false
    private var isTorchOn = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            try {
                tts?.language = Locale.UK
                tts?.setPitch(0.92f)
                tts?.setSpeechRate(1.03f)

                val voices = tts?.voices
                if (voices != null) {
                    val britishMale = voices.firstOrNull { v ->
                        val name = v.name.lowercase(Locale.ROOT)
                        (v.locale == Locale.UK || v.locale.language == "en") &&
                        (name.contains("male") || name.contains("rjs") || name.contains("gbd") || name.contains("en-gb"))
                    }
                    if (britishMale != null) {
                        tts?.voice = britishMale
                    }
                }
            } catch (_: Throwable) {}

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    activity.onTtsStarted()
                }

                override fun onDone(utteranceId: String?) {
                    activity.onTtsFinished()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    activity.onTtsFinished()
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    activity.onTtsFinished()
                }
            })
            isTtsReady = true
        }
    }

    @JavascriptInterface
    fun speakBritish(text: String) {
        if (text.isBlank()) return
        activity.runOnUiThread {
            activity.speakBritish(text)
        }
    }

    @JavascriptInterface
    fun isNativeApp(): Boolean = true

    @JavascriptInterface
    fun speak(text: String) {
        if (text.isBlank()) return
        activity.runOnUiThread {
            if (isTtsReady) {
                val hasPersian = text.any { it in '\u0600'..'\u06FF' }
                if (hasPersian) {
                    val persianLocale = Locale("fa", "IR")
                    val result = tts?.setLanguage(persianLocale)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts?.language = Locale.UK
                    }
                } else {
                    tts?.language = Locale.UK
                    tts?.setPitch(0.92f)
                    tts?.setSpeechRate(1.03f)
                }
                activity.onTtsStarted()
                val params = Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "JARVIS_TTS_" + System.currentTimeMillis())
                }
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "JARVIS_TTS")
            }
        }
    }

    @JavascriptInterface
    fun stopSpeaking() {
        activity.runOnUiThread {
            try {
                tts?.stop()
                activity.onTtsFinished()
            } catch (_: Throwable) {}
        }
    }

    @JavascriptInterface
    fun onTtsStarted() {
        activity.onTtsStarted()
    }

    @JavascriptInterface
    fun onTtsFinished() {
        activity.onTtsFinished()
    }

    @JavascriptInterface
    fun startVoiceRecognition() {
        activity.runOnUiThread {
            onStartVoice()
        }
    }

    @JavascriptInterface
    fun stopVoiceRecognition() {
        activity.runOnUiThread {
            onStopVoice()
        }
    }

    @JavascriptInterface
    fun setWakeWordEnabled(enabled: Boolean) {
        activity.runOnUiThread {
            activity.setWakeWordListening(enabled)
        }
    }

    @JavascriptInterface
    fun isWakeWordEnabled(): Boolean {
        return activity.isWakeWordListening()
    }

    @JavascriptInterface
    fun launchApp(query: String): String {
        return activity.launchApp(query)
    }

    @JavascriptInterface
    fun listInstalledApps(): String {
        return activity.listInstalledApps()
    }

    @JavascriptInterface
    fun toggleTorch(enabled: Boolean): Boolean {
        return try {
            val cameraManager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return false
            cameraManager.setTorchMode(cameraId, enabled)
            isTorchOn = enabled
            true
        } catch (_: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun toggleTorchAuto(): Boolean {
        return toggleTorch(!isTorchOn)
    }

    @JavascriptInterface
    fun getBatteryStatus(): String {
        return try {
            val batteryIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                activity.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                             status == BatteryManager.BATTERY_STATUS_FULL
            """{"percentage": $pct, "charging": $isCharging, "level": $level, "scale": $scale}"""
        } catch (e: Exception) {
            """{"error": "${e.message}"}"""
        }
    }

    @JavascriptInterface
    fun vibrate(durationMs: Long) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = activity.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                activity.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(durationMs)
            }
        } catch (_: Exception) {}
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

    @JavascriptInterface
    fun acquireWakeLock() {
        JarvisVoiceService.acquireWakeLock(activity)
    }

    @JavascriptInterface
    fun releaseWakeLock() {
        JarvisVoiceService.releaseWakeLock()
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Throwable) {}
    }
}

typealias JarvisBridge = JarvisAndroidBridge
