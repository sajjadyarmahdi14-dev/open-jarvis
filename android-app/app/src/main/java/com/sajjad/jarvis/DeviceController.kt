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
    @Volatile var isTorchOn: Boolean = false

    // -------------------------------------------------------------------------
    // 1. Music & Media Playback Control (delegated to MediaEngine)
    // -------------------------------------------------------------------------

    fun playMusic(context: Context) {
        MediaEngine.handleMediaPlay(context, "audio", "", null, "local", false)
    }

    fun pauseMusic(context: Context) {
        MediaEngine.handleMediaControl(context, "pause")
    }

    fun nextTrack(context: Context) {
        MediaEngine.handleMediaControl(context, "next")
    }

    fun previousTrack(context: Context) {
        MediaEngine.handleMediaControl(context, "previous")
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
        MediaEngine.handleMediaPlay(context, "audio", query, null, null, false)
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

    fun sendSmsDirect(context: Context, contactOrNumber: String, message: String): Boolean {
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
                Log.i(TAG, "SMS sent silently in background via SmsManager to $cleanPhone")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Direct SmsManager failed: ${e.message}")
            }
        }
        return false
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

    fun resolveInstalledTelegramPackage(context: Context): String {
        val candidates = listOf(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.telegram.plus",
            "org.telegram.messenger.beta",
            "nekox.messenger",
            "org.thunderdog.challegram"
        )
        val pm = context.packageManager
        for (pkg in candidates) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: Exception) {}
        }
        return "org.telegram.messenger"
    }

    fun resolveInstalledWhatsAppPackage(context: Context): String {
        val candidates = listOf("com.whatsapp", "com.whatsapp.w4b")
        val pm = context.packageManager
        for (pkg in candidates) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: Exception) {}
        }
        return "com.whatsapp"
    }

    fun sendEmail(context: Context, recipient: String, subject: String, body: String, autoSend: Boolean = false) {
        val cleanRecipient = recipient.trim()
        val cleanSubject = subject.ifBlank { "Jarvis Message" }
        // Fix 5: RFC mailto query params AND explicit extras ensure body is filled across Gmail, Outlook & Xiaomi Mail
        val encodedUri = "mailto:${Uri.encode(cleanRecipient)}?subject=${Uri.encode(cleanSubject)}&body=${Uri.encode(body)}"
        val emailIntent = Intent(Intent.ACTION_SENDTO, Uri.parse(encodedUri)).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(cleanRecipient))
            putExtra(Intent.EXTRA_SUBJECT, cleanSubject)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        AppLauncher.launchWithOverlayFallback(context, emailIntent)
        Log.i(TAG, "Opened email compose intent for $cleanRecipient with body: '$body'")

        if (autoSend && JarvisAccessibilityService.isServiceRunning()) {
            JarvisAccessibilityService.instance?.let { service ->
                service.serviceScope.launch {
                    delay(1200)
                    service.verifyAndSendEmailCompose(body)
                }
            }
        }
    }

    fun sendMessenger(
        context: Context,
        app: String,
        contact: String,
        message: String,
        onVerified: ((Boolean, String) -> Unit)? = null
    ) {
        val appLower = app.lowercase(Locale.ROOT)
        val accessService = JarvisAccessibilityService.instance

        if (accessService == null) {
            val err = "Accessibility service is off; cannot send message automatically."
            Log.w(TAG, err)
            onVerified?.invoke(false, err)
            return
        }

        if (appLower.contains("whatsapp")) {
            val pkg = resolveInstalledWhatsAppPackage(context)
            val num = findContactNumber(context, contact) ?: contact
            val cleanDigits = num.replace("+", "").replace(" ", "").replace("-", "")
            val waUri = if (cleanDigits.isNotBlank() && cleanDigits.any { it.isDigit() }) {
                Uri.parse("https://api.whatsapp.com/send?phone=$cleanDigits&text=${Uri.encode(message)}")
            } else {
                Uri.parse("https://api.whatsapp.com/send?text=${Uri.encode(message)}")
            }
            val waIntent = Intent(Intent.ACTION_VIEW, waUri).apply {
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            AppLauncher.launchWithOverlayFallback(context, waIntent)

            accessService.serviceScope.launch {
                val result = accessService.sendVerifiedMessage(pkg, message)
                onVerified?.invoke(result.first, result.second)
            }
        } else if (appLower.contains("telegram")) {
            val pkg = resolveInstalledTelegramPackage(context)
            val cleanTarget = contact.trim()

            val isSavedMessages = cleanTarget.equals("Saved Messages", ignoreCase = true) ||
                    cleanTarget.contains("saved", ignoreCase = true) ||
                    cleanTarget.contains("ذخیره", ignoreCase = true)

            if (isSavedMessages) {
                AppLauncher.launchApp(context, "telegram")
                accessService.serviceScope.launch {
                    delay(800)
                    val openedSaved = accessService.openTelegramSavedMessages()
                    if (openedSaved) {
                        val result = accessService.sendVerifiedMessage(pkg, message)
                        onVerified?.invoke(result.first, result.second)
                    } else {
                        onVerified?.invoke(false, "Could not open Saved Messages in Telegram")
                    }
                }
            } else {
                val cleanUser = cleanTarget.removePrefix("https://t.me/").removePrefix("t.me/").removePrefix("@").trim()
                val tgIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("tg://resolve?domain=$cleanUser")
                    setPackage(pkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                AppLauncher.launchWithOverlayFallback(context, tgIntent)

                accessService.serviceScope.launch {
                    val result = accessService.sendVerifiedMessage(pkg, message)
                    onVerified?.invoke(result.first, result.second)
                }
            }
        } else {
            // Third-party messengers: iGap, Eitaa, Bale, Rubika, etc.
            AppLauncher.launchApp(context, app)
            accessService.serviceScope.launch {
                delay(1200)
                val result = accessService.sendVerifiedMessage(app, message)
                onVerified?.invoke(result.first, result.second)
            }
        }
    }

    fun resolveEmergencyNumber(target: String): String? {
        val clean = target.trim().lowercase(Locale.ROOT).replace("به ", "")
        val emergencyMap = mapOf(
            "آمبولانس" to "115",
            "اورژانس" to "115",
            "امبولانس" to "115",
            "ambulance" to "115",
            "emergency medical" to "115",
            "medical emergency" to "115",
            "پلیس" to "110",
            "کلانتری" to "110",
            "police" to "110",
            "cop" to "110",
            "آتش نشانی" to "125",
            "آتشنشانی" to "125",
            "اتش نشانی" to "125",
            "اتشنشانی" to "125",
            "fire department" to "125",
            "firefighter" to "125",
            "هلال احمر" to "112",
            "امداد" to "112",
            "red crescent" to "112",
            "emergency" to "112",
            "اورژانس گاز" to "194",
            "اتفاقات گاز" to "194",
            "گاز" to "194",
            "gas emergency" to "194",
            "برق" to "121",
            "اتفاقات برق" to "121",
            "electricity" to "121",
            "آب" to "122",
            "اتفاقات آب" to "122",
            "water" to "122",
            "پلیس راهور" to "120",
            "راهور" to "120",
            "راهنمایی رانندگی" to "120",
            "traffic police" to "120",
            "911" to "911",
            "۹۱۱" to "911",
            "۱۱۵" to "115",
            "۱۱۰" to "110",
            "۱۲۵" to "125",
            "۱۱۲" to "112",
            "۱۹۴" to "194",
            "۱۲۱" to "121",
            "۱۲۲" to "122",
            "۱۲۰" to "120"
        )
        for ((k, v) in emergencyMap) {
            if (clean == k || clean.contains(k)) return v
        }
        return null
    }

    fun makeCall(context: Context, target: String) {
        val emergency = resolveEmergencyNumber(target)
        var num = emergency ?: target.trim()
        val faDigits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
        val arDigits = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')
        for (i in 0..9) {
            num = num.replace(faDigits[i], ('0' + i)).replace(arDigits[i], ('0' + i))
        }

        val isDigits = num.replace(" ", "").replace("-", "").replace("+", "").all { it.isDigit() }
        if (!isDigits && emergency == null) {
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
    // 4. Camera & Photography (Native Full Launch)
    // -------------------------------------------------------------------------

    fun openCamera(context: Context) {
        // ALWAYS launch native camera app fully using MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA or com.android.camera with FLAG_ACTIVITY_NEW_TASK
        val camPackages = listOf(
            "com.android.camera",
            "com.google.android.GoogleCamera",
            "org.codeaurora.snapcam",
            "com.sec.android.app.camera"
        )
        for (pkg in camPackages) {
            val res = AppLauncher.tryLaunchPackage(context, pkg, "Camera")
            if (res != null) {
                Log.i(TAG, "Launched native camera package: $pkg")
                return
            }
        }
        val cameraIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        AppLauncher.launchWithOverlayFallback(context, cameraIntent)
        Log.i(TAG, "Launched camera via MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA")
    }

    // -------------------------------------------------------------------------
    // 5. System Hardware Controls (Torch, Brightness, Volume, Clipboard)
    // -------------------------------------------------------------------------

    fun setTorchState(context: Context, turnOn: Boolean): Boolean {
        try {
            val camMgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val flashCameraId = camMgr.cameraIdList.firstOrNull { id ->
                try {
                    val chars = camMgr.getCameraCharacteristics(id)
                    chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                } catch (_: Exception) {
                    false
                }
            } ?: camMgr.cameraIdList.firstOrNull()
            if (flashCameraId != null) {
                camMgr.setTorchMode(flashCameraId, turnOn)
                isTorchOn = turnOn
                Log.i(TAG, "Torch state set to: $turnOn (camera: $flashCameraId)")
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed setting torch state: ${e.message}")
        }
        return false
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
    // 5. Unified Client Action Dispatcher (Delegated to ActionDispatcher)
    // -------------------------------------------------------------------------

    fun executeClientAction(
        context: Context,
        act: JSONObject,
        onScreenRead: ((String) -> Unit)? = null
    ) {
        ActionDispatcher.dispatchAction(context, act) { feedback ->
            onScreenRead?.invoke(feedback)
        }
    }
}
