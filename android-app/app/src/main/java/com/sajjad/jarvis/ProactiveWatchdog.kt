package com.sajjad.jarvis

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sajjad.jarvis.security.SecurityProtocolEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Feature 5: Proactive Device & VPS Health Watchdog.
 *
 * Lightweight, battery-friendly proactive monitor with 45-minute anti-spam cooldown per alert type:
 * 1. Device Health Watchdog (ACTION_BATTERY_CHANGED):
 *    - Battery reaches >= threshold (90% / 100%) while charging
 *    - Battery drops <= 15% while unplugged
 *    - Battery temperature exceeds 43.0 C
 * 2. VPS Health Watchdog (5-minute coroutine poll when network connected & toggle ON):
 *    - Alerts on HUD + Notification + Telegram if CPU > 90%, RAM > 90%, or 2 consecutive failed pings
 */
object ProactiveWatchdog {

    private const val TAG = "ProactiveWatchdog"
    private const val CHANNEL_ID = "jarvis_watchdog_alerts"
    const val DEFAULT_COOLDOWN_MS = 45 * 60 * 1000L // 45 minutes anti-spam cooldown
    const val VPS_POLL_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes

    const val PREF_WATCHDOG_BATTERY_HIGH = "watchdog_battery_high"
    const val PREF_WATCHDOG_BATTERY_HIGH_THRESHOLD = "watchdog_battery_high_threshold"
    const val PREF_WATCHDOG_BATTERY_LOW = "watchdog_battery_low"
    const val PREF_WATCHDOG_BATTERY_TEMP = "watchdog_battery_temp"
    const val PREF_WATCHDOG_VPS_HEALTH = "watchdog_vps_health"

    const val ALERT_BATTERY_HIGH = "battery_high"
    const val ALERT_BATTERY_LOW = "battery_low"
    const val ALERT_BATTERY_TEMP = "battery_temp"
    const val ALERT_VPS_CPU = "vps_cpu"
    const val ALERT_VPS_RAM = "vps_ram"
    const val ALERT_VPS_OFFLINE = "vps_offline"

    /**
     * Pure-Kotlin anti-spam cooldown tracker for JUnit verification.
     */
    class AlertCooldownTracker(private val cooldownMs: Long = DEFAULT_COOLDOWN_MS) {
        private val lastAlertMap = ConcurrentHashMap<String, Long>()

        fun shouldEmit(alertType: String, nowMs: Long): Boolean {
            val prev = lastAlertMap[alertType] ?: 0L
            if (prev > 0L && (nowMs - prev) < cooldownMs) {
                return false
            }
            lastAlertMap[alertType] = nowMs
            return true
        }

        fun reset(alertType: String? = null) {
            if (alertType == null) lastAlertMap.clear() else lastAlertMap.remove(alertType)
        }
    }

    /**
     * Pure-Kotlin evaluator returning alert type to emit, if any, given device thresholds.
     */
    fun evaluateBatteryThresholds(
        pct: Int,
        isCharging: Boolean,
        tempCelsius: Float,
        enableHigh: Boolean,
        highThreshold: Int,
        enableLow: Boolean,
        enableTemp: Boolean
    ): List<String> {
        val triggered = mutableListOf<String>()
        if (enableHigh && isCharging && pct >= highThreshold.coerceIn(80, 100)) {
            triggered.add(ALERT_BATTERY_HIGH)
        }
        if (enableLow && !isCharging && pct in 1..15) {
            triggered.add(ALERT_BATTERY_LOW)
        }
        if (enableTemp && tempCelsius > 43.0f) {
            triggered.add(ALERT_BATTERY_TEMP)
        }
        return triggered
    }

    val cooldownTracker = AlertCooldownTracker(DEFAULT_COOLDOWN_MS)
    private val scope = CoroutineScope(Dispatchers.IO)
    private var vpsPollJob: Job? = null
    private var consecutiveVpsFailures = 0

    fun evaluateBatteryIntent(
        context: Context,
        pct: Int,
        isCharging: Boolean,
        tempTenthsC: Int
    ) {
        if (pct < 0) return
        val prefs = context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
        val enableHigh = prefs.getBoolean(PREF_WATCHDOG_BATTERY_HIGH, true)
        val highThreshold = prefs.getInt(PREF_WATCHDOG_BATTERY_HIGH_THRESHOLD, 90)
        val enableLow = prefs.getBoolean(PREF_WATCHDOG_BATTERY_LOW, true)
        val enableTemp = prefs.getBoolean(PREF_WATCHDOG_BATTERY_TEMP, true)

        val tempC = tempTenthsC / 10.0f
        val nowMs = System.currentTimeMillis()
        val alerts = evaluateBatteryThresholds(
            pct = pct,
            isCharging = isCharging,
            tempCelsius = tempC,
            enableHigh = enableHigh,
            highThreshold = highThreshold,
            enableLow = enableLow,
            enableTemp = enableTemp
        )

        val isFa = Locale.getDefault().language == "fa"
        for (alertType in alerts) {
            if (!cooldownTracker.shouldEmit(alertType, nowMs)) continue

            val (title, msg) = when (alertType) {
                ALERT_BATTERY_HIGH -> if (isFa) {
                    "شارژ بهینه‌سازی باتری" to "قربان، شارژ باتری به $pct درصد رسید؛ برای حفظ سلامت باتری می‌توانید شارژر را جدا کنید."
                } else {
                    "Battery Longevity Guard" to "Battery reached $pct%, sir. Unplugging now helps preserve long-term battery health."
                }
                ALERT_BATTERY_LOW -> if (isFa) {
                    "هشدار شارژ کم باتری" to "قربان، شارژ باتری به $pct درصد کاهش یافته است. اتصال به شارژر را پیشنهاد می‌کنم."
                } else {
                    "Low Battery Alert" to "Battery dropped to $pct%, sir. Recommending power connection."
                }
                ALERT_BATTERY_TEMP -> if (isFa) {
                    "هشدار دمای بالای باتری" to "قربان، دمای باتری به ${String.format(Locale.US, "%.1f", tempC)} درجه رسیده است. لطفاً دستگاه را خنک کنید."
                } else {
                    "Battery Thermal Warning" to "Device battery temperature reached ${String.format(Locale.US, "%.1f", tempC)}°C, sir."
                }
                else -> continue
            }

            emitAlert(context, alertType, title, msg, notifyTelegram = false)
        }
    }

    fun startVpsWatchdogLoop(context: Context) {
        if (vpsPollJob?.isActive == true) return
        val appCtx = context.applicationContext
        vpsPollJob = scope.launch {
            while (isActive) {
                delay(VPS_POLL_INTERVAL_MS)
                try {
                    val prefs = appCtx.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
                    if (!prefs.getBoolean(PREF_WATCHDOG_VPS_HEALTH, true)) continue
                    if (!isNetworkConnected(appCtx)) continue
                    pollVpsOnce(appCtx)
                } catch (e: Exception) {
                    Log.w(TAG, "VPS Watchdog poll tick error: ${e.message}")
                }
            }
        }
        Log.i(TAG, "Proactive VPS Watchdog started (5m interval, 45m anti-spam cooldown).")
    }

    fun stopVpsWatchdogLoop() {
        vpsPollJob?.cancel()
        vpsPollJob = null
    }

    private fun pollVpsOnce(context: Context) {
        val prefs = context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
        val server = (prefs.getString("server_url", "http://104.252.175.159:8765")
            ?: "http://104.252.175.159:8765").trim().removeSuffix("/")
        val nowMs = System.currentTimeMillis()
        val isFa = Locale.getDefault().language == "fa"

        var conn: HttpURLConnection? = null
        try {
            val u = URL("$server/api/vps/status")
            conn = (u.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
            }
            if (conn.responseCode in 200..299) {
                consecutiveVpsFailures = 0
                val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val json = JSONObject(resp)
                val cpuObj = json.optJSONObject("cpu")
                val ramObj = json.optJSONObject("ram")
                val cpuPct = cpuObj?.optDouble("used_pct", -1.0)?.let {
                    if (it >= 0.0) it
                    else {
                        val load = cpuObj.optDouble("load_1m", 0.0)
                        val cores = cpuObj.optInt("cores", 1).coerceAtLeast(1)
                        (load / cores) * 100.0
                    }
                } ?: 0.0
                val ramPct = ramObj?.optDouble("used_pct", 0.0) ?: 0.0

                if (cpuPct > 90.0 && cooldownTracker.shouldEmit(ALERT_VPS_CPU, nowMs)) {
                    val title = if (isFa) "هشدار پردازنده VPS" else "VPS High CPU Alert"
                    val msg = if (isFa) "مصرف پردازنده سرور به ${cpuPct.toInt()} درصد رسیده است قربان." else "VPS CPU load exceeded 90% (${cpuPct.toInt()}%), sir."
                    emitAlert(context, ALERT_VPS_CPU, title, msg, notifyTelegram = true)
                }
                if (ramPct > 90.0 && cooldownTracker.shouldEmit(ALERT_VPS_RAM, nowMs)) {
                    val title = if (isFa) "هشدار حافظه RAM سرور" else "VPS High RAM Alert"
                    val msg = if (isFa) "مصرف رم سرور به ${ramPct.toInt()} درصد رسیده است قربان." else "VPS RAM usage exceeded 90% (${ramPct.toInt()}%), sir."
                    emitAlert(context, ALERT_VPS_RAM, title, msg, notifyTelegram = true)
                }
            } else {
                consecutiveVpsFailures++
            }
        } catch (e: Exception) {
            consecutiveVpsFailures++
            Log.w(TAG, "VPS Watchdog ping failure #$consecutiveVpsFailures: ${e.message}")
        } finally {
            conn?.disconnect()
        }

        if (consecutiveVpsFailures >= 2 && cooldownTracker.shouldEmit(ALERT_VPS_OFFLINE, nowMs)) {
            val title = if (isFa) "هشدار قطعی VPS" else "VPS Unreachable Alert"
            val msg = if (isFa) "سرور مرکزی به دو درخواست متوالی پاسخ نداد قربان." else "VPS failed 2 consecutive health checks, sir."
            emitAlert(context, ALERT_VPS_OFFLINE, title, msg, notifyTelegram = false)
        }
    }

    private fun emitAlert(
        context: Context,
        alertType: String,
        title: String,
        message: String,
        notifyTelegram: Boolean
    ) {
        // Never interrupt silent Anti-Theft lockdown
        if (SecurityProtocolEngine.isLockdownActive) return

        Log.w(TAG, "Watchdog Alert [$alertType]: $title — $message")

        // 1. System Notification
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (nm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val ch = NotificationChannel(
                        CHANNEL_ID,
                        "J.A.R.V.I.S. Health Watchdog",
                        NotificationManager.IMPORTANCE_DEFAULT
                    )
                    nm.createNotificationChannel(ch)
                }
                val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setSmallIcon(R.drawable.ic_jarvis)
                    .setAutoCancel(true)
                    .build()
                nm.notify(alertType.hashCode(), notif)
            }
        } catch (_: Exception) {}

        // 2. Floating HUD & Chat notice (+ subtle voice if headset / unmuted)
        scope.launch(Dispatchers.Main) {
            JarvisOverlayManager.instance?.showOverlay()
            JarvisOverlayManager.instance?.updateText("$title: $message")
            JarvisVoiceService.instance?.let { svc ->
                svc.broadcastUiUpdate(assistantReply = "⚠️ $title: $message")
                if (!JarvisVoiceService.isServiceMuted) {
                    svc.speakBritish(message)
                }
            }
        }

        // 3. Forward to Telegram Vault if requested and VPS reachable
        if (notifyTelegram) {
            scope.launch(Dispatchers.IO) {
                try {
                    val prefs = context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
                    val server = (prefs.getString("server_url", "http://104.252.175.159:8765")
                        ?: "http://104.252.175.159:8765").trim().removeSuffix("/")
                    val u = URL("$server/api/security/alert")
                    val conn = (u.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                        connectTimeout = 5000
                        readTimeout = 5000
                        doOutput = true
                    }
                    val body = JSONObject()
                        .put("alert_type", alertType)
                        .put("title", title)
                        .put("message", message)
                        .put("severity", "warning")
                    OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()) }
                    conn.responseCode
                    conn.disconnect()
                } catch (_: Exception) {}
            }
        }
    }

    private fun isNetworkConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
