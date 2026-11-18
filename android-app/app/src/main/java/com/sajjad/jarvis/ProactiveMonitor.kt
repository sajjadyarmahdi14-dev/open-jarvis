package com.sajjad.jarvis

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Part B: Headphone Privacy Guard & Proactive System Monitor
 *
 * Implements audio output privacy checking, rate-limited proactive message announcements,
 * low-battery discharge alerts, and rich morning briefing generation.
 */
object ProactiveMonitor {

    private const val TAG = "ProactiveMonitor"
    private val scope = CoroutineScope(Dispatchers.Main)

    private val lastAlertTimes = ConcurrentHashMap<String, Long>()
    private var hasAnnouncedLowBattery = false

    private val MESSAGING_PACKAGES = setOf(
        "org.telegram.messenger",
        "org.telegram.plus",
        "org.thunderdog.challegram",
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.google.android.apps.messaging",
        "com.android.mms",
        "com.instagram.android",
        "com.facebook.orca",
        "com.twitter.android",
        "com.discord",
        "ch.threema.app",
        "org.thoughtcrime.securesms"
    )

    fun isProactiveAlertsEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
        return prefs.getBoolean("proactive_alerts_enabled", true)
    }

    /**
     * Part B1: Check whether private headphones (Bluetooth or Wired or USB) are actively connected.
     */
    fun isPrivateAudioConnected(context: Context): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            devices.any { device ->
                when (device.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_USB_HEADSET -> true
                    else -> false
                }
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isWiredHeadsetOn || audioManager.isBluetoothA2dpOn || audioManager.isBluetoothScoOn
        }
    }

    /**
     * Part B1: Evaluate incoming notification for privacy-guarded proactive voice announcement.
     */
    fun handleIncomingNotification(context: Context, item: JarvisNotificationListener.CachedNotification) {
        if (!isProactiveAlertsEnabled(context)) return

        val pkg = item.packageName
        val isMessagingApp = MESSAGING_PACKAGES.contains(pkg) || item.remoteInput != null
        if (!isMessagingApp) return

        val now = System.currentTimeMillis()
        val senderKey = "${item.packageName}::${item.senderTitle}"
        val lastTime = lastAlertTimes[senderKey] ?: 0L

        // Coalesce bursts from the same chat within 15 seconds
        if (now - lastTime < 15000L) {
            return
        }
        lastAlertTimes[senderKey] = now

        val sender = item.senderTitle
        val text = item.messageText
        val isHeadphones = isPrivateAudioConnected(context)
        val isFa = JarvisVoiceService.isPersianOrArabic(text) || JarvisVoiceService.isPersianOrArabic(sender)

        val announcement = if (isHeadphones) {
            // Private audio connected: Safe to read short preview aloud
            val preview = if (text.length > 60) text.take(60) + "..." else text
            if (isFa) {
                "پیام از $sender: $preview. مایلید پاسخ دهید؟"
            } else {
                "Message from $sender: $preview. Would you like to reply?"
            }
        } else {
            // Device speaker (public room): NEVER leak private message content!
            if (isFa) {
                "قربان، یک پیام جدید از $sender دارید."
            } else {
                "Sir, you have a private message from $sender."
            }
        }

        scope.launch {
            JarvisVoiceService.instance?.let { service ->
                if (!service.isServiceMuted) {
                    service.broadcastUiUpdate(assistantReply = announcement)
                    JarvisOverlayManager.instance?.updateText(announcement)
                    service.speakBritish(announcement)
                }
            }
        }
    }

    /**
     * Part B2: Proactive Battery Discharge Monitor (Triggers once <= 15% during discharge).
     */
    fun checkBatteryAlert(context: Context, batteryPct: Int, isCharging: Boolean) {
        if (batteryPct < 0) return

        if (isCharging || batteryPct > 20) {
            hasAnnouncedLowBattery = false
            return
        }

        if (!isCharging && batteryPct <= 15 && !hasAnnouncedLowBattery) {
            hasAnnouncedLowBattery = true
            if (!isProactiveAlertsEnabled(context)) return

            val announcement = if (Locale.getDefault().language == "fa") {
                "قربان، شارژ باتری به $batteryPct درصد کاهش یافته است. اتصال به شارژر را پیشنهاد می‌کنم."
            } else {
                "Power reserves are critically low at $batteryPct%, sir. I recommend connecting to a power source."
            }

            scope.launch {
                JarvisVoiceService.instance?.let { service ->
                    if (!service.isServiceMuted) {
                        service.broadcastUiUpdate(assistantReply = announcement)
                        JarvisOverlayManager.instance?.updateText(announcement)
                        service.speakBritish(announcement)
                    }
                }
            }
        }
    }

    /**
     * Part B3: Morning Briefing Generator (Aggregates time, date, battery, unread notifications, network).
     */
    fun generateMorningBriefing(context: Context, serverUrl: String, onResult: (String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val (batPct, isCharging) = try {
                val ifilter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
                val bStatus = context.registerReceiver(null, ifilter)
                val level = bStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = bStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                val pct = if (level >= 0 && scale > 0) (level * 100) / scale else 80
                val status = bStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                Pair(pct, charging)
            } catch (_: Exception) {
                Pair(80, false)
            }

            val dateFormat = SimpleDateFormat("h:mm a, EEEE, MMMM d", Locale.getDefault())
            val currentDateTime = dateFormat.format(Date())

            val unreadCount = JarvisNotificationListener.getCachedNotifications().size
            val topNotifications = JarvisNotificationListener.getCachedNotifications().take(3).map {
                "${it.appLabel} from ${it.senderTitle}: ${it.messageText.take(40)}"
            }

            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val isWifi = cm?.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val netStr = if (isWifi) "High-speed Wi-Fi" else "Cellular Network"

            val prompt = """
                Deliver an energetic, witty 15-second Iron Man J.A.R.V.I.S. morning briefing based on this live telemetry:
                - Current Time & Date: $currentDateTime
                - Battery: $batPct% (${if (isCharging) "Charging" else "Discharging"})
                - Network: $netStr
                - Unread Notifications: $unreadCount pending (${topNotifications.joinToString("; ")})
                Keep it concise (max 2-3 sentences), address me as sir, and match the language of the request.
            """.trimIndent()

            val reply = try {
                val url = URL("${serverUrl.removeSuffix("/")}/api/chat")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.connectTimeout = 5000
                conn.readTimeout = 12000
                conn.doOutput = true

                val body = JSONObject().apply {
                    put("message", prompt)
                    put("tts", false)
                }
                OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()) }

                if (conn.responseCode in 200..299) {
                    val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    JSONObject(resp).optString("response", "")
                } else null
            } catch (e: Exception) {
                Log.w(TAG, "Morning briefing server request failed: ${e.message}")
                null
            }

            val finalBriefing = if (!reply.isNullOrBlank()) {
                reply
            } else {
                "Good morning, sir. All core systems are nominal. The time is $currentDateTime, battery is at $batPct%, and you have $unreadCount unread notifications."
            }

            withContext(Dispatchers.Main) {
                onResult(finalBriefing)
            }
        }
    }
}
