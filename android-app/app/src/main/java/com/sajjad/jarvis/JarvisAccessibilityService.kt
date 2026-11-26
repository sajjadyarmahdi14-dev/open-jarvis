package com.sajjad.jarvis

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.sajjad.jarvis.security.SecurityProtocolEngine
import com.sajjad.jarvis.security.SilentCameraCapturer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class JarvisAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var isReceiverRegistered = false

    private val uiActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            try {
                val actionPayload = intent.getStringExtra("action_payload")
                    ?: intent.getStringExtra("action_json")
                if (!actionPayload.isNullOrBlank()) {
                    executeUiAction(JSONObject(actionPayload))
                } else {
                    val act = intent.getStringExtra("action") ?: return
                    val json = JSONObject().apply {
                        put("action", act)
                        intent.getStringExtra("target")?.let { put("target", it) }
                        intent.getStringExtra("text")?.let { put("text", it) }
                        intent.getStringExtra("direction")?.let { put("direction", it) }
                        intent.getStringExtra("scale")?.let { put("scale", it) }
                    }
                    executeUiAction(json)
                }
            } catch (e: Exception) {
                Log.e(TAG, "uiActionReceiver error: ${e.message}", e)
            }
        }
    }

    companion object {
        private const val TAG = "JarvisAccessibility"

        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set

        fun isServiceRunning(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "JarvisAccessibilityService connected and ready for deep automation.")
        try {
            val filter = IntentFilter("com.sajjad.jarvis.EXECUTE_UI_ACTION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(uiActionReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(uiActionReceiver, filter)
            }
            isReceiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed registering uiActionReceiver: ${e.message}")
        }
    }

    private var lastLockdownActionTime = 0L
    private val volumeStateMachine = VolumeTriggerStateMachine()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingLongPressRunnable: Runnable? = null

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return super.onKeyEvent(event)

        // Never intercept hardware keys during active Iron Man security lockdown
        if (SecurityProtocolEngine.isLockdownActive) {
            return super.onKeyEvent(event)
        }

        val prefs = getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
        volumeStateMachine.doubleTapEnabled = prefs.getBoolean(
            VolumeTriggerStateMachine.PREF_STEALTH_DOUBLE_TAP,
            true
        )
        volumeStateMachine.longPressAction = VolumeTriggerStateMachine.LongPressAction.fromPref(
            prefs.getString(VolumeTriggerStateMachine.PREF_STEALTH_LONG_PRESS, "off")
        )

        val mappedAction = when (event.action) {
            KeyEvent.ACTION_DOWN -> VolumeTriggerStateMachine.ACTION_DOWN
            KeyEvent.ACTION_UP -> VolumeTriggerStateMachine.ACTION_UP
            else -> return super.onKeyEvent(event)
        }
        val mappedKey = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> VolumeTriggerStateMachine.KEYCODE_VOLUME_DOWN
            KeyEvent.KEYCODE_VOLUME_UP -> VolumeTriggerStateMachine.KEYCODE_VOLUME_UP
            else -> event.keyCode
        }

        if (mappedAction == VolumeTriggerStateMachine.ACTION_UP) {
            pendingLongPressRunnable?.let { mainHandler.removeCallbacks(it) }
            pendingLongPressRunnable = null
        }

        val actionResult = volumeStateMachine.onKeyEvent(
            keyCode = mappedKey,
            action = mappedAction,
            repeatCount = event.repeatCount,
            eventTimeMs = SystemClock.elapsedRealtime()
        )

        // Schedule 750ms hold timer on fresh Volume Down press for screen-off reliability
        if (mappedKey == VolumeTriggerStateMachine.KEYCODE_VOLUME_DOWN &&
            mappedAction == VolumeTriggerStateMachine.ACTION_DOWN &&
            event.repeatCount == 0 &&
            actionResult == VolumeTriggerStateMachine.VolumeAction.PASS_THROUGH &&
            volumeStateMachine.longPressAction != VolumeTriggerStateMachine.LongPressAction.OFF
        ) {
            val capturedDownTs = volumeStateMachine.lastDownMs
            pendingLongPressRunnable?.let { mainHandler.removeCallbacks(it) }
            val runnable = Runnable {
                val holdAction = volumeStateMachine.onLongPressTimeout(capturedDownTs)
                if (holdAction != VolumeTriggerStateMachine.VolumeAction.PASS_THROUGH) {
                    executeStealthVolumeAction(holdAction)
                }
            }
            pendingLongPressRunnable = runnable
            mainHandler.postDelayed(runnable, VolumeTriggerStateMachine.LONG_PRESS_HOLD_MS)
        }

        return when (actionResult) {
            VolumeTriggerStateMachine.VolumeAction.PASS_THROUGH -> super.onKeyEvent(event)
            VolumeTriggerStateMachine.VolumeAction.CONSUME_ONLY -> {
                pendingLongPressRunnable?.let { mainHandler.removeCallbacks(it) }
                pendingLongPressRunnable = null
                true
            }
            else -> {
                pendingLongPressRunnable?.let { mainHandler.removeCallbacks(it) }
                pendingLongPressRunnable = null
                executeStealthVolumeAction(actionResult)
                true
            }
        }
    }

    private fun executeStealthVolumeAction(action: VolumeTriggerStateMachine.VolumeAction) {
        when (action) {
            VolumeTriggerStateMachine.VolumeAction.TRIGGER_STEALTH_LISTEN -> {
                Log.i(TAG, "Stealth Hardware Trigger: Double-Tap Volume Down -> Active Listening (no wake-word)")
                fireHapticPulse(35L)
                val svc = JarvisVoiceService.instance
                if (svc != null) {
                    svc.triggerStealthListen()
                } else {
                    try {
                        val intent = Intent(applicationContext, JarvisVoiceService::class.java).apply {
                            this.action = JarvisVoiceService.ACTION_STEALTH_LISTEN
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed launching stealth voice listener: ${e.message}")
                    }
                }
            }
            VolumeTriggerStateMachine.VolumeAction.TRIGGER_LONG_PRESS_SHUTTER -> {
                Log.i(TAG, "Stealth Hardware Trigger: Long-Press Volume Down -> Silent Background Camera2 Shutter")
                fireHapticPulse(45L)
                serviceScope.launch(Dispatchers.IO) {
                    val photoBytes = SilentCameraCapturer.capturePhoto(
                        applicationContext,
                        CameraCharacteristics.LENS_FACING_BACK
                    )
                    if (photoBytes != null) {
                        saveStealthPhotoToGallery(photoBytes)
                        fireHapticPulse(25L)
                    }
                }
            }
            VolumeTriggerStateMachine.VolumeAction.TRIGGER_LONG_PRESS_MEMO -> {
                Log.i(TAG, "Stealth Hardware Trigger: Long-Press Volume Down -> Quick Voice Memo Toggle")
                fireHapticPulse(45L)
                val isFa = Locale.getDefault().language == "fa"
                if (VoiceRecorderEngine.isRecording) {
                    VoiceRecorderEngine.stopRecording(applicationContext, isFa = isFa)
                } else {
                    VoiceRecorderEngine.startRecording(applicationContext, isFa = isFa)
                }
            }
            else -> {}
        }
    }

    private fun fireHapticPulse(durationMs: Long) {
        try {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vib?.vibrate(durationMs)
            }
        } catch (_: Exception) {}
    }

    private fun saveStealthPhotoToGallery(jpegBytes: ByteArray) {
        try {
            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())
            val fileName = "JARVIS_STEALTH_$ts.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Jarvis")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
            contentResolver.openOutputStream(uri)?.use { out -> out.write(jpegBytes) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            }
            Log.i(TAG, "Silent background photo saved to DCIM/Jarvis/$fileName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving stealth photo to gallery: ${e.message}")
        }
    }

    /**
     * Background Clipboard Privacy Fallback: extracts currently focused/selected text from active window.
     */
    fun extractClipboardFallbackText(): String? {
        val root = rootInActiveWindow ?: return null
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditableNode(root)
        if (focused != null) {
            val txt = focused.text?.toString()?.trim()
            if (!txt.isNullOrBlank()) {
                val start = focused.textSelectionStart
                val end = focused.textSelectionEnd
                if (start in 0 until txt.length && end in (start + 1)..txt.length) {
                    return txt.substring(start, end).trim()
                }
                return txt
            }
        }
        return null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Anti-Power-Off & Anti-QuickSettings Tamper Shield during Iron Man Lockdown
        if (SecurityProtocolEngine.isLockdownActive) {
            handleLockdownTamperShield(event)
        }
    }

    private fun handleLockdownTamperShield(event: AccessibilityEvent) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLockdownActionTime < 350L) return

        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return
        }

        val pkg = event.packageName?.toString() ?: ""
        val cls = event.className?.toString() ?: ""

        val texts = mutableListOf<String>()
        event.text?.forEach { texts.add(it.toString()) }
        event.contentDescription?.let { texts.add(it.toString()) }
        val textBlob = texts.joinToString(" ").lowercase(Locale.ROOT)

        val isSystemUi = pkg.contains("systemui", ignoreCase = true) || pkg.contains("android", ignoreCase = true)

        val isPowerMenu = cls.contains("GlobalActions", ignoreCase = true) ||
                cls.contains("PowerDialog", ignoreCase = true) ||
                cls.contains("Shutdown", ignoreCase = true) ||
                textBlob.contains("power off") ||
                textBlob.contains("restart") ||
                textBlob.contains("reboot") ||
                textBlob.contains("خاموش") ||
                textBlob.contains("راه‌اندازی مجدد") ||
                textBlob.contains("ریستارت")

        val isQuickSettings = cls.contains("NotificationPanelView", ignoreCase = true) ||
                cls.contains("NotificationShade", ignoreCase = true) ||
                cls.contains("QSPanel", ignoreCase = true) ||
                cls.contains("QuickStatusBar", ignoreCase = true) ||
                cls.contains("QSContainer", ignoreCase = true) ||
                textBlob.contains("airplane mode") ||
                textBlob.contains("flight mode") ||
                textBlob.contains("حالت پرواز") ||
                textBlob.contains("حالت هواپیما") ||
                textBlob.contains("quick settings") ||
                textBlob.contains("تنظیمات سریع")

        if (isPowerMenu || (isSystemUi && isQuickSettings)) {
            Log.w(TAG, "Tamper Shield: Slamming shut power menu / quick settings during lockdown!")
            lastLockdownActionTime = now
            performGlobalAction(GLOBAL_ACTION_BACK)
            serviceScope.launch {
                delay(100L)
                performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            }
        }
    }

    /**
     * Unlocks device screen by swiping up and entering PIN via accessibility node injection or digit keypad taps.
     */
    fun unlockWithPin(pin: String): Boolean {
        val dm = resources.displayMetrics
        val startY = dm.heightPixels * 0.82f
        val endY = dm.heightPixels * 0.20f
        val centerX = dm.widthPixels * 0.50f

        val path = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 250)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
        Log.i(TAG, "unlockWithPin: dispatched swipe-up gesture")

        if (pin.isBlank()) return true

        serviceScope.launch {
            delay(500L)
            val root = rootInActiveWindow

            // 1. Try finding editable text field (Password / PIN input)
            val inputNode = findEditableNode(root)
            if (inputNode != null) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pin)
                }
                inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                delay(200L)
                findNodeByPredicate(rootInActiveWindow) { n ->
                    val t = (n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")
                    t.equals("enter", ignoreCase = true) || t.equals("ok", ignoreCase = true) ||
                            t.equals("done", ignoreCase = true) || t.contains("ورود") || t.contains("تایید")
                }?.let { clickNodeOrParent(it) }
                return@launch
            }

            // 2. Fallback: Digit keypad buttons
            for (ch in pin) {
                val digitStr = ch.toString()
                val digitNode = findNodeByPredicate(rootInActiveWindow) { n ->
                    val t = n.text?.toString()?.trim() ?: ""
                    val d = n.contentDescription?.toString()?.trim() ?: ""
                    t == digitStr || d == digitStr || d.startsWith(digitStr)
                }
                if (digitNode != null) {
                    clickNodeOrParent(digitNode)
                }
                delay(120L)
            }
            delay(200L)
            findNodeByPredicate(rootInActiveWindow) { n ->
                val t = (n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")
                t.equals("enter", ignoreCase = true) || t.equals("ok", ignoreCase = true) ||
                        t.equals("done", ignoreCase = true) || t.contains("ورود") || t.contains("تایید")
            }?.let { clickNodeOrParent(it) }
        }
        return true
    }

    /**
     * Toggles Wi-Fi or Mobile Data via Connectivity Panel automation.
     */
    fun toggleQuickSettingOrConnectivity(target: String): Boolean {
        try {
            DeviceController.openConnectivityPanel(this)
            serviceScope.launch {
                delay(600L)
                val root = rootInActiveWindow
                val toggle = findNodeByPredicate(root) { node ->
                    val text = (node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: "")
                    if (target == "wifi") {
                        text.contains("Wi‑Fi", ignoreCase = true) ||
                                text.contains("Wi-Fi", ignoreCase = true) ||
                                text.contains("وای فای", ignoreCase = true) ||
                                text.contains("وای‌فای", ignoreCase = true)
                    } else {
                        text.contains("Mobile data", ignoreCase = true) ||
                                text.contains("داده همراه", ignoreCase = true) ||
                                text.contains("اینترنت همراه", ignoreCase = true) ||
                                text.contains("دیتا", ignoreCase = true)
                    }
                }
                if (toggle != null) {
                    clickNodeOrParent(toggle)
                    Log.i(TAG, "toggleQuickSettingOrConnectivity: clicked toggle for $target")
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "toggleQuickSettingOrConnectivity failed: ${e.message}")
            return false
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "JarvisAccessibilityService interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance == this) {
            instance = null
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(uiActionReceiver)
            } catch (_: Exception) {}
            isReceiverRegistered = false
        }
        if (instance == this) {
            instance = null
        }
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // 1. Text Input & Auto-Send
    // -------------------------------------------------------------------------

    fun typeText(text: String, autoSend: Boolean = false): Boolean {
        val cleanText = UtteranceStitcher.sanitizeLiteralText(text)
        val root = rootInActiveWindow ?: return false
        val targetNode = findEditableNode(root) ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        if (targetNode == null) {
            Log.w(TAG, "No editable or focused input node found in active window.")
            return false
        }

        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cleanText)
        }
        val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        Log.i(TAG, "typeText performed: success=$success, text='$cleanText'")

        if (autoSend) {
            serviceScope.launch {
                delay(350)
                clickSendButton()
            }
        }
        return success
    }

    fun findEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val cls = node.className?.toString() ?: ""
        if (node.isEditable || cls.contains("EditText", ignoreCase = true)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableNode(child)
            if (found != null) return found
        }
        return null
    }

    fun clickSendButton(): Boolean {
        val root = rootInActiveWindow ?: return false
        val sendKeywords = listOf("send", "ارسال", "send_button", "send_btn", "send button", "ic_send")

        val match = findNodeByPredicate(root) { n ->
            val text = (n.text?.toString() ?: "").lowercase(Locale.ROOT)
            val desc = (n.contentDescription?.toString() ?: "").lowercase(Locale.ROOT)
            val viewId = (n.viewIdResourceName ?: "").lowercase(Locale.ROOT)
            sendKeywords.any { k -> desc.contains(k) || viewId.contains(k) || text == k }
        }

        if (match != null) {
            return clickNodeOrParent(match)
        }
        return false
    }

    fun openTelegramSavedMessages(): Boolean {
        val root = rootInActiveWindow ?: return false
        // 1. Look for direct "Saved Messages" / "پیام‌های ذخیره شده"
        val matchDirect = findNodeByPredicate(root) { n ->
            val text = (n.text?.toString() ?: "").lowercase(Locale.ROOT)
            val desc = (n.contentDescription?.toString() ?: "").lowercase(Locale.ROOT)
            text.contains("saved messages") || text.contains("پیام‌های ذخیره") ||
                    desc.contains("saved messages") || desc.contains("پیام‌های ذخیره")
        }
        if (matchDirect != null) {
            return clickNodeOrParent(matchDirect)
        }

        // 2. Open drawer / menu if available
        val menuBtn = findNodeByPredicate(root) { n ->
            val desc = (n.contentDescription?.toString() ?: "").lowercase(Locale.ROOT)
            val text = (n.text?.toString() ?: "").lowercase(Locale.ROOT)
            desc.contains("open navigation") || desc.contains("menu") || text.contains("منو")
        }
        if (menuBtn != null) {
            clickNodeOrParent(menuBtn)
            Thread.sleep(300)
            val rootDrawer = rootInActiveWindow
            if (rootDrawer != null) {
                val matchInDrawer = findNodeByPredicate(rootDrawer) { n ->
                    val text = (n.text?.toString() ?: "").lowercase(Locale.ROOT)
                    text.contains("saved messages") || text.contains("پیام‌های ذخیره")
                }
                if (matchInDrawer != null) {
                    return clickNodeOrParent(matchInDrawer)
                }
            }
        }
        return false
    }

    suspend fun sendVerifiedMessage(targetApp: String, literalMessage: String): Pair<Boolean, String> = withContext(Dispatchers.Main) {
        val cleanText = UtteranceStitcher.sanitizeLiteralText(literalMessage)
        var targetEditNode: AccessibilityNodeInfo? = null

        // Poll for up to 3.0 seconds (15 * 200ms) for an active EditText
        for (i in 0 until 15) {
            delay(200)
            val root = rootInActiveWindow ?: continue
            targetEditNode = findEditableNode(root) ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (targetEditNode != null) break
        }

        if (targetEditNode == null) {
            Log.w(TAG, "sendVerifiedMessage: No editable chat input found in $targetApp")
            return@withContext Pair(false, "Chat input box not found")
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cleanText)
        }
        val textSet = targetEditNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!textSet) {
            Log.w(TAG, "sendVerifiedMessage: ACTION_SET_TEXT failed in $targetApp")
            return@withContext Pair(false, "Could not set message text in input")
        }
        delay(350)

        // Locate and click the send button
        val sendClicked = clickSendButton()
        if (!sendClicked) {
            Log.w(TAG, "sendVerifiedMessage: Send button could not be clicked in $targetApp")
            return@withContext Pair(false, "Send button not found or click failed")
        }

        // Wait 400ms and verify the EditText is cleared
        delay(400)
        val rootAfter = rootInActiveWindow
        val editAfter = if (rootAfter != null) findEditableNode(rootAfter) else null
        val currentText = editAfter?.text?.toString() ?: ""

        if (currentText.isBlank() || !currentText.contains(cleanText)) {
            Log.i(TAG, "sendVerifiedMessage: Send verified! Input box cleared.")
            return@withContext Pair(true, "Message verified sent")
        } else {
            Log.w(TAG, "sendVerifiedMessage: Input box still holds text after send click!")
            return@withContext Pair(false, "Send click did not clear the input box")
        }
    }

    suspend fun verifyAndSendEmailCompose(expectedBody: String): Boolean = withContext(Dispatchers.Main) {
        delay(600)
        val root = rootInActiveWindow ?: return@withContext false

        // Check if body is empty in compose screen, fill if needed
        val editNodes = mutableListOf<AccessibilityNodeInfo>()
        fun collectEdit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.isEditable || node.className?.toString()?.contains("EditText", ignoreCase = true) == true) {
                editNodes.add(node)
            }
            for (i in 0 until node.childCount) {
                collectEdit(node.getChild(i))
            }
        }
        collectEdit(root)

        // Usually last editable node in Email compose is the message body
        if (editNodes.isNotEmpty()) {
            val bodyNode = editNodes.last()
            val currentText = bodyNode.text?.toString() ?: ""
            if (currentText.isBlank() && expectedBody.isNotBlank()) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, expectedBody)
                }
                bodyNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                delay(300)
            }
        }

        // Click Send button (top action bar in Gmail/Email)
        val sendBtn = findNodeByPredicate(root) { n ->
            val desc = (n.contentDescription?.toString() ?: "").lowercase(Locale.ROOT)
            val text = (n.text?.toString() ?: "").lowercase(Locale.ROOT)
            val viewId = (n.viewIdResourceName ?: "").lowercase(Locale.ROOT)
            desc.contains("send") || desc.contains("ارسال") || viewId.contains("send_action") || viewId.contains("send") || text == "send" || text == "ارسال"
        }
        if (sendBtn != null) {
            val clicked = clickNodeOrParent(sendBtn)
            Log.i(TAG, "verifyAndSendEmailCompose: Clicked send button ($clicked)")
            return@withContext clicked
        }
        return@withContext false
    }

    // -------------------------------------------------------------------------
    // 2. Click Elements by Target Text
    // -------------------------------------------------------------------------

    fun clickElement(targetText: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val cleanTarget = targetText.trim().lowercase(Locale.ROOT)
        if (cleanTarget.isBlank()) return false

        val match = findNodeByPredicate(root) { n ->
            val text = (n.text?.toString() ?: "").lowercase(Locale.ROOT)
            val desc = (n.contentDescription?.toString() ?: "").lowercase(Locale.ROOT)
            text.contains(cleanTarget) || desc.contains(cleanTarget)
        }

        if (match != null) {
            val clicked = clickNodeOrParent(match)
            Log.i(TAG, "clickElement '$targetText' clicked=$clicked")
            return clicked
        }
        Log.w(TAG, "clickElement could not find element matching '$targetText'")
        return false
    }

    fun clickPlayableMediaOrPlayButton(): Boolean {
        val root = rootInActiveWindow ?: return false
        val playKeywords = listOf(
            "play", "shuffle", "پخش", "شروع", "play_btn", "play_button", "play button",
            "btn_play", "action_play", "start", "watch", "تماشا", "پلی"
        )

        // 1. Explicit play or shuffle button
        val matchPlayBtn = findNodeByPredicate(root) { n ->
            val text = (n.text?.toString() ?: "").lowercase(Locale.ROOT)
            val desc = (n.contentDescription?.toString() ?: "").lowercase(Locale.ROOT)
            val viewId = (n.viewIdResourceName ?: "").lowercase(Locale.ROOT)
            playKeywords.any { k -> text == k || desc == k || viewId.contains(k) }
        }
        if (matchPlayBtn != null) {
            val clicked = clickNodeOrParent(matchPlayBtn)
            if (clicked) {
                Log.i(TAG, "clickPlayableMediaOrPlayButton clicked explicit play/shuffle button")
                return true
            }
        }

        // 2. Playable media row / video beneath search bar
        val matchResultItem = findNodeByPredicate(root) { n ->
            if (!n.isClickable && n.parent?.isClickable != true) return@findNodeByPredicate false
            val text = (n.text?.toString() ?: "").lowercase(Locale.ROOT)
            val desc = (n.contentDescription?.toString() ?: "").lowercase(Locale.ROOT)
            val viewId = (n.viewIdResourceName ?: "").lowercase(Locale.ROOT)
            val cls = n.className?.toString() ?: ""
            if (cls.contains("EditText", ignoreCase = true) || cls.contains("SearchView", ignoreCase = true)) return@findNodeByPredicate false
            if (viewId.contains("search") || viewId.contains("filter") || viewId.contains("chip") || viewId.contains("tab")) return@findNodeByPredicate false
            if (text in listOf("all", "videos", "shorts", "playlists", "music", "همه", "ویدیوها", "آهنگ‌ها")) return@findNodeByPredicate false

            viewId.contains("item") || viewId.contains("row") || viewId.contains("video") || viewId.contains("track") ||
                    viewId.contains("song") || desc.contains("video") || desc.contains("play") || (text.isNotBlank() && n.isClickable)
        }

        if (matchResultItem != null) {
            val clicked = clickNodeOrParent(matchResultItem)
            if (clicked) {
                Log.i(TAG, "clickPlayableMediaOrPlayButton clicked first playable media result item")
                return true
            }
        }
        return false
    }

    fun tapAtCoordinates(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = dispatchGesture(gesture, null, null)
        Log.i(TAG, "tapAtCoordinates ($x, $y) dispatched=$dispatched")
        return dispatched
    }

    fun clickNodeOrParent(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        while (current != null) {
            if (current.isClickable) {
                val ok = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (ok) return true
                // Fallback: If performAction returned false on a clickable node, dispatch gesture tap at center
                val rect = Rect()
                current.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    val tapped = tapAtCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
                    if (tapped) return true
                }
            }
            current = current.parent
        }
        // If not clickable flag, try direct click then center tap as fallback
        val directOk = node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        if (directOk) return true
        if (node != null) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) {
                return tapAtCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
            }
        }
        return false
    }

    fun findNodeByPredicate(
        node: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByPredicate(child, predicate)
            if (found != null) return found
        }
        return null
    }

    // -------------------------------------------------------------------------
    // 3. Automated Photo Capture (Shutter Click)
    // -------------------------------------------------------------------------

    fun takePhoto(context: Context): Boolean {
        // ALWAYS launch native camera app fully via DeviceController (OEM app with auto-save to gallery)
        DeviceController.openCamera(context)

        serviceScope.launch {
            // Wait 1500ms for camera app to launch and autofocus
            delay(1500)
            val root = rootInActiveWindow ?: return@launch
            val shutterKeywords = listOf("shutter", "take picture", "capture", "عکس", "شاتر", "camera_shutter")

            var shutterNode = findNodeByPredicate(root) { n ->
                val desc = (n.contentDescription?.toString() ?: "").lowercase(Locale.ROOT)
                val text = (n.text?.toString() ?: "").lowercase(Locale.ROOT)
                val id = (n.viewIdResourceName ?: "").lowercase(Locale.ROOT)
                shutterKeywords.any { k -> desc.contains(k) || text.contains(k) || id.contains(k) }
            }

            // Fallback: If no explicit shutter label found, search for circular/prominent clickable button at screen bottom
            if (shutterNode == null) {
                val displayMetrics = resources.displayMetrics
                val bottomThreshold = (displayMetrics.heightPixels * 0.70).toInt()
                shutterNode = findNodeByPredicate(root) { n ->
                    if (!n.isClickable) return@findNodeByPredicate false
                    val rect = Rect()
                    n.getBoundsInScreen(rect)
                    rect.top >= bottomThreshold && rect.width() >= 120 && rect.height() >= 120
                }
            }

            if (shutterNode != null) {
                val clicked = clickNodeOrParent(shutterNode)
                Log.i(TAG, "Shutter button clicked: $clicked")
            } else {
                Log.w(TAG, "Could not find camera shutter button.")
            }
        }
        return true
    }

    // -------------------------------------------------------------------------
    // 4. Screen Reading (Extract On-Screen Text)
    // -------------------------------------------------------------------------

    fun readScreen(): String {
        val root = rootInActiveWindow ?: return "Screen content is currently empty or protected."
        val textList = mutableListOf<String>()
        collectScreenText(root, textList)
        val fullContent = textList.distinct().joinToString("\n")
        return if (fullContent.isBlank()) "No visible text detected on screen." else fullContent
    }

    private fun collectScreenText(node: AccessibilityNodeInfo?, outList: MutableList<String>) {
        if (node == null || !node.isVisibleToUser) return

        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()

        if (!text.isNullOrEmpty() && text.length > 1) {
            outList.add(text)
        }
        if (!desc.isNullOrEmpty() && desc != text && desc.length > 1) {
            outList.add(desc)
        }

        for (i in 0 until node.childCount) {
            collectScreenText(node.getChild(i), outList)
        }
    }

    // -------------------------------------------------------------------------
    // 5. Global Actions & Gestures
    // -------------------------------------------------------------------------

    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    fun takeScreenshot(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        } else {
            false
        }
    }

    /**
     * Part B: Zero-Popup Screen Vision (Android 11+ / API 30+)
     *
     * Captures screenshot silently using AccessibilityService.takeScreenshot with ZERO popups,
     * hides the floating HUD for 100ms beforehand, converts HardwareBuffer to software Bitmap,
     * downscales to 1024px, compresses to JPEG 75%, and encodes to Base64.
     */
    fun captureScreenBase64(onResult: (String?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "captureScreenBase64 requires Android 11+ (API 30+)")
            onResult(null)
            return
        }

        serviceScope.launch(Dispatchers.Main) {
            // Temporarily hide Floating HUD for 100ms before taking screenshot
            JarvisOverlayManager.instance?.setTemporarilyHidden(true)
            delay(100)

            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        JarvisOverlayManager.instance?.setTemporarilyHidden(false)
                        val hardwareBuffer = screenshotResult.hardwareBuffer
                        val colorSpace = screenshotResult.colorSpace

                        serviceScope.launch(Dispatchers.Default) {
                            val base64 = try {
                                val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                                    ?.copy(Bitmap.Config.ARGB_8888, false)
                                if (bitmap != null) {
                                    VisionEngine.processAndEncodeBitmap(bitmap).also {
                                        bitmap.recycle()
                                    }
                                } else {
                                    null
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "HardwareBuffer conversion error: ${e.message}")
                                null
                            } finally {
                                try { hardwareBuffer.close() } catch (_: Exception) {}
                            }

                            withContext(Dispatchers.Main) {
                                onResult(base64)
                            }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        JarvisOverlayManager.instance?.setTemporarilyHidden(false)
                        val errStr = when (errorCode) {
                            1 -> "INTERNAL_ERROR"
                            2 -> "NO_END_OF_FRAME"
                            3 -> "INTERVAL_TIME_SHORT"
                            4 -> "INVALID_DISPLAY"
                            5 -> "INVALID_WINDOW"
                            else -> "UNKNOWN"
                        }
                        Log.w(TAG, "takeScreenshot failed with error code: $errorCode ($errStr)")
                        onResult(null)
                    }
                })
            } catch (e: Exception) {
                JarvisOverlayManager.instance?.setTemporarilyHidden(false)
                Log.e(TAG, "captureScreenBase64 exception: ${e.message}", e)
                onResult(null)
            }
        }
    }

    suspend fun captureScreenBase64Suspend(): String? = suspendCancellableCoroutine { cont ->
        captureScreenBase64 { b64 ->
            if (cont.isActive) cont.resume(b64)
        }
    }

    fun scrollDown(): Boolean = performScrollGesture(scrollDown = true)

    fun scrollUp(): Boolean = performScrollGesture(scrollDown = false)

    private fun performScrollGesture(scrollDown: Boolean): Boolean {
        val displayMetrics = resources.displayMetrics
        val centerX = (displayMetrics.widthPixels / 2).toFloat()
        val startY = if (scrollDown) (displayMetrics.heightPixels * 0.75f) else (displayMetrics.heightPixels * 0.25f)
        val endY = if (scrollDown) (displayMetrics.heightPixels * 0.25f) else (displayMetrics.heightPixels * 0.75f)

        val path = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    // -------------------------------------------------------------------------
    // 6. Live Semantic Screen Serializer & Closed-Loop Autonomous UI Execution
    // -------------------------------------------------------------------------

    fun serializeCurrentWindow(): Pair<List<String>, Map<Int, AccessibilityNodeInfo>> {
        val root = rootInActiveWindow ?: return Pair(emptyList(), emptyMap())
        val nodeMap = mutableMapOf<Int, AccessibilityNodeInfo>()
        val serialized = mutableListOf<String>()
        var counter = 0

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null || !node.isVisibleToUser) return

            val text = node.text?.toString()?.replace("\n", " ")?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.replace("\n", " ")?.trim() ?: ""
            val isClickable = node.isClickable
            val isEditable = node.isEditable
            val isCheckable = node.isCheckable
            val isChecked = node.isChecked
            val className = node.className?.toString()?.substringAfterLast(".") ?: "View"

            val isInformative = text.isNotEmpty() || desc.isNotEmpty() || isClickable || isEditable || isCheckable
            if (isInformative) {
                val id = counter++
                nodeMap[id] = node
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val line = "[$id] $className | text=\"$text\" | desc=\"$desc\" | clickable=$isClickable | editable=$isEditable | checked=$isChecked | bounds=(${rect.left},${rect.top})"
                serialized.add(line)
            }

            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }

        traverse(root)
        return Pair(serialized, nodeMap)
    }

    fun executeAtomicAction(
        actionType: String,
        params: JSONObject,
        nodeMap: Map<Int, AccessibilityNodeInfo>
    ): Boolean {
        Log.i(TAG, "executeAtomicAction: type=$actionType, params=$params")
        return when (actionType.lowercase(Locale.ROOT)) {
            "tap", "click" -> {
                val nodeId = params.optInt("node_id", -1)
                val node = if (nodeId >= 0) nodeMap[nodeId] else null
                if (node != null) {
                    clickNodeOrParent(node)
                } else {
                    val targetText = params.optString("target_text", params.optString("target", ""))
                    if (targetText.isNotBlank()) clickElement(targetText) else false
                }
            }
            "input_text", "type" -> {
                val rawText = params.optString("text", "")
                val cleanText = UtteranceStitcher.sanitizeLiteralText(rawText)
                val submit = params.optBoolean("submit", true)
                val nodeId = params.optInt("node_id", -1)
                val node = if (nodeId >= 0) nodeMap[nodeId] else findEditableNode(rootInActiveWindow)
                if (node != null) {
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cleanText)
                    }
                    val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    if (submit) {
                        serviceScope.launch {
                            delay(350)
                            clickSendButton()
                        }
                    }
                    ok
                } else {
                    typeText(cleanText, submit)
                }
            }
            "scroll" -> {
                val dir = params.optString("direction", "down").lowercase(Locale.ROOT)
                if (dir == "up") scrollUp() else scrollDown()
            }
            "wait_and_tap" -> {
                val delayMs = params.optLong("delay_ms", 1500L)
                val nodeId = params.optInt("node_id", -1)
                serviceScope.launch {
                    delay(delayMs)
                    val target = if (nodeId >= 0) nodeMap[nodeId] else null
                    if (target != null) {
                        clickNodeOrParent(target)
                    } else {
                        clickSendButton()
                    }
                }
                true
            }
            "media_key" -> {
                val key = params.optString("key", "PLAY").uppercase(Locale.ROOT)
                when (key) {
                    "PLAY" -> DeviceController.playMusic(this)
                    "PAUSE" -> DeviceController.pauseMusic(this)
                    "NEXT" -> DeviceController.nextTrack(this)
                    "PREVIOUS" -> DeviceController.previousTrack(this)
                }
                true
            }
            "local_audio_uri" -> {
                val query = params.optString("query", "")
                if (query.isNotBlank()) {
                    DeviceController.playSong(this, query)
                }
                true
            }
            "tap_coords", "tap_coordinates", "click_coords" -> {
                val displayMetrics = resources.displayMetrics
                val xNorm = params.optDouble("x_norm", params.optDouble("x", -1.0)).toFloat()
                val yNorm = params.optDouble("y_norm", params.optDouble("y", -1.0)).toFloat()
                if (xNorm >= 0f && yNorm >= 0f) {
                    val px = if (xNorm <= 1.0f) xNorm * displayMetrics.widthPixels else xNorm
                    val py = if (yNorm <= 1.0f) yNorm * displayMetrics.heightPixels else yNorm
                    tapAtCoordinates(px, py)
                } else {
                    false
                }
            }
            "back" -> goBack()
            "home" -> goHome()
            "done" -> true
            else -> false
        }
    }

    private fun postAgentStep(serverUrl: String, payload: JSONObject): JSONObject? {
        return try {
            val cleanUrl = serverUrl.trim().removeSuffix("/")
            val url = URL("$cleanUrl/api/agent/step")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 6000
            conn.readTimeout = 12000
            conn.doOutput = true

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
            val code = conn.responseCode
            if (code in 200..299) {
                val respStr = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                JSONObject(respStr)
            } else {
                Log.w(TAG, "postAgentStep HTTP $code")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "postAgentStep error: ${e.message}")
            null
        }
    }

    @Volatile
    var lastDumpedNodeMap: Map<String, AccessibilityNodeInfo> = emptyMap()
        private set

    // -------------------------------------------------------------------------
    // 6. The "Eyes": Universal UI Dumper (JSON Tree)
    // -------------------------------------------------------------------------

    fun dumpUiTree(): String {
        val root = rootInActiveWindow ?: return "[]"
        val nodeMap = mutableMapOf<String, AccessibilityNodeInfo>()
        val array = JSONArray()
        var counter = 0

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null || !node.isVisibleToUser) return

            val rawText = node.text?.toString()?.replace("\n", " ")?.trim() ?: ""
            val rawDesc = node.contentDescription?.toString()?.replace("\n", " ")?.trim() ?: ""
            val text = if (rawText.isNotBlank()) rawText else rawDesc
            val isClickable = node.isClickable
            val isEditable = node.isEditable || (node.className?.toString()?.contains("EditText", ignoreCase = true) == true)
            val isScrollable = node.isScrollable
            val className = node.className?.toString() ?: "android.view.View"

            val viewId = node.viewIdResourceName?.trim()
            val nodeId = if (!viewId.isNullOrBlank()) viewId else "node_$counter"

            nodeMap[nodeId] = node
            nodeMap["node_$counter"] = node
            nodeMap["$counter"] = node
            if (!viewId.isNullOrBlank()) {
                val simpleId = viewId.substringAfterLast("/")
                nodeMap[simpleId] = node
            }
            if (rawText.isNotBlank()) {
                nodeMap[rawText.lowercase(Locale.ROOT)] = node
            }
            if (rawDesc.isNotBlank() && rawDesc != rawText) {
                nodeMap[rawDesc.lowercase(Locale.ROOT)] = node
            }

            val rect = Rect()
            node.getBoundsInScreen(rect)

            val boundsObj = JSONObject().apply {
                put("left", rect.left)
                put("top", rect.top)
                put("right", rect.right)
                put("bottom", rect.bottom)
                put("x", rect.left)
                put("y", rect.top)
                put("width", rect.width())
                put("height", rect.height())
            }

            val obj = JSONObject().apply {
                put("node_id", nodeId)
                put("text", text)
                put("contentDescription", rawDesc)
                put("className", className)
                put("isClickable", isClickable)
                put("isEditable", isEditable)
                put("isScrollable", isScrollable)
                put("boundsInScreen", boundsObj)
                // Backwards-compatible fields
                put("class", className.substringAfterLast("."))
                put("clickable", isClickable)
                put("editable", isEditable)
                put("checked", node.isChecked)
                put("x", rect.left)
                put("y", rect.top)
            }
            array.put(obj)
            counter++

            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }

        traverse(root)
        lastDumpedNodeMap = nodeMap
        return array.toString()
    }

    fun dumpUiTreeArray(): Pair<JSONArray, Map<String, AccessibilityNodeInfo>> {
        val jsonStr = dumpUiTree()
        return Pair(JSONArray(jsonStr), lastDumpedNodeMap)
    }

    // -------------------------------------------------------------------------
    // 7. The "Hands": Generic Gesture & Action Executor
    // -------------------------------------------------------------------------

    fun swipe(direction: String): Boolean {
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels.toFloat()
        val height = displayMetrics.heightPixels.toFloat()

        val startX: Float
        val startY: Float
        val endX: Float
        val endY: Float

        when (direction.lowercase(Locale.ROOT)) {
            "left" -> {
                // Swipe right-to-left across viewfinder / tabs
                startX = width * 0.85f
                startY = height * 0.50f
                endX = width * 0.15f
                endY = height * 0.50f
            }
            "right" -> {
                // Swipe left-to-right
                startX = width * 0.15f
                startY = height * 0.50f
                endX = width * 0.85f
                endY = height * 0.50f
            }
            "up" -> {
                // Swipe bottom-to-top (scroll down)
                startX = width * 0.50f
                startY = height * 0.75f
                endX = width * 0.50f
                endY = height * 0.25f
            }
            "down" -> {
                // Swipe top-to-bottom (scroll up)
                startX = width * 0.50f
                startY = height * 0.25f
                endX = width * 0.50f
                endY = height * 0.75f
            }
            else -> {
                startX = width * 0.50f
                startY = height * 0.75f
                endX = width * 0.50f
                endY = height * 0.25f
            }
        }

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 350)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = dispatchGesture(gesture, null, null)
        Log.i(TAG, "Dispatched swipe '$direction': $dispatched")
        return dispatched
    }

    fun pinch(scale: String): Boolean {
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels.toFloat()
        val height = displayMetrics.heightPixels.toFloat()
        val centerX = width * 0.5f
        val centerY = height * 0.5f

        val path1 = Path()
        val path2 = Path()

        val isZoomIn = scale.lowercase(Locale.ROOT) in listOf("out", "zoom_in", "in_to_out", "expand")
        if (isZoomIn) {
            // Pinch outward (zoom in)
            path1.moveTo(centerX - 80f, centerY - 80f)
            path1.lineTo(centerX - 350f, centerY - 350f)

            path2.moveTo(centerX + 80f, centerY + 80f)
            path2.lineTo(centerX + 350f, centerY + 350f)
        } else {
            // Pinch inward (zoom out)
            path1.moveTo(centerX - 350f, centerY - 350f)
            path1.lineTo(centerX - 80f, centerY - 80f)

            path2.moveTo(centerX + 350f, centerY + 350f)
            path2.lineTo(centerX + 80f, centerY + 80f)
        }

        val stroke1 = GestureDescription.StrokeDescription(path1, 0, 400)
        val stroke2 = GestureDescription.StrokeDescription(path2, 0, 400)
        val gesture = GestureDescription.Builder().addStroke(stroke1).addStroke(stroke2).build()
        val dispatched = dispatchGesture(gesture, null, null)
        Log.i(TAG, "Dispatched pinch '$scale': $dispatched")
        return dispatched
    }

    fun clickTarget(target: String): Boolean {
        val cleanTarget = target.trim()
        if (cleanTarget.isBlank()) return false

        // 1. Try finding in last dumped node map
        var node = lastDumpedNodeMap[cleanTarget]
            ?: lastDumpedNodeMap["node_$cleanTarget"]
            ?: lastDumpedNodeMap[cleanTarget.lowercase(Locale.ROOT)]

        // 2. If not found in map, find by viewIdResourceName or text in current window
        if (node == null) {
            val root = rootInActiveWindow
            if (root != null) {
                node = findNodeByPredicate(root) { n ->
                    val id = n.viewIdResourceName ?: ""
                    val t = n.text?.toString() ?: ""
                    val d = n.contentDescription?.toString() ?: ""
                    id.equals(cleanTarget, ignoreCase = true) ||
                    id.endsWith("/$cleanTarget", ignoreCase = true) ||
                    t.equals(cleanTarget, ignoreCase = true) ||
                    d.equals(cleanTarget, ignoreCase = true) ||
                    t.contains(cleanTarget, ignoreCase = true) ||
                    d.contains(cleanTarget, ignoreCase = true)
                }
            }
        }

        if (node != null) {
            return clickNodeOrParent(node)
        }

        // 3. Fallback to generic clickElement
        return clickElement(cleanTarget)
    }

    fun typeTarget(target: String, text: String, autoSend: Boolean = false): Boolean {
        val cleanText = UtteranceStitcher.sanitizeLiteralText(text)
        val cleanTarget = target.trim()
        var node: AccessibilityNodeInfo? = null

        if (cleanTarget.isNotBlank()) {
            node = lastDumpedNodeMap[cleanTarget]
                ?: lastDumpedNodeMap["node_$cleanTarget"]
                ?: lastDumpedNodeMap[cleanTarget.lowercase(Locale.ROOT)]

            if (node == null) {
                val root = rootInActiveWindow
                if (root != null) {
                    node = findNodeByPredicate(root) { n ->
                        val id = n.viewIdResourceName ?: ""
                        val t = n.text?.toString() ?: ""
                        val d = n.contentDescription?.toString() ?: ""
                        id.equals(cleanTarget, ignoreCase = true) ||
                        id.endsWith("/$cleanTarget", ignoreCase = true) ||
                        t.contains(cleanTarget, ignoreCase = true) ||
                        d.contains(cleanTarget, ignoreCase = true)
                    }
                }
            }
        }

        if (node == null) {
            node = findEditableNode(rootInActiveWindow)
        }

        if (node != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cleanText)
            }
            val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.i(TAG, "typeTarget into node $node: success=$success, text='$cleanText'")
            if (autoSend) {
                serviceScope.launch {
                    delay(350)
                    clickSendButton()
                }
            }
            return success
        }

        return typeText(cleanText, autoSend)
    }

    fun executeUiAction(
        actionPayload: JSONObject,
        nodeMap: Map<String, AccessibilityNodeInfo> = emptyMap()
    ): Boolean {
        val action = actionPayload.optString("action", actionPayload.optString("type", "")).lowercase(Locale.ROOT)
        Log.i(TAG, "executeUiAction: action='$action', payload=$actionPayload")
        return when (action) {
            "click", "tap" -> {
                val target = actionPayload.optString(
                    "target",
                    actionPayload.optString("target_text", actionPayload.optString("keyword", actionPayload.optString("node_id", "")))
                )
                clickTarget(target)
            }
            "type", "input_text" -> {
                val target = actionPayload.optString("target", actionPayload.optString("node_id", ""))
                val rawText = actionPayload.optString("text", actionPayload.optString("message", ""))
                val submit = actionPayload.optBoolean("submit", actionPayload.optBoolean("auto_send", false))
                typeTarget(target, rawText, submit)
            }
            "swipe" -> {
                val dir = actionPayload.optString("direction", "left")
                swipe(dir)
            }
            "pinch" -> {
                val scale = actionPayload.optString("scale", "out")
                pinch(scale)
            }
            "scroll" -> {
                val dir = actionPayload.optString("direction", "down").lowercase(Locale.ROOT)
                if (dir == "up") scrollUp() else scrollDown()
            }
            "global" -> {
                val sub = actionPayload.optString("target", actionPayload.optString("type", "")).lowercase(Locale.ROOT)
                when (sub) {
                    "back" -> goBack()
                    "home" -> goHome()
                    "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                    "notifications" -> openNotifications()
                    else -> goBack()
                }
            }
            "back" -> goBack()
            "home" -> goHome()
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications" -> openNotifications()
            "wait_and_tap" -> {
                val delayMs = actionPayload.optLong("delay_ms", 1500L)
                val target = actionPayload.optString("target", actionPayload.optString("node_id", ""))
                serviceScope.launch {
                    delay(delayMs)
                    if (target.isNotBlank()) {
                        clickTarget(target)
                    } else {
                        clickSendButton()
                    }
                }
                true
            }
            "done" -> true
            "tap_coords", "tap_coordinates", "click_coords" -> {
                val displayMetrics = resources.displayMetrics
                val xNorm = actionPayload.optDouble("x_norm", actionPayload.optDouble("x", -1.0)).toFloat()
                val yNorm = actionPayload.optDouble("y_norm", actionPayload.optDouble("y", -1.0)).toFloat()
                if (xNorm >= 0f && yNorm >= 0f) {
                    val px = if (xNorm <= 1.0f) xNorm * displayMetrics.widthPixels else xNorm
                    val py = if (yNorm <= 1.0f) yNorm * displayMetrics.heightPixels else yNorm
                    tapAtCoordinates(px, py)
                } else {
                    false
                }
            }
            else -> {
                val map = if (nodeMap.isNotEmpty()) nodeMap else lastDumpedNodeMap
                val intMap = mutableMapOf<Int, AccessibilityNodeInfo>()
                map.forEach { (k, v) ->
                    val cleanK = k.removePrefix("node_").toIntOrNull()
                    if (cleanK != null) intMap[cleanK] = v
                }
                executeAtomicAction(action, actionPayload, intMap)
            }
        }
    }

    fun runAgentLoop(
        goal: String,
        serverUrl: String,
        onComplete: ((String) -> Unit)? = null
    ) {
        serviceScope.launch(Dispatchers.IO) {
            val maxSteps = 8
            var currentStep = 0
            var lastReply = ""

            // If goal implies camera, ensure native OEM camera app is opened fully
            val goalLower = goal.lowercase(Locale.ROOT)
            val isCameraGoal = listOf("camera", "photo", "picture", "video", "فیلم", "عکس", "شاتر", "zoom", "portrait").any { goalLower.contains(it) }
            if (isCameraGoal) {
                withContext(Dispatchers.Main) {
                    val currentPkg = rootInActiveWindow?.packageName?.toString() ?: ""
                    if (!currentPkg.contains("camera", ignoreCase = true)) {
                        DeviceController.openCamera(this@JarvisAccessibilityService)
                    }
                }
                delay(1500)
            }

            while (currentStep < maxSteps) {
                if (currentStep == 0 && !isCameraGoal) delay(1000)

                val (serializedNodes, _) = withContext(Dispatchers.Main) {
                    serializeCurrentWindow()
                }

                val uiTreeJsonStr = withContext(Dispatchers.Main) {
                    dumpUiTree()
                }

                val currentPkg = withContext(Dispatchers.Main) {
                    rootInActiveWindow?.packageName?.toString() ?: ""
                }

                val jsonArray = JSONArray()
                serializedNodes.forEach { jsonArray.put(it) }

                val uiNodesArray = try { JSONArray(uiTreeJsonStr) } catch (_: Exception) { JSONArray() }
                var clickableCount = 0
                for (i in 0 until uiNodesArray.length()) {
                    val n = uiNodesArray.optJSONObject(i)
                    if (n?.optBoolean("clickable") == true) clickableCount++
                }

                var screenshotBase64: String? = null
                if (clickableCount < 5 || isCameraGoal) {
                    Log.i(TAG, "UI tree has $clickableCount < 5 clickable nodes. Capturing silent screenshot fallback...")
                    screenshotBase64 = captureScreenBase64Suspend()
                }

                val payload = JSONObject().apply {
                    put("goal", goal)
                    put("package", currentPkg)
                    put("step", currentStep)
                    put("ui_tree", jsonArray)
                    put("ui_nodes", uiNodesArray)
                    put("ui_tree_json", uiTreeJsonStr)
                    if (!screenshotBase64.isNullOrBlank()) {
                        put("screenshot_base64", screenshotBase64)
                    }
                }

                val stepResponse = postAgentStep(serverUrl, payload)
                if (stepResponse == null) {
                    Log.w(TAG, "Agent step $currentStep returned null response from server")
                    break
                }

                val actionType = stepResponse.optString("action", "done")
                lastReply = stepResponse.optString("reply", "")

                if (actionType.equals("done", ignoreCase = true)) {
                    Log.i(TAG, "Agent goal completed at step $currentStep: reply='$lastReply'")
                    break
                }

                withContext(Dispatchers.Main) {
                    executeUiAction(stepResponse, lastDumpedNodeMap)
                }

                currentStep++
                delay(1500)
            }

            withContext(Dispatchers.Main) {
                val finalMsg = if (lastReply.isNotBlank()) lastReply else "Task completed, sir."
                onComplete?.invoke(finalMsg)
                JarvisVoiceService.instance?.speakBritish(finalMsg)
            }
        }
    }
}
