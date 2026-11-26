package com.sajjad.jarvis.live

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.sajjad.jarvis.AppLauncher
import com.sajjad.jarvis.JarvisAccessibilityService
import com.sajjad.jarvis.JarvisOverlayManager
import com.sajjad.jarvis.JarvisVoiceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import java.util.Locale

/**
 * LiveScreenWatcher (OpenJarvis v2.5.0-android):
 *
 * Real-time continuous Live Screen Watching engine with ZERO MediaProjection popups.
 * Uses JarvisAccessibilityService.takeScreenshot() (Android 11+ / API 30+).
 *
 * Maintains a rolling visual ring buffer of keyframes + foreground app + UI text hierarchy.
 * Captures fresh frame at exact millisecond of user question, maintains multi-turn visual history,
 * and auto-timeouts after 3 minutes of inactivity to conserve battery.
 */
object LiveScreenWatcher {

    private const val TAG = "LiveScreenWatcher"
    private const val INACTIVITY_TIMEOUT_MS = 180_000L // 3 minutes
    private const val CADENCE_INTERVAL_MS = 3500L      // 3.5 seconds

    @Volatile
    var isActive: Boolean = false
        private set

    @Volatile
    var currentSessionId: String? = null
        private set

    private val scope = CoroutineScope(Dispatchers.IO)
    private var cadenceJob: Job? = null

    // Rolling visual ring buffer of up to 3 distinct keyframes (Base64 JPEG)
    private val frameRingBuffer = ArrayDeque<String>(3)
    private var lastCapturedFrameBase64: String? = null
    private var lastUiTextHash: Int = 0
    private var lastInteractionTimeMs: Long = 0L

    /**
     * Start continuous live screen watching session.
     */
    fun startWatching(
        context: Context,
        initialQuestion: String? = null,
        onSpokenReply: ((String) -> Unit)? = null
    ) {
        val accessService = JarvisAccessibilityService.instance
        val isFa = isPersian(initialQuestion ?: "")

        // If Accessibility service is not running, prompt user to enable it in Settings with clear Xiaomi/HyperOS path
        if (accessService == null) {
            val msg = if (isFa) {
                "سرویس دسترسی‌پذیری خاموش است؛ در تنظیمات به بخش Downloaded apps رفته و Jarvis را روشن کنید قربان."
            } else {
                "Jarvis Accessibility service is off; in Settings, open Downloaded apps and enable Jarvis, sir."
            }
            JarvisVoiceService.instance?.speakBritish(msg)
            onSpokenReply?.invoke(msg)
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                AppLauncher.launchWithOverlayFallback(context, intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to launch accessibility settings: ${e.message}")
            }
            return
        }

        isActive = true
        currentSessionId = "vis_${System.currentTimeMillis()}"
        lastInteractionTimeMs = System.currentTimeMillis()
        frameRingBuffer.clear()

        // Show live screen watching badge on Floating HUD
        val hudMsg = if (isFa) "در حال مشاهده صفحه — هر سوالی دارید بپرسید [توقف]"
                     else "Watching Screen — Ask anything [Stop]"
        JarvisOverlayManager.instance?.updateState("live_screen_watching", hudMsg)

        scope.launch {
            // Immediate first frame capture
            val firstFrame = accessService.captureScreenBase64Suspend()
            if (!firstFrame.isNullOrBlank()) {
                synchronized(frameRingBuffer) {
                    frameRingBuffer.addLast(firstFrame)
                    lastCapturedFrameBase64 = firstFrame
                }
            }

            if (!initialQuestion.isNullOrBlank()) {
                // User asked trigger + question in one breath
                askScreenQuestion(context, initialQuestion, onSpokenReply)
            } else {
                // Single-shot start confirmation
                val confirmReply = if (isFa) "دارم صفحه رو می‌بینم قربان، بپرس." else "I'm watching your screen, sir. Go ahead."
                withContext(Dispatchers.Main) {
                    JarvisVoiceService.instance?.speakBritish(confirmReply)
                    JarvisOverlayManager.instance?.updateText(confirmReply)
                    JarvisOverlayManager.instance?.updateState("active_conversation")
                    onSpokenReply?.invoke(confirmReply)
                }
            }

            // Start 3.5s background smart cadence loop
            startCadenceLoop(context)
        }

        Log.i(TAG, "LiveScreenWatcher started (session=$currentSessionId)")
    }

    /**
     * Ask a question about the active live screen.
     * Guaranteed to capture fresh live frame at exact millisecond of question.
     */
    fun askScreenQuestion(
        context: Context,
        question: String,
        onReply: ((String) -> Unit)? = null
    ) {
        val accessService = JarvisAccessibilityService.instance
        val sessId = currentSessionId ?: "vis_${System.currentTimeMillis()}"
        lastInteractionTimeMs = System.currentTimeMillis()

        JarvisOverlayManager.instance?.updateState("thinking", "Analyzing screen...")

        scope.launch {
            // 1. Capture guaranteed fresh live frame at exact millisecond
            val freshFrame = accessService?.captureScreenBase64Suspend()
                ?: lastCapturedFrameBase64 ?: ""

            if (freshFrame.isNotBlank()) {
                synchronized(frameRingBuffer) {
                    if (frameRingBuffer.size >= 3) {
                        frameRingBuffer.removeFirst()
                    }
                    frameRingBuffer.addLast(freshFrame)
                    lastCapturedFrameBase64 = freshFrame
                }
            }

            // 2. Extract visible UI text hierarchy and foreground app package
            val uiText = accessService?.dumpUiTree() ?: ""
            val fgApp = accessService?.rootInActiveWindow?.packageName?.toString() ?: ""

            val recentFramesList: List<String> = synchronized(frameRingBuffer) {
                frameRingBuffer.toList().filter { it != freshFrame }
            }

            // 3. Make POST call to VPS /api/vision/live
            var answerText: String? = null
            try {
                val server = getServerUrl(context)
                val u = URL("${server.removeSuffix("/")}/api/vision/live")
                val conn = (u.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 12000
                    readTimeout = 20000
                    doOutput = true
                }

                val body = JSONObject().apply {
                    put("session_id", sessId)
                    put("current_frame_base64", freshFrame)
                    put("recent_frames_base64", JSONArray(recentFramesList))
                    put("ui_text_context", uiText.take(1500))
                    put("foreground_app", fgApp)
                    put("prompt", question)
                    put("language", if (isPersian(question)) "fa" else "en")
                }.toString()

                conn.outputStream.use { it.write(body.toByteArray()) }

                if (conn.responseCode in 200..299) {
                    val respStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val respJson = JSONObject(respStr)
                    answerText = respJson.optString("response", "")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Live vision request failed: ${e.message}", e)
            }

            val isFa = isPersian(question)
            val cleanReply = if (!answerText.isNullOrBlank()) answerText!!
                             else (if (isFa) "قربان، در حال حاضر امکان تحلیل صفحه وجود نداشت." else "Could not analyze the screen content, sir.")

            withContext(Dispatchers.Main) {
                JarvisVoiceService.instance?.let { svc ->
                    svc.speakBritish(cleanReply)
                    JarvisOverlayManager.instance?.updateText(cleanReply)
                    JarvisOverlayManager.instance?.updateState("speaking")
                }
                onReply?.invoke(cleanReply)
            }
        }
    }

    /**
     * Stop live screen watching session.
     */
    fun stopWatching(context: Context) {
        if (!isActive) return
        isActive = false
        cadenceJob?.cancel()
        cadenceJob = null
        frameRingBuffer.clear()
        lastCapturedFrameBase64 = null
        currentSessionId = null

        JarvisOverlayManager.instance?.updateState("standby")
        Log.i(TAG, "LiveScreenWatcher stopped.")
    }

    // -------------------------------------------------------------------------
    // Smart 3.5s Cadence Loop & 3-Min Battery Timeout
    // -------------------------------------------------------------------------
    private fun startCadenceLoop(context: Context) {
        cadenceJob?.cancel()
        cadenceJob = scope.launch {
            while (isActive) {
                delay(CADENCE_INTERVAL_MS)

                // Check 3-minute inactivity timeout
                val idleTime = System.currentTimeMillis() - lastInteractionTimeMs
                if (idleTime >= INACTIVITY_TIMEOUT_MS) {
                    Log.i(TAG, "Live screen watcher auto-paused due to 3-minute inactivity.")
                    withContext(Dispatchers.Main) {
                        val isFa = true
                        val timeoutMsg = if (isFa) "تماشای صفحه جهت ذخیره باتری متوقف شد قربان."
                                         else "Screen watch paused to save battery, sir."
                        JarvisVoiceService.instance?.speakBritish(timeoutMsg)
                        stopWatching(context)
                    }
                    break
                }

                // Check if screen content changed
                val accessService = JarvisAccessibilityService.instance ?: continue
                val currentUiText = accessService.dumpUiTree()
                val currentHash = currentUiText.hashCode()

                if (currentHash != lastUiTextHash) {
                    lastUiTextHash = currentHash
                    val frameB64 = accessService.captureScreenBase64Suspend()
                    if (!frameB64.isNullOrBlank()) {
                        synchronized(frameRingBuffer) {
                            if (frameRingBuffer.size >= 3) {
                                frameRingBuffer.removeFirst()
                            }
                            frameRingBuffer.addLast(frameB64)
                            lastCapturedFrameBase64 = frameB64
                        }
                    }
                }
            }
        }
    }

    private fun isPersian(text: String): Boolean {
        return text.any { it in '\u0600'..'\u06FF' }
    }

    private fun getServerUrl(context: Context): String {
        val prefs = context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
        val raw = prefs.getString("server_url", "http://104.252.175.159:8765") ?: "http://104.252.175.159:8765"
        return raw.trim().removeSuffix("/")
    }
}
