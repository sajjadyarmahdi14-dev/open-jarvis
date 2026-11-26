package com.sajjad.jarvis

import android.content.ClipboardManager
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
 * Feature 4: Smart Background Clipboard Assistant & Brain Dump Quick Notes.
 *
 * 1. Clipboard Actions: Translate, Summarize, Explain via Tier 2 AI, or Send directly
 *    to Telegram Security Vault with 0 AI tokens! Includes Android 10+ Accessibility fallback.
 * 2. Quick Notes / Brain Dump (0 LLM tokens): Saves notes to SQLite POST /api/notes with
 *    heuristic auto-tagging and reads today's notes cleanly via Male Edge-TTS.
 */
object ClipboardAndNotesEngine {

    private const val TAG = "ClipboardAndNotes"
    private val scope = CoroutineScope(Dispatchers.IO)

    enum class ClipboardAction {
        TRANSLATE,
        SUMMARIZE,
        EXPLAIN,
        SEND_TO_VAULT
    }

    /**
     * Pure-Kotlin extractor for 0-token Brain Dump Quick Note triggers.
     */
    fun extractQuickNoteText(rawText: String, isFa: Boolean): String? {
        val clean = rawText.trim()
        if (clean.isBlank()) return null

        val patterns = listOf(
            Regex("^(?:note\\s+this\\s+down|note\\s+this|save\\s+note|take\\s+a\\s+note|brain\\s+dump|quick\\s+note)\\s*[:\\-]?\\s*(.+)$", RegexOption.IGNORE_CASE),
            Regex("^(?:یادداشت\\s*کن|یادداشت\\s*بردار|نوت\\s*بردار|نوت\\s*بنویس|ایده\\s*بنویس|این\\s+ایده\\s+رو\\s+بنویس|یادداشت)\\s*[:\\-]?\\s*(.+)$")
        )
        for (p in patterns) {
            val m = p.find(clean)
            if (m != null) {
                val content = m.groupValues[1].trim()
                if (content.isNotBlank()) return content
            }
        }
        return null
    }

    fun isReadNotesTrigger(norm: String): Boolean {
        val triggers = listOf(
            "read my notes", "read today's notes", "read todays notes", "show my notes", "check my notes",
            "یادداشتهای امروز رو بخون", "یادداشت های امروز رو بخون", "یادداشت‌های امروز رو بخون",
            "نوتهام رو بخون", "نوت های امروز", "نوت‌های امروز", "نوت هام رو بخون",
            "یادداشت هام رو بخون", "یادداشت‌هام رو بخون", "یادداشت های امروز", "نوتام رو بخون"
        )
        return triggers.any { norm.contains(it) }
    }

    fun detectClipboardAction(norm: String): ClipboardAction? {
        if (norm.contains("کلیپبورد رو بفرست تلگرام") || norm.contains("بفرست تلگرام") && norm.contains("کلیپبورد") ||
            norm.contains("متن کپی شده رو بفرست") || norm.contains("send clipboard to vault") ||
            norm.contains("send clipboard to telegram") || norm.contains("clipboard to vault")
        ) {
            return ClipboardAction.SEND_TO_VAULT
        }
        if (norm.contains("کلیپبورد رو ترجمه") || norm.contains("متنی که کپی کردم رو ترجمه") ||
            norm.contains("ترجمه کلیپبورد") || norm.contains("translate clipboard") ||
            norm.contains("translate copied") || norm.contains("translate what i copied")
        ) {
            return ClipboardAction.TRANSLATE
        }
        if (norm.contains("کلیپبورد رو خلاصه") || norm.contains("متنی که کپی کردم رو خلاصه") ||
            norm.contains("خلاصه کلیپبورد") || norm.contains("summarize clipboard") ||
            norm.contains("summarize copied")
        ) {
            return ClipboardAction.SUMMARIZE
        }
        if (norm.contains("کلیپبورد رو توضیح") || norm.contains("متنی که کپی کردم رو توضیح") ||
            norm.contains("توضیح کلیپبورد") || norm.contains("explain clipboard") ||
            norm.contains("explain copied")
        ) {
            return ClipboardAction.EXPLAIN
        }
        return null
    }

    private suspend fun readClipboardSafely(context: Context): String? = withContext(Dispatchers.Main) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = cm?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val txt = clip.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
                if (!txt.isNullOrBlank()) return@withContext txt
            }
        } catch (e: Exception) {
            Log.w(TAG, "ClipboardManager access notice: ${e.message}")
        }

        // Android 10+ background privacy fallback via Accessibility focused/selected window text
        try {
            val accessFallback = JarvisAccessibilityService.instance?.extractClipboardFallbackText()
            if (!accessFallback.isNullOrBlank()) return@withContext accessFallback
        } catch (e: Exception) {
            Log.w(TAG, "Accessibility clipboard fallback notice: ${e.message}")
        }

        null
    }

    fun handleClipboardCommand(context: Context, action: ClipboardAction, isFa: Boolean) {
        scope.launch {
            val clipText = readClipboardSafely(context)
            if (clipText.isNullOrBlank()) {
                val blockedMsg = if (isFa) {
                    "کلیپبورد خالی است یا اندروید خواندن آن در پس‌زمینه را محدود کرده است؛ لطفاً اپ جارویس را باز کنید قربان."
                } else {
                    "Clipboard is empty or blocked in background by Android privacy settings, sir. Please open Jarvis once or paste it directly."
                }
                speakAndDisplay(blockedMsg)
                return@launch
            }

            if (action == ClipboardAction.SEND_TO_VAULT) {
                val ok = postClipboardToVault(context, clipText)
                val msg = if (ok) {
                    if (isFa) "متن کلیپبورد با موفقیت به تلگرام ارسال شد قربان." else "Clipboard text forwarded to Telegram Vault, sir."
                } else {
                    if (isFa) "ارسال کلیپبورد به صندوق امنیتی با خطا مواجه شد قربان." else "Could not forward clipboard text to Telegram Vault, sir."
                }
                speakAndDisplay(msg)
                return@launch
            }

            val clipIsFa = NlpNormalizer.isPersian(clipText)
            val prompt = when (action) {
                ClipboardAction.TRANSLATE -> if (clipIsFa) {
                    "Translate this copied text into clear, natural English in 2-3 spoken sentences (no markdown): $clipText"
                } else {
                    "متن کپی شده زیر را به فارسی روان و دقیق ترجمه کن (بدون علائم مارک‌داون): $clipText"
                }
                ClipboardAction.SUMMARIZE -> if (isFa) {
                    "متن کپی شده زیر را خیلی خلاصه در ۲ الی ۳ جمله گویا خلاصه کن: $clipText"
                } else {
                    "Concisely summarize this copied text in 2-3 spoken sentences without markdown: $clipText"
                }
                ClipboardAction.EXPLAIN -> if (isFa) {
                    "متن کپی شده زیر را خیلی ساده و واضح در ۲ الی ۳ جمله توضیح بده: $clipText"
                } else {
                    "Explain this copied text clearly and concisely in 2-3 spoken sentences: $clipText"
                }
                ClipboardAction.SEND_TO_VAULT -> ""
            }

            val aiReply = callChatApi(context, prompt)
            val finalMsg = if (!aiReply.isNullOrBlank()) {
                aiReply
            } else {
                if (isFa) "پردازش متن کلیپبورد انجام نشد قربان." else "I could not process the clipboard text, sir."
            }
            speakAndDisplay(finalMsg)
        }
    }

    fun saveQuickNote(context: Context, noteText: String, isFa: Boolean) {
        scope.launch {
            val (ok, category) = postNoteToServer(context, noteText)
            val catFa = when (category) {
                "task" -> "وظیفه"
                "idea" -> "ایده"
                "quote" -> "نقل‌قول"
                else -> "عمومی"
            }
            val msg = if (ok) {
                if (isFa) "در بخش $catFa یادداشت شد قربان." else "Saved note under $category, sir."
            } else {
                if (isFa) "خطا در ذخیره یادداشت روی سرور قربان." else "Could not save note to VPS right now, sir."
            }
            speakAndDisplay(msg)
        }
    }

    fun readTodayNotes(context: Context, isFa: Boolean) {
        scope.launch {
            val notes = fetchNotesFromServer(context, today = true, limit = 15)
            val msg = if (notes.isEmpty()) {
                if (isFa) "امروز هیچ یادداشتی ثبت نشده است قربان." else "You have no notes recorded today, sir."
            } else {
                val maxShow = notes.take(5)
                if (isFa) {
                    val items = maxShow.mapIndexed { idx, (_, text) -> "${idx + 1}: $text" }.joinToString(". ")
                    "امروز ${notes.size} یادداشت دارید قربان. $items."
                } else {
                    val items = maxShow.mapIndexed { idx, (_, text) -> "Number ${idx + 1}: $text" }.joinToString(". ")
                    "You have ${notes.size} notes from today, sir. $items."
                }
            }
            speakAndDisplay(msg)
        }
    }

    private suspend fun postClipboardToVault(context: Context, text: String): Boolean = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val server = getServerUrl(context)
            val u = URL("$server/api/security/clipboard")
            conn = (u.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connectTimeout = 5000
                readTimeout = 8000
                doOutput = true
            }
            val body = JSONObject().put("text", text).put("source", "android_clipboard")
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()) }
            conn.responseCode in 200..299
        } catch (e: Exception) {
            Log.w(TAG, "postClipboardToVault error: ${e.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }

    private suspend fun postNoteToServer(context: Context, text: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val prefs = context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
            val hasTelegram = !prefs.getString("security_telegram_token", "").isNullOrBlank() &&
                !prefs.getString("security_telegram_chat_id", "").isNullOrBlank()
            val server = getServerUrl(context)
            val u = URL("$server/api/notes")
            conn = (u.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connectTimeout = 5000
                readTimeout = 8000
                doOutput = true
            }
            val body = JSONObject().apply {
                put("text", text)
                put("forward_telegram", hasTelegram)
            }
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()) }
            if (conn.responseCode in 200..299) {
                val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val cat = JSONObject(resp).optString("category", "general")
                Pair(true, cat)
            } else {
                Pair(false, "general")
            }
        } catch (e: Exception) {
            Log.w(TAG, "postNoteToServer error: ${e.message}")
            Pair(false, "general")
        } finally {
            conn?.disconnect()
        }
    }

    private suspend fun fetchNotesFromServer(
        context: Context,
        today: Boolean,
        limit: Int
    ): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val server = getServerUrl(context)
            val u = URL("$server/api/notes?today=$today&limit=$limit")
            conn = (u.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 8000
            }
            if (conn.responseCode in 200..299) {
                val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val arr = JSONObject(resp).optJSONArray("notes") ?: return@withContext emptyList()
                val out = mutableListOf<Pair<String, String>>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val cat = obj.optString("category", "general")
                    val txt = obj.optString("text", "")
                    if (txt.isNotBlank()) out.add(Pair(cat, txt))
                }
                return@withContext out
            }
            emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "fetchNotesFromServer error: ${e.message}")
            emptyList()
        } finally {
            conn?.disconnect()
        }
    }

    private suspend fun callChatApi(context: Context, prompt: String): String? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val prefs = context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
            val apiKey = prefs.getString("api_key", "") ?: ""
            val server = getServerUrl(context)
            val u = URL("$server/api/chat")
            conn = (u.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
                connectTimeout = 6000
                readTimeout = 20000
                doOutput = true
            }
            val body = JSONObject().put("message", prompt).put("tts", false)
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()) }
            if (conn.responseCode in 200..299) {
                val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                JSONObject(resp).optString("response", "").takeIf { it.isNotBlank() }
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Clipboard chat AI error: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private suspend fun speakAndDisplay(msg: String) {
        withContext(Dispatchers.Main) {
            JarvisVoiceService.instance?.let { svc ->
                svc.broadcastUiUpdate(assistantReply = msg)
                JarvisOverlayManager.instance?.updateText(msg)
                svc.speakBritish(msg)
            }
        }
    }

    private fun getServerUrl(context: Context): String {
        val prefs = context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
        return (prefs.getString("server_url", "http://104.252.175.159:8765")
            ?: "http://104.252.175.159:8765").trim().removeSuffix("/")
    }
}
