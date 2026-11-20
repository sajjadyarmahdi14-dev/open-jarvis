package com.sajjad.jarvis

import android.content.Context
import android.util.Log
import com.sajjad.jarvis.router.NlpNormalizer
import com.sajjad.jarvis.router.domains.CommunicationDomain
import com.sajjad.jarvis.router.domains.MediaDomain
import com.sajjad.jarvis.router.domains.NotificationDomain
import com.sajjad.jarvis.router.domains.PhoneDomain
import com.sajjad.jarvis.router.domains.SystemAndAppDomain
import com.sajjad.jarvis.router.domains.UtilityDomain
import org.json.JSONObject
import java.util.Locale

/**
 * Siri-Grade 3-Tier Multi-Domain Zero-Token Engine Router for J.A.R.V.I.S.
 *
 * Tier 1: Deterministically intercepts and handles all device, system, hardware, camera, media,
 * clock/alarm, communication, notification, telemetry, navigation, protocols, and math operations locally with ZERO tokens
 * and ZERO round-trips to VPS LLM.
 *
 * Tier 2 & 3: Smart Handoff or unhandled conversational/reasoning requests escalate seamlessly
 * to VPS AI Brain (/api/chat, /api/chat/stream) or Universal UI Agent without crashes.
 */
object LocalCommandRouter {

    private const val TAG = "LocalCommandRouter"

    data class LocalRouteResult(
        val handled: Boolean,
        val spokenFeedback: String? = null,
        val category: String = "",
        val clientAction: JSONObject? = null,
        val handoffToAi: Boolean = false
    )

    fun route(context: Context, rawText: String): LocalRouteResult {
        val clean = rawText.trim()
        if (clean.isBlank()) return LocalRouteResult(handled = false)

        val isFa = NlpNormalizer.isPersian(clean)
        val lower = clean.lowercase(Locale.ROOT)

        try {
            // 0. Session, Wake Word & Standby Control
            val sessionRes = handleSessionAndWake(context, clean, lower, isFa)
            if (sessionRes.handled) return sessionRes

            // 1. Media Domain (Next, Prev, Pause, Stop, Resume, Seek, Info, Live Device Search)
            val mediaRes = MediaDomain.handle(context, rawText, clean, lower, isFa)
            if (mediaRes.handled) return mediaRes

            // 2. Phone Domain (Emergency 115/110/125/112, Contacts, Redial, Speakerphone, Call Log)
            val phoneRes = PhoneDomain.handle(context, rawText, clean, lower, isFa)
            if (phoneRes.handled) return phoneRes

            // 3. Notification Domain (Dismiss, Clear All, Open, Reply, Read, Mute)
            val notifRes = NotificationDomain.handle(context, rawText, clean, lower, isFa)
            if (notifRes.handled) return notifRes

            // 4. Communication Domain (Email, SMS, Messengers + Smart AI Handoff for Composition)
            val commRes = CommunicationDomain.handle(context, rawText, clean, lower, isFa)
            if (commRes.handled) return commRes
            if (commRes.handoffToAi) {
                Log.i(TAG, "Request flagged for Smart AI Handoff by CommunicationDomain")
                return commRes
            }

            // 5. System & App Domain (All-Apps Launcher, Screen Elements, System Nav, Hardware, Settings)
            val sysRes = SystemAndAppDomain.handle(context, rawText, clean, lower, isFa)
            if (sysRes.handled) return sysRes

            // 6. Utility Domain (CameraX, Clock/Date, Alarms/Timers, Calendar, Maps, Browser, Telemetry, Protocols, Memory, VPS, Math)
            val utilRes = UtilityDomain.handle(context, rawText, clean, lower, isFa)
            if (utilRes.handled) return utilRes

        } catch (e: Exception) {
            Log.e(TAG, "LocalCommandRouter exception while routing '$rawText': ${e.message}", e)
        }

        return LocalRouteResult(handled = false, handoffToAi = true)
    }

    // -------------------------------------------------------------------------
    // Wake Word, Session & Standby Helpers
    // -------------------------------------------------------------------------
    val WAKE_WORDS = setOf(
        "jarvis", "hey jarvis", "hi jarvis", "ok jarvis", "hello jarvis", "dear jarvis",
        "jervis", "javis", "garvis", "charvis", "jarvish", "jarvez", "travis",
        "جارویس", "ای جارویس", "جرویس", "هی جارویس", "سلام جارویس", "چاویس", "چارویس", "چرویس",
        "جارویز", "جرویز", "جاروویس", "جاربیس", "دارویس", "یارویس", "جارو بیس", "سرویس"
    )

    fun levenshtein(s1: String, s2: String): Int {
        return NlpNormalizer.levenshtein(s1, s2)
    }

    fun isWakeToken(token: String): Boolean {
        val clean = token.lowercase(Locale.ROOT).replace(Regex("[^a-zA-Z\\u0600-\\u06FF]"), "")
        if (clean in WAKE_WORDS) return true
        if (clean.length >= 3) {
            if (levenshtein(clean, "jarvis") <= 2) return true
            if (levenshtein(clean, "jervis") <= 2) return true
            if (levenshtein(clean, "garvis") <= 2) return true
            if (levenshtein(clean, "travis") <= 2) return true
            if (levenshtein(clean, "جارویس") <= 1) return true
            if (levenshtein(clean, "جرویس") <= 1) return true
            if (levenshtein(clean, "چارویس") <= 1) return true
        }
        return false
    }

    fun isAllWakeWords(text: String): Boolean {
        val clean = text.lowercase(Locale.ROOT).replace(Regex("[,.!?،؟]+"), " ").trim()
        val tokens = clean.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return false
        val prefixTokens = setOf("hey", "hi", "ok", "hello", "dear", "ای", "هی", "سلام")
        val mainTokens = tokens.filter { it !in prefixTokens }
        if (mainTokens.isEmpty()) return false
        return tokens.all { token ->
            token in prefixTokens || isWakeToken(token)
        }
    }

    private fun handleSessionAndWake(context: Context, clean: String, lower: String, isFa: Boolean): LocalRouteResult {
        // Standalone wake word or repeated wake-word deduplication ("Jarvis Jarvis Jarvis")
        val normalizedLower = lower.replace(Regex("[,.!?،؟]+"), "").trim()
        if (WAKE_WORDS.contains(normalizedLower) || isAllWakeWords(lower)) {
            val reply = if (isFa) "در خدمت شما هستم قربان. گوش می‌دهم." else "At your service, sir. I'm listening."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "wake_session")
        }

        // Stop / Cancel / Shut up / Sleep
        val stopPatterns = listOf(
            "stop", "cancel", "shut up", "wait", "be quiet", "silence", "stop listening",
            "go to sleep", "sleep mode", "mute yourself", "standby", "that's all jarvis",
            "استپ", "صبر کن", "وایسا", "وایستا", "ساکت شو", "خفه شو", "کافیه", "بس کن",
            "خاموش شو", "گوش نده", "دیگه گوش نده", "برو استندبای", "استندبای", "بخواب",
            "فعلا جارویس", "خداحافظ جارویس", "خداحافظ"
        )
        if (stopPatterns.any { lower == it || lower.startsWith("$it ") || lower.endsWith(" $it") }) {
            try {
                MediaEngine.handleMediaControl(context, "pause")
                JarvisCameraActivity.instance?.finish()
            } catch (_: Exception) {}
            val reply = if (isFa) "به حالت استندبای رفتم قربان." else "Standing by, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "session_stop")
        }

        // Repeat last reply
        val repeatPatterns = listOf(
            "repeat", "say that again", "what did you say", "repeat that", "say again",
            "تکرار کن", "دوباره بگو", "چی گفتی", "چی شد", "چی گفتی جارویس"
        )
        if (repeatPatterns.any { lower.contains(it) }) {
            val last = JarvisVoiceService.lastSpokenText
            val reply = if (!last.isNullOrBlank()) last else (if (isFa) "پیام قبلی ثبت نشده است قربان." else "No previous message recorded, sir.")
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "repeat_speech")
        }

        // Language Override
        if (lower.contains("speak english") || lower.contains("switch to english") || lower.contains("انگلیسی صحبت کن") || lower.contains("انگلیسی حرف بزن")) {
            return LocalRouteResult(handled = true, spokenFeedback = "Switching to English, sir. How can I assist you?", category = "language_override")
        }
        if (lower.contains("speak persian") || lower.contains("switch to persian") || lower.contains("فارسی صحبت کن") || lower.contains("فارسی حرف بزن") || lower.contains("فارسی بگو")) {
            return LocalRouteResult(handled = true, spokenFeedback = "به زبان فارسی تغییر یافت قربان. در خدمتم.", category = "language_override")
        }

        return LocalRouteResult(handled = false)
    }

    // Gregorian to Jalali delegation
    fun gregorianToJalali(gy: Int, gm: Int, gd: Int): Triple<Int, Int, Int> {
        return UtilityDomain.gregorianToJalali(gy, gm, gd)
    }
}
