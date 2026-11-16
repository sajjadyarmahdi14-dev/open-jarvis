package com.sajjad.jarvis

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
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
            tts?.language = Locale.getDefault()
            isTtsReady = true
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
                        tts?.language = Locale.getDefault()
                    }
                } else {
                    tts?.language = Locale.US
                }
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "JARVIS_TTS")
            }
        }
    }

    @JavascriptInterface
    fun stopSpeaking() {
        activity.runOnUiThread {
            tts?.stop()
        }
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
    fun toggleTorch(enabled: Boolean): Boolean {
        return try {
            val cameraManager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return false
            cameraManager.setTorchMode(cameraId, enabled)
            isTorchOn = enabled
            true
        } catch (e: Exception) {
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
        JarvisForegroundService.acquireWakeLock(activity)
    }

    @JavascriptInterface
    fun releaseWakeLock() {
        JarvisForegroundService.releaseWakeLock()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}

typealias JarvisBridge = JarvisAndroidBridge
