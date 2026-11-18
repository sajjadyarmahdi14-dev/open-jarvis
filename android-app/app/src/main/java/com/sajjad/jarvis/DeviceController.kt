package com.sajjad.jarvis

import android.Manifest
import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.Locale

object DeviceController {

    private const val TAG = "DeviceController"
    private var isTorchOn: Boolean = false

    // -------------------------------------------------------------------------
    // 1. Music & Media Playback Control
    // -------------------------------------------------------------------------

    fun playMusic(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        dispatchMediaKey(am, KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    fun pauseMusic(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        dispatchMediaKey(am, KeyEvent.KEYCODE_MEDIA_PAUSE)
    }

    fun nextTrack(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        dispatchMediaKey(am, KeyEvent.KEYCODE_MEDIA_NEXT)
    }

    fun previousTrack(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        dispatchMediaKey(am, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }

    private fun dispatchMediaKey(am: AudioManager, keyCode: Int) {
        try {
            val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val up = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            am.dispatchMediaKeyEvent(down)
            am.dispatchMediaKeyEvent(up)
            Log.i(TAG, "Dispatched media key code: $keyCode")
        } catch (e: Exception) {
            Log.e(TAG, "dispatchMediaKey failed: ${e.message}")
        }
    }

    fun playSong(context: Context, query: String) {
        val cleanQuery = query.trim()
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(SearchManager.QUERY, cleanQuery)
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val pm = context.packageManager
        val preferredPackages = listOf(
            "com.google.android.apps.youtube.music",
            "com.miui.player",
            "com.spotify.music",
            "com.apple.android.music"
        )

        var chosenPackage: String? = null
        for (pkg in preferredPackages) {
            try {
                pm.getPackageInfo(pkg, 0)
                chosenPackage = pkg
                break
            } catch (_: Exception) {}
        }

        if (chosenPackage != null) {
            intent.setPackage(chosenPackage)
        }

        AppLauncher.launchWithOverlayFallback(context, intent)
        Log.i(TAG, "playSong executed for query='$cleanQuery' package=$chosenPackage")
    }

    // -------------------------------------------------------------------------
    // 2. Contacts & Communication
    // -------------------------------------------------------------------------

    fun findContactNumber(context: Context, contactName: String): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val cleanQuery = contactName.trim().lowercase(Locale.ROOT)
        var matchedNumber: String? = null
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                var exactMatch: String? = null
                var partialMatch: String? = null
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx)?.lowercase(Locale.ROOT) ?: continue
                    val number = cursor.getString(numIdx) ?: continue
                    if (name == cleanQuery) {
                        exactMatch = number
                        break
                    } else if (name.contains(cleanQuery) || cleanQuery.contains(name)) {
                        if (partialMatch == null) {
                            partialMatch = number
                        }
                    }
                }
                matchedNumber = exactMatch ?: partialMatch
            }
        } catch (e: Exception) {
            Log.w(TAG, "Contacts query exception: ${e.message}")
        }
        return matchedNumber
    }

    fun sendSms(context: Context, contactOrNumber: String, message: String) {
        var num = contactOrNumber.trim()
        val isDigits = num.replace(" ", "").replace("-", "").replace("+", "").all { it.isDigit() }
        if (!isDigits) {
            num = findContactNumber(context, num) ?: num
        }
        val cleanPhone = num.replace(" ", "").replace("-", "")

        val hasSendSmsPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        if (hasSendSmsPermission && cleanPhone.any { it.isDigit() }) {
            try {
                @Suppress("DEPRECATION")
                val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    SmsManager.getDefault()
                }
                val parts = smsManager.divideMessage(message)
                if (parts.size > 1) {
                    smsManager.sendMultipartTextMessage(cleanPhone, null, parts, null, null)
                } else {
                    smsManager.sendTextMessage(cleanPhone, null, message, null, null)
                }
                Log.i(TAG, "SMS sent directly via SmsManager to $cleanPhone")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Direct SmsManager failed, falling back to intent: ${e.message}")
            }
        }

        // Fallback to pre-filled SMS intent
        val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$cleanPhone")).apply {
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        AppLauncher.launchWithOverlayFallback(context, smsIntent)
    }

    fun sendEmail(context: Context, recipient: String, subject: String, body: String) {
        val cleanRecipient = recipient.trim()
        val emailIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$cleanRecipient")).apply {
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        AppLauncher.launchWithOverlayFallback(context, emailIntent)
        Log.i(TAG, "Opened email compose intent for $cleanRecipient")
    }

    fun sendMessenger(context: Context, app: String, contact: String, message: String) {
        val appLower = app.lowercase(Locale.ROOT)
        if (appLower.contains("whatsapp")) {
            val num = findContactNumber(context, contact) ?: contact
            val cleanPhone = num.replace("+", "").replace(" ", "").replace("-", "")
            val waIntent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://api.whatsapp.com/send?phone=$cleanPhone&text=${Uri.encode(message)}")
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            AppLauncher.launchWithOverlayFallback(context, waIntent)
        } else if (appLower.contains("telegram")) {
            val tgIntent = Intent(Intent.ACTION_VIEW).apply {
                val cleanUser = contact.removePrefix("@").trim()
                data = Uri.parse("tg://resolve?domain=$cleanUser")
                setPackage("org.telegram.messenger")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            AppLauncher.launchWithOverlayFallback(context, tgIntent)
            if (JarvisAccessibilityService.isServiceRunning()) {
                JarvisAccessibilityService.instance?.typeText(message, autoSend = true)
            }
        } else {
            // General messenger app launcher
            AppLauncher.launchApp(context, app)
            if (JarvisAccessibilityService.isServiceRunning()) {
                JarvisAccessibilityService.instance?.typeText(message, autoSend = false)
            }
        }
    }

    fun makeCall(context: Context, target: String) {
        var num = target.trim()
        val isDigits = num.replace(" ", "").replace("-", "").replace("+", "").all { it.isDigit() }
        if (!isDigits) {
            num = findContactNumber(context, num) ?: ""
        }
        if (num.isNotBlank()) {
            val cleanPhone = num.replace(" ", "").replace("-", "")
            val callIntent = if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                Intent(Intent.ACTION_CALL, Uri.parse("tel:$cleanPhone"))
            } else {
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleanPhone"))
            }.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            AppLauncher.launchWithOverlayFallback(context, callIntent)
        } else {
            val dialerIntent = Intent(Intent.ACTION_DIAL).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            AppLauncher.launchWithOverlayFallback(context, dialerIntent)
        }
    }

    // -------------------------------------------------------------------------
    // 3. Alarms & Timers
    // -------------------------------------------------------------------------

    fun setAlarm(context: Context, hour: Int, minute: Int, message: String) {
        val alarmIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        AppLauncher.launchWithOverlayFallback(context, alarmIntent)
    }

    fun setTimer(context: Context, seconds: Int, message: String) {
        val timerIntent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        AppLauncher.launchWithOverlayFallback(context, timerIntent)
    }

    // -------------------------------------------------------------------------
    // 4. System Hardware Controls (Torch, Brightness, Volume, Clipboard)
    // -------------------------------------------------------------------------

    fun setTorchState(context: Context, turnOn: Boolean) {
        try {
            val camMgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = camMgr.cameraIdList.firstOrNull()
            if (cameraId != null) {
                camMgr.setTorchMode(cameraId, turnOn)
                isTorchOn = turnOn
                Log.i(TAG, "Torch state set to: $turnOn")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed setting torch state: ${e.message}")
        }
    }

    fun setBrightness(context: Context, level: Int) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.System.canWrite(context)) {
                val clamped = level.coerceIn(0, 255)
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, clamped)
                Log.i(TAG, "Screen brightness set to $clamped")
            } else {
                val intent = Intent(Settings.ACTION_DISPLAY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                AppLauncher.launchWithOverlayFallback(context, intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "setBrightness error: ${e.message}")
        }
    }

    fun setVolume(context: Context, action: String, level: Int = -1) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (action) {
            "up" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "down" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "mute" -> am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
            "set" -> {
                val clamped = level.coerceIn(0, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                am.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, AudioManager.FLAG_SHOW_UI)
            }
        }
    }

    fun setClipboard(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Jarvis", text)
        cm.setPrimaryClip(clip)
    }

    fun getClipboard(context: Context): String {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip != null && clip.itemCount > 0) {
            return clip.getItemAt(0).text?.toString() ?: ""
        }
        return ""
    }

    fun openConnectivityPanel(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val intent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } else {
                val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "openConnectivityPanel failed: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // 5. Unified Client Action Dispatcher
    // -------------------------------------------------------------------------

    fun executeClientAction(
        context: Context,
        act: JSONObject,
        onScreenRead: ((String) -> Unit)? = null
    ) {
        try {
            val type = act.optString("type", "")
            when (type) {
                // Media / Music Controls
                "play_music" -> playMusic(context)
                "pause_music" -> pauseMusic(context)
                "next_track" -> nextTrack(context)
                "previous_track" -> previousTrack(context)
                "play_song" -> {
                    val query = act.optString("query") ?: act.optString("song") ?: ""
                    if (query.isNotBlank()) playSong(context, query)
                }

                // In-App Accessibility Automation & Closed-Loop Agent
                "autonomous_agent", "agent_goal", "in_app_task", "operate_device_ui", "android_operate_ui" -> {
                    val goal = act.optString("goal", act.optString("objective", ""))
                    val targetApp = act.optString("target_app", act.optString("target", act.optString("package", "")))
                    val prefs = context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
                    val serverUrl = prefs.getString("server_url", "http://104.252.175.159:8765") ?: "http://104.252.175.159:8765"

                    if (targetApp.isNotBlank()) {
                        AppLauncher.launchApp(context, targetApp)
                    }
                    JarvisAccessibilityService.instance?.runAgentLoop(goal, serverUrl) { reply ->
                        Log.i(TAG, "Autonomous agent completed: $reply")
                    }
                }
                "execute_ui_action" -> {
                    JarvisAccessibilityService.instance?.executeUiAction(act)
                }
                "type_text" -> {
                    val text = act.optString("text", "")
                    val autoSend = act.optBoolean("auto_send", false)
                    JarvisAccessibilityService.instance?.typeText(text, autoSend)
                }
                "click_element" -> {
                    val target = act.optString("target_text") ?: act.optString("target") ?: ""
                    JarvisAccessibilityService.instance?.clickElement(target)
                }
                "take_photo" -> {
                    JarvisAccessibilityService.instance?.takePhoto(context)
                }
                "read_screen" -> {
                    val screenText = JarvisAccessibilityService.instance?.readScreen() ?: "Accessibility service not active."
                    onScreenRead?.invoke(screenText)
                }
                "scroll_down" -> JarvisAccessibilityService.instance?.scrollDown()
                "scroll_up" -> JarvisAccessibilityService.instance?.scrollUp()
                "go_back" -> JarvisAccessibilityService.instance?.goBack()
                "go_home" -> JarvisAccessibilityService.instance?.goHome()
                "open_notifications" -> JarvisAccessibilityService.instance?.openNotifications()
                "take_screenshot" -> JarvisAccessibilityService.instance?.takeScreenshot()

                // System & Communication
                "send_sms" -> {
                    val target = act.optString("contact_or_number") ?: act.optString("number") ?: ""
                    val msg = act.optString("message", "")
                    sendSms(context, target, msg)
                }
                "send_email" -> {
                    val recipient = act.optString("recipient") ?: act.optString("to") ?: ""
                    val subject = act.optString("subject", "")
                    val body = act.optString("body", "")
                    sendEmail(context, recipient, subject, body)
                }
                "send_messenger" -> {
                    val app = act.optString("app", "telegram")
                    val contact = act.optString("contact", "")
                    val msg = act.optString("message", "")
                    sendMessenger(context, app, contact, msg)
                }
                "set_alarm", "alarm" -> {
                    val h = act.optInt("hour", 7)
                    val m = act.optInt("minute", 0)
                    val msg = act.optString("message", "Jarvis Alarm")
                    setAlarm(context, h, m, msg)
                }
                "set_timer" -> {
                    val seconds = act.optInt("seconds", 60)
                    val msg = act.optString("message", "Jarvis Timer")
                    setTimer(context, seconds, msg)
                }
                "system_control" -> {
                    when (act.optString("action")) {
                        "brightness" -> setBrightness(context, act.optInt("level", 128))
                        "connectivity_panel" -> openConnectivityPanel(context)
                        "clipboard_set" -> setClipboard(context, act.optString("text", ""))
                        "volume" -> setVolume(context, act.optString("sub_action", "up"), act.optInt("level", -1))
                    }
                }

                // App Launching & Legacy
                "launch_app" -> {
                    val target = act.optString("target") ?: act.optString("package") ?: ""
                    if (target.isNotBlank()) AppLauncher.launchApp(context, target)
                }
                "call" -> {
                    val target = act.optString("number") ?: act.optString("name") ?: ""
                    makeCall(context, target)
                }
                "sms" -> {
                    val num = act.optString("number", "")
                    val msg = act.optString("message", "")
                    sendSms(context, num, msg)
                }
                "torch" -> {
                    val state = act.optString("state", "toggle")
                    val turnOn = when (state) {
                        "on" -> true
                        "off" -> false
                        else -> !isTorchOn
                    }
                    setTorchState(context, turnOn)
                }
                "volume" -> {
                    val actStr = act.optString("action", "up")
                    val level = act.optInt("level", -1)
                    setVolume(context, actStr, level)
                }
                "open_url" -> {
                    val urlStr = act.optString("url", "").trim()
                    if (urlStr.isNotBlank()) {
                        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(urlStr)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        AppLauncher.launchWithOverlayFallback(context, viewIntent)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing client action: ${e.message}")
        }
    }
}
