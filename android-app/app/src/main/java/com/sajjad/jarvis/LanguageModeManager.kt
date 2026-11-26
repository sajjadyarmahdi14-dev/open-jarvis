package com.sajjad.jarvis

import android.content.Context
import android.util.Log
import com.sajjad.jarvis.router.NlpNormalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Feature 6: Hands-Free Voice Language Coach & Pocket Live Interpreter Manager.
 *
 * - Practice Coach: Conversational hands-free English or German tutor (en-GB-RyanNeural / de-DE-ConradNeural),
 *   concise spoken corrections, keeping ACTIVE_CONVERSATION smoothly open while walking.
 * - Pocket Interpreter: Pure simultaneous spoken translation to English / German / Persian with zero chatter,
 *   speaking via 100% Male Edge-TTS voices ONLY.
 */
object LanguageModeManager {

    private const val TAG = "LanguageModeManager"

    enum class Mode {
        OFF,
        PRACTICE,
        INTERPRETER
    }

    sealed class CommandAction {
        data class EnterPractice(val targetLanguage: String, val isFa: Boolean) : CommandAction()
        data class EnterInterpreter(val targetLanguage: String, val isFa: Boolean) : CommandAction()
        data class Exit(val isFa: Boolean) : CommandAction()
    }

    @Volatile
    var currentMode: Mode = Mode.OFF
        private set

    @Volatile
    var targetLanguage: String = "en"
        private set

    @Volatile
    private var sessionId: String = "lang_${System.currentTimeMillis()}"

    val isActive: Boolean
        get() = currentMode != Mode.OFF

    private val scope = CoroutineScope(Dispatchers.IO)

    fun getVoiceForLanguage(lang: String?, text: String = ""): String {
        val norm = lang?.trim()?.lowercase(Locale.ROOT) ?: ""
        return when {
            norm in listOf("de", "german", "deutsch", "آلمانی") -> "de-DE-ConradNeural"
            norm in listOf("fa", "persian", "farsi", "فارسی") -> "fa-IR-FaridNeural"
            norm in listOf("en", "english", "انگلیسی") -> "en-GB-RyanNeural"
            text.any { it in '\u0600'..'\u06FF' } -> "fa-IR-FaridNeural"
            text.any { it in "äöüßÄÖÜ" } -> "de-DE-ConradNeural"
            else -> "en-GB-RyanNeural"
        }
    }

    fun parseLanguageCommand(rawText: String, isFa: Boolean): CommandAction? {
        val norm = NlpNormalizer.cleanLower(rawText)
        if (norm.isBlank()) return null

        // Exit triggers
        val exitPracticeTriggers = listOf(
            "پایان تمرین", "تمرین کافیه", "خروج از تمرین", "بسه تمرین",
            "stop practice", "exit language mode", "end practice", "exit practice"
        )
        val exitInterpTriggers = listOf(
            "پایان مترجم", "مترجم کافیه", "خروج از مترجم", "بسه ترجمه",
            "exit interpreter", "stop translating", "stop interpreter", "exit interpreter mode"
        )
        if (exitPracticeTriggers.any { norm.contains(it) } || exitInterpTriggers.any { norm.contains(it) }) {
            return CommandAction.Exit(isFa)
        }

        // Enter Interpreter triggers
        val interpTriggers = listOf(
            "حالت مترجم", "ترجمه همزمان به آلمانی", "ترجمه همزمان به انگلیسی", "ترجمه همزمان به فارسی",
            "ترجمه همزمان", "interpreter mode", "translate live to"
        )
        if (interpTriggers.any { norm.contains(it) }) {
            val lang = detectTargetLangFromCommand(norm, fallback = if (isFa) "en" else "de")
            return CommandAction.EnterInterpreter(lang, isFa)
        }

        // Enter Language Practice Coach triggers
        val practiceTriggers = listOf(
            "حالت تمرین زبان", "تمرین آلمانی", "تمرین انگلیسی", "تمرین زبان",
            "language practice", "practice german", "practice english", "language coach"
        )
        if (practiceTriggers.any { norm.contains(it) }) {
            val lang = detectTargetLangFromCommand(norm, fallback = "en")
            return CommandAction.EnterPractice(lang, isFa)
        }

        return null
    }

    private fun detectTargetLangFromCommand(norm: String, fallback: String): String {
        return when {
            norm.contains("آلمانی") || norm.contains("المانی") || norm.contains("german") || norm.contains("deutsch") -> "de"
            norm.contains("انگلیسی") || norm.contains("english") -> "en"
            norm.contains("فارسی") || norm.contains("persian") || norm.contains("farsi") -> "fa"
            else -> fallback
        }
    }

    fun activateMode(mode: Mode, lang: String, context: Context, isFa: Boolean): String {
        currentMode = mode
        targetLanguage = lang
        sessionId = "lang_${mode.name.lowercase(Locale.US)}_${System.currentTimeMillis()}"

        val langNameFa = when (lang) {
            "de" -> "آلمانی"
            "fa" -> "فارسی"
            else -> "انگلیسی"
        }
        val langNameEn = when (lang) {
            "de" -> "German"
            "fa" -> "Persian"
            else -> "English"
        }

        return if (mode == Mode.PRACTICE) {
            if (lang == "de") {
                "Hallo! Ich bin dein Deutsch-Coach. Lass uns gleich anfangen: Wie geht es dir heute und was hast du vor?"
            } else {
                "Language practice mode active for $langNameEn, sir. Let's begin: how is your day going so far?"
            }
        } else {
            if (isFa) {
                "حالت مترجم همزمان به زبان $langNameFa فعال شد قربان. صحبت کنید."
            } else {
                "Pocket live interpreter to $langNameEn engaged, sir. Speak whenever you're ready."
            }
        }
    }

    fun exitMode(isFa: Boolean): String {
        val prev = currentMode
        currentMode = Mode.OFF
        return if (prev == Mode.PRACTICE) {
            if (isFa) "حالت تمرین زبان به پایان رسید قربان." else "Language practice complete, sir."
        } else {
            if (isFa) "حالت مترجم همزمان خاموش شد قربان." else "Live interpreter disengaged, sir."
        }
    }

    fun handleUserTurn(
        context: Context,
        userText: String,
        turnId: String = "",
        onReply: ((String, String) -> Unit)? = null
    ) {
        val activeMode = currentMode
        val activeLang = targetLanguage
        if (activeMode == Mode.OFF) return

        val modeStr = if (activeMode == Mode.INTERPRETER) "interpreter" else "practice"

        scope.launch {
            val (reply, ttsLang) = callLanguageTurnEndpoint(context, modeStr, activeLang, userText)
            withContext(Dispatchers.Main) {
                onReply?.invoke(reply, ttsLang)
                val svc = JarvisVoiceService.instance
                if (svc != null) {
                    svc.broadcastUiUpdate(assistantReply = reply, turnId = turnId, wakeStatus = "speaking")
                    JarvisOverlayManager.instance?.updateText(reply)
                    svc.speakWithLanguage(reply, ttsLang) {
                        // Keep ACTIVE_CONVERSATION open cleanly after speaking during hands-free session
                        svc.keepActiveConversationAlive(45000L)
                    }
                }
            }
        }
    }

    private suspend fun callLanguageTurnEndpoint(
        context: Context,
        mode: String,
        targetLang: String,
        userText: String
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val prefs = context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
            val server = (prefs.getString("server_url", "http://104.252.175.159:8765")
                ?: "http://104.252.175.159:8765").trim().removeSuffix("/")
            val apiKey = prefs.getString("api_key", "") ?: ""

            val u = URL("$server/api/language/turn")
            conn = (u.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
                connectTimeout = 6000
                readTimeout = 20000
                doOutput = true
            }
            val payload = JSONObject().apply {
                put("mode", mode)
                put("target_language", targetLang)
                put("user_text", userText)
                put("session_id", sessionId)
                put("level", "A1-B1")
            }
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(payload.toString()) }
            if (conn.responseCode in 200..299) {
                val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val json = JSONObject(resp)
                val reply = json.optString("reply", "").trim()
                val respLang = json.optString("target_language", targetLang)
                if (reply.isNotBlank()) {
                    return@withContext Pair(reply, respLang)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Language turn endpoint error: ${e.message}")
        } finally {
            conn?.disconnect()
        }

        val fallback = if (targetLang == "de") {
            "Verstanden. Bitte wiederholen Sie das noch einmal."
        } else if (targetLang == "fa") {
            "متوجه شدم قربان. لطفاً ادامه دهید."
        } else {
            "Understood, sir. Please go ahead with the next phrase."
        }
        Pair(fallback, targetLang)
    }
}
