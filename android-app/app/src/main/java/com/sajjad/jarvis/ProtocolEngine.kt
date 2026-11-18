package com.sajjad.jarvis

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Part C: One-Word Iron Man Protocols
 *
 * Implements atomic, multi-step chained system automation protocols safely guarding
 * Settings.System.canWrite and NotificationPolicyAccess to prevent SecurityExceptions.
 */
object ProtocolEngine {

    private const val TAG = "ProtocolEngine"
    private val scope = CoroutineScope(Dispatchers.Main)
    private var activeFocusJob: Job? = null

    /**
     * Protocol 1: Sleep Protocol ("Sleep protocol", "پروتکل شب", "حالت خواب")
     */
    fun executeSleepProtocol(context: Context, isPersian: Boolean = false) {
        scope.launch {
            try {
                // 1. Lower screen brightness to ~10% (safe guard)
                setBrightnessSafe(context, 25)

                // 2. Enable Do Not Disturb (safe guard)
                setDndFilterSafe(context, NotificationManager.INTERRUPTION_FILTER_PRIORITY)

                // 3. Lower media volume to 20%
                setVolumePercentSafe(context, AudioManager.STREAM_MUSIC, 20)

                // 4. Soft spoken confirmation
                val confirmation = if (isPersian) {
                    "پروتکل خواب فعال شد. شب بخیر قربان."
                } else {
                    "Sleep protocol engaged. Goodnight, sir."
                }
                JarvisVoiceService.instance?.speakBritish(confirmation)

                // 5. Switch Jarvis voice service to sleep mode
                JarvisVoiceService.instance?.let { service ->
                    service.setVadMode(JarvisVoiceService.VadMode.SLEEP_WAKEWORD_ONLY)
                }

                Log.i(TAG, "Sleep protocol executed successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "executeSleepProtocol error: ${e.message}", e)
            }
        }
    }

    /**
     * Protocol 2: Walk Mode ("Walk mode", "Commute protocol", "پروتکل پیاده‌روی", "پروتکل بیرون")
     */
    fun executeWalkProtocol(context: Context, isPersian: Boolean = false) {
        scope.launch {
            try {
                // 1. Raise brightness to ~85% for outdoor sunlight visibility
                setBrightnessSafe(context, 215)

                // 2. Set media volume to 65%
                setVolumePercentSafe(context, AudioManager.STREAM_MUSIC, 65)

                // 3. Enable proactive alerts in settings
                context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("proactive_alerts_enabled", true)
                    .apply()

                // 4. Spoken confirmation
                val confirmation = if (isPersian) {
                    "حالت پیاده‌روی فعال شد قربان."
                } else {
                    "Walk mode active, sir."
                }
                JarvisVoiceService.instance?.speakBritish(confirmation) {
                    // 5. Start shuffled music via MediaEngine
                    MediaEngine.handleMediaPlay(context, "audio", "", null, "local", shuffle = true)
                }

                Log.i(TAG, "Walk protocol executed successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "executeWalkProtocol error: ${e.message}", e)
            }
        }
    }

    /**
     * Protocol 3: Focus Protocol ("Focus protocol", "پروتکل تمرکز", "حالت تمرکز", default 45 min)
     */
    fun executeFocusProtocol(context: Context, durationMinutes: Int = 45, isPersian: Boolean = false) {
        scope.launch {
            try {
                // 1. Enable Do Not Disturb
                setDndFilterSafe(context, NotificationManager.INTERRUPTION_FILTER_PRIORITY)

                // 2. Spoken confirmation
                val confirmation = if (isPersian) {
                    "پروتکل تمرکز به مدت $durationMinutes دقیقه فعال شد قربان."
                } else {
                    "Focus protocol active for $durationMinutes minutes, sir."
                }
                JarvisVoiceService.instance?.speakBritish(confirmation)

                // 3. Start internal Pomodoro timer
                activeFocusJob?.cancel()
                activeFocusJob = scope.launch {
                    delay(durationMinutes * 60 * 1000L)
                    // Completion chime and gentle announcement
                    setDndFilterSafe(context, NotificationManager.INTERRUPTION_FILTER_ALL)
                    val alert = if (isPersian) {
                        "جلسه تمرکز به پایان رسید قربان. خسته نباشید."
                    } else {
                        "Focus session complete, sir. Well done."
                    }
                    JarvisVoiceService.instance?.speakBritish(alert)
                }

                Log.i(TAG, "Focus protocol executed for $durationMinutes min.")
            } catch (e: Exception) {
                Log.e(TAG, "executeFocusProtocol error: ${e.message}", e)
            }
        }
    }

    /**
     * Protocol 4: Ghost Protocol ("Ghost protocol", "پروتکل مخفی", "پروتکل شبح")
     * Ultra-Fast Local Execution (<300ms) with COMPLETE SILENCE!
     */
    fun executeGhostProtocol(context: Context) {
        try {
            // 1. Instantly stop/pause all media playback
            MediaEngine.handleMediaControl(context, "pause")
            sendMediaKeyEvent(context, KeyEvent.KEYCODE_MEDIA_PAUSE)

            // 2. Mute media, ring, and notification volumes to 0 immediately
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.let {
                it.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                it.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
                it.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
            }

            // 3. Immediately hide Floating HUD Overlay
            JarvisOverlayManager.instance?.hideOverlay()

            // 4. Return to home screen and optionally lock screen
            JarvisAccessibilityService.instance?.goHome()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                JarvisAccessibilityService.instance?.performGlobalAction(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                )
            }

            // 5. Completely silent: ZERO spoken output!
            Log.i(TAG, "Ghost protocol engaged silently.")
        } catch (e: Exception) {
            Log.w(TAG, "Ghost protocol execution note: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // Safe Hardware & System Setting Helpers
    // -------------------------------------------------------------------------

    private fun setBrightnessSafe(context: Context, value: Int) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.System.canWrite(context)) {
                    Settings.System.putInt(
                        context.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS,
                        value.coerceIn(0, 255)
                    )
                }
            } else {
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    value.coerceIn(0, 255)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "setBrightnessSafe error: ${e.message}")
        }
    }

    private fun setDndFilterSafe(context: Context, filter: Int) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (nm?.isNotificationPolicyAccessGranted == true) {
                    nm.setInterruptionFilter(filter)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "setDndFilterSafe error: ${e.message}")
        }
    }

    private fun setVolumePercentSafe(context: Context, streamType: Int, percent: Int) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val max = am.getStreamMaxVolume(streamType)
            val target = Math.round(max * (percent.coerceIn(0, 100) / 100f))
            am.setStreamVolume(streamType, target, 0)
        } catch (e: Exception) {
            Log.w(TAG, "setVolumePercentSafe error: ${e.message}")
        }
    }

    private fun sendMediaKeyEvent(context: Context, keyCode: Int) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        } catch (_: Exception) {}
    }
}
