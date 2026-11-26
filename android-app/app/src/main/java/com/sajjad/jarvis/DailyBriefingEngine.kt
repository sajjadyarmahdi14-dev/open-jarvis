package com.sajjad.jarvis

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.sajjad.jarvis.router.domains.UtilityDomain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Feature 3: Executive Daily Briefing Engine.
 *
 * Gathers structured device + VPS telemetry in <2 seconds:
 * 1. Local Time + Jalali (for Persian) / Gregorian (for English) date — 0 tokens.
 * 2. Battery level & charging state — 0 tokens.
 * 3. Unread important notification summary from JarvisNotificationListener
 *    strictly honoring Headphone Privacy Guard!
 * 4. Live VPS health status & concise weather summary from GET /api/briefing (<=3.5s timeout).
 */
object DailyBriefingEngine {

    private const val TAG = "DailyBriefingEngine"
    const val PREF_AUTO_MORNING_BRIEFING = "auto_morning_briefing_enabled"
    const val PREF_LAST_AUTO_BRIEFING_DATE = "last_auto_morning_briefing_date"
    const val PREF_BRIEFING_CITY = "briefing_city"

    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Pure-Kotlin rule checker for Auto Morning Briefing on first headphone connect between 05:00 and 12:00.
     */
    fun shouldRunAutoMorningBriefing(
        enabled: Boolean,
        hourOfDay: Int,
        minute: Int,
        todayDateKey: String,
        lastRunDateKey: String?
    ): Boolean {
        if (!enabled) return false
        val inMorningWindow = (hourOfDay in 5..11) || (hourOfDay == 12 && minute == 0)
        if (!inMorningWindow) return false
        if (todayDateKey.isBlank()) return false
        return lastRunDateKey != todayDateKey
    }

    fun maybeTriggerOnHeadphoneConnect(context: Context) {
        val prefs = context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(PREF_AUTO_MORNING_BRIEFING, true)
        val cal = Calendar.getInstance()
        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
        val lastKey = prefs.getString(PREF_LAST_AUTO_BRIEFING_DATE, null)

        val shouldRun = shouldRunAutoMorningBriefing(
            enabled = enabled,
            hourOfDay = cal.get(Calendar.HOUR_OF_DAY),
            minute = cal.get(Calendar.MINUTE),
            todayDateKey = todayKey,
            lastRunDateKey = lastKey
        )
        if (!shouldRun) return

        prefs.edit().putString(PREF_LAST_AUTO_BRIEFING_DATE, todayKey).apply()
        val isFa = Locale.getDefault().language == "fa"
        Log.i(TAG, "First morning headphone connection detected ($todayKey). Delivering Auto Daily Briefing...")
        deliverExecutiveBriefing(context, isFa = isFa)
    }

    fun deliverExecutiveBriefing(context: Context, isFa: Boolean, onDone: ((String) -> Unit)? = null) {
        scope.launch {
            val briefingText = buildExecutiveBriefing(context, isFa)
            withContext(Dispatchers.Main) {
                onDone?.invoke(briefingText)
                val svc = JarvisVoiceService.instance
                if (svc != null) {
                    svc.broadcastUiUpdate(assistantReply = briefingText, wakeStatus = "speaking")
                    JarvisOverlayManager.instance?.updateText(briefingText)
                    svc.speakBritish(briefingText)
                }
            }
        }
    }

    suspend fun buildExecutiveBriefing(context: Context, isFa: Boolean): String = withContext(Dispatchers.IO) {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR)
        val h12 = if (hour == 0) 12 else hour
        val min = cal.get(Calendar.MINUTE)
        val minStr = String.format(Locale.US, "%02d", min)
        val amPm = if (cal.get(Calendar.AM_PM) == Calendar.AM) {
            if (isFa) "صبح" else "AM"
        } else {
            if (isFa) "بعد از ظهر" else "PM"
        }

        // 1. Date & Time (0 tokens)
        val dateStr = if (isFa) {
            val gy = cal.get(Calendar.YEAR)
            val gm = cal.get(Calendar.MONTH) + 1
            val gd = cal.get(Calendar.DAY_OF_MONTH)
            val (jy, jm, jd) = UtilityDomain.gregorianToJalali(gy, gm, gd)
            val monthNames = arrayOf("", "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور", "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند")
            val dayNames = arrayOf("", "یکشنبه", "دوشنبه", "سه‌شنبه", "چهارشنبه", "پنج‌شنبه", "جمعه", "شنبه")
            "${dayNames[cal.get(Calendar.DAY_OF_WEEK)]} $jd ${monthNames[jm]} $jy، ساعت $h12:$minStr $amPm"
        } else {
            val df = SimpleDateFormat("EEEE, MMMM d", Locale.ENGLISH)
            "${df.format(Date())}, $h12:$minStr $amPm"
        }

        // 2. Battery Level & Charging (0 tokens)
        val (batPct, isCharging) = try {
            val bStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = bStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = bStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) (level * 100) / scale else 80
            val st = bStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val chg = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            Pair(pct, chg)
        } catch (_: Exception) {
            Pair(80, false)
        }

        // 3. Notifications (Strictly Honoring Headphone Privacy Guard!)
        val cachedNotifs = JarvisNotificationListener.getCachedNotifications()
        val isPrivateHeadphone = ProactiveMonitor.isPrivateAudioConnected(context)
        val notifSummary = if (cachedNotifs.isEmpty()) {
            if (isFa) "اعلان جدیدی ندارید" else "you have no unread notifications"
        } else if (isPrivateHeadphone) {
            val topSenders = cachedNotifs.map { it.senderTitle }.distinct().take(2).joinToString(if (isFa) " و " else " and ")
            if (isFa) "${cachedNotifs.size} اعلان مهم از طرف $topSenders دارید" else "${cachedNotifs.size} unread notifications, including $topSenders"
        } else {
            // Public speaker -> NEVER leak sender content or body!
            if (isFa) "${cachedNotifs.size} اعلان خوانده‌نشده در صندوق پیام دارید" else "${cachedNotifs.size} unread notifications waiting privately"
        }

        // 4. VPS Status & Weather from GET /api/briefing (<= 3.5s timeout fallback)
        var weatherLine = ""
        var vpsLine = ""
        try {
            val prefs = context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
            val server = (prefs.getString("server_url", "http://104.252.175.159:8765")
                ?: "http://104.252.175.159:8765").trim().removeSuffix("/")
            val city = prefs.getString(PREF_BRIEFING_CITY, "Tehran")?.trim().orEmpty().ifBlank { "Tehran" }
            val encCity = URLEncoder.encode(city, "UTF-8")
            val u = URL("$server/api/briefing?city=$encCity&language=${if (isFa) "fa" else "en"}")
            val conn = (u.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 2000
                readTimeout = 3500
            }
            if (conn.responseCode in 200..299) {
                val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val json = JSONObject(resp)
                weatherLine = if (isFa) json.optString("weather_summary_fa", "") else json.optString("weather_summary_en", "")
                vpsLine = if (isFa) json.optString("vps_summary_fa", "") else json.optString("vps_summary_en", "")
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "GET /api/briefing fallback used: ${e.message}")
        }

        val greeting = when (cal.get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> if (isFa) "صبح بخیر قربان." else "Good morning, sir."
            in 12..17 -> if (isFa) "روز بخیر قربان." else "Good afternoon, sir."
            else -> if (isFa) "عصر بخیر قربان." else "Good evening, sir."
        }

        val sb = StringBuilder()
        if (isFa) {
            sb.append("$greeting امروز $dateStr. ")
            sb.append("باتری گوشی $batPct درصد${if (isCharging) " و در حال شارژ" else ""} است و $notifSummary. ")
            if (weatherLine.isNotBlank()) sb.append("$weatherLine ")
            if (vpsLine.isNotBlank()) sb.append(vpsLine)
        } else {
            sb.append("$greeting It is $dateStr. ")
            sb.append("Battery is at $batPct%${if (isCharging) " and charging" else ""}, and $notifSummary. ")
            if (weatherLine.isNotBlank()) sb.append("$weatherLine ")
            if (vpsLine.isNotBlank()) sb.append(vpsLine)
        }

        sb.toString().trim()
    }
}
