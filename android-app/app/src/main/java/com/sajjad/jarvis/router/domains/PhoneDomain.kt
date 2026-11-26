package com.sajjad.jarvis.router.domains

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.CallLog
import android.util.Log
import com.sajjad.jarvis.AppLauncher
import com.sajjad.jarvis.DeviceController
import com.sajjad.jarvis.LocalCommandRouter.LocalRouteResult
import com.sajjad.jarvis.router.NlpNormalizer

/**
 * PhoneDomain (Tier 1 Zero-Token Engine):
 *
 * Calling, Contacts & Built-in Emergency Services Directory:
 * 1. Immediate emergency dispatch: Ambulance (115), Police (110), Fire (125), Red Crescent (112), Gas (194), Electricity (121), Water (122), Traffic (120), Global (911).
 * 2. Contacts lookup via ContactsContract with fuzzy Persian/English matching.
 * 3. Redial, speakerphone toggle, and call log access.
 */
object PhoneDomain {

    private const val TAG = "PhoneDomain"

    fun handle(context: Context, rawText: String, clean: String, lower: String, isFa: Boolean): LocalRouteResult {
        if (com.sajjad.jarvis.FakeCallActivity.parseFakeCallIntent(rawText, isFa) != null) {
            return LocalRouteResult(handled = false)
        }
        val norm = NlpNormalizer.cleanLower(rawText)

        // ---------------------------------------------------------------------
        // 1. Redial Last Number
        // ---------------------------------------------------------------------
        val redialWords = listOf("redial", "call again", "call back", "دوباره زنگ بزن", "تماس مجدد", "ردیال")
        if (redialWords.any { norm == it || norm.contains(it) }) {
            val intent = Intent(Intent.ACTION_CALL_BUTTON).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            AppLauncher.launchWithOverlayFallback(context, intent)
            val reply = if (isFa) "در حال شماره‌گیری مجدد قربان." else "Redialing now, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "phone_redial")
        }

        // ---------------------------------------------------------------------
        // 2. Speakerphone Toggle
        // ---------------------------------------------------------------------
        if (norm.contains("speaker on") || norm.contains("enable speaker") || norm.contains("روی اسپیکر") || norm.contains("اسپیکر رو روشن کن")) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.isSpeakerphoneOn = true
            val reply = if (isFa) "اسپیکر تلفن روشن شد قربان." else "Speakerphone enabled, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "phone_speaker")
        }
        if (norm.contains("speaker off") || norm.contains("disable speaker") || norm.contains("اسپیکر رو خاموش کن") || norm.contains("از روی اسپیکر بردار")) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.isSpeakerphoneOn = false
            val reply = if (isFa) "اسپیکر تلفن خاموش شد قربان." else "Speakerphone disabled, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "phone_speaker")
        }

        // ---------------------------------------------------------------------
        // 3. Call Log / Missed Calls
        // ---------------------------------------------------------------------
        val callLogWords = listOf(
            "call log", "recent calls", "missed calls", "open call log",
            "تماس‌های اخیر", "تماسهای اخیر", "تماس‌های از دست رفته", "تماسهای از دست رفته", "تاریخچه تماس"
        )
        if (callLogWords.any { norm.contains(it) }) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                type = CallLog.Calls.CONTENT_TYPE
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            AppLauncher.launchWithOverlayFallback(context, intent)
            val reply = if (isFa) "تاریخچه تماس‌ها باز شد قربان." else "Opening call log, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "phone_call_log")
        }

        // ---------------------------------------------------------------------
        // 4. Built-In Emergency & Direct Calling
        // ---------------------------------------------------------------------
        var callTarget: String? = null

        // Check explicit emergency keywords first
        val emergencyNumber = DeviceController.resolveEmergencyNumber(norm)
        if (emergencyNumber != null) {
            DeviceController.makeCall(context, emergencyNumber)
            val reply = if (isFa) "در حال برقراری تماس با $clean ($emergencyNumber) قربان." else "Calling emergency service $emergencyNumber, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "phone_emergency")
        }

        // English pattern: "call <target>"
        val callMatchEn = Regex("^call\\s+(.+)$").find(lower)
        if (callMatchEn != null) {
            callTarget = callMatchEn.groupValues[1].trim()
        } else if (isFa) {
            val callMatchFa1 = Regex("^(تماس بگیر با|زنگ بزن به|تماس با|زنگ بزن)\\s+(.+)$").find(clean)
            if (callMatchFa1 != null) {
                callTarget = callMatchFa1.groupValues[2].trim()
            } else {
                val callMatchFa2 = Regex("^(.+?)\\s*(زنگ بزن|تماس بگیر)$").find(clean)
                if (callMatchFa2 != null) {
                    callTarget = callMatchFa2.groupValues[1].removeSuffix("به ").trim()
                }
            }
        }

        if (!callTarget.isNullOrBlank()) {
            val targetClean = callTarget.removePrefix("to ").trim()
            if (targetClean.isNotBlank()) {
                DeviceController.makeCall(context, targetClean)
                val reply = if (isFa) "در حال برقراری تماس با $targetClean قربان." else "Calling $targetClean, sir."
                return LocalRouteResult(handled = true, spokenFeedback = reply, category = "phone_call")
            }
        }

        return LocalRouteResult(handled = false)
    }
}
