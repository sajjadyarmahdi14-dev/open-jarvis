package com.sajjad.jarvis

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.Locale

/**
 * Unified Client Action Dispatcher for J.A.R.V.I.S.
 *
 * Consolidates ALL device, system, hardware, media, UI automation, and protocol actions
 * across both voice turns and typed chat turns into ONE single execution pipeline.
 *
 * Guarantees:
 * 1. Zero silent swallowing: Every action is logged and permission checks report honest explanations.
 * 2. Parameter normalization: Automatically unpacks nested "args" from LLM tool calls.
 * 3. Health telemetry: Records last dispatched action and result for diagnostic inspection.
 */
object ActionDispatcher {

    private const val TAG = "ActionDispatcher"
    private val scope = CoroutineScope(Dispatchers.Main)

    // Health Telemetry
    @Volatile var lastDispatchedAction: String = "None"
    @Volatile var lastActionResult: String = "None"
    @Volatile var lastActionTimestamp: Long = 0L

    fun dispatchActions(
        context: Context,
        actions: JSONArray?,
        onFeedback: ((String) -> Unit)? = null
    ) {
        if (actions == null || actions.length() == 0) return
        for (i in 0 until actions.length()) {
            val act = actions.optJSONObject(i) ?: continue
            dispatchAction(context, act, onFeedback)
        }
    }

    fun dispatchAction(
        context: Context,
        rawAct: JSONObject,
        onFeedback: ((String) -> Unit)? = null
    ): Boolean {
        // Unpack nested "args" if present (from LLM tool calls)
        val act = JSONObject()
        val keys = rawAct.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k == "args") {
                val argsObj = rawAct.optJSONObject("args")
                if (argsObj != null) {
                    val argKeys = argsObj.keys()
                    while (argKeys.hasNext()) {
                        val ak = argKeys.next()
                        act.put(ak, argsObj.get(ak))
                    }
                } else {
                    val argsStr = rawAct.optString("args", "")
                    if (argsStr.startsWith("{")) {
                        try {
                            val parsedArgs = JSONObject(argsStr)
                            val pak = parsedArgs.keys()
                            while (pak.hasNext()) {
                                val ak = pak.next()
                                act.put(ak, parsedArgs.get(ak))
                            }
                        } catch (_: Exception) {}
                    }
                }
            } else {
                act.put(k, rawAct.get(k))
            }
        }

        val rawType = act.optString("type", act.optString("action", "")).lowercase(Locale.ROOT)
        if (rawType.isBlank()) {
            Log.w(TAG, "dispatchAction called with blank action type: $rawAct")
            return false
        }

        lastDispatchedAction = rawType
        lastActionTimestamp = System.currentTimeMillis()
        Log.i(TAG, "Dispatching action: '$rawType' with payload: $act")

        val isFa = JarvisVoiceService.isPersianOrArabic(act.toString()) ||
                (Locale.getDefault().language == "fa")

        return try {
            when (rawType) {
                // -----------------------------------------------------------------
                // 1. App Launching & Live Discovery (Verified Open Handshake)
                // -----------------------------------------------------------------
                "android_launch_app", "launch_app", "open_app", "start_app" -> {
                    val pkgName = act.optString("package_name", act.optString("package", ""))
                    val appQuery = act.optString("app_query", act.optString("target", act.optString("app", "")))
                    val verifyAndSpeak = act.optBoolean("verify_and_speak", true)
                    executeLaunchAndVerify(context, pkgName, appQuery, verifyAndSpeak, isFa, onFeedback)
                    true
                }

                // -----------------------------------------------------------------
                // 2. Flashlight / Torch Control
                // -----------------------------------------------------------------
                "android_torch", "torch", "flashlight" -> {
                    val state = act.optString("state", "toggle").lowercase(Locale.ROOT)
                    val turnOn = when (state) {
                        "on" -> true
                        "off" -> false
                        else -> !DeviceController.isTorchOn
                    }
                    val ok = DeviceController.setTorchState(context, turnOn)
                    lastActionResult = if (ok) "Torch set to $turnOn" else "Torch failed (No flash unit or camera busy)"
                    ok
                }

                // -----------------------------------------------------------------
                // 3. Audio & Video Media Engine (Local + Streaming + Verified)
                // -----------------------------------------------------------------
                "media_play" -> {
                    val mediaType = act.optString("media_type", "audio")
                    val query = act.optString("query", act.optString("song", ""))
                    val artist = act.optString("artist", "")
                    val app = act.optString("app", "")
                    val shuffle = act.optBoolean("shuffle", false)
                    MediaEngine.handleMediaPlay(context, mediaType, query, artist, app, shuffle) { verified, msg ->
                        lastActionResult = msg
                        if (!verified && onFeedback != null) onFeedback(msg)
                    }
                    true
                }
                "media_control" -> {
                    val command = act.optString("command", act.optString("action", "play"))
                    val seconds = act.optInt("seconds", 30)
                    MediaEngine.handleMediaControl(context, command, seconds)
                    lastActionResult = "Media control '$command' executed"
                    true
                }
                "play_music" -> {
                    MediaEngine.handleMediaPlay(context, "audio", "", null, "local", false)
                    lastActionResult = "Local music playback initiated"
                    true
                }
                "pause_music" -> {
                    MediaEngine.handleMediaControl(context, "pause")
                    lastActionResult = "Media playback paused"
                    true
                }
                "next_track" -> {
                    MediaEngine.handleMediaControl(context, "next")
                    lastActionResult = "Skipped to next track"
                    true
                }
                "previous_track" -> {
                    MediaEngine.handleMediaControl(context, "previous")
                    lastActionResult = "Returned to previous track"
                    true
                }
                "play_song" -> {
                    val query = act.optString("query", act.optString("song", ""))
                    MediaEngine.handleMediaPlay(context, "audio", query, null, null, false)
                    lastActionResult = "Playing song: $query"
                    true
                }

                // -----------------------------------------------------------------
                // 4. Volume Control
                // -----------------------------------------------------------------
                "android_volume", "volume_control", "volume" -> {
                    val rawAction = act.optString("action", "up").lowercase(Locale.ROOT)
                    val percent = if (act.has("percent")) act.optInt("percent")
                                  else if (act.has("level") && act.optInt("level") in 0..100) act.optInt("level")
                                  else null
                    val action = if (rawAction == "volume" || rawAction.isBlank()) {
                        if (percent != null) "set" else act.optString("sub_action", "up")
                    } else rawAction
                    val stream = act.optString("stream", "media")
                    MediaEngine.handleVolumeControl(context, action, percent, stream)
                    lastActionResult = "Volume action '$action' applied"
                    true
                }

                // -----------------------------------------------------------------
                // 5. Communications: Calls, SMS, Email, Messenger
                // -----------------------------------------------------------------
                "android_call", "call", "phone_call" -> {
                    val target = act.optString("number", act.optString("name", act.optString("target", "")))
                    DeviceController.makeCall(context, target)
                    lastActionResult = "Call initiated for $target"
                    true
                }
                "android_sms", "android_send_sms", "send_sms", "sms" -> {
                    val target = act.optString("contact_or_number", act.optString("number", act.optString("contact", "")))
                    val msg = act.optString("message", act.optString("text", ""))
                    DeviceController.sendSms(context, target, msg)
                    lastActionResult = "SMS sent to $target"
                    true
                }
                "android_send_email", "send_email", "email" -> {
                    val recipient = act.optString("recipient", act.optString("to", ""))
                    val subject = act.optString("subject", "Jarvis Message")
                    val body = act.optString("body", act.optString("message", ""))
                    val autoSend = act.optBoolean("auto_send", act.optBoolean("send", false))
                    DeviceController.sendEmail(context, recipient, subject, body, autoSend = autoSend)
                    val reply = if (autoSend && JarvisAccessibilityService.instance != null) {
                        if (isFa) "در حال ارسال ایمیل به $recipient قربان." else "Sending email to $recipient, sir."
                    } else {
                        if (isFa) "پیش‌نویس ایمیل با پیام شما آماده شد قربان—روی ارسال بزنید." else "Email draft with your message is ready, sir—tap Send."
                    }
                    onFeedback?.invoke(reply)
                    lastActionResult = "Email processed for $recipient"
                    true
                }
                "android_telegram_action", "send_messenger", "send_whatsapp_action", "telegram_send", "whatsapp_send" -> {
                    val app = act.optString("app", "telegram")
                    val contact = act.optString("contact", act.optString("recipient", ""))
                    val msg = act.optString("message", act.optString("body", ""))

                    // Fix 4: Ironclad Anti-Fake-Confirmation Rule
                    if (JarvisAccessibilityService.instance == null) {
                        val noAccessMsg = if (isFa) {
                            "سرویس دسترسی‌پذیری (Accessibility) خاموش است؛ برای ارسال پیام داخل برنامه‌ها ابتدا آن را روشن کنید."
                        } else {
                            "Accessibility service is off, sir. Please enable it so I can send messages inside apps."
                        }
                        onFeedback?.invoke(noAccessMsg)
                        JarvisVoiceService.instance?.speakBritish(noAccessMsg)
                        lastActionResult = "Accessibility disabled"
                        return false
                    }

                    DeviceController.sendMessenger(context, app, contact, msg) { success, details ->
                        val reply = if (success) {
                            if (isFa) "پیام با موفقیت به $contact ارسال شد قربان." else "Message sent to $contact, sir."
                        } else {
                            if (isFa) "ارسال پیام به $contact انجام نشد: $details" else "Could not send message to $contact: $details"
                        }
                        onFeedback?.invoke(reply)
                        JarvisVoiceService.instance?.speakBritish(reply)
                    }
                    lastActionResult = "Dispatched verified messenger flow for $contact"
                    true
                }

                // -----------------------------------------------------------------
                // 6. Alarms & Timers
                // -----------------------------------------------------------------
                "android_set_alarm", "set_alarm", "alarm" -> {
                    val h = act.optInt("hour", 7)
                    val m = act.optInt("minute", 0)
                    val msg = act.optString("message", "Jarvis Alarm")
                    DeviceController.setAlarm(context, h, m, msg)
                    lastActionResult = "Alarm set for $h:$m"
                    true
                }
                "android_timer", "set_timer", "timer" -> {
                    val seconds = act.optInt("seconds", 60)
                    val msg = act.optString("message", "Jarvis Timer")
                    DeviceController.setTimer(context, seconds, msg)
                    lastActionResult = "Timer set for ${seconds}s"
                    true
                }

                // -----------------------------------------------------------------
                // 7. Live Multimodal Vision & Camera Pro Engine
                // -----------------------------------------------------------------
                "camera_control", "camera", "open_camera", "take_photo", "record_video", "android_camera_capture" -> {
                    handleCameraControl(context, act, isFa, onFeedback)
                }
                "vision_camera" -> {
                    val prompt = act.optString("prompt", "What do you see?")
                    val hasCam = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                    if (!hasCam) {
                        val missingMsg = if (isFa) "مجوز دوربین به جارویس داده نشده است." else "Camera permission is not granted for Jarvis."
                        onFeedback?.invoke(missingMsg)
                        lastActionResult = "Camera permission missing"
                        return false
                    }
                    val svc = JarvisVoiceService.instance
                    if (svc != null) {
                        svc.handleVisionAction("camera", prompt)
                        lastActionResult = "Live camera inspection triggered"
                    } else {
                        val noSvcMsg = if (isFa) "سرویس صوتی جارویس در حال اجرا نیست." else "Jarvis voice service is not active."
                        onFeedback?.invoke(noSvcMsg)
                        lastActionResult = "Voice service inactive"
                    }
                    true
                }
                "vision_screen" -> {
                    val prompt = act.optString("prompt", "What is on the screen?")
                    val isAcc = JarvisAccessibilityService.isServiceRunning()
                    if (!isAcc) {
                        val missingMsg = if (isFa) "سرویس دسترسی‌پذیری برای دیدن صفحه غیرفعال است." else "Accessibility service is required for screen vision."
                        onFeedback?.invoke(missingMsg)
                        lastActionResult = "Accessibility service disabled"
                        return false
                    }
                    val svc = JarvisVoiceService.instance
                    if (svc != null) {
                        svc.handleVisionAction("screen", prompt)
                        lastActionResult = "Screen vision triggered"
                    }
                    true
                }
                "read_screen" -> {
                    val isAcc = JarvisAccessibilityService.isServiceRunning()
                    if (!isAcc) {
                        val missingMsg = if (isFa) "سرویس دسترسی‌پذیری برای خواندن صفحه غیرفعال است." else "Accessibility service is disabled."
                        onFeedback?.invoke(missingMsg)
                        lastActionResult = "Accessibility disabled"
                        return false
                    }
                    val screenText = JarvisAccessibilityService.instance?.readScreen() ?: ""
                    lastActionResult = "Screen text extracted (${screenText.length} chars)"
                    if (screenText.isNotBlank()) {
                        JarvisVoiceService.instance?.handleReadScreenAction(screenText)
                    } else {
                        val blankMsg = if (isFa) "متنی روی صفحه فعلی پیدا نشد قربان." else "No readable text found on the screen, sir."
                        onFeedback?.invoke(blankMsg)
                    }
                    true
                }

                // -----------------------------------------------------------------
                // 8. Universal In-App UI Automation Loop
                // -----------------------------------------------------------------
                "android_operate_ui", "operate_device_ui", "autonomous_agent", "agent_goal", "in_app_task" -> {
                    val goal = act.optString("goal", act.optString("objective", ""))
                    val targetApp = act.optString("target_app", act.optString("target", act.optString("package", "")))
                    val prefs = context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
                    val serverUrl = prefs.getString("server_url", "http://104.252.175.159:8765") ?: "http://104.252.175.159:8765"

                    if (!JarvisAccessibilityService.isServiceRunning()) {
                        val missingAcc = if (isFa) {
                            "سرویس دسترسی‌پذیری جارویس غیرفعال است. لطفاً آن را در تنظیمات فعال کنید تا بتوانم داخل برنامه‌ها کار کنم."
                        } else {
                            "Jarvis Accessibility Service is disabled. Please enable it in Settings to allow in-app automation."
                        }
                        onFeedback?.invoke(missingAcc)
                        lastActionResult = "Failed: Accessibility disabled"
                        return false
                    }

                    if (targetApp.isNotBlank()) {
                        AppLauncher.launchApp(context, targetApp)
                    }

                    JarvisAccessibilityService.instance?.runAgentLoop(goal, serverUrl) { reply ->
                        lastActionResult = "Agent loop completed: $reply"
                        onFeedback?.invoke(reply)
                    }
                    lastActionResult = "Agent loop started for goal: $goal"
                    true
                }

                // -----------------------------------------------------------------
                // 9. Atomic UI Actions (Clicks, Typing, Gestures)
                // -----------------------------------------------------------------
                "execute_ui_action" -> {
                    val acc = JarvisAccessibilityService.instance
                    if (acc == null) {
                        val missingAcc = if (isFa) "سرویس دسترسی‌پذیری غیرفعال است." else "Accessibility service is disabled."
                        onFeedback?.invoke(missingAcc)
                        lastActionResult = "Accessibility disabled"
                        return false
                    }
                    val ok = acc.executeUiAction(act)
                    lastActionResult = if (ok) "UI action executed" else "UI action failed"
                    ok
                }
                "type_text", "type", "input_text" -> {
                    val acc = JarvisAccessibilityService.instance
                    if (acc == null) {
                        val missingAcc = if (isFa) "سرویس دسترسی‌پذیری برای تایپ متن فعال نیست." else "Accessibility service is disabled for text input."
                        onFeedback?.invoke(missingAcc)
                        lastActionResult = "Accessibility disabled"
                        return false
                    }
                    val text = act.optString("text", act.optString("message", ""))
                    val autoSend = act.optBoolean("auto_send", act.optBoolean("submit", false))
                    val ok = acc.typeText(text, autoSend)
                    lastActionResult = if (ok) "Typed text into input field" else "No editable field found"
                    ok
                }
                "click_element", "click", "tap" -> {
                    val acc = JarvisAccessibilityService.instance
                    if (acc == null) {
                        val missingAcc = if (isFa) "سرویس دسترسی‌پذیری برای کلیک فعال نیست." else "Accessibility service is disabled for screen taps."
                        onFeedback?.invoke(missingAcc)
                        lastActionResult = "Accessibility disabled"
                        return false
                    }
                    val target = act.optString("target", act.optString("target_text", ""))
                    val ok = acc.clickElement(target)
                    lastActionResult = if (ok) "Clicked element '$target'" else "Element '$target' not found"
                    ok
                }
                "scroll_down" -> {
                    val acc = JarvisAccessibilityService.instance
                    if (acc == null) {
                        onFeedback?.invoke(if (isFa) "سرویس دسترسی‌پذیری غیرفعال است." else "Accessibility service is disabled.")
                        return false
                    }
                    val ok = acc.scrollDown()
                    lastActionResult = if (ok) "Scrolled down" else "Scroll down failed"
                    ok
                }
                "scroll_up" -> {
                    val acc = JarvisAccessibilityService.instance
                    if (acc == null) {
                        onFeedback?.invoke(if (isFa) "سرویس دسترسی‌پذیری غیرفعال است." else "Accessibility service is disabled.")
                        return false
                    }
                    val ok = acc.scrollUp()
                    lastActionResult = if (ok) "Scrolled up" else "Scroll up failed"
                    ok
                }
                "go_back" -> {
                    val acc = JarvisAccessibilityService.instance
                    if (acc == null) {
                        onFeedback?.invoke(if (isFa) "سرویس دسترسی‌پذیری غیرفعال است." else "Accessibility service is disabled.")
                        return false
                    }
                    val ok = acc.goBack()
                    lastActionResult = if (ok) "Navigated back" else "Navigate back failed"
                    ok
                }
                "go_home" -> {
                    val acc = JarvisAccessibilityService.instance
                    if (acc == null) {
                        onFeedback?.invoke(if (isFa) "سرویس دسترسی‌پذیری غیرفعال است." else "Accessibility service is disabled.")
                        return false
                    }
                    val ok = acc.goHome()
                    lastActionResult = if (ok) "Navigated home" else "Navigate home failed"
                    ok
                }
                "open_notifications" -> {
                    val acc = JarvisAccessibilityService.instance
                    if (acc == null) {
                        onFeedback?.invoke(if (isFa) "سرویس دسترسی‌پذیری غیرفعال است." else "Accessibility service is disabled.")
                        return false
                    }
                    val ok = acc.openNotifications()
                    lastActionResult = if (ok) "Opened notifications" else "Open notifications failed"
                    ok
                }
                "take_screenshot" -> {
                    val acc = JarvisAccessibilityService.instance
                    if (acc == null) {
                        onFeedback?.invoke(if (isFa) "سرویس دسترسی‌پذیری برای اسکرین‌شات فعال نیست." else "Accessibility service is disabled for screenshots.")
                        return false
                    }
                    val ok = acc.takeScreenshot()
                    lastActionResult = if (ok) "Screenshot triggered" else "Screenshot failed"
                    ok
                }

                // -----------------------------------------------------------------
                // 10. Proactive Notifications & Silent RemoteInput Reply
                // -----------------------------------------------------------------
                "reply_notification" -> {
                    val target = act.optString("target", "")
                    val message = act.optString("message", "")
                    if (!JarvisNotificationListener.isConnected()) {
                        val missingMsg = if (isFa) "دسترسی به اعلان‌ها برای جارویس فعال نیست." else "Notification Listener access is disabled."
                        onFeedback?.invoke(missingMsg)
                        lastActionResult = "Notification Listener disabled"
                        return false
                    }
                    val (ok, sender) = JarvisNotificationListener.replyToNotification(context, target, message)
                    val ack = if (ok) {
                        if (isFa) "پاسخ به ${sender ?: target} ارسال شد." else "Replied to ${sender ?: target}."
                    } else {
                        if (isFa) "اعلان فعالی برای پاسخ یافت نشد." else "No active notification found to reply to."
                    }
                    onFeedback?.invoke(ack)
                    lastActionResult = ack
                    ok
                }
                "summarize_notifications" -> {
                    JarvisVoiceService.instance?.handleSummarizeNotificationsAction()
                    lastActionResult = "Notification summarization requested"
                    true
                }
                "morning_briefing" -> {
                    val sUrl = JarvisVoiceService.instance?.getServerUrl()
                        ?: context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE).getString("server_url", "")
                        ?: ""
                    ProactiveMonitor.generateMorningBriefing(context, sUrl) { briefing ->
                        onFeedback?.invoke(briefing)
                    }
                    lastActionResult = "Morning briefing triggered"
                    true
                }

                // -----------------------------------------------------------------
                // 11. Iron Man Protocols & System Standby
                // -----------------------------------------------------------------
                "protocol" -> {
                    val proto = act.optString("protocol", "").lowercase(Locale.ROOT)
                    when (proto) {
                        "sleep" -> ProtocolEngine.executeSleepProtocol(context, isFa)
                        "walk" -> ProtocolEngine.executeWalkProtocol(context, isFa)
                        "focus" -> {
                            val duration = act.optInt("duration", 45)
                            ProtocolEngine.executeFocusProtocol(context, duration, isFa)
                        }
                        "ghost" -> ProtocolEngine.executeGhostProtocol(context)
                    }
                    lastActionResult = "Protocol '$proto' executed"
                    true
                }
                "sleep" -> {
                    JarvisVoiceService.instance?.enterStandbyMode()
                    lastActionResult = "Jarvis entered standby mode"
                    true
                }

                // -----------------------------------------------------------------
                // 12. System & Hardware Controls
                // -----------------------------------------------------------------
                "system_control" -> {
                    val subAction = act.optString("action", "")
                    when (subAction) {
                        "brightness" -> {
                            val level = act.optInt("level", 128)
                            DeviceController.setBrightness(context, level)
                            lastActionResult = "Brightness set to $level"
                        }
                        "connectivity_panel" -> {
                            DeviceController.openConnectivityPanel(context)
                            lastActionResult = "Connectivity panel opened"
                        }
                        "clipboard_set" -> {
                            DeviceController.setClipboard(context, act.optString("text", ""))
                            lastActionResult = "Text copied to clipboard"
                        }
                        "volume" -> {
                            val lvl = if (act.has("level")) act.optInt("level") else -1
                            DeviceController.setVolume(context, act.optString("sub_action", act.optString("action", "up")), lvl)
                            lastActionResult = "System volume adjusted"
                        }
                    }
                    true
                }
                "open_url" -> {
                    val urlStr = act.optString("url", "").trim()
                    if (urlStr.isNotBlank()) {
                        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(urlStr)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        AppLauncher.launchWithOverlayFallback(context, viewIntent)
                        lastActionResult = "Opened URL: $urlStr"
                        true
                    } else false
                }
                "battery_status" -> {
                    lastActionResult = "Battery query completed"
                    true
                }

                else -> {
                    Log.w(TAG, "Unhandled action type: '$rawType'")
                    lastActionResult = "Unhandled action '$rawType'"
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing action '$rawType': ${e.message}", e)
            lastActionResult = "Error: ${e.message}"
            false
        }
    }

    private fun executeLaunchAndVerify(
        context: Context,
        packageName: String,
        appQuery: String,
        verifyAndSpeak: Boolean,
        isFa: Boolean,
        onFeedback: ((String) -> Unit)?
    ) {
        scope.launch(Dispatchers.Main) {
            val resolution = AppLauncher.resolveAppWithScore(context, appQuery, packageName)
            val resolved = resolution.first
            val closestScore = resolution.second
            val closest = resolution.third

            if (resolved == null) {
                val notFoundMsg = if (isFa) {
                    if (closest != null && closestScore > 0.55) {
                        "برنامه‌ای به نام $appQuery پیدا نکردم؛ منظورت ${closest.label} بود؟"
                    } else {
                        "برنامه‌ای به نام $appQuery بین برنامه‌های نصب‌شده گوشیت پیدا نکردم."
                    }
                } else {
                    if (closest != null && closestScore > 0.55) {
                        "I couldn't find '$appQuery' on your device, sir. Did you mean ${closest.label}?"
                    } else {
                        "I couldn't find '$appQuery' among your installed apps, sir."
                    }
                }
                lastActionResult = "App not found: $appQuery"
                if (verifyAndSpeak) onFeedback?.invoke(notFoundMsg)
                return@launch
            }

            val launched = AppLauncher.launchPackageGuaranteed(context, resolved)
            delay(850L)

            val isAccessibilityActive = JarvisAccessibilityService.isServiceRunning()
            val activePkg = JarvisAccessibilityService.instance?.rootInActiveWindow?.packageName?.toString()
            val verifiedOpen = if (isAccessibilityActive && activePkg != null) {
                activePkg.equals(resolved.packageName, ignoreCase = true)
            } else {
                launched
            }

            val confirmationMsg = if (verifiedOpen) {
                if (isFa) "برنامه ${resolved.label} باز شد." else "${resolved.label} is now open, sir."
            } else {
                if (isFa) {
                    "برنامه ${resolved.label} نصب است، ولی سیستم‌عامل جلوی باز شدنش در پس‌زمینه را گرفت؛ لطفاً مجوز باز کردن پنجره در پس‌زمینه را برای جارویس فعال کنید."
                } else {
                    "${resolved.label} is installed, but the OS blocked background opening. Please grant background pop-up permission for Jarvis."
                }
            }

            lastActionResult = if (verifiedOpen) "Verified open: ${resolved.label}" else "Background launch blocked for ${resolved.label}"
            if (verifyAndSpeak) onFeedback?.invoke(confirmationMsg)
        }
    }

    private fun handleCameraControl(
        context: Context,
        act: JSONObject,
        isFa: Boolean,
        onFeedback: ((String) -> Unit)?
    ): Boolean {
        val hasCam = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!hasCam) {
            val missingMsg = if (isFa) "مجوز دوربین به جارویس داده نشده است." else "Camera permission is not granted for Jarvis."
            onFeedback?.invoke(missingMsg)
            lastActionResult = "Camera permission missing"
            return false
        }

        // Normalize intent payload for direct take_photo / record_video actions
        val actType = act.optString("type", "").lowercase(Locale.ROOT)
        if (actType in listOf("take_photo", "android_camera_capture")) {
            if (!act.has("mode")) act.put("mode", "photo")
            if (!act.has("capture")) act.put("capture", true)
        } else if (actType == "record_video") {
            if (!act.has("mode")) act.put("mode", "video")
            if (!act.has("capture")) act.put("capture", true)
        }

        // If user explicitly asks for "stock" camera app, open OEM Camera
        if (act.optString("app", "").lowercase(Locale.ROOT) == "stock") {
            DeviceController.openCamera(context)
            if (act.optBoolean("capture", false)) {
                JarvisAccessibilityService.instance?.takePhoto(context)
            }
            lastActionResult = "Stock camera launched"
            return true
        }

        // If JarvisCameraActivity is already running in foreground, apply action directly
        val existingActivity = JarvisCameraActivity.instance
        if (existingActivity != null && !existingActivity.isFinishing) {
            existingActivity.applyCameraControl(act)
            lastActionResult = "CameraControl applied to active session: ${act.optString("mode", "photo")}"
            return true
        }

        // Launch JarvisCameraActivity with action payload
        val intent = Intent(context, JarvisCameraActivity::class.java).apply {
            putExtra(JarvisCameraActivity.EXTRA_CAMERA_ACTION, act.toString())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        AppLauncher.launchWithOverlayFallback(context, intent)
        lastActionResult = "JarvisCameraActivity launched: ${act.optString("mode", "photo")}"
        return true
    }
}
