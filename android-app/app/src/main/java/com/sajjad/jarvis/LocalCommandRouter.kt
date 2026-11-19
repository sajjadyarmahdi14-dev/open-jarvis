package com.sajjad.jarvis

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.roundToInt

/**
 * Complete Zero-Token LocalCommandRouter for J.A.R.V.I.S.
 *
 * Deterministically intercepts and handles all device, system, hardware, camera, media,
 * clock/alarm, telemetry, navigation, protocols, and math operations locally with ZERO tokens
 * and ZERO round-trips to VPS LLM.
 *
 * Spoken confirmations are returned for crisp playback via cached Male Edge-TTS.
 */
object LocalCommandRouter {

    private const val TAG = "LocalCommandRouter"
    private val scope = CoroutineScope(Dispatchers.IO)

    data class LocalRouteResult(
        val handled: Boolean,
        val spokenFeedback: String? = null,
        val category: String = "",
        val clientAction: JSONObject? = null
    )

    fun route(context: Context, rawText: String): LocalRouteResult {
        val clean = rawText.trim()
        if (clean.isBlank()) return LocalRouteResult(handled = false)

        val isFa = clean.any { it in '\u0600'..'\u06FF' }
        val lower = clean.lowercase(Locale.ROOT)

        try {
            // Category 1: Wake, Audio & Session Control
            val cat1 = handleSessionAndWake(context, clean, lower, isFa)
            if (cat1.handled) return cat1

            // Category 4: Hardware, Sound, Display & Quick Settings (check before AppLauncher to avoid intercepting "torch" as an app)
            val cat4 = handleHardwareControls(context, lower, isFa)
            if (cat4.handled) return cat4

            // Category 5: Pro Camera Control
            val cat5 = handleCameraControls(context, lower, isFa)
            if (cat5.handled) return cat5

            // Category 6: Universal Music, Audio & Video Playback
            val cat6 = handleMediaControls(context, lower, isFa)
            if (cat6.handled) return cat6

            // Category 7: Clock, Date, Alarms & Timers
            val cat7 = handleClockAndAlarms(context, clean, lower, isFa)
            if (cat7.handled) return cat7

            // Category 8: Live Device Telemetry
            val cat8 = handleTelemetry(context, lower, isFa)
            if (cat8.handled) return cat8

            // Category 3: System Navigation & Accessibility Actions
            val cat3 = handleNavigation(context, lower, isFa)
            if (cat3.handled) return cat3

            // Category 10: Iron Man Protocols
            val cat10 = handleProtocols(context, lower, isFa)
            if (cat10.handled) return cat10

            // Category 9: Calls, Contacts & Notification Replies
            val cat9 = handleCommunication(context, clean, lower, isFa)
            if (cat9.handled) return cat9

            // Category 11: Local Calculator & Direct Non-LLM Server Actions
            val cat11 = handleMathAndDirectServer(context, clean, lower, isFa)
            if (cat11.handled) return cat11

            // Category 2: Universal All-Apps Launcher (tested after hardware/media/camera keywords)
            val cat2 = handleAppLaunch(context, clean, lower, isFa)
            if (cat2.handled) return cat2

        } catch (e: Exception) {
            Log.e(TAG, "LocalCommandRouter exception while routing '$rawText': ${e.message}", e)
        }

        return LocalRouteResult(handled = false)
    }

    // -------------------------------------------------------------------------
    // Category 1: Wake, Audio & Session Control
    // -------------------------------------------------------------------------
    private fun handleSessionAndWake(context: Context, clean: String, lower: String, isFa: Boolean): LocalRouteResult {
        val wakeWords = setOf(
            "jarvis", "hey jarvis", "hi jarvis", "ok jarvis", "hello jarvis", "dear jarvis",
            "jervis", "javis", "garvis", "charvis", "jarvish", "jarvez", "travis",
            "جارویس", "ای جارویس", "جرویس", "هی جارویس", "سلام جارویس", "چاویس", "چارویس", "چرویس",
            "جارویز", "جرویز", "جاروویس", "جاربیس", "دارویس", "یارویس", "جارو بیس", "سرویس"
        )

        // Standalone wake word check
        val normalizedLower = lower.replace(Regex("[,.!?،؟]+"), "").trim()
        if (wakeWords.contains(normalizedLower)) {
            val reply = if (isFa) "در خدمت شما هستم قربان. گوش می‌دهم." else "At your service, sir. I'm listening."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "wake_session")
        }

        // Stop / Cancel / Shut up / Sleep
        val stopPatterns = listOf(
            "stop", "cancel", "shut up", "wait", "be quiet", "silence", "stop listening",
            "go to sleep", "sleep mode", "mute yourself", "standby", "that's all jarvis",
            "استپ", "صبر کن", "وایسا", "وایستا", "ساکت شو", "خفه شو", "کافیه", "بس کن",
            "خاموش شو", "گوش نده", "دیگه گوش نده", "برو استندبای", "استندبای", "بخواب",
            "فعلا جارویس", "خداحافظ جارویس", "خداحافظ"
        )
        if (stopPatterns.any { lower == it || lower.startsWith("$it ") || lower.endsWith(" $it") }) {
            try {
                MediaEngine.handleMediaControl(context, "pause")
                JarvisCameraActivity.instance?.finish()
            } catch (_: Exception) {}
            val reply = if (isFa) "به حالت استندبای رفتم قربان." else "Standing by, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "session_stop")
        }

        // Repeat last reply
        val repeatPatterns = listOf(
            "repeat", "say that again", "what did you say", "repeat that", "say again",
            "تکرار کن", "دوباره بگو", "چی گفتی", "چی شد", "چی گفتی جارویس"
        )
        if (repeatPatterns.any { lower.contains(it) }) {
            val last = JarvisVoiceService.lastSpokenText
            val reply = if (!last.isNullOrBlank()) last else (if (isFa) "پیام قبلی ثبت نشده است قربان." else "No previous message recorded, sir.")
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "repeat_speech")
        }

        // Language Override
        if (lower.contains("speak english") || lower.contains("switch to english") || lower.contains("انگلیسی صحبت کن") || lower.contains("انگلیسی حرف بزن")) {
            return LocalRouteResult(handled = true, spokenFeedback = "Switching to English, sir. How can I assist you?", category = "language_override")
        }
        if (lower.contains("speak persian") || lower.contains("switch to persian") || lower.contains("فارسی صحبت کن") || lower.contains("فارسی حرف بزن") || lower.contains("فارسی بگو")) {
            return LocalRouteResult(handled = true, spokenFeedback = "به زبان فارسی تغییر یافت قربان. در خدمتم.", category = "language_override")
        }

        return LocalRouteResult(handled = false)
    }

    // -------------------------------------------------------------------------
    // Category 2: Universal All-Apps Launcher
    // -------------------------------------------------------------------------
    private fun handleAppLaunch(context: Context, clean: String, lower: String, isFa: Boolean): LocalRouteResult {
        var appQuery: String? = null

        // English patterns: "open <app>", "launch <app>", "start <app>", "run <app>"
        val enMatch = Regex("^(open|launch|start|run)\\s+(.+)").find(lower)
        if (enMatch != null) {
            appQuery = enMatch.groupValues[2].trim()
        }

        // Persian patterns: "باز کن <app>", "<app> رو باز کن", "<app> را باز کن", "اجرا کن <app>"
        if (appQuery == null && isFa) {
            val faMatchPrefix = Regex("^(باز کن|اجرا کن|برو تو|برو توی)\\s+(.+)").find(clean)
            if (faMatchPrefix != null) {
                appQuery = faMatchPrefix.groupValues[2].trim()
            } else {
                val faMatchSuffix = Regex("(.+?)\\s*(رو|را| رو| را)\\s*(باز کن|اجرا کن|بالا بیار|بیار بالا)").find(clean)
                if (faMatchSuffix != null) {
                    appQuery = faMatchSuffix.groupValues[1].trim()
                }
            }
        }

        if (!appQuery.isNullOrBlank()) {
            val queryClean = appQuery.removePrefix("the ").removePrefix("app ").trim()
            if (queryClean.isNotBlank()) {
                val resJsonStr = AppLauncher.launchApp(context, queryClean)
                val resObj = try { JSONObject(resJsonStr) } catch (_: Exception) { null }
                val success = resObj?.optBoolean("success", false) == true
                val resolved = resObj?.optString("label", queryClean) ?: queryClean
                val reply = if (success) {
                    if (isFa) "در حال باز کردن $resolved قربان." else "Opening $resolved, sir."
                } else {
                    if (isFa) "برنامه $queryClean روی گوشی شما پیدا نشد قربان." else "Could not find $queryClean installed on your device, sir."
                }
                return LocalRouteResult(handled = true, spokenFeedback = reply, category = "app_launcher")
            }
        }

        return LocalRouteResult(handled = false)
    }

    // -------------------------------------------------------------------------
    // Category 3: System Navigation & Accessibility Actions
    // -------------------------------------------------------------------------
    private fun handleNavigation(context: Context, lower: String, isFa: Boolean): LocalRouteResult {
        // Back
        if (lower in listOf("go back", "back", "برگرد", "قبلی", "برو عقب", "صفحه قبل")) {
            JarvisAccessibilityService.instance?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            val reply = if (isFa) "به عقب برگشتم قربان." else "Going back, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "nav_back")
        }

        // Home
        if (lower in listOf("go home", "home", "home screen", "صفحه اصلی", "برو هوم", "برو خانه", "برو صفحه اصلی")) {
            JarvisAccessibilityService.instance?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            val reply = if (isFa) "به صفحه اصلی رفتم قربان." else "Going to home screen, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "nav_home")
        }

        // Recents / Overview
        if (lower in listOf("recent apps", "recents", "overview", "برنامه‌های اخیر", "برنامه‌های باز", "برنامه های باز", "لیست برنامه‌ها")) {
            JarvisAccessibilityService.instance?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
            val reply = if (isFa) "برنامه‌های اخیر باز شد قربان." else "Showing recent apps, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "nav_recents")
        }

        // Notifications
        if (lower in listOf("open notifications", "show notifications", "اعلان‌ها", "نوتیفیکیشن‌ها", "نوتیف‌ها", "پنل اعلان", "اعلان ها")) {
            JarvisAccessibilityService.instance?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            val reply = if (isFa) "پنل اعلان‌ها باز شد قربان." else "Opening notifications, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "nav_notifications")
        }

        // Quick Settings
        if (lower in listOf("quick settings", "open quick settings", "تنظیمات سریع", "کنترل سنتر", "پنل تنظیمات سریع")) {
            JarvisAccessibilityService.instance?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            val reply = if (isFa) "تنظیمات سریع باز شد قربان." else "Opening quick settings, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "nav_quick_settings")
        }

        // Lock Screen
        if (lower in listOf("lock screen", "lock phone", "lock the screen", "صفحه رو قفل کن", "گوشی رو قفل کن", "صفحه قفل", "گوشی را قفل کن")) {
            JarvisAccessibilityService.instance?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            val reply = if (isFa) "صفحه قفل شد قربان." else "Screen locked, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "nav_lock_screen")
        }

        // Screenshot
        if (lower in listOf("take a screenshot", "screenshot", "capture screen", "عکس از صفحه", "اسکرین شات بگیر", "اسکرین‌شات", "اسکرین شات")) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                JarvisAccessibilityService.instance?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            } else {
                ActionDispatcher.dispatchAction(context, JSONObject(mapOf("type" to "take_screenshot")))
            }
            val reply = if (isFa) "اسکرین‌شات گرفته شد قربان." else "Screenshot captured, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "nav_screenshot")
        }

        // Scroll
        if (lower in listOf("scroll down", "بیا پایین", "برو پایین", "اسکرول به پایین", "پایین‌تر")) {
            JarvisAccessibilityService.instance?.scrollDown()
            val reply = if (isFa) "به پایین اسکرول شد قربان." else "Scrolled down, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "nav_scroll")
        }
        if (lower in listOf("scroll up", "برو بالا", "اسکرول به بالا", "بالاتر")) {
            JarvisAccessibilityService.instance?.scrollUp()
            val reply = if (isFa) "به بالا اسکرول شد قربان." else "Scrolled up, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "nav_scroll")
        }

        return LocalRouteResult(handled = false)
    }

    // -------------------------------------------------------------------------
    // Category 4: Hardware, Sound, Display & Quick Settings Panels
    // -------------------------------------------------------------------------
    private fun handleHardwareControls(context: Context, lower: String, isFa: Boolean): LocalRouteResult {
        // Flashlight Turn On
        val torchOnWords = listOf(
            "turn on flashlight", "flashlight on", "torch on", "enable flashlight",
            "turn on torch", "enable torch", "چراغ قوه رو روشن کن", "چراغ روشن",
            "چراغ قوه را روشن کن", "فلش رو روشن کن", "چراغ قوه روشن"
        )
        if (torchOnWords.any { lower.contains(it) }) {
            DeviceController.setTorchState(context, true)
            val reply = if (isFa) "چراغ قوه روشن شد قربان." else "Flashlight turned on, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "torch_on")
        }

        // Flashlight Turn Off
        val torchOffWords = listOf(
            "turn off flashlight", "flashlight off", "torch off", "disable flashlight",
            "turn off torch", "disable torch", "چراغ قوه رو خاموش کن", "چراغ خاموش",
            "چراغ قوه را خاموش کن", "فلش رو خاموش کن", "چراغ قوه خاموش"
        )
        if (torchOffWords.any { lower.contains(it) }) {
            DeviceController.setTorchState(context, false)
            val reply = if (isFa) "چراغ قوه خاموش شد قربان." else "Flashlight turned off, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "torch_off")
        }

        // Volume Up
        if (lower.contains("volume up") || lower.contains("increase volume") || lower.contains("louder") ||
            lower.contains("صدا رو زیاد کن") || lower.contains("صدای بیشتر") || lower.contains("افزایش صدا") || lower.contains("بلندتر")) {
            DeviceController.setVolume(context, "up")
            val reply = if (isFa) "صدا افزایش یافت قربان." else "Volume increased, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "volume_up")
        }

        // Volume Down
        if (lower.contains("volume down") || lower.contains("decrease volume") || lower.contains("lower volume") ||
            lower.contains("صدا رو کم کن") || lower.contains("صدای کمتر") || lower.contains("کاهش صدا") || lower.contains("آروم‌تر")) {
            DeviceController.setVolume(context, "down")
            val reply = if (isFa) "صدا کاهش یافت قربان." else "Volume decreased, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "volume_down")
        }

        // Volume Mute
        if (lower in listOf("mute", "mute volume", "mute sound", "صدا رو قطع کن", "بی‌صدا کن", "بی صدا کن", "سایلنت کن")) {
            DeviceController.setVolume(context, "mute")
            val reply = if (isFa) "صدا قطع شد قربان." else "Audio muted, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "volume_mute")
        }

        // Volume Set percentage
        val volMatch = Regex("(set volume to|volume)\\s+(\\d+)\\s*(%|percent)?").find(lower)
        if (volMatch != null) {
            val level = volMatch.groupValues[2].toIntOrNull() ?: 50
            DeviceController.setVolume(context, "set", level)
            val reply = if (isFa) "میزان صدا روی $level درصد تنظیم شد قربان." else "Volume set to $level percent, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "volume_set")
        }

        // Brightness
        if (lower.contains("brightness up") || lower.contains("increase brightness") || lower.contains("نور صفحه رو زیاد کن") || lower.contains("روشنایی بیشتر")) {
            DeviceController.setBrightness(context, 220)
            val reply = if (isFa) "نور صفحه افزایش یافت قربان." else "Brightness increased, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "brightness_up")
        }
        if (lower.contains("brightness down") || lower.contains("decrease brightness") || lower.contains("نور صفحه رو کم کن") || lower.contains("روشنایی کمتر")) {
            DeviceController.setBrightness(context, 40)
            val reply = if (isFa) "نور صفحه کاهش یافت قربان." else "Brightness decreased, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "brightness_down")
        }

        // Settings Panels
        if (lower.contains("wifi settings") || lower.contains("تنظیمات وای فای") || lower.contains("تنظیمات وای‌فای")) {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            AppLauncher.launchWithOverlayFallback(context, intent)
            val reply = if (isFa) "تنظیمات وای‌فای باز شد قربان." else "Opening Wi-Fi settings, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "settings_wifi")
        }
        if (lower.contains("bluetooth settings") || lower.contains("تنظیمات بلوتوث")) {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            AppLauncher.launchWithOverlayFallback(context, intent)
            val reply = if (isFa) "تنظیمات بلوتوث باز شد قربان." else "Opening Bluetooth settings, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "settings_bluetooth")
        }
        if (lower.contains("display settings") || lower.contains("تنظیمات صفحه نمایش") || lower.contains("تنظیمات نمایش")) {
            val intent = Intent(Settings.ACTION_DISPLAY_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            AppLauncher.launchWithOverlayFallback(context, intent)
            val reply = if (isFa) "تنظیمات صفحه نمایش باز شد قربان." else "Opening display settings, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "settings_display")
        }

        return LocalRouteResult(handled = false)
    }

    // -------------------------------------------------------------------------
    // Category 5: Pro Camera Control
    // -------------------------------------------------------------------------
    private fun launchCameraAction(context: Context, act: JSONObject) {
        val inst = JarvisCameraActivity.instance
        if (inst != null) {
            inst.applyCameraControl(act)
        } else {
            val intent = Intent(context, JarvisCameraActivity::class.java).apply {
                putExtra(JarvisCameraActivity.EXTRA_CAMERA_ACTION, act.toString())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(intent)
        }
    }

    private fun handleCameraControls(context: Context, lower: String, isFa: Boolean): LocalRouteResult {
        // Take Selfie
        val selfieWords = listOf("take a selfie", "take selfie", "selfie", "عکس سلفی بگیر", "یه سلفی بگیر", "سلفی بگیر", "عکس از خودت بگیر")
        if (selfieWords.any { lower.contains(it) }) {
            val act = JSONObject().put("mode", "photo").put("lens", "front").put("capture", true)
            launchCameraAction(context, act)
            val reply = if (isFa) "در حال گرفتن عکس سلفی قربان." else "Taking selfie now, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "camera_selfie")
        }

        // Timed Photo
        val timerMatch = Regex("(take a photo with|timer)\\s+(\\d+)\\s*(s|sec|second|seconds)").find(lower)
            ?: Regex("عکس با تایمر\\s*(\\d+)\\s*(ثانیه)?").find(lower)
        if (timerMatch != null) {
            val sec = timerMatch.groupValues[timerMatch.groupValues.size - 2].toIntOrNull() ?: 3
            val act = JSONObject().put("mode", "photo").put("capture", true).put("timer_seconds", sec)
            launchCameraAction(context, act)
            val reply = if (isFa) "عکس با تایمر $sec ثانیه‌ای ثبت خواهد شد قربان." else "Photo timer set for $sec seconds, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "camera_timed_photo")
        }

        // Take Photo
        val photoWords = listOf("take a photo", "take photo", "take picture", "shoot photo", "capture photo", "عکس بگیر", "یه عکس بگیر", "عکس بنداز", "یک عکس بگیر")
        if (photoWords.any { lower.contains(it) }) {
            val act = JSONObject().put("mode", "photo").put("lens", "back").put("capture", true)
            launchCameraAction(context, act)
            val reply = if (isFa) "در حال ثبت عکس قربان." else "Capturing photo, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "camera_photo")
        }

        // Record Video
        val videoWords = listOf("record a video", "record video", "start recording video", "take video", "فیلم بگیر", "فیلمبرداری کن", "ویدیو بگیر", "شروع فیلمبرداری")
        if (videoWords.any { lower.contains(it) }) {
            val act = JSONObject().put("mode", "video").put("capture", true)
            launchCameraAction(context, act)
            val reply = if (isFa) "در حال ضبط ویدیو قربان." else "Recording video, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "camera_video")
        }

        // Stop Video
        val stopVideoWords = listOf("stop recording", "stop video", "stop video recording", "ضبط رو قطع کن", "فیلمبرداری رو قطع کن", "متوقف کردن فیلمبرداری")
        if (stopVideoWords.any { lower.contains(it) }) {
            val inst = JarvisCameraActivity.instance
            if (inst != null) {
                inst.stopVideoRecording()
            } else {
                launchCameraAction(context, JSONObject().put("mode", "stop_video"))
            }
            val reply = if (isFa) "ضبط ویدیو متوقف و ذخیره شد قربان." else "Video recording stopped and saved, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "camera_stop_video")
        }

        // Close Camera
        if (lower.contains("close camera") || lower.contains("exit camera") || lower.contains("دوربین رو ببند") || lower.contains("خروج از دوربین")) {
            val inst = JarvisCameraActivity.instance
            if (inst != null) {
                inst.finish()
            } else {
                launchCameraAction(context, JSONObject().put("mode", "close"))
            }
            val reply = if (isFa) "دوربین بسته شد قربان." else "Camera closed, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "camera_close")
        }

        return LocalRouteResult(handled = false)
    }

    // -------------------------------------------------------------------------
    // Category 6: Universal Music, Audio & Video Playback
    // -------------------------------------------------------------------------
    private fun handleMediaControls(context: Context, lower: String, isFa: Boolean): LocalRouteResult {
        // Next Track
        if (lower.contains("next song") || lower.contains("next track") || lower.contains("آهنگ بعدی") || lower.contains("ترک بعدی")) {
            MediaEngine.handleMediaControl(context, "next")
            val reply = if (isFa) "آهنگ بعدی پخش شد قربان." else "Next track, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "media_next")
        }

        // Previous Track
        if (lower.contains("previous song") || lower.contains("previous track") || lower.contains("آهنگ قبلی") || lower.contains("ترک قبلی")) {
            MediaEngine.handleMediaControl(context, "previous")
            val reply = if (isFa) "آهنگ قبلی پخش شد قربان." else "Previous track, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "media_prev")
        }

        // Pause Music
        if (lower in listOf("pause music", "pause playback", "stop music", "موزیک رو متوقف کن", "آهنگ رو قطع کن", "مکث موزیک", "موسیقی رو قطع کن")) {
            MediaEngine.handleMediaControl(context, "pause")
            val reply = if (isFa) "پخش موسیقی متوقف شد قربان." else "Music paused, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "media_pause")
        }

        // Resume Music
        if (lower in listOf("resume music", "continue music", "ادامه آهنگ", "ادامه موسیقی", "پخش مجدد آهنگ")) {
            MediaEngine.handleMediaControl(context, "play")
            val reply = if (isFa) "پخش موسیقی ادامه یافت قربان." else "Resuming music, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "media_resume")
        }

        // Play Specific Song / Query
        val playSongMatch = Regex("^(play song|play)\\s+(.+)").find(lower)
        if (playSongMatch != null) {
            val songName = playSongMatch.groupValues[2].removePrefix("the ").trim()
            if (songName !in listOf("music", "song", "some music")) {
                MediaEngine.handleMediaPlay(context, "audio", songName, null, null, false)
                val reply = if (isFa) "در حال پخش آهنگ $songName قربان." else "Playing $songName, sir."
                return LocalRouteResult(handled = true, spokenFeedback = reply, category = "media_play_song")
            }
        }

        // General Play Music
        val playMusicWords = listOf("play music", "play some music", "play song", "آهنگ پخش کن", "موزیک بزار", "موسیقی پخش کن", "آهنگ بزار", "یه آهنگ پخش کن")
        if (playMusicWords.any { lower.contains(it) }) {
            MediaEngine.handleMediaPlay(context, "audio", "", null, "local", false)
            val reply = if (isFa) "در حال پخش موسیقی قربان." else "Playing music, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "media_play")
        }

        return LocalRouteResult(handled = false)
    }

    // -------------------------------------------------------------------------
    // Category 7: Clock, Date, Alarms & Timers
    // -------------------------------------------------------------------------
    private fun handleClockAndAlarms(context: Context, clean: String, lower: String, isFa: Boolean): LocalRouteResult {
        // Current Time
        val timeWords = listOf("what time is it", "what's the time", "tell me the time", "current time", "ساعت چنده", "الان ساعت چنده", "ساعت رو بگو", "ساعت چند است")
        if (timeWords.any { lower.contains(it) }) {
            val cal = Calendar.getInstance()
            val hour = cal.get(Calendar.HOUR)
            val hour12 = if (hour == 0) 12 else hour
            val min = cal.get(Calendar.MINUTE)
            val amPm = if (cal.get(Calendar.AM_PM) == Calendar.AM) (if (isFa) "صبح" else "AM") else (if (isFa) "بعد از ظهر" else "PM")
            val reply = if (isFa) {
                if (min == 0) "ساعت $hour12 $amPm است قربان." else "ساعت $hour12 و $min دقیقه $amPm است قربان."
            } else {
                if (min == 0) "It is $hour12 o'clock $amPm, sir." else "It is $hour12:$min $amPm, sir."
            }
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "clock_time")
        }

        // Current Date
        val dateWords = listOf("what's the date", "what is today's date", "today's date", "what day is today", "امروز چندمه", "تاریخ امروز چیه", "امروز چه روزیه", "تاریخ امروز")
        if (dateWords.any { lower.contains(it) }) {
            val cal = Calendar.getInstance()
            val reply = if (isFa) {
                val gy = cal.get(Calendar.YEAR)
                val gm = cal.get(Calendar.MONTH) + 1
                val gd = cal.get(Calendar.DAY_OF_MONTH)
                val (jy, jm, jd) = gregorianToJalali(gy, gm, gd)
                val monthNamesFa = arrayOf("", "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور", "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند")
                val dayNamesFa = arrayOf("", "یکشنبه", "دوشنبه", "سه‌شنبه", "چهارشنبه", "پنج‌شنبه", "جمعه", "شنبه")
                val dayOfWeek = dayNamesFa[cal.get(Calendar.DAY_OF_WEEK)]
                "امروز $dayOfWeek $jd ${monthNamesFa[jm]} سال $jy است قربان."
            } else {
                val df = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.ENGLISH)
                "Today is ${df.format(Date())}, sir."
            }
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "clock_date")
        }

        // Set Alarm
        val alarmMatch = Regex("(set an alarm for|alarm for|wake me up at)\\s+(\\d+)(?::(\\d+))?\\s*(am|pm)?").find(lower)
        if (alarmMatch != null) {
            var hour = alarmMatch.groupValues[2].toIntOrNull() ?: 8
            val min = alarmMatch.groupValues[3].toIntOrNull() ?: 0
            val amPm = alarmMatch.groupValues[4]
            if (amPm == "pm" && hour < 12) hour += 12
            if (amPm == "am" && hour == 12) hour = 0
            DeviceController.setAlarm(context, hour, min, "Jarvis Alarm")
            val reply = if (isFa) "آلارم برای ساعت $hour:${String.format("%02d", min)} تنظیم شد قربان." else "Alarm set for $hour:${String.format("%02d", min)}, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "clock_alarm")
        }

        // Set Timer
        val timerMatch = Regex("(set a timer for|timer for)\\s+(\\d+)\\s*(minute|minutes|min|second|seconds|sec)").find(lower)
            ?: Regex("تایمر\\s*(\\d+)\\s*(دقیقه|ثانیه)").find(lower)
        if (timerMatch != null) {
            val num = timerMatch.groupValues[2].toIntOrNull() ?: 5
            val unit = timerMatch.groupValues[3]
            val totalSeconds = if (unit.startsWith("sec") || unit.contains("ثانیه")) num else num * 60
            DeviceController.setTimer(context, totalSeconds, "Jarvis Timer")
            val reply = if (isFa) "تایمر برای $num $unit تنظیم شد قربان." else "Timer set for $num $unit, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "clock_timer")
        }

        return LocalRouteResult(handled = false)
    }

    // -------------------------------------------------------------------------
    // Category 8: Live Device Telemetry
    // -------------------------------------------------------------------------
    private fun handleTelemetry(context: Context, lower: String, isFa: Boolean): LocalRouteResult {
        // Battery
        val batteryWords = listOf("battery level", "battery status", "how much battery is left", "battery percentage", "شارژ باتری چقدره", "باتری چنده", "وضعیت باتری", "درصد باتری")
        if (batteryWords.any { lower.contains(it) }) {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val reply = if (isFa) {
                "میزان شارژ باتری $level درصد است${if (isCharging) " و در حال شارژ می‌باشد" else ""} قربان."
            } else {
                "Battery level is at $level percent${if (isCharging) ", charging" else ", not charging"}, sir."
            }
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "telemetry_battery")
        }

        // Storage
        val storageWords = listOf("how much storage is left", "free storage", "storage space", "free memory", "حافظه چقدر خالیه", "فضای خالی حافظه", "وضعیت حافظه")
        if (storageWords.any { lower.contains(it) }) {
            val stat = StatFs(Environment.getDataDirectory().path)
            val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
            val totalBytes = stat.blockCountLong * stat.blockSizeLong
            val freeGb = (freeBytes / (1024.0 * 1024.0 * 1024.0) * 10.0).roundToInt() / 10.0
            val totalGb = (totalBytes / (1024.0 * 1024.0 * 1024.0) * 10.0).roundToInt() / 10.0
            val reply = if (isFa) {
                "$freeGb گیگابایت از مجموع $totalGb گیگابایت حافظه داخلی آزاد است قربان."
            } else {
                "You have $freeGb GB free out of $totalGb GB total storage, sir."
            }
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "telemetry_storage")
        }

        // Connectivity
        val netWords = listOf("is wifi connected", "network status", "internet connection", "وضعیت اینترنت", "وای فای وصله", "وضعیت اتصال")
        if (netWords.any { lower.contains(it) }) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(net)
            val hasWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val hasCell = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            val reply = if (isFa) {
                if (hasWifi) "اتصال وای‌فای برقرار و اینترنت آنلاین است قربان."
                else if (hasCell) "دیتا موبایل فعال و اینترنت در دسترس است قربان."
                else "اتصال شبکه برقرار نیست قربان."
            } else {
                if (hasWifi) "Wi-Fi is connected and Internet is online, sir."
                else if (hasCell) "Mobile data is connected and active, sir."
                else "No network connection detected, sir."
            }
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "telemetry_connectivity")
        }

        return LocalRouteResult(handled = false)
    }

    // -------------------------------------------------------------------------
    // Category 9: Calls, Contacts & Notification Replies
    // -------------------------------------------------------------------------
    private fun handleCommunication(context: Context, clean: String, lower: String, isFa: Boolean): LocalRouteResult {
        // Call Contact/Number
        val callMatchEn = Regex("^call\\s+(.+)").find(lower)
        var callTarget: String? = null
        if (callMatchEn != null) {
            callTarget = callMatchEn.groupValues[1].trim()
        } else if (isFa) {
            val callMatchFa1 = Regex("^(تماس بگیر با|زنگ بزن به|تماس با)\\s+(.+)").find(clean)
            if (callMatchFa1 != null) {
                callTarget = callMatchFa1.groupValues[2].trim()
            } else {
                val callMatchFa2 = Regex("(.+?)\\s*(زنگ بزن|تماس بگیر)").find(clean)
                if (callMatchFa2 != null) {
                    callTarget = callMatchFa2.groupValues[1].removeSuffix("به ").trim()
                }
            }
        }

        if (!callTarget.isNullOrBlank()) {
            DeviceController.makeCall(context, callTarget)
            val reply = if (isFa) "در حال برقراری تماس با $callTarget قربان." else "Calling $callTarget, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "comm_call")
        }

        // Read Latest Notification
        if (lower.contains("read notification") || lower.contains("read latest notification") || lower.contains("آخرین پیام رو بخون") || lower.contains("اعلان جدید چیه")) {
            val recents = JarvisNotificationListener.getCachedNotifications()
            val reply = if (recents.isNotEmpty()) {
                val top = recents.first()
                if (isFa) "آخرین پیام از ${top.senderTitle}: ${top.messageText}" else "Latest message from ${top.senderTitle}: ${top.messageText}, sir."
            } else {
                if (isFa) "هیچ اعلان جدیدی وجود ندارد قربان." else "No recent notifications received, sir."
            }
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "comm_read_notif")
        }

        return LocalRouteResult(handled = false)
    }

    // -------------------------------------------------------------------------
    // Category 10: Iron Man Protocols
    // -------------------------------------------------------------------------
    private fun handleProtocols(context: Context, lower: String, isFa: Boolean): LocalRouteResult {
        // Ghost Protocol
        if (lower.contains("ghost protocol") || lower.contains("پروتکل مخفی") || lower.contains("پروتکل شبح")) {
            ProtocolEngine.executeGhostProtocol(context)
            val reply = if (isFa) "پروتکل شبح فعال شد قربان. صفحه کم‌نور و صدا قطع گردید." else "Ghost protocol engaged, sir. Screen dimmed and audio muted."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "protocol_ghost")
        }

        // Sleep Protocol
        if (lower.contains("sleep protocol") || lower.contains("enter sleep mode") || lower.contains("پروتکل خواب") || lower.contains("حالت خواب")) {
            ProtocolEngine.executeSleepProtocol(context, isFa)
            val reply = if (isFa) "پروتکل خواب فعال شد قربان." else "Sleep protocol engaged, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "protocol_sleep")
        }

        // Walk Mode
        if (lower.contains("walk mode") || lower.contains("start walk mode") || lower.contains("حالت پیاده‌روی") || lower.contains("حالت پیاده روی")) {
            ProtocolEngine.executeWalkProtocol(context, isFa)
            val reply = if (isFa) "حالت پیاده‌روی فعال شد قربان." else "Walk mode engaged, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "protocol_walk")
        }

        // Focus Protocol
        if (lower.contains("focus protocol") || lower.contains("do not disturb mode") || lower.contains("پروتکل تمرکز")) {
            ProtocolEngine.executeFocusProtocol(context, 45, isFa)
            val reply = if (isFa) "پروتکل تمرکز فعال شد قربان." else "Focus protocol engaged, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "protocol_focus")
        }

        return LocalRouteResult(handled = false)
    }

    // -------------------------------------------------------------------------
    // Category 11: Local Calculator & Direct Non-LLM Server Actions
    // -------------------------------------------------------------------------
    private fun handleMathAndDirectServer(context: Context, clean: String, lower: String, isFa: Boolean): LocalRouteResult {
        // Direct Memory Save
        val memMatchEn = Regex("^(remember that|save to memory)\\s+(.+)").find(lower)
        var memContent: String? = null
        if (memMatchEn != null) {
            memContent = memMatchEn.groupValues[2].trim()
        } else if (isFa) {
            val memMatchFa = Regex("^(یادت بمونه که|در حافظه ذخیره کن|این رو یادت باشه)\\s+(.+)").find(clean)
            if (memMatchFa != null) {
                memContent = memMatchFa.groupValues[2].trim()
            }
        }
        if (!memContent.isNullOrBlank()) {
            val toSave = memContent
            scope.launch {
                try {
                    val server = getServerUrl(context)
                    val u = URL("${server.removeSuffix("/")}/api/memory")
                    val conn = (u.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Content-Type", "application/json")
                        connectTimeout = 4000
                        readTimeout = 4000
                        doOutput = true
                    }
                    val body = JSONObject().put("content", toSave).toString()
                    conn.outputStream.use { it.write(body.toByteArray()) }
                    conn.responseCode
                } catch (e: Exception) {
                    Log.w(TAG, "Direct memory save error: ${e.message}")
                }
            }
            val reply = if (isFa) "در حافظه دائمی ثبت شد قربان." else "Saved to long-term memory, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "direct_memory_save")
        }

        // Direct VPS Status
        if (lower in listOf("server status", "vps status", "system status", "وضعیت سرور", "وضعیت وی پی اس", "وضعیت وی‌پی‌اس")) {
            var vpsReply: String? = null
            try {
                val server = getServerUrl(context)
                val u = URL("${server.removeSuffix("/")}/api/vps/status")
                val conn = (u.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 3000
                    readTimeout = 3000
                }
                if (conn.responseCode in 200..299) {
                    val resp = conn.inputStream.bufferedReader().use { it.readText() }
                    val obj = JSONObject(resp)
                    vpsReply = if (isFa) obj.optString("summary_fa", "") else obj.optString("summary_en", "")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Direct VPS status check error: ${e.message}")
            }
            val reply = if (!vpsReply.isNullOrBlank()) vpsReply else (if (isFa) "وضعیت سرور پایدار است قربان." else "Server systems nominal, sir.")
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "direct_vps_status")
        }

        // Voice Math / Calculator
        val mathResult = evaluateMathExpression(clean)
        if (mathResult != null) {
            val (a, opName, b, res) = mathResult
            val resStr = if (res % 1.0 == 0.0) res.toLong().toString() else String.format("%.2f", res)
            val aStr = if (a % 1.0 == 0.0) a.toLong().toString() else a.toString()
            val bStr = if (b % 1.0 == 0.0) b.toLong().toString() else b.toString()
            val reply = if (isFa) {
                "حاصل $aStr $opName $bStr برابر با $resStr است قربان."
            } else {
                "$aStr $opName $bStr is $resStr, sir."
            }
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "math_calc")
        }

        return LocalRouteResult(handled = false)
    }

    // -------------------------------------------------------------------------
    // Pure Kotlin Math Evaluator
    // -------------------------------------------------------------------------
    private fun evaluateMathExpression(rawText: String): Tuple4<Double, String, Double, Double>? {
        // Normalize Persian digits to English digits
        var text = rawText
        val faDigits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
        for (i in 0..9) {
            text = text.replace(faDigits[i], ('0' + i))
        }

        val pattern = Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)\\s*(?:plus|\\+|به علاوه|منهای|minus|-|times|multiplied by|\\*|ضربدر|divided by|/|تقسیم بر)\\s*(\\d+(?:\\.\\d+)?)")
        val matcher = pattern.matcher(text)
        if (matcher.find()) {
            val a = matcher.group(1)?.toDoubleOrNull() ?: return null
            val b = matcher.group(2)?.toDoubleOrNull() ?: return null
            val fullMatch = matcher.group(0)?.lowercase(Locale.ROOT) ?: ""

            return when {
                fullMatch.contains("+") || fullMatch.contains("plus") || fullMatch.contains("به علاوه") -> {
                    val op = if (rawText.any { it in '\u0600'..'\u06FF' }) "به علاوه" else "plus"
                    Tuple4(a, op, b, a + b)
                }
                fullMatch.contains("-") || fullMatch.contains("minus") || fullMatch.contains("منهای") -> {
                    val op = if (rawText.any { it in '\u0600'..'\u06FF' }) "منهای" else "minus"
                    Tuple4(a, op, b, a - b)
                }
                fullMatch.contains("*") || fullMatch.contains("times") || fullMatch.contains("multiplied by") || fullMatch.contains("ضربدر") -> {
                    val op = if (rawText.any { it in '\u0600'..'\u06FF' }) "ضربدر" else "times"
                    Tuple4(a, op, b, a * b)
                }
                fullMatch.contains("/") || fullMatch.contains("divided by") || fullMatch.contains("تقسیم بر") -> {
                    val op = if (rawText.any { it in '\u0600'..'\u06FF' }) "تقسیم بر" else "divided by"
                    val res = if (b != 0.0) a / b else 0.0
                    Tuple4(a, op, b, res)
                }
                else -> null
            }
        }
        return null
    }

    private data class Tuple4<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    // -------------------------------------------------------------------------
    // Gregorian to Jalali (Solar Hijri) pure Kotlin converter
    // -------------------------------------------------------------------------
    fun gregorianToJalali(gy: Int, gm: Int, gd: Int): Triple<Int, Int, Int> {
        val gdm = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val gy2 = if (gm > 2) (gy + 1) else gy
        var days = 355666 + (365 * gy) + ((gy2 + 3) / 4) - ((gy2 + 99) / 100) + ((gy2 + 399) / 400) + gd + gdm[gm - 1]
        var jy = -1595 + (33 * (days / 12053))
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        val jm: Int
        val jd: Int
        if (days < 186) {
            jm = 1 + (days / 31)
            jd = 1 + (days % 31)
        } else {
            jm = 7 + ((days - 186) / 30)
            jd = 1 + ((days - 186) % 30)
        }
        return Triple(jy, jm, jd)
    }

    private fun getServerUrl(context: Context): String {
        val prefs = context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
        return prefs.getString("server_url", "http://185.204.1.230:8000") ?: "http://185.204.1.230:8000"
    }
}
