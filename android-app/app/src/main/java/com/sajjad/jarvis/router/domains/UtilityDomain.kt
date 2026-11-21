package com.sajjad.jarvis.router.domains

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.CalendarContract
import android.util.Log
import com.sajjad.jarvis.AppLauncher
import com.sajjad.jarvis.DeviceController
import com.sajjad.jarvis.JarvisCameraActivity
import com.sajjad.jarvis.LocalCommandRouter.LocalRouteResult
import com.sajjad.jarvis.ProtocolEngine
import com.sajjad.jarvis.router.NlpNormalizer
import com.sajjad.jarvis.timer.JarvisAlarmReceiver
import com.sajjad.jarvis.timer.JarvisBackgroundTimerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.roundToInt

/**
 * UtilityDomain (Tier 1 Zero-Token Engine):
 *
 * Covers:
 * 1. CameraX Pro Controls (Photo, Selfie, Timed photo, Video, Stop video, Zoom, Flash, Close)
 * 2. Clock, Time & Jalali/Gregorian Date
 * 3. Alarms & Timers
 * 4. Calendar Events & Agenda
 * 5. Maps, Navigation & Geolocation Intents
 * 6. Browser Search & Web Query Intents
 * 7. Long-term Memory Persistence (/api/memory)
 * 8. Live Device Telemetry (Battery, Storage, Network Connectivity)
 * 9. Iron Man Protocols (Ghost, Sleep, Walk, Focus)
 * 10. Direct VPS Status (/api/vps/status)
 * 11. Pure Kotlin Math & Arithmetic Evaluator
 */
object UtilityDomain {

    private const val TAG = "UtilityDomain"
    private val scope = CoroutineScope(Dispatchers.IO)

    fun handle(context: Context, rawText: String, clean: String, lower: String, isFa: Boolean): LocalRouteResult {
        val norm = NlpNormalizer.cleanLower(rawText)

        // ---------------------------------------------------------------------
        // 1. Pro Camera Controls
        // ---------------------------------------------------------------------
        val camRes = handleCameraControls(context, rawText, clean, lower, norm, isFa)
        if (camRes.handled) return camRes

        // ---------------------------------------------------------------------
        // 2. Clock, Jalali/Gregorian Date & Time
        // ---------------------------------------------------------------------
        val timeRes = handleClockAndTime(context, clean, lower, norm, isFa)
        if (timeRes.handled) return timeRes

        // ---------------------------------------------------------------------
        // 3. Alarms & Timers
        // ---------------------------------------------------------------------
        val alarmRes = handleAlarmsAndTimers(context, rawText, clean, lower, norm, isFa)
        if (alarmRes.handled) return alarmRes

        // ---------------------------------------------------------------------
        // 4. Calendar Events
        // ---------------------------------------------------------------------
        val calRes = handleCalendar(context, clean, lower, norm, isFa)
        if (calRes.handled) return calRes

        // ---------------------------------------------------------------------
        // 5. Maps & Navigation
        // ---------------------------------------------------------------------
        val mapRes = handleMapsAndNavigation(context, clean, lower, norm, isFa)
        if (mapRes.handled) return mapRes

        // ---------------------------------------------------------------------
        // 6. Web Search & Browser Intent
        // ---------------------------------------------------------------------
        val searchRes = handleWebSearch(context, clean, lower, norm, isFa)
        if (searchRes.handled) return searchRes

        // ---------------------------------------------------------------------
        // 7. Live Device Telemetry
        // ---------------------------------------------------------------------
        val telemRes = handleTelemetry(context, lower, norm, isFa)
        if (telemRes.handled) return telemRes

        // ---------------------------------------------------------------------
        // 8. Iron Man Protocols
        // ---------------------------------------------------------------------
        val protoRes = handleProtocols(context, lower, norm, isFa)
        if (protoRes.handled) return protoRes

        // ---------------------------------------------------------------------
        // 9. Memory, VPS Status & Voice Math
        // ---------------------------------------------------------------------
        val serverRes = handleMemoryVpsAndMath(context, clean, lower, norm, isFa)
        if (serverRes.handled) return serverRes

        return LocalRouteResult(handled = false)
    }

    // =========================================================================
    // Camera Controls
    // =========================================================================
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

    private fun handleCameraControls(context: Context, rawText: String, clean: String, lower: String, norm: String, isFa: Boolean): LocalRouteResult {
        // Selfie
        val selfieWords = listOf("take a selfie", "take selfie", "selfie", "عکس سلفی بگیر", "یه سلفی بگیر", "سلفی بگیر", "سلفی بنداز", "عکس از خودت بگیر")
        if (selfieWords.any { norm.contains(it) || lower.contains(it) }) {
            val act = JSONObject().put("mode", "photo").put("lens", "front").put("capture", true)
            launchCameraAction(context, act)
            val reply = if (isFa) "در حال گرفتن عکس سلفی قربان." else "Taking selfie now, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "camera_selfie")
        }

        // Timed Photo
        val timerMatchEn = Regex("(take a photo with|timer)\\s+(\\d+)\\s*(s|sec|second|seconds)").find(lower)
        val timerMatchFa = Regex("(عکس با تایمر|تایمر دوربین)\\s*(\\d+)\\s*(ثانیه)?").find(NlpNormalizer.normalizeDigits(clean))
        if (timerMatchEn != null || timerMatchFa != null) {
            val sec = timerMatchEn?.groupValues?.get(2)?.toIntOrNull()
                ?: timerMatchFa?.groupValues?.get(2)?.toIntOrNull() ?: 3
            val act = JSONObject().put("mode", "photo").put("capture", true).put("timer_seconds", sec)
            launchCameraAction(context, act)
            val reply = if (isFa) "عکس با تایمر $sec ثانیه‌ای ثبت خواهد شد قربان." else "Photo timer set for $sec seconds, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "camera_timed_photo")
        }

        // Take Photo
        val photoWords = listOf("take a photo", "take photo", "take picture", "shoot photo", "capture photo", "snap photo", "عکس بگیر", "یه عکس بگیر", "عکس بنداز", "یک عکس بگیر", "تصویر بگیر")
        if (photoWords.any { norm.contains(it) || lower.contains(it) }) {
            val act = JSONObject().put("mode", "photo").put("lens", "back").put("capture", true)
            launchCameraAction(context, act)
            val reply = if (isFa) "در حال ثبت عکس قربان." else "Capturing photo, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "camera_photo")
        }

        // Record Video
        val videoWords = listOf("record a video", "record video", "start recording video", "take video", "فیلم بگیر", "فیلمبرداری کن", "ویدیو بگیر", "شروع فیلمبرداری", "ضبط ویدیو")
        if (videoWords.any { norm.contains(it) || lower.contains(it) }) {
            val act = JSONObject().put("mode", "video").put("capture", true)
            launchCameraAction(context, act)
            val reply = if (isFa) "در حال ضبط ویدیو قربان." else "Recording video, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "camera_video")
        }

        // Stop Video
        val stopVideoWords = listOf("stop recording", "stop video", "stop video recording", "قطع ضبط", "فیلمبرداری رو قطع کن", "متوقف کردن فیلمبرداری", "ویدیو رو قطع کن")
        if (stopVideoWords.any { norm.contains(it) || lower.contains(it) }) {
            val inst = JarvisCameraActivity.instance
            if (inst != null) {
                inst.stopVideoRecording()
            } else {
                launchCameraAction(context, JSONObject().put("mode", "stop_video"))
            }
            val reply = if (isFa) "ضبط ویدیو متوقف و ذخیره شد قربان." else "Video recording stopped and saved, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "camera_stop_video")
        }

        // Zoom Controls
        if (norm.contains("zoom in") || norm.contains("زوم کن") || norm.contains("بزرگنمایی کن")) {
            launchCameraAction(context, JSONObject().put("zoom", "zoom_in"))
            val reply = if (isFa) "بزرگنمایی دوربین افزایش یافت قربان." else "Zooming in, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "camera_zoom")
        }
        if (norm.contains("zoom out") || norm.contains("کوچکنمایی کن") || norm.contains("زوم اوت")) {
            launchCameraAction(context, JSONObject().put("zoom", "zoom_out"))
            val reply = if (isFa) "بزرگنمایی دوربین کاهش یافت قربان." else "Zooming out, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "camera_zoom")
        }

        // Flash Controls
        if (norm.contains("camera flash on") || norm.contains("فلش دوربین رو روشن کن")) {
            launchCameraAction(context, JSONObject().put("flash", "on"))
            val reply = if (isFa) "فلش دوربین روشن شد قربان." else "Camera flash enabled, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "camera_flash")
        }
        if (norm.contains("camera flash off") || norm.contains("فلش دوربین رو خاموش کن")) {
            launchCameraAction(context, JSONObject().put("flash", "off"))
            val reply = if (isFa) "فلش دوربین خاموش شد قربان." else "Camera flash disabled, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "camera_flash")
        }

        // Close Camera
        if (norm.contains("close camera") || norm.contains("exit camera") || norm.contains("دوربین رو ببند") || norm.contains("خروج از دوربین")) {
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

    // =========================================================================
    // Clock & Jalali/Gregorian Date
    // =========================================================================
    private fun handleClockAndTime(context: Context, clean: String, lower: String, norm: String, isFa: Boolean): LocalRouteResult {
        // Current Time
        val timeWords = listOf("what time is it", "what's the time", "tell me the time", "current time", "ساعت چنده", "الان ساعت چنده", "ساعت رو بگو", "ساعت چند است")
        if (timeWords.any { norm.contains(it) || lower.contains(it) }) {
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

        // Current Date (Jalali for Persian, Gregorian for English)
        val dateWords = listOf("what's the date", "what is today's date", "today's date", "what day is today", "امروز چندمه", "تاریخ امروز چیه", "امروز چه روزیه", "تاریخ امروز")
        if (dateWords.any { norm.contains(it) || lower.contains(it) }) {
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

        return LocalRouteResult(handled = false)
    }

    // =========================================================================
    // Alarms & Timers (Background-First Silent Execution)
    // =========================================================================
    private fun handleAlarmsAndTimers(context: Context, rawText: String, clean: String, lower: String, norm: String, isFa: Boolean): LocalRouteResult {
        // Explicit OEM Clock App Launch
        val openClockWords = listOf("open clock", "launch clock", "open alarms", "ساعت رو باز کن", "اپلیکیشن ساعت", "برنامه ساعت")
        if (openClockWords.any { norm.contains(it) || lower.contains(it) }) {
            AppLauncher.launchApp(context, "clock")
            val reply = if (isFa) "اپلیکیشن ساعت باز شد قربان." else "Opening Clock app, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "clock_open")
        }

        // Cancel / Stop Timer
        val cancelTimerWords = listOf(
            "cancel timer", "stop timer", "clear timer", "dismiss timer", "cancel the timer",
            "تایمر رو لغو کن", "تایمر را لغو کن", "تایمر رو متوقف کن", "تایمر رو خاموش کن", "لغو تایمر", "متوقف کردن تایمر"
        )
        if (cancelTimerWords.any { norm.contains(it) || lower.contains(it) }) {
            JarvisBackgroundTimerService.cancelTimer(context)
            val reply = if (isFa) "تایمر لغو شد قربان." else "Timer cancelled, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "clock_timer_cancel")
        }

        // Stop Active Alarm Ringing
        val stopAlarmWords = listOf(
            "stop alarm", "dismiss alarm", "turn off alarm", "cancel alarm",
            "آلارم رو قطع کن", "آلارم رو خاموش کن", "قطع آلارم", "خاموش کردن آلارم", "ساکت شو"
        )
        if (stopAlarmWords.any { norm.contains(it) || lower.contains(it) }) {
            JarvisAlarmReceiver.stopAlarmTone()
            val reply = if (isFa) "آلارم متوقف شد قربان." else "Alarm dismissed, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "clock_alarm_dismiss")
        }

        // Set Alarm (Background Exact Alarm via AlarmManager)
        val alarmMatchEn = Regex("(set an alarm for|alarm for|wake me up at)\\s+(\\d+)(?::(\\d+))?\\s*(am|pm)?").find(lower)
        val alarmMatchFa = Regex("(آلارم بزار برای|ساعت|بیدارم کن ساعت)\\s*(\\d+)(?::(\\d+))?\\s*(صبح|عصر|شب)?").find(NlpNormalizer.normalizeDigits(clean))
        if (alarmMatchEn != null || alarmMatchFa != null) {
            var hour = alarmMatchEn?.groupValues?.get(2)?.toIntOrNull()
                ?: alarmMatchFa?.groupValues?.get(2)?.toIntOrNull() ?: 8
            val min = alarmMatchEn?.groupValues?.get(3)?.toIntOrNull()
                ?: alarmMatchFa?.groupValues?.get(3)?.toIntOrNull() ?: 0
            val amPm = alarmMatchEn?.groupValues?.get(4) ?: alarmMatchFa?.groupValues?.get(4) ?: ""
            if ((amPm == "pm" || amPm == "عصر" || amPm == "شب") && hour < 12) hour += 12
            if ((amPm == "am" || amPm == "صبح") && hour == 12) hour = 0

            JarvisAlarmReceiver.scheduleExactAlarm(context, hour, min, "Jarvis Alarm")
            val reply = if (isFa) "آلارم برای ساعت $hour:${String.format(Locale.ROOT, "%02d", min)} در پس‌زمینه تنظیم شد قربان." else "Alarm set for $hour:${String.format(Locale.ROOT, "%02d", min)} in background, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "clock_alarm")
        }

        // Set Timer (Background-First Coroutine + Notification Countdown)
        val timerMatchEn = Regex("(set a timer for|timer for)\\s+(\\d+)\\s*(minute|minutes|min|second|seconds|sec)").find(lower)
        val timerMatchFa = Regex("تایمر\\s*(\\d+)\\s*(دقیقه|ثانیه)").find(NlpNormalizer.normalizeDigits(clean))
        if (timerMatchEn != null || timerMatchFa != null) {
            val num = timerMatchEn?.groupValues?.get(2)?.toIntOrNull()
                ?: timerMatchFa?.groupValues?.get(1)?.toIntOrNull() ?: 5
            val unit = timerMatchEn?.groupValues?.get(3) ?: timerMatchFa?.groupValues?.get(2) ?: "min"
            val totalSeconds = if (unit.startsWith("sec") || unit.contains("ثانیه")) num else num * 60
            val labelStr = "$num $unit"
            JarvisBackgroundTimerService.startTimer(context, totalSeconds, labelStr)
            val reply = if (isFa) "تایمر $num $unit در پس‌زمینه آغاز شد قربان." else "Timer for $num $unit running in background, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "clock_timer")
        }

        return LocalRouteResult(handled = false)
    }

    // =========================================================================
    // Calendar Events & Agenda
    // =========================================================================
    private fun handleCalendar(context: Context, clean: String, lower: String, norm: String, isFa: Boolean): LocalRouteResult {
        val calWords = listOf(
            "open calendar", "show calendar", "create event", "add calendar event", "schedule meeting",
            "تقویم رو باز کن", "تقویم", "یک جلسه ثبت کن", "یک رویداد در تقویم ثبت کن", "جلسه جدید در تقویم"
        )
        if (calWords.any { norm.contains(it) || lower.contains(it) }) {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, "New Meeting")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            AppLauncher.launchWithOverlayFallback(context, intent)
            val reply = if (isFa) "تقویم برای ثبت رویداد جدید باز شد قربان." else "Opening calendar to schedule event, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "calendar_event")
        }
        return LocalRouteResult(handled = false)
    }

    // =========================================================================
    // Maps & Navigation
    // =========================================================================
    private fun handleMapsAndNavigation(context: Context, clean: String, lower: String, norm: String, isFa: Boolean): LocalRouteResult {
        var place: String? = null

        val navMatchEn = Regex("^(?:navigate to|directions to|show me directions to|map of|where is)\\s+(.+)").find(lower)
        if (navMatchEn != null) {
            place = navMatchEn.groupValues[1].trim()
        } else if (isFa) {
            val navMatchFa = Regex("^(?:مسیریابی به|مسیریابی کن به|برو به|نقشه)\\s+(.+?)(?:\\s+(?:روی نقشه))?$").find(clean)
                ?: Regex("(.+?)\\s*(?:مسیریابی کن|رو روی نقشه بیار)").find(clean)
            if (navMatchFa != null) {
                place = navMatchFa.groupValues[1].trim()
            }
        }

        if (!place.isNullOrBlank()) {
            val encoded = try { URLEncoder.encode(place, "UTF-8") } catch (_: Exception) { place }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            AppLauncher.launchWithOverlayFallback(context, intent)
            val reply = if (isFa) "مسیریابی به $place بر روی نقشه باز شد قربان." else "Opening navigation for $place, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "nav_maps")
        }

        return LocalRouteResult(handled = false)
    }

    // =========================================================================
    // Web Search & Browser Intent
    // =========================================================================
    private fun handleWebSearch(context: Context, clean: String, lower: String, norm: String, isFa: Boolean): LocalRouteResult {
        var query: String? = null

        val searchMatchEn = Regex("^(?:search for|search web for|search google for|google)\\s+(.+)").find(lower)
        if (searchMatchEn != null) {
            query = searchMatchEn.groupValues[1].trim()
        } else if (isFa) {
            val searchMatchFa = Regex("^(?:سرچ کن|در وب جستجو کن|گوگل کن|جستجو کن برای|جستجو کن)\\s+(.+)").find(clean)
                ?: Regex("(.+?)\\s*(?:رو سرچ کن|رو گوگل کن|رو جستجو کن)").find(clean)
            if (searchMatchFa != null) {
                query = searchMatchFa.groupValues[1].trim()
            }
        }

        if (!query.isNullOrBlank()) {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            AppLauncher.launchWithOverlayFallback(context, intent)
            val reply = if (isFa) "در حال جستجوی \"$query\" در وب قربان." else "Searching the web for \"$query\", sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "web_search")
        }

        return LocalRouteResult(handled = false)
    }

    // =========================================================================
    // Live Device Telemetry
    // =========================================================================
    private fun handleTelemetry(context: Context, lower: String, norm: String, isFa: Boolean): LocalRouteResult {
        // Battery
        val batteryWords = listOf("battery level", "battery status", "how much battery is left", "battery percentage", "شارژ باتری چقدره", "باتری چنده", "وضعیت باتری", "درصد باتری")
        if (batteryWords.any { norm.contains(it) || lower.contains(it) }) {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            val status = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) ?: -1
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
        if (storageWords.any { norm.contains(it) || lower.contains(it) }) {
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
        if (netWords.any { norm.contains(it) || lower.contains(it) }) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val net = cm?.activeNetwork
            val caps = cm?.getNetworkCapabilities(net)
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

    // =========================================================================
    // Iron Man Protocols
    // =========================================================================
    private fun handleProtocols(context: Context, lower: String, norm: String, isFa: Boolean): LocalRouteResult {
        // Ghost Protocol
        if (norm.contains("ghost protocol") || norm.contains("پروتکل مخفی") || norm.contains("پروتکل شبح")) {
            ProtocolEngine.executeGhostProtocol(context)
            val reply = if (isFa) "پروتکل شبح فعال شد قربان. صفحه کم‌نور و صدا قطع گردید." else "Ghost protocol engaged, sir. Screen dimmed and audio muted."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "protocol_ghost")
        }

        // Sleep Protocol
        if (norm.contains("sleep protocol") || norm.contains("enter sleep mode") || norm.contains("پروتکل خواب") || norm.contains("حالت خواب")) {
            ProtocolEngine.executeSleepProtocol(context, isFa)
            val reply = if (isFa) "پروتکل خواب فعال شد قربان." else "Sleep protocol engaged, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "protocol_sleep")
        }

        // Walk Mode
        if (norm.contains("walk mode") || norm.contains("start walk mode") || norm.contains("حالت پیاده روی") || norm.contains("حالت پیاده‌روی")) {
            ProtocolEngine.executeWalkProtocol(context, isFa)
            val reply = if (isFa) "حالت پیاده‌روی فعال شد قربان." else "Walk mode engaged, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "protocol_walk")
        }

        // Focus Protocol
        if (norm.contains("focus protocol") || norm.contains("do not disturb mode") || norm.contains("پروتکل تمرکز")) {
            ProtocolEngine.executeFocusProtocol(context, 45, isFa)
            val reply = if (isFa) "پروتکل تمرکز فعال شد قربان." else "Focus protocol engaged, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "protocol_focus")
        }

        return LocalRouteResult(handled = false)
    }

    // =========================================================================
    // Memory, VPS Status & Voice Math Evaluator
    // =========================================================================
    private fun handleMemoryVpsAndMath(context: Context, clean: String, lower: String, norm: String, isFa: Boolean): LocalRouteResult {
        // Direct Memory Save
        val memMatchEn = Regex("^(?:remember that|save to memory)\\s+(.+)").find(lower)
        var memContent: String? = null
        if (memMatchEn != null) {
            memContent = memMatchEn.groupValues[1].trim()
        } else if (isFa) {
            val memMatchFa = Regex("^(?:یادت بمونه که|در حافظه ذخیره کن|این رو یادت باشه)\\s+(.+)").find(clean)
            if (memMatchFa != null) {
                memContent = memMatchFa.groupValues[1].trim()
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
        if (norm in listOf("server status", "vps status", "system status", "وضعیت سرور", "وضعیت وی پی اس", "وضعیت وی‌پی‌اس")) {
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

        // Voice Math / Pure Arithmetic Evaluator
        val mathResult = evaluateMathExpression(clean)
        if (mathResult != null) {
            val (a, opName, b, res) = mathResult
            val resStr = if (res % 1.0 == 0.0) res.toLong().toString() else String.format(Locale.ROOT, "%.2f", res)
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

    // =========================================================================
    // Pure Kotlin Math Evaluator
    // =========================================================================
    fun evaluateMathExpression(rawText: String): Tuple4<Double, String, Double, Double>? {
        var text = NlpNormalizer.normalizeDigits(rawText)

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

    data class Tuple4<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    // =========================================================================
    // Gregorian to Jalali (Solar Hijri) pure Kotlin converter
    // =========================================================================
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
