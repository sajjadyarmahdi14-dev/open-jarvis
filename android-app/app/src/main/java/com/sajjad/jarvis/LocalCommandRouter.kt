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
import com.sajjad.jarvis.security.SecurityProtocolEngine
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
        val handoffToAi: Boolean = false,
        val suppressFallbackSpeech: Boolean = false
    )

    fun route(context: Context, rawText: String): LocalRouteResult {
        val clean = rawText.trim()
        if (clean.isBlank()) return LocalRouteResult(handled = false)

        val isFa = NlpNormalizer.isPersian(clean)
        val lower = clean.lowercase(Locale.ROOT)

        try {
            // Security Protocol Passphrase Interception (Screen Unlock / Stand Down)
            val secState = SecurityProtocolEngine.currentState
            if (secState == SecurityProtocolEngine.SecurityState.AWAITING_PASSPHRASE_FOR_UNLOCK) {
                SecurityProtocolEngine.handlePassphraseForUnlock(context, clean, isFa)
                return LocalRouteResult(handled = true, category = "security_unlock_passphrase", suppressFallbackSpeech = true)
            }
            if (secState == SecurityProtocolEngine.SecurityState.AWAITING_PASSPHRASE_FOR_STANDDOWN) {
                SecurityProtocolEngine.handlePassphraseForStandDown(context, clean, isFa)
                return LocalRouteResult(handled = true, category = "security_standdown_passphrase", suppressFallbackSpeech = true)
            }

            // 0. Session, Wake Word & Standby Control
            val sessionRes = handleSessionAndWake(context, clean, lower, isFa)
            if (sessionRes.handled) return sessionRes

            // 0.5 Live Multimodal Session Controls (Audio Listening & Screen Watching)
            val liveRes = handleLiveMultimodalControls(context, rawText, clean, lower, isFa)
            if (liveRes.handled) return liveRes

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
        val norm = NlpNormalizer.cleanLower(clean)

        // Language Coach / Pocket Live Interpreter Enter/Exit Commands
        val langCmd = LanguageModeManager.parseLanguageCommand(clean, isFa)
        if (langCmd != null) {
            return when (langCmd) {
                is LanguageModeManager.CommandAction.Exit -> {
                    val reply = LanguageModeManager.exitMode(isFa)
                    LocalRouteResult(handled = true, spokenFeedback = reply, category = "language_exit")
                }
                is LanguageModeManager.CommandAction.EnterPractice -> {
                    val reply = LanguageModeManager.activateMode(
                        LanguageModeManager.Mode.PRACTICE,
                        langCmd.targetLanguage,
                        context,
                        langCmd.isFa
                    )
                    JarvisVoiceService.instance?.let { svc ->
                        svc.broadcastUiUpdate(assistantReply = reply, wakeStatus = "speaking")
                        JarvisOverlayManager.instance?.updateText(reply)
                        svc.speakWithLanguage(reply, langCmd.targetLanguage) {
                            svc.keepActiveConversationAlive(45000L)
                        }
                    }
                    LocalRouteResult(handled = true, spokenFeedback = null, category = "language_practice_start", suppressFallbackSpeech = true)
                }
                is LanguageModeManager.CommandAction.EnterInterpreter -> {
                    val reply = LanguageModeManager.activateMode(
                        LanguageModeManager.Mode.INTERPRETER,
                        langCmd.targetLanguage,
                        context,
                        langCmd.isFa
                    )
                    JarvisVoiceService.instance?.keepActiveConversationAlive(45000L)
                    LocalRouteResult(handled = true, spokenFeedback = reply, category = "language_interpreter_start")
                }
            }
        }

        // Standalone wake word or repeated wake-word deduplication ("Jarvis Jarvis Jarvis")
        val normalizedLower = lower.replace(Regex("[,.!?،؟]+"), "").trim()
        if (WAKE_WORDS.contains(normalizedLower) || isAllWakeWords(lower)) {
            val reply = if (isFa) "در خدمت شما هستم قربان. گوش می‌دهم." else "At your service, sir. I'm listening."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "wake_session")
        }

        // Stop / Cancel / Shut up / Sleep (Fix 3: Instant Hard Stop)
        val stopPatterns = listOf(
            "stop", "shut up", "quiet", "be quiet", "cancel", "wait", "silence", "stop listening",
            "go to sleep", "sleep mode", "mute yourself", "standby", "that's all jarvis",
            "بسه", "ساکت شو", "وایسا", "وایستا", "قطع کن", "استپ", "صبر کن", "خفه شو",
            "کافیه", "بس کن", "خاموش شو", "گوش نده", "دیگه گوش نده", "برو استندبای",
            "استندبای", "بخواب", "فعلا جارویس", "خداحافظ جارویس", "خداحافظ"
        )
        if (stopPatterns.any { lower == it || lower.startsWith("$it ") || lower.endsWith(" $it") || norm == it || norm.startsWith("$it ") || norm.endsWith(" $it") }) {
            try {
                if (LanguageModeManager.isActive) LanguageModeManager.exitMode(isFa)
                MediaEngine.handleMediaControl(context, "pause")
                JarvisCameraActivity.instance?.finish()
            } catch (_: Exception) {}
            // Instant hard stop flushes speech queue, cancels streams & increments generation ID
            JarvisVoiceService.instance?.triggerHardStop(speakAck = true, isFa = isFa)
            return LocalRouteResult(handled = true, spokenFeedback = null, category = "session_stop", suppressFallbackSpeech = true)
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

    private fun handleLiveMultimodalControls(
        context: Context,
        rawText: String,
        clean: String,
        lower: String,
        isFa: Boolean
    ): LocalRouteResult {
        val norm = NlpNormalizer.cleanLower(rawText)

        // 1. Live Screen Watching Stop
        if (com.sajjad.jarvis.live.LiveScreenWatcher.isActive) {
            val screenStopWords = listOf("دیگه نگاه نکن", "تماشا رو قطع کن", "صفحه رو نببین", "دیگه صفحه رو نگاه نکن", "stop watching", "stop looking at my screen")
            if (screenStopWords.any { norm == it || norm.contains(it) }) {
                com.sajjad.jarvis.live.LiveScreenWatcher.stopWatching(context)
                val reply = if (isFa) "تماشای صفحه متوقف شد قربان." else "Stopped watching screen, sir."
                return LocalRouteResult(handled = true, spokenFeedback = reply, category = "live_screen_stop")
            }
        }

        // 2. Live Audio Listening Stop
        if (com.sajjad.jarvis.live.LiveAudioSessionManager.isActive) {
            val audioStopWords = listOf("تموم شد", "کافیه", "بسه", "دیگه گوش نده", "خب بگو", "stop listening", "done listening")
            if (audioStopWords.any { norm == it || norm.contains(it) }) {
                com.sajjad.jarvis.live.LiveAudioSessionManager.stopAndAsk(context)
                return LocalRouteResult(handled = true, spokenFeedback = null, category = "live_audio_stop")
            }
        }

        // 3. Live Screen Watching Start (Fix 9)
        val screenStartWords = listOf(
            "صفحه گوشیم رو نگاه کن", "به صفحه گوشیم نگاه کن", "صفحمو ببین", "اسکرینم رو نگاه کن",
            "صفحه رو ببین", "صفحه گوشیمو ببین", "به اسکرینم نگاه کن", "اسکرینم رو ببین",
            "صفحهام رو ببین", "صفحه‌ام رو ببین", "صفحه رو نگاه کن",
            "watch my screen", "look at my screen", "start watching my screen"
        )
        if (screenStartWords.any { norm == it || norm.contains(it) || lower.contains(it) }) {
            var question: String? = null
            for (trigger in screenStartWords) {
                if (norm.contains(trigger)) {
                    val after = norm.substringAfter(trigger).trim().removePrefix("و ").removePrefix("ببین ").trim()
                    if (after.length > 2) {
                        question = after
                    }
                    break
                }
            }
            com.sajjad.jarvis.live.LiveScreenWatcher.startWatching(context, question)
            return LocalRouteResult(handled = true, spokenFeedback = null, category = "live_screen_start")
        }

        // 4. Live Audio Listening Start (Fix 9)
        val audioStartWords = listOf(
            "به این گوش کن", "به این آهنگ گوش کن", "به این ویدیو گوش کن", "ببین این چی میگه",
            "گوش کن ببین چه آهنگیه", "گوش کن این چه آهنگیه", "گوش کن ببین چی میگه",
            "گوش کن", "به این گوش بده", "گوش بده ببین چی میگه",
            "listen to this", "listen to this song", "listen to this video", "listen to the audio"
        )
        if (audioStartWords.any { norm == it || norm.contains(it) || lower.contains(it) }) {
            var question: String? = null
            for (trigger in audioStartWords) {
                if (norm.contains(trigger)) {
                    val after = norm.substringAfter(trigger).trim().removePrefix("و ").trim()
                    if (after.length > 2) {
                        question = after
                    }
                    break
                }
            }
            com.sajjad.jarvis.live.LiveAudioSessionManager.startListening(context, question)
            val reply = if (question == null) {
                if (isFa) "در حال گوش دادن به صوت قربان..." else "Listening to audio, sir..."
            } else null
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "live_audio_start")
        }

        return LocalRouteResult(handled = false)
    }

    // Gregorian to Jalali delegation
    fun gregorianToJalali(gy: Int, gm: Int, gd: Int): Triple<Int, Int, Int> {
        return UtilityDomain.gregorianToJalali(gy, gm, gd)
    }
}
