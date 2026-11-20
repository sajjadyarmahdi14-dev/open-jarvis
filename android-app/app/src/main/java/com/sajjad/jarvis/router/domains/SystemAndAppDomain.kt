package com.sajjad.jarvis.router.domains

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.sajjad.jarvis.ActionDispatcher
import com.sajjad.jarvis.AppLauncher
import com.sajjad.jarvis.DeviceController
import com.sajjad.jarvis.JarvisAccessibilityService
import com.sajjad.jarvis.LocalCommandRouter.LocalRouteResult
import com.sajjad.jarvis.router.NlpNormalizer
import org.json.JSONObject
import java.util.Locale

/**
 * SystemAndAppDomain (Tier 1 Zero-Token Engine):
 *
 * 1. Universal App Launcher across all installed packages.
 * 2. Direct On-Screen Button Tap ("روی <label> بزن", "tap <label>") via AccessibilityNodeInfo search (0 tokens!).
 * 3. Accessibility Navigation: Back, Home, Recents, Notifications, Quick Settings, Lock Screen, Screenshot, Scroll.
 * 4. Hardware & Settings controls: Flashlight, Volume, Brightness, Ringer mode, Auto-rotate, Settings panels.
 */
object SystemAndAppDomain {

    private const val TAG = "SystemAndAppDomain"

    fun handle(context: Context, rawText: String, clean: String, lower: String, isFa: Boolean): LocalRouteResult {
        val norm = NlpNormalizer.cleanLower(rawText)

        // ---------------------------------------------------------------------
        // 1. Direct On-Screen Button Tap ("روی <label> بزن", "tap <label>")
        // ---------------------------------------------------------------------
        val tapMatchFa = Regex("^(?:روی|کلیک کن روی|بزن روی|لمس کن)\\s+(.+?)\\s*(?:بزن|کلیک کن|لمس کن)?$").find(clean)
        val tapMatchEn = Regex("^(?:tap on|tap|click on|click)\\s+(.+)$").find(lower)

        if (tapMatchFa != null || tapMatchEn != null) {
            val label = tapMatchFa?.groupValues?.get(1)?.trim() ?: tapMatchEn?.groupValues?.get(1)?.trim() ?: ""
            if (label.isNotBlank() && label !in listOf("on", "next", "previous", "camera", "button")) {
                val ok = JarvisAccessibilityService.instance?.clickElement(label) == true
                val reply = if (ok) {
                    if (isFa) "روی $label کلیک شد قربان." else "Tapped on $label, sir."
                } else {
                    if (isFa) "عنصر $label روی صفحه فعلی پیدا نشد قربان." else "Could not find element '$label' on screen, sir."
                }
                return LocalRouteResult(handled = true, spokenFeedback = reply, category = "accessibility_tap")
            }
        }

        // ---------------------------------------------------------------------
        // 2. System Navigation (Back, Home, Recents, Notifications, Quick Settings, Lock Screen, Screenshot, Scroll)
        // ---------------------------------------------------------------------
        if (norm in listOf("go back", "back", "برگرد", "برو عقب", "صفحه قبل", "بازگشت")) {
            JarvisAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            val reply = if (isFa) "به عقب برگشتم قربان." else "Going back, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "nav_back")
        }

        if (norm in listOf("go home", "home", "home screen", "صفحه اصلی", "برو هوم", "برو خانه", "برو صفحه اصلی")) {
            JarvisAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            val reply = if (isFa) "به صفحه اصلی رفتم قربان." else "Going to home screen, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "nav_home")
        }

        if (norm in listOf("recent apps", "recents", "overview", "برنامه‌های اخیر", "برنامه های باز", "برنامه‌های باز")) {
            JarvisAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            val reply = if (isFa) "برنامه‌های اخیر باز شد قربان." else "Showing recent apps, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "nav_recents")
        }

        if (norm in listOf("open notifications", "show notifications", "اعلان‌ها", "پنل اعلان", "اعلان ها")) {
            JarvisAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            val reply = if (isFa) "پنل اعلان‌ها باز شد قربان." else "Opening notifications, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "nav_notifications")
        }

        if (norm in listOf("quick settings", "open quick settings", "تنظیمات سریع", "کنترل سنتر", "پنل تنظیمات سریع")) {
            JarvisAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            val reply = if (isFa) "تنظیمات سریع باز شد قربان." else "Opening quick settings, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "nav_quick_settings")
        }

        if (norm in listOf("lock screen", "lock phone", "صفحه رو قفل کن", "گوشی رو قفل کن", "صفحه قفل", "گوشی را قفل کن")) {
            JarvisAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            val reply = if (isFa) "صفحه قفل شد قربان." else "Screen locked, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "nav_lock_screen")
        }

        if (norm in listOf("take a screenshot", "screenshot", "capture screen", "عکس از صفحه", "اسکرین شات بگیر", "اسکرین‌شات", "اسکرین شات")) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                JarvisAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            } else {
                ActionDispatcher.dispatchAction(context, JSONObject(mapOf("type" to "take_screenshot")))
            }
            val reply = if (isFa) "اسکرین‌شات گرفته شد قربان." else "Screenshot captured, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "nav_screenshot")
        }

        if (norm in listOf("scroll down", "بیا پایین", "برو پایین", "اسکرول به پایین", "پایین‌تر")) {
            JarvisAccessibilityService.instance?.scrollDown()
            val reply = if (isFa) "به پایین اسکرول شد قربان." else "Scrolled down, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "nav_scroll")
        }
        if (norm in listOf("scroll up", "برو بالا", "اسکرول به بالا", "بالاتر")) {
            JarvisAccessibilityService.instance?.scrollUp()
            val reply = if (isFa) "به بالا اسکرول شد قربان." else "Scrolled up, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "nav_scroll")
        }

        // ---------------------------------------------------------------------
        // 3. Flashlight / Torch Controls
        // ---------------------------------------------------------------------
        val torchOnWords = listOf(
            "turn on flashlight", "flashlight on", "torch on", "enable flashlight",
            "turn on torch", "enable torch", "چراغ قوه رو روشن کن", "چراغ روشن",
            "چراغ قوه را روشن کن", "فلش رو روشن کن", "چراغ قوه روشن"
        )
        if (torchOnWords.any { norm.contains(it) }) {
            DeviceController.setTorchState(context, true)
            val reply = if (isFa) "چراغ قوه روشن شد قربان." else "Flashlight turned on, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "torch_on")
        }

        val torchOffWords = listOf(
            "turn off flashlight", "flashlight off", "torch off", "disable flashlight",
            "turn off torch", "disable torch", "چراغ قوه رو خاموش کن", "چراغ خاموش",
            "چراغ قوه را خاموش کن", "فلش رو خاموش کن", "چراغ قوه خاموش"
        )
        if (torchOffWords.any { norm.contains(it) }) {
            DeviceController.setTorchState(context, false)
            val reply = if (isFa) "چراغ قوه خاموش شد قربان." else "Flashlight turned off, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "torch_off")
        }

        // ---------------------------------------------------------------------
        // 4. Volume Controls
        // ---------------------------------------------------------------------
        if (norm.contains("volume up") || norm.contains("increase volume") || norm.contains("louder") ||
            norm.contains("صدا رو زیاد کن") || norm.contains("صدای بیشتر") || norm.contains("افزایش صدا") || norm.contains("بلندتر")) {
            DeviceController.setVolume(context, "up")
            val reply = if (isFa) "صدا افزایش یافت قربان." else "Volume increased, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "volume_up")
        }

        if (norm.contains("volume down") || norm.contains("decrease volume") || norm.contains("lower volume") ||
            norm.contains("صدا رو کم کن") || norm.contains("صدای کمتر") || norm.contains("کاهش صدا") || norm.contains("آروم‌تر")) {
            DeviceController.setVolume(context, "down")
            val reply = if (isFa) "صدا کاهش یافت قربان." else "Volume decreased, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "volume_down")
        }

        if (norm in listOf("mute", "mute volume", "mute sound", "صدا رو قطع کن", "بی‌صدا کن", "بی صدا کن", "سایلنت کن")) {
            DeviceController.setVolume(context, "mute")
            val reply = if (isFa) "صدا قطع شد قربان." else "Audio muted, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "volume_mute")
        }

        val volMatch = Regex("(set volume to|volume|صدا روی)\\s+(\\d+)\\s*(%|درصد)?").find(norm)
        if (volMatch != null) {
            val level = volMatch.groupValues[2].toIntOrNull() ?: 50
            DeviceController.setVolume(context, "set", level)
            val reply = if (isFa) "میزان صدا روی $level درصد تنظیم شد قربان." else "Volume set to $level percent, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "volume_set")
        }

        // ---------------------------------------------------------------------
        // 5. Brightness Controls
        // ---------------------------------------------------------------------
        if (norm.contains("brightness up") || norm.contains("increase brightness") || norm.contains("نور صفحه رو زیاد کن") || norm.contains("روشنایی بیشتر")) {
            DeviceController.setBrightness(context, 220)
            val reply = if (isFa) "نور صفحه افزایش یافت قربان." else "Brightness increased, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "brightness_up")
        }
        if (norm.contains("brightness down") || norm.contains("decrease brightness") || norm.contains("نور صفحه رو کم کن") || norm.contains("روشنایی کمتر")) {
            DeviceController.setBrightness(context, 40)
            val reply = if (isFa) "نور صفحه کاهش یافت قربان." else "Brightness decreased, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "brightness_down")
        }

        // ---------------------------------------------------------------------
        // 6. Settings Panels (Wi-Fi, Bluetooth, Hotspot, Mobile Data, Battery, Sound)
        // ---------------------------------------------------------------------
        if (norm.contains("wifi settings") || norm.contains("تنظیمات وای فای") || norm.contains("تنظیمات وای‌فای")) {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            AppLauncher.launchWithOverlayFallback(context, intent)
            val reply = if (isFa) "تنظیمات وای‌فای باز شد قربان." else "Opening Wi-Fi settings, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "settings_wifi")
        }
        if (norm.contains("bluetooth settings") || norm.contains("تنظیمات بلوتوث")) {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            AppLauncher.launchWithOverlayFallback(context, intent)
            val reply = if (isFa) "تنظیمات بلوتوث باز شد قربان." else "Opening Bluetooth settings, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "settings_bluetooth")
        }
        if (norm.contains("display settings") || norm.contains("تنظیمات صفحه نمایش") || norm.contains("تنظیمات نمایش")) {
            val intent = Intent(Settings.ACTION_DISPLAY_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            AppLauncher.launchWithOverlayFallback(context, intent)
            val reply = if (isFa) "تنظیمات صفحه نمایش باز شد قربان." else "Opening display settings, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "settings_display")
        }
        if (norm.contains("sound settings") || norm.contains("تنظیمات صدا")) {
            val intent = Intent(Settings.ACTION_SOUND_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            AppLauncher.launchWithOverlayFallback(context, intent)
            val reply = if (isFa) "تنظیمات صدا باز شد قربان." else "Opening sound settings, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "settings_sound")
        }

        // ---------------------------------------------------------------------
        // 7. Universal All-Apps Launcher
        // ---------------------------------------------------------------------
        var appQuery: String? = null
        val enMatch = Regex("^(open|launch|start|run)\\s+(.+)").find(lower)
        if (enMatch != null) {
            appQuery = enMatch.groupValues[2].trim()
        } else if (isFa) {
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
}
