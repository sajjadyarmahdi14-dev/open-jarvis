package com.sajjad.jarvis

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

// ---------------------------------------------------------------------------
// Futuristic Dark Sci-Fi Palette
// ---------------------------------------------------------------------------
private val DarkBackground = Color(0xFF070A10)
private val DarkSurface = Color(0xFF0D1424)
private val DarkCard = Color(0xFF131D33)
private val CyanNeon = Color(0xFF00E5FF)
private val CyanDim = Color(0xFF06B6D4)
private val VioletAccent = Color(0xFF8B5CF6)
private val GreenActive = Color(0xFF10B981)
private val RedOffline = Color(0xFFEF4444)
private val AmberWarning = Color(0xFFF59E0B)
private val TextPrimary = Color(0xFFF8FAFC)
private val TextSecondary = Color(0xFF94A3B8)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val turnId: String = "",
    val isUser: Boolean,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class FreeModelItem(
    val id: String,
    val name: String,
    val contextLength: Int = 32768,
    val description: String = ""
)

private val CuratedFreeModels = listOf(
    FreeModelItem(
        "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free",
        "NVIDIA: Nemotron-3 Nano Omni (Free)",
        32768,
        "Omni-modal reasoning model with fast real-time latency (Default)"
    ),
    FreeModelItem(
        "google/gemma-3-27b-it:free",
        "Google: Gemma 3 27B Instruct (Free)",
        32768,
        "Google cutting-edge open weight instruction-tuned model"
    ),
    FreeModelItem(
        "meta-llama/llama-3.3-70b-instruct:free",
        "Meta: Llama 3.3 70B Instruct (Free)",
        131072,
        "State-of-the-art 70B open model with 128k context"
    ),
    FreeModelItem(
        "qwen/qwen-2.5-72b-instruct:free",
        "Qwen: Qwen 2.5 72B Instruct (Free)",
        32768,
        "High intelligence Alibaba instruction model"
    ),
    FreeModelItem(
        "mistralai/mistral-small-3.1-24b-instruct:free",
        "Mistral: Mistral Small 3.1 24B (Free)",
        32768,
        "Efficient low-latency multimodal reasoning"
    )
)

private fun pcmToWavBytes(pcmData: ByteArray, sampleRate: Int = 16000): ByteArray {
    val totalDataLen = pcmData.size + 36
    val byteRate = sampleRate * 1 * 16 / 8
    val blockAlign: Short = 2
    val bitsPerSample: Short = 16
    val channels: Short = 1

    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray(Charsets.US_ASCII))
    header.putInt(totalDataLen)
    header.put("WAVE".toByteArray(Charsets.US_ASCII))
    header.put("fmt ".toByteArray(Charsets.US_ASCII))
    header.putInt(16)
    header.putShort(1.toShort())
    header.putShort(channels)
    header.putInt(sampleRate)
    header.putInt(byteRate)
    header.putShort(blockAlign)
    header.putShort(bitsPerSample)
    header.put("data".toByteArray(Charsets.US_ASCII))
    header.putInt(pcmData.size)

    val out = ByteArray(44 + pcmData.size)
    System.arraycopy(header.array(), 0, out, 0, 44)
    System.arraycopy(pcmData, 0, out, 44, pcmData.size)
    return out
}

class MainActivity : ComponentActivity(), JarvisVoiceService.VoiceEventListener {

    private var serverUrlState = mutableStateOf("http://104.252.175.159:8765")
    private var activeModelState = mutableStateOf("nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free")
    private var apiKeyState = mutableStateOf("")
    private var isConnectedState = mutableStateOf(false)
    private var isServiceActiveState = mutableStateOf(true)
    private var isTtsMutedState = mutableStateOf(false)
    private var voiceStateText = mutableStateOf("STANDBY: Say \"Jarvis\"")
    private var audioAmplitudeState = mutableFloatStateOf(0.0f)
    private var messagesState = mutableStateListOf<ChatMessage>()
    private var isProcessingState = mutableStateOf(false)

    private var healthCheckJob: Job? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        startVoiceService()
        if (perms[Manifest.permission.RECORD_AUDIO] == true) {
            JarvisVoiceService.startRecordingIfPermitted(applicationContext)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        loadPreferences()
        requestPermissionsAndSetup()
        startVoiceService()
        startHealthCheckLoop()
        registerUiUpdateReceiver()

        // Step 10: Dynamic app indexer & VPS sync
        AppLauncher.scanAllInstalledApps(applicationContext)
        AppLauncher.syncAppsToServer(applicationContext, serverUrlState.value)

        if (intent?.getBooleanExtra("trigger_voice", false) == true) {
            triggerVoiceListen()
        }

        setContent {
            JarvisTheme {
                var showSettings by remember { mutableStateOf(false) }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .statusBarsPadding()
                                .navigationBarsPadding()
                        ) {
                            // Top Status Bar
                            JarvisTopBar(
                                serverUrl = serverUrlState.value,
                                activeModel = activeModelState.value,
                                isConnected = isConnectedState.value,
                                isServiceActive = isServiceActiveState.value,
                                isTtsMuted = isTtsMutedState.value,
                                onToggleService = { enable ->
                                    isServiceActiveState.value = enable
                                    if (enable) startVoiceService() else stopVoiceService()
                                },
                                onToggleTtsMute = { toggleTtsMute() },
                                onOpenSettings = { showSettings = true }
                            )

                            // Animated Voice Orb & Real-Time Status Indicator
                            JarvisVoiceOrbSection(
                                voiceState = voiceStateText.value,
                                amplitude = audioAmplitudeState.floatValue,
                                onOrbClick = { triggerVoiceListen() }
                            )

                            // Native Chat Messages Stream
                            val listState = rememberLazyListState()
                            LaunchedEffect(messagesState.size) {
                                if (messagesState.isNotEmpty()) {
                                    listState.animateScrollToItem(messagesState.size - 1)
                                }
                            }

                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                if (messagesState.isEmpty()) {
                                    item {
                                        JarvisWelcomeCard()
                                    }
                                }
                                items(messagesState, key = { it.id }) { msg ->
                                    ChatMessageBubble(msg = msg)
                                }
                            }

                            // Quick Action Chips
                            QuickActionChipsRow(
                                onChipClick = { prompt ->
                                    sendMessage(prompt)
                                }
                            )

                            // Bottom Input Bar
                            JarvisBottomInputBar(
                                isProcessing = isProcessingState.value,
                                onSendMessage = { text ->
                                    sendMessage(text)
                                },
                                onMicClick = {
                                    triggerVoiceListen()
                                }
                            )
                        }

                        // Native Settings Screen Dialog
                        if (showSettings) {
                            JarvisSettingsDialog(
                                currentUrl = serverUrlState.value,
                                currentModel = activeModelState.value,
                                currentApiKey = apiKeyState.value,
                                onDismiss = { showSettings = false },
                                onSave = { newUrl, newModel, newApiKey ->
                                    savePreferences(newUrl, newModel, newApiKey)
                                    showSettings = false
                                    startHealthCheckLoop()
                                },
                                onAssistantSetup = {
                                    openAssistantSettings()
                                },
                                onOverlaySetup = {
                                    openOverlaySettings()
                                },
                                onBatterySetup = {
                                    openBatteryOptimizationSettings()
                                },
                                onAccessibilitySetup = {
                                    openAccessibilitySettings()
                                },
                                onNotificationListenerSetup = {
                                    openNotificationListenerSettings()
                                },
                                onProtocolPermissionsSetup = {
                                    openDndAndWriteSettings()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        JarvisVoiceService.serviceListener = this
        isServiceActiveState.value = JarvisVoiceService.isServiceRunning
    }

    override fun onDestroy() {
        super.onDestroy()
        healthCheckJob?.cancel()
        JarvisVoiceService.serviceListener = null
        try {
            unregisterReceiver(uiUpdateReceiver)
        } catch (_: Throwable) {}
    }

    // -----------------------------------------------------------------------
    // Preference & State Management
    // -----------------------------------------------------------------------

    private fun loadPreferences() {
        val prefs = getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
        val rawUrl = prefs.getString("server_url", "http://104.252.175.159:8765")?.trim() ?: "http://104.252.175.159:8765"
        val cleanUrl = if (rawUrl.isBlank() || rawUrl == "http://127.0.0.1:8765") "http://104.252.175.159:8765" else rawUrl.removeSuffix("/")
        serverUrlState.value = cleanUrl

        val rawModel = prefs.getString("active_model", "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free") ?: "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free"
        activeModelState.value = if (rawModel == "openrouter/free" || rawModel.isBlank()) "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free" else rawModel

        val rawApiKey = prefs.getString("api_key", "")?.trim() ?: ""
        apiKeyState.value = rawApiKey

        isTtsMutedState.value = prefs.getBoolean("is_tts_muted", false)
    }

    private fun toggleTtsMute() {
        val newMuted = !isTtsMutedState.value
        isTtsMutedState.value = newMuted
        val prefs = getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_tts_muted", newMuted).apply()
        val msg = if (newMuted) "Speech output muted" else "Speech output enabled"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun savePreferences(newUrl: String, newModel: String, newApiKey: String) {
        val cleanUrl = newUrl.trim().removeSuffix("/")
        val cleanModel = newModel.trim()
        val cleanApiKey = newApiKey.trim()
        val prefs = getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("server_url", cleanUrl)
            .putString("active_model", cleanModel)
            .putString("api_key", cleanApiKey)
            .apply()
        serverUrlState.value = cleanUrl
        activeModelState.value = cleanModel
        apiKeyState.value = cleanApiKey
        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
    }

    // -----------------------------------------------------------------------
    // VoiceEventListener Implementation
    // -----------------------------------------------------------------------

    override fun onWakeStateChanged(state: String) {
        runOnUiThread {
            voiceStateText.value = when (state) {
                "listening_cmd" -> "LISTENING (Tap to Stop)..."
                "processing" -> "THINKING..."
                "speaking" -> "SPEAKING..."
                "paused", "sleep" -> "SLEEPING / MUTED"
                "empty_stt" -> "Didn't catch that (empty STT)"
                "local_action" -> "Local Action (0 tokens)"
                "asking_ai" -> "Asking AI..."
                else -> "STANDBY: Say \"Jarvis\""
            }
        }
    }

    override fun onSpeechStarted() {
        runOnUiThread {
            voiceStateText.value = "LISTENING (Tap to Stop)..."
        }
    }

    override fun onSpeechEnded() {
        runOnUiThread {
            voiceStateText.value = "TRANSCRIBING..."
        }
    }

    override fun onPartialSpeech(text: String) {}

    private fun handleUserMessage(userText: String, turnId: String = "") {
        val clean = userText.trim()
        if (clean.isBlank()) return
        runOnUiThread {
            if (turnId.isNotBlank() && messagesState.any { it.isUser && it.turnId == turnId }) {
                return@runOnUiThread
            }
            val recentDup = messagesState.lastOrNull { it.isUser }
            val now = System.currentTimeMillis()
            if (recentDup != null && recentDup.text == clean && (now - recentDup.timestamp) < 2500L) {
                return@runOnUiThread
            }
            messagesState.add(ChatMessage(turnId = turnId, isUser = true, text = clean))
        }
    }

    private fun handleAssistantMessage(response: String, turnId: String = "", isAppend: Boolean = false) {
        val clean = response.trim()
        if (clean.isBlank()) return
        runOnUiThread {
            if (turnId.isNotBlank()) {
                val existingIndex = messagesState.indexOfLast { !it.isUser && it.turnId == turnId }
                if (existingIndex >= 0) {
                    val existing = messagesState[existingIndex]
                    val updatedText = if (isAppend) {
                        if (existing.text.contains(clean)) existing.text
                        else "${existing.text} $clean".trim()
                    } else {
                        clean
                    }
                    messagesState[existingIndex] = existing.copy(text = updatedText)
                    return@runOnUiThread
                }
            }
            val recentDup = messagesState.lastOrNull { !it.isUser }
            if (recentDup != null && recentDup.text == clean && (System.currentTimeMillis() - recentDup.timestamp) < 2000L) {
                return@runOnUiThread
            }
            messagesState.add(ChatMessage(turnId = turnId, isUser = false, text = clean))
        }
    }

    override fun onFinalSpeech(userText: String, turnId: String) {
        handleUserMessage(userText, turnId)
    }

    override fun onAssistantResponse(response: String, turnId: String, isAppend: Boolean) {
        handleAssistantMessage(response, turnId, isAppend)
    }

    override fun onTtsStarted() {
        runOnUiThread {
            voiceStateText.value = "SPEAKING..."
        }
    }

    override fun onTtsFinished() {
        runOnUiThread {
            voiceStateText.value = "STANDBY: Say \"Jarvis\""
        }
    }

    override fun onAmplitudeChanged(amplitude: Float) {
        runOnUiThread {
            audioAmplitudeState.floatValue = amplitude
        }
    }

    // -----------------------------------------------------------------------
    // Background UI Update Broadcast Receiver
    // -----------------------------------------------------------------------

    private val uiUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val userText = intent?.getStringExtra("user_text")
            val assistantReply = intent?.getStringExtra("assistant_reply")
            val turnId = intent?.getStringExtra("turn_id") ?: ""
            val isAppend = intent?.getBooleanExtra("is_append", false) ?: false
            val wakeStatus = intent?.getStringExtra("wake_status")
            val amp = intent?.getFloatExtra("amplitude", -1f) ?: -1f

            // Avoid double delivery when MainActivity is active and attached as direct serviceListener
            if (JarvisVoiceService.serviceListener === this@MainActivity) {
                if (!wakeStatus.isNullOrBlank()) {
                    onWakeStateChanged(wakeStatus)
                }
                if (amp >= 0f) {
                    audioAmplitudeState.floatValue = amp
                }
                return
            }

            if (!userText.isNullOrBlank()) {
                handleUserMessage(userText, turnId)
            }
            if (!assistantReply.isNullOrBlank()) {
                handleAssistantMessage(assistantReply, turnId, isAppend)
            }
            if (!wakeStatus.isNullOrBlank()) {
                onWakeStateChanged(wakeStatus)
            }
            if (amp >= 0f) {
                audioAmplitudeState.floatValue = amp
            }
        }
    }

    private fun registerUiUpdateReceiver() {
        val uiFilter = IntentFilter("com.openjarvis.ACTION_UI_UPDATE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(this, uiUpdateReceiver, uiFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(uiUpdateReceiver, uiFilter)
        }
    }

    // -----------------------------------------------------------------------
    // Core Service & Health Actions
    // -----------------------------------------------------------------------

    private fun startVoiceService() {
        try {
            val intent = Intent(this, JarvisVoiceService::class.java).apply {
                action = JarvisVoiceService.ACTION_START
            }
            ContextCompat.startForegroundService(this, intent)
            isServiceActiveState.value = true
        } catch (e: Exception) {
            Log.e("MainActivity", "startVoiceService error: ${e.message}")
        }
    }

    private fun stopVoiceService() {
        try {
            val intent = Intent(this, JarvisVoiceService::class.java).apply {
                action = JarvisVoiceService.ACTION_STOP
            }
            startService(intent)
            isServiceActiveState.value = false
        } catch (_: Exception) {}
    }

    fun triggerVoiceListen() {
        val service = JarvisVoiceService.instance
        if (service?.isTtsSpeaking == true || service?.acousticState == JarvisVoiceService.AcousticState.SPEAKING) {
            service.triggerBargeInAbort()
            voiceStateText.value = "INTERRUPTED: Say \"Jarvis\""
        } else if (service?.isListeningOrRecording() == true) {
            service.toggleTapToTalk()
            voiceStateText.value = "TRANSCRIBING..."
        } else {
            service?.toggleTapToTalk() ?: JarvisVoiceService.forceStartListening(this)
            voiceStateText.value = "LISTENING (Tap to Stop)..."
        }
    }

    private fun startHealthCheckLoop() {
        healthCheckJob?.cancel()
        healthCheckJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val ok = checkServerHealth(serverUrlState.value)
                withContext(Dispatchers.Main) {
                    isConnectedState.value = ok
                }
                delay(6000)
            }
        }
    }

    private fun checkServerHealth(urlStr: String): Boolean {
        return try {
            val u = URL("${urlStr.removeSuffix("/")}/api/health")
            val conn = u.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "GET"
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        }
    }

    private fun speakText(text: String) {
        if (!isTtsMutedState.value) {
            val svc = JarvisVoiceService.instance
            if (svc != null) {
                svc.speakBritish(text)
            } else {
                try {
                    val speakIntent = Intent(applicationContext, JarvisVoiceService::class.java).apply {
                        action = JarvisVoiceService.ACTION_SPEAK_TEXT
                        putExtra(JarvisVoiceService.EXTRA_SPEAK_TEXT, text)
                    }
                    ContextCompat.startForegroundService(applicationContext, speakIntent)
                } catch (_: Exception) {}
            }
        }
    }

    private fun sendMessage(text: String) {
        val clean = text.trim()
        if (clean.isBlank() || isProcessingState.value) return

        val turnId = UUID.randomUUID().toString()
        handleUserMessage(clean, turnId)

        // Check Zero-Token LocalCommandRouter first for typed chat!
        val localResult = LocalCommandRouter.route(applicationContext, clean)
        if (localResult.handled) {
            val isFa = clean.any { it in '\u0600'..'\u06FF' }
            val reply = localResult.spokenFeedback ?: (if (isFa) "انجام شد قربان." else "Done, sir.")
            handleAssistantMessage(reply, turnId, false)
            speakText(reply)
            isProcessingState.value = false
            voiceStateText.value = "STANDBY: Say \"Jarvis\""
            return
        }

        isProcessingState.value = true
        voiceStateText.value = "THINKING..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val server = serverUrlState.value.removeSuffix("/")
                val u = URL("$server/api/chat")
                val conn = u.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.setRequestProperty("Accept", "application/json")
                val currentKey = apiKeyState.value.trim()
                if (currentKey.isNotBlank()) {
                    conn.setRequestProperty("Authorization", "Bearer $currentKey")
                }
                conn.connectTimeout = 6000
                conn.readTimeout = 25000
                conn.doOutput = true

                val (batteryPct, isCharging) = getBatteryTelemetry()
                val body = JSONObject().apply {
                    put("message", clean)
                    put("tts", false)
                    put("device_battery", batteryPct)
                    put("device_charging", isCharging)
                }

                OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()) }

                if (conn.responseCode in 200..299) {
                    val respStr = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val json = JSONObject(respStr)
                    val reply = json.optString("response", "Done, sir.")
                    val clientActions = json.optJSONArray("client_actions")

                    withContext(Dispatchers.Main) {
                        handleAssistantMessage(reply, turnId, false)
                        isProcessingState.value = false
                        voiceStateText.value = "STANDBY: Say \"Jarvis\""
                    }

                    // Execute client actions via Unified ActionDispatcher
                    if (clientActions != null) {
                        ActionDispatcher.dispatchActions(applicationContext, clientActions) { feedback ->
                            handleAssistantMessage(feedback, turnId, true)
                            if (!isTtsMutedState.value) {
                                val svc = JarvisVoiceService.instance
                                if (svc != null) {
                                    svc.speakBritish(feedback)
                                }
                            }
                        }
                    }

                    // Read out reply via TTS (guaranteed single-flight loud Edge-TTS)
                    if (!isTtsMutedState.value) {
                        val svc = JarvisVoiceService.instance
                        if (svc != null) {
                            svc.speakBritish(reply)
                        } else {
                            try {
                                val speakIntent = Intent(applicationContext, JarvisVoiceService::class.java).apply {
                                    action = JarvisVoiceService.ACTION_SPEAK_TEXT
                                    putExtra(JarvisVoiceService.EXTRA_SPEAK_TEXT, reply)
                                }
                                ContextCompat.startForegroundService(applicationContext, speakIntent)
                            } catch (_: Exception) {}
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        handleAssistantMessage("Server returned HTTP ${conn.responseCode}", turnId, false)
                        isProcessingState.value = false
                        voiceStateText.value = "STANDBY: Say \"Jarvis\""
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    handleAssistantMessage("Connection error: ${e.message}", turnId, false)
                    isProcessingState.value = false
                    voiceStateText.value = "STANDBY: Say \"Jarvis\""
                }
            }
        }
    }

    private fun executeClientAction(act: JSONObject) {
        try {
            ActionDispatcher.dispatchAction(applicationContext, act) { feedback ->
                handleAssistantMessage(feedback, "", true)
            }
        } catch (_: Exception) {}
    }

    private fun getBatteryTelemetry(): Pair<Int, Boolean> {
        return try {
            val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val b = registerReceiver(null, ifilter)
            val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
            val status = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            Pair(pct, isCharging)
        } catch (_: Exception) {
            Pair(-1, false)
        }
    }

    // -----------------------------------------------------------------------
    // System Settings Helpers (Pillar 1)
    // -----------------------------------------------------------------------

    private fun openAssistantSettings() {
        try {
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Open Settings -> Default Apps -> Assist app", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open overlay settings", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("BatteryLife")
    private fun openBatteryOptimizationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "Battery optimization already disabled", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) {}
            }
        }
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Open Settings -> Accessibility -> Jarvis", Toast.LENGTH_LONG).show()
        }
    }

    private fun openNotificationListenerSettings() {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open Notification Listener settings: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openDndAndWriteSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                if (nm?.isNotificationPolicyAccessGranted != true) {
                    val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    return
                }
                if (!Settings.System.canWrite(this)) {
                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    return
                }
                Toast.makeText(this, "DND and Write permissions are already granted!", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open permissions settings: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestPermissionsAndSetup() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
            perms.add(Manifest.permission.READ_MEDIA_AUDIO)
            perms.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}

// ---------------------------------------------------------------------------
// Composable UI Components
// ---------------------------------------------------------------------------

@Composable
private fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = DarkBackground,
            surface = DarkSurface,
            primary = CyanNeon,
            secondary = VioletAccent,
            onBackground = TextPrimary,
            onSurface = TextPrimary
        ),
        content = content
    )
}

@Composable
private fun JarvisTopBar(
    serverUrl: String,
    activeModel: String,
    isConnected: Boolean,
    isServiceActive: Boolean,
    isTtsMuted: Boolean,
    onToggleService: (Boolean) -> Unit,
    onToggleTtsMute: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Surface(
        color = DarkSurface,
        modifier = Modifier
            .fillMaxWidth()
            .border(width = 0.5.dp, color = CyanNeon.copy(alpha = 0.15f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Column (weight 1f takes available space, never pushes right controls off-screen)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "J.A.R.V.I.S.",
                        color = CyanNeon,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (isConnected) GreenActive else RedOffline)
                    )
                }
                val host = serverUrl.removePrefix("http://").removePrefix("https://").trimEnd('/')
                val statusText = if (isServiceActive) "● Listening" else "● Ready"
                Text(
                    text = "$host  $statusText",
                    color = TextSecondary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Right Controls Row (wrapContentWidth, always anchored to the right edge and visible)
            Row(
                modifier = Modifier.wrapContentWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                // Compact 24/7 Switch (scale 0.8)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    Switch(
                        checked = isServiceActive,
                        onCheckedChange = onToggleService,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = CyanNeon,
                            checkedTrackColor = CyanDim.copy(alpha = 0.3f),
                            uncheckedThumbColor = TextSecondary,
                            uncheckedTrackColor = DarkCard
                        ),
                        modifier = Modifier
                            .scale(0.8f)
                            .height(28.dp)
                    )
                    Text(
                        text = "24/7",
                        color = if (isServiceActive) CyanNeon else TextSecondary,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Speaker Mute/Unmute (36.dp)
                IconButton(
                    onClick = onToggleTtsMute,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (isTtsMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        contentDescription = if (isTtsMuted) "Unmute Speech" else "Mute Speech",
                        tint = if (isTtsMuted) RedOffline else CyanNeon,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Settings Gear (36.dp, bright Cyan) - ALWAYS 100% visible and tappable
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = CyanNeon,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun JarvisVoiceOrbSection(
    voiceState: String,
    amplitude: Float,
    onOrbClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "OrbPulse")
        val breath by infiniteTransition.animateFloat(
            initialValue = 0.85f,
            targetValue = 1.15f,
            animationSpec = infiniteRepeatable(
                animation = tween(2400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "OrbBreath"
        )

        val rotation by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(4000, easing = LinearEasing)
            ),
            label = "OrbSpin"
        )

        val isListening = voiceState.contains("LISTENING")
        val isThinking = voiceState.contains("THINKING")
        val isSpeaking = voiceState.contains("SPEAKING")
        val isMuted = voiceState.contains("MUTED") || voiceState.contains("SLEEPING")

        val orbColor by animateColorAsState(
            targetValue = when {
                isListening -> Color(0xFF00FF88)
                isThinking -> VioletAccent
                isSpeaking -> CyanNeon
                isMuted -> AmberWarning
                else -> CyanNeon
            },
            label = "OrbColor"
        )

        // Live MIC LIVE indicator row + dynamic dB bars
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(bottom = 12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(Color(0xFF00FF88), CircleShape)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "MIC LIVE",
                color = Color(0xFF00FF88),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.width(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                val clampedAmp = amplitude.coerceIn(0f, 1f)
                val bars = listOf(0.15f, 0.35f, 0.60f, 0.85f)
                bars.forEach { threshold ->
                    val isActive = clampedAmp >= threshold
                    val barHeight = when (threshold) {
                        0.15f -> 6.dp
                        0.35f -> 10.dp
                        0.60f -> 14.dp
                        else -> 18.dp
                    }
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(barHeight)
                            .background(
                                if (isActive) Color(0xFF00FF88) else Color(0xFF00FF88).copy(alpha = 0.25f),
                                RoundedCornerShape(1.dp)
                            )
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .size(140.dp)
                .clickable { onOrbClick() },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val baseRadius = size.minDimension / 3.4f
                val dynamicRadius = if (isListening) {
                    baseRadius + (amplitude * 35f)
                } else {
                    baseRadius * breath
                }

                // Outer ambient glow ring
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(orbColor.copy(alpha = 0.35f), Color.Transparent),
                        center = center,
                        radius = dynamicRadius * 1.5f
                    ),
                    radius = dynamicRadius * 1.5f,
                    center = center
                )

                // Middle pulsing wave ring
                drawCircle(
                    color = orbColor.copy(alpha = 0.5f),
                    radius = dynamicRadius * 1.15f,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )

                // Core glowing orb
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(orbColor, orbColor.copy(alpha = 0.7f), DarkBackground),
                        center = center,
                        radius = dynamicRadius
                    ),
                    radius = dynamicRadius,
                    center = center
                )
            }

            // Center Icon / State glyph
            Icon(
                imageVector = when {
                    isListening -> Icons.Default.Mic
                    isThinking -> Icons.Default.Psychology
                    isSpeaking -> Icons.Default.GraphicEq
                    isMuted -> Icons.Default.MicOff
                    else -> Icons.Default.Sensors
                },
                contentDescription = null,
                tint = DarkBackground,
                modifier = Modifier.size(36.dp)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // State Badge
        Surface(
            color = DarkCard,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, orbColor.copy(alpha = 0.4f))
        ) {
            Text(
                text = voiceState,
                color = orbColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun JarvisWelcomeCard() {
    Surface(
        color = DarkCard,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, CyanNeon.copy(alpha = 0.2f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "⚡ J.A.R.V.I.S. 100% NATIVE ENGINE",
                color = CyanNeon,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Always-on 24/7 background voice assistant is online. Say \"Jarvis\" anytime, even with screen locked or app swiped closed.",
                color = TextSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun ChatMessageBubble(msg: ChatMessage) {
    val alignment = if (msg.isUser) Alignment.CenterEnd else Alignment.CenterStart
    val bgColor = if (msg.isUser) Color(0xFF1E293B) else Color(0xFF0F2236)
    val borderColor = if (msg.isUser) Color.Transparent else CyanNeon.copy(alpha = 0.3f)
    val textColor = if (msg.isUser) TextPrimary else CyanNeon.copy(alpha = 0.95f)

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        Surface(
            color = bgColor,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (msg.isUser) 16.dp else 4.dp,
                bottomEnd = if (msg.isUser) 4.dp else 16.dp
            ),
            border = BorderStroke(0.8.dp, borderColor),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (!msg.isUser) {
                    Text(
                        text = "J.A.R.V.I.S.",
                        color = CyanDim,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                Text(
                    text = msg.text,
                    color = textColor,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            }
        }
    }
}

@Composable
private fun QuickActionChipsRow(onChipClick: (String) -> Unit) {
    val chips = listOf(
        "🔋 Battery" to "check battery status",
        "🔦 Flashlight" to "toggle flashlight",
        "📱 Telegram" to "open telegram",
        "💤 Stop Listening" to "jarvis stop listening"
    )

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(chips) { (label, command) ->
            Surface(
                color = DarkCard,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(0.6.dp, CyanNeon.copy(alpha = 0.25f)),
                modifier = Modifier.clickable { onChipClick(command) }
            ) {
                Text(
                    text = label,
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun JarvisBottomInputBar(
    isProcessing: Boolean,
    onSendMessage: (String) -> Unit,
    onMicClick: () -> Unit
) {
    var textInput by remember { mutableStateOf("") }

    Surface(
        color = DarkSurface,
        modifier = Modifier
            .fillMaxWidth()
            .border(width = 0.5.dp, color = CyanNeon.copy(alpha = 0.15f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Push-to-Talk Mic Button
            IconButton(
                onClick = onMicClick,
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(CyanNeon.copy(alpha = 0.15f))
                    .border(1.dp, CyanNeon.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Push to Talk",
                    tint = CyanNeon
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Text Input Field
            TextField(
                value = textInput,
                onValueChange = { textInput = it },
                placeholder = {
                    Text(
                        text = "Ask J.A.R.V.I.S. or say \"Jarvis\"...",
                        color = TextSecondary.copy(alpha = 0.6f),
                        fontSize = 13.sp
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 46.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = DarkCard,
                    unfocusedContainerColor = DarkCard,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = CyanNeon,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                shape = RoundedCornerShape(24.dp),
                singleLine = true
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Send Button
            IconButton(
                onClick = {
                    if (textInput.isNotBlank()) {
                        onSendMessage(textInput)
                        textInput = ""
                    }
                },
                enabled = !isProcessing && textInput.isNotBlank(),
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(if (textInput.isNotBlank()) CyanNeon else DarkCard)
            ) {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = "Send",
                    tint = if (textInput.isNotBlank()) DarkBackground else TextSecondary
                )
            }
        }
    }
}

@Composable
private fun JarvisSettingsDialog(
    currentUrl: String,
    currentModel: String,
    currentApiKey: String,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
    onAssistantSetup: () -> Unit,
    onOverlaySetup: () -> Unit,
    onBatterySetup: () -> Unit,
    onAccessibilitySetup: () -> Unit,
    onNotificationListenerSetup: () -> Unit,
    onProtocolPermissionsSetup: () -> Unit
) {
    var urlText by remember { mutableStateOf(currentUrl) }
    var modelText by remember { mutableStateOf(currentModel) }
    var apiKeyText by remember { mutableStateOf(currentApiKey) }
    var isPasswordVisible by remember { mutableStateOf(false) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE) }
    var proactiveAlertsEnabled by remember {
        mutableStateOf(prefs.getBoolean("proactive_alerts_enabled", true))
    }
    var pinFloatingHud by remember {
        mutableStateOf(prefs.getBoolean(JarvisOverlayManager.PREF_PIN_HUD, false))
    }

    val coroutineScope = rememberCoroutineScope()
    var isTestingConnection by remember { mutableStateOf(false) }
    var testDialogTitle by remember { mutableStateOf<String?>(null) }
    var testDialogMessage by remember { mutableStateOf<String?>(null) }
    var testDialogIsError by remember { mutableStateOf(false) }

    var isFetchingModels by remember { mutableStateOf(false) }
    var showModelSelectionDialog by remember { mutableStateOf(false) }
    var showMemoryDialog by remember { mutableStateOf(false) }
    var showHealthCheckDialog by remember { mutableStateOf(false) }
    var availableModels by remember { mutableStateOf(CuratedFreeModels) }

    var isTestingMic by remember { mutableStateOf(false) }
    var diagnosticTitle by remember { mutableStateOf<String?>(null) }
    var diagnosticMessage by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = DarkCard,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, CyanNeon.copy(alpha = 0.35f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⚙ ENGINE SETTINGS",
                        color = CyanNeon,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                // Server URL Field
                Column {
                    Text(text = "Server URL (Remote VPS / Local)", color = TextSecondary, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanNeon,
                            unfocusedBorderColor = CyanDim.copy(alpha = 0.4f),
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                }

                // AI Model Field
                Column {
                    Text(text = "AI Model", color = TextSecondary, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = modelText,
                        onValueChange = { modelText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanNeon,
                            unfocusedBorderColor = CyanDim.copy(alpha = 0.4f),
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Button(
                        onClick = {
                            if (!isFetchingModels) {
                                isFetchingModels = true
                                coroutineScope.launch(Dispatchers.IO) {
                                    val fetchedList = mutableListOf<FreeModelItem>()
                                    try {
                                        val server = urlText.trim().removeSuffix("/")
                                        val u = URL("$server/api/models/free")
                                        val conn = u.openConnection() as HttpURLConnection
                                        conn.requestMethod = "GET"
                                        conn.setRequestProperty("Accept", "application/json")
                                        val k = apiKeyText.trim()
                                        if (k.isNotBlank()) {
                                            conn.setRequestProperty("Authorization", "Bearer $k")
                                        }
                                        conn.connectTimeout = 7000
                                        conn.readTimeout = 12000

                                        val code = conn.responseCode
                                        if (code in 200..299) {
                                            val respStr = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                                            val json = JSONObject(respStr)
                                            val arr = json.optJSONArray("models")
                                            if (arr != null && arr.length() > 0) {
                                                for (i in 0 until arr.length()) {
                                                    val obj = arr.getJSONObject(i)
                                                    val id = obj.optString("id", "")
                                                    if (id.isNotBlank() && id.endsWith(":free")) {
                                                        val name = obj.optString("name", id)
                                                        val ctx = obj.optInt("context_length", 32768)
                                                        val desc = obj.optString("description", "")
                                                        fetchedList.add(FreeModelItem(id, name, ctx, desc))
                                                    }
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.w("JarvisSettings", "Failed fetching free models from server: ${e.message}")
                                    }

                                    withContext(Dispatchers.Main) {
                                        isFetchingModels = false
                                        if (fetchedList.isNotEmpty()) {
                                            availableModels = fetchedList
                                        } else {
                                            availableModels = CuratedFreeModels
                                        }
                                        showModelSelectionDialog = true
                                    }
                                }
                            }
                        },
                        enabled = !isFetchingModels,
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurface),
                        border = BorderStroke(1.dp, if (isFetchingModels) CyanDim.copy(alpha = 0.5f) else CyanNeon.copy(alpha = 0.7f)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isFetchingModels) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                color = CyanNeon,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "Fetching free models...", color = CyanNeon, fontSize = 12.sp)
                        } else {
                            Text(text = "🔍 Search Free Models", color = CyanNeon, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                // AI API Key Field
                Column {
                    Text(text = "AI API Key (OpenRouter / OpenAI / Gemini)", color = TextSecondary, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = apiKeyText,
                        onValueChange = { apiKeyText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                Text(
                                    text = if (isPasswordVisible) "HIDE" else "SHOW",
                                    color = CyanDim,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        },
                        placeholder = {
                            Text(
                                text = "sk-or-v1-... or AIzaSy...",
                                color = TextSecondary.copy(alpha = 0.4f),
                                fontSize = 12.sp
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanNeon,
                            unfocusedBorderColor = CyanDim.copy(alpha = 0.4f),
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                }

                // Test AI Connection Button
                Button(
                    onClick = {
                        if (!isTestingConnection) {
                            isTestingConnection = true
                            coroutineScope.launch(Dispatchers.IO) {
                                try {
                                    val server = urlText.trim().removeSuffix("/")
                                    val u = URL("$server/api/test-connection")
                                    val conn = u.openConnection() as HttpURLConnection
                                    conn.requestMethod = "POST"
                                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                                    conn.setRequestProperty("Accept", "application/json")
                                    val k = apiKeyText.trim()
                                    if (k.isNotBlank()) {
                                        conn.setRequestProperty("Authorization", "Bearer $k")
                                    }
                                    conn.connectTimeout = 8000
                                    conn.readTimeout = 18000
                                    conn.doOutput = true

                                    val body = JSONObject().apply {
                                        put("server_url", urlText.trim())
                                        put("model", modelText.trim())
                                        put("api_key", k)
                                        put("save", true)
                                    }
                                    OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()) }

                                    val code = conn.responseCode
                                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                                    val respStr = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                                    val json = if (respStr.isNotBlank()) JSONObject(respStr) else JSONObject()
                                    val status = json.optString("status", if (code in 200..299) "success" else "error")
                                    val msg = json.optString("message", if (status == "success") "Connection Successful!" else "HTTP $code Error")
                                    val latency = json.optInt("latency_ms", -1)

                                    withContext(Dispatchers.Main) {
                                        isTestingConnection = false
                                        if (status == "success") {
                                            testDialogIsError = false
                                            testDialogTitle = "Connection Successful!"
                                            testDialogMessage = if (latency > 0) "$msg\nLatency: ${latency}ms" else msg
                                        } else {
                                            testDialogIsError = true
                                            testDialogTitle = "Connection Failed"
                                            testDialogMessage = msg
                                        }
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        isTestingConnection = false
                                        testDialogIsError = true
                                        testDialogTitle = "Connection Error"
                                        testDialogMessage = "Failed to reach server: ${e.message}"
                                    }
                                }
                            }
                        }
                    },
                    enabled = !isTestingConnection,
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurface),
                    border = BorderStroke(1.dp, if (isTestingConnection) CyanDim else CyanNeon),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isTestingConnection) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = CyanNeon,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Testing AI Connection...", color = CyanNeon, fontSize = 13.sp)
                    } else {
                        Text(text = "⚡ Test AI Connection", color = CyanNeon, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                // 🩺 Run System Health Check Button
                Button(
                    onClick = { showHealthCheckDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurface),
                    border = BorderStroke(1.dp, GreenActive),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "🩺 Run System Health Check",
                        color = GreenActive,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (showHealthCheckDialog) {
                    SystemHealthCheckDialog(
                        serverUrl = urlText,
                        apiKey = apiKeyText,
                        onDismiss = { showHealthCheckDialog = false }
                    )
                }

                // -------------------------------------------------------------
                // 🔬 HARDWARE & SENSOR DIAGNOSTICS
                // -------------------------------------------------------------
                HorizontalDivider(color = CyanNeon.copy(alpha = 0.15f))

                Text(
                    text = "🔬 HARDWARE & SENSOR DIAGNOSTICS",
                    color = CyanDim,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                // 🎙 Test Mic & VPS STT (3s) Button
                Button(
                    onClick = {
                        if (!isTestingMic) {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                diagnosticTitle = "Permission Required"
                                diagnosticMessage = "RECORD_AUDIO permission is not granted. Please enable microphone permission in Android settings."
                                return@Button
                            }
                            isTestingMic = true
                            coroutineScope.launch(Dispatchers.IO) {
                                try {
                                    val sampleRate = 16000
                                    val minBuf = AudioRecord.getMinBufferSize(
                                        sampleRate,
                                        AudioFormat.CHANNEL_IN_MONO,
                                        AudioFormat.ENCODING_PCM_16BIT
                                    )
                                    val bufSize = maxOf(minBuf, 3200)
                                    val record = AudioRecord(
                                        MediaRecorder.AudioSource.MIC,
                                        sampleRate,
                                        AudioFormat.CHANNEL_IN_MONO,
                                        AudioFormat.ENCODING_PCM_16BIT,
                                        bufSize
                                    )
                                    val bos = ByteArrayOutputStream()
                                    val sBuf = ShortArray(1600)
                                    var peakRms = 0.0

                                    record.startRecording()
                                    val startT = SystemClock.elapsedRealtime()
                                    while (SystemClock.elapsedRealtime() - startT < 3000L) {
                                        val n = record.read(sBuf, 0, 1600)
                                        if (n > 0) {
                                            var sum = 0.0
                                            val bArr = ByteArray(n * 2)
                                            for (i in 0 until n) {
                                                val s = sBuf[i].toInt()
                                                sum += s * s
                                                bArr[i * 2] = (s and 0xFF).toByte()
                                                bArr[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                                            }
                                            val rms = Math.sqrt(sum / n)
                                            if (rms > peakRms) peakRms = rms
                                            bos.write(bArr)
                                        }
                                        delay(40)
                                    }
                                    try {
                                        record.stop()
                                        record.release()
                                    } catch (_: Exception) {}

                                    val pcmData = bos.toByteArray()
                                    val wavBytes = pcmToWavBytes(pcmData, sampleRate)

                                    val server = urlText.trim().removeSuffix("/")
                                    val u = URL("$server/api/stt")
                                    val conn = (u.openConnection() as HttpURLConnection).apply {
                                        requestMethod = "POST"
                                        setRequestProperty("Content-Type", "audio/wav")
                                        setRequestProperty("Accept", "application/json")
                                        connectTimeout = 6000
                                        readTimeout = 12000
                                        doOutput = true
                                    }
                                    conn.outputStream.use { it.write(wavBytes) }
                                    val code = conn.responseCode
                                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                                    val respStr = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                                    val json = try { JSONObject(respStr) } catch (_: Exception) { JSONObject() }
                                    val transcript = json.optString("text", json.optString("transcript", ""))
                                    val lang = json.optString("language", "auto")
                                    val err = json.optString("error", "")

                                    withContext(Dispatchers.Main) {
                                        isTestingMic = false
                                        diagnosticTitle = "🎙 Mic & STT Results"
                                        diagnosticMessage = "• Peak RMS: ${peakRms.toInt()}\n" +
                                                "• Audio Size: ${wavBytes.size} bytes (3.0s WAV)\n" +
                                                "• Server HTTP: $code\n" +
                                                "• Language: $lang\n" +
                                                "• Transcript: \"${transcript.ifBlank { "(empty)" }}\"\n" +
                                                if (err.isNotBlank()) "• Error: $err" else "• Status: Audio pipeline verified!"
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        isTestingMic = false
                                        diagnosticTitle = "🎙 Mic Diagnostic Error"
                                        diagnosticMessage = "Mic recording or STT network call failed: ${e.message}"
                                    }
                                }
                            }
                        }
                    },
                    enabled = !isTestingMic,
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurface),
                    border = BorderStroke(1.dp, CyanDim),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isTestingMic) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = CyanNeon, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Recording 3s & Testing VPS STT...", color = CyanNeon, fontSize = 13.sp)
                    } else {
                        Text("🎙 Test Mic & VPS STT (3s)", color = CyanDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                // 📷 Test Camera Photo Capture Button
                Button(
                    onClick = {
                        val act = JSONObject().apply {
                            put("type", "camera_control")
                            put("mode", "photo")
                            put("capture", true)
                        }
                        val intent = Intent(context, JarvisCameraActivity::class.java).apply {
                            putExtra(JarvisCameraActivity.EXTRA_CAMERA_ACTION, act.toString())
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                        AppLauncher.launchWithOverlayFallback(context, intent)
                        diagnosticTitle = "📷 Camera Photo Test"
                        diagnosticMessage = "Launched JarvisCameraActivity with mode=photo, capture=true.\nCheck DCIM/Jarvis in Gallery for captured image."
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurface),
                    border = BorderStroke(1.dp, VioletAccent),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("📷 Test Camera Photo Capture", color = VioletAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }

                if (diagnosticTitle != null && diagnosticMessage != null) {
                    AlertDialog(
                        onDismissRequest = {
                            diagnosticTitle = null
                            diagnosticMessage = null
                        },
                        title = {
                            Text(text = diagnosticTitle ?: "", color = CyanNeon, fontWeight = FontWeight.Bold)
                        },
                        text = {
                            Text(text = diagnosticMessage ?: "", color = TextPrimary, fontSize = 14.sp)
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    diagnosticTitle = null
                                    diagnosticMessage = null
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = CyanNeon)
                            ) {
                                Text("OK", color = DarkBackground, fontWeight = FontWeight.Bold)
                            }
                        },
                        containerColor = DarkCard,
                        shape = RoundedCornerShape(16.dp)
                    )
                }

                HorizontalDivider(color = CyanNeon.copy(alpha = 0.15f))

                // Part D: Proactive Alerts Toggle Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Proactive Alerts (Messages & Battery)",
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Headphone voice previews & low-battery alerts",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = proactiveAlertsEnabled,
                        onCheckedChange = { checked ->
                            proactiveAlertsEnabled = checked
                            prefs.edit().putBoolean("proactive_alerts_enabled", checked).apply()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = CyanNeon,
                            checkedTrackColor = CyanDim,
                            uncheckedThumbColor = TextSecondary,
                            uncheckedTrackColor = DarkSurface
                        )
                    )
                }

                // Pin Floating HUD Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Pin Floating HUD",
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Keep Iron Man HUD overlay permanently visible on top of screen",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = pinFloatingHud,
                        onCheckedChange = { checked ->
                            pinFloatingHud = checked
                            prefs.edit().putBoolean(JarvisOverlayManager.PREF_PIN_HUD, checked).apply()
                            if (checked) {
                                JarvisOverlayManager.instance?.showOverlay()
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = CyanNeon,
                            checkedTrackColor = CyanDim,
                            uncheckedThumbColor = TextSecondary,
                            uncheckedTrackColor = DarkSurface
                        )
                    )
                }

                HorizontalDivider(color = CyanNeon.copy(alpha = 0.15f))

                // One-Tap System Assistant Setup Buttons (Pillar 1)
                Text(
                    text = "System Permissions & Roles",
                    color = CyanDim,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )

                Button(
                    onClick = onAssistantSetup,
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurface),
                    border = BorderStroke(0.6.dp, CyanNeon.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "Set as Default Device Assistant", color = CyanNeon, fontSize = 12.sp)
                }

                Button(
                    onClick = onOverlaySetup,
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurface),
                    border = BorderStroke(0.6.dp, CyanNeon.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "Allow Background Pop-ups (Xiaomi)", color = CyanNeon, fontSize = 12.sp)
                }

                Button(
                    onClick = onBatterySetup,
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurface),
                    border = BorderStroke(0.6.dp, CyanNeon.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "Disable Battery Optimization", color = CyanNeon, fontSize = 12.sp)
                }

                Button(
                    onClick = onAccessibilitySetup,
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurface),
                    border = BorderStroke(0.6.dp, CyanNeon.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "Enable Deep App Control (Accessibility)", color = CyanNeon, fontSize = 12.sp)
                }

                Button(
                    onClick = onNotificationListenerSetup,
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurface),
                    border = BorderStroke(0.6.dp, CyanNeon.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "Enable Notification Access & Silent Reply", color = CyanNeon, fontSize = 12.sp)
                }

                Button(
                    onClick = onProtocolPermissionsSetup,
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurface),
                    border = BorderStroke(0.6.dp, CyanNeon.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "Allow DND & Brightness Protocols", color = CyanNeon, fontSize = 12.sp)
                }

                HorizontalDivider(color = CyanNeon.copy(alpha = 0.15f))

                // Part A: Long-Term Memory Management
                Button(
                    onClick = { showMemoryDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurface),
                    border = BorderStroke(0.8.dp, CyanNeon.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "🧠 Manage Long-Term Memory",
                        color = CyanNeon,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Step 10: Installed Apps Diagnostic Viewer
                var showAppsDialog by remember { mutableStateOf(false) }
                Button(
                    onClick = { showAppsDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurface),
                    border = BorderStroke(0.8.dp, CyanNeon.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "📱 Installed Apps Indexed: ${AppLauncher.dynamicAppIndex.size} (Tap to View / Rescan)",
                        color = CyanNeon,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (showAppsDialog) {
                    InstalledAppsDialog(
                        serverUrl = urlText,
                        onDismiss = { showAppsDialog = false }
                    )
                }

                // Save Button
                Button(
                    onClick = { onSave(urlText, modelText, apiKeyText) },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanNeon),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "Save & Reconnect", color = DarkBackground, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (testDialogMessage != null) {
        AlertDialog(
            onDismissRequest = { testDialogMessage = null },
            title = {
                Text(
                    text = testDialogTitle ?: "Connection Test",
                    color = if (testDialogIsError) Color(0xFFEF4444) else Color(0xFF10B981),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = testDialogMessage ?: "",
                    color = TextPrimary
                )
            },
            confirmButton = {
                TextButton(onClick = { testDialogMessage = null }) {
                    Text("OK", color = CyanNeon, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = DarkSurface,
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp
        )
    }

    if (showModelSelectionDialog) {
        AlertDialog(
            onDismissRequest = { showModelSelectionDialog = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SELECT FREE MODEL",
                        color = CyanNeon,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "${availableModels.size} available",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Select a verified free model to power J.A.R.V.I.S.:",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(availableModels) { item ->
                            val isSelected = modelText.trim() == item.id
                            Surface(
                                onClick = {
                                    modelText = item.id
                                    showModelSelectionDialog = false
                                },
                                color = if (isSelected) CyanNeon.copy(alpha = 0.15f) else DarkSurface,
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) CyanNeon else CyanDim.copy(alpha = 0.25f)
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = {
                                            modelText = item.id
                                            showModelSelectionDialog = false
                                        },
                                        colors = RadioButtonDefaults.colors(
                                            selectedColor = CyanNeon,
                                            unselectedColor = CyanDim.copy(alpha = 0.4f)
                                        )
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.name,
                                            color = if (isSelected) CyanNeon else TextPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = item.id,
                                            color = TextSecondary,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        if (item.contextLength > 0) {
                                            Text(
                                                text = "Context: ${item.contextLength / 1024}k tokens • Free",
                                                color = GreenActive,
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModelSelectionDialog = false }) {
                    Text("Close", color = CyanNeon, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = DarkCard,
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp
        )
    }

    if (showMemoryDialog) {
        MemoryManagementDialog(
            serverUrl = urlText,
            onDismiss = { showMemoryDialog = false }
        )
    }
}

data class StoredMemoryItem(
    val id: Int,
    val category: String,
    val content: String,
    val created_at: String
)

@Composable
private fun MemoryManagementDialog(
    serverUrl: String,
    onDismiss: () -> Unit
) {
    var memories by remember { mutableStateOf<List<StoredMemoryItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    fun fetchMemories() {
        isLoading = true
        scope.launch(Dispatchers.IO) {
            val list = mutableListOf<StoredMemoryItem>()
            try {
                val s = serverUrl.trim().removeSuffix("/")
                val u = URL("$s/api/memory")
                val conn = u.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/json")
                conn.connectTimeout = 6000
                conn.readTimeout = 8000
                if (conn.responseCode in 200..299) {
                    val respStr = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val json = JSONObject(respStr)
                    val arr = json.optJSONArray("memories")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            list.add(
                                StoredMemoryItem(
                                    id = o.optInt("id", 0),
                                    category = o.optString("category", "general"),
                                    content = o.optString("content", ""),
                                    created_at = o.optString("created_at", "").take(10)
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("MemoryDialog", "Error fetching memories: ${e.message}")
            }
            withContext(Dispatchers.Main) {
                memories = list
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        fetchMemories()
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = DarkCard,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, CyanNeon.copy(alpha = 0.4f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🧠 Memory Banks",
                        color = CyanNeon,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                Text(
                    text = "Persistent facts, preferences & learned user details",
                    color = TextSecondary,
                    fontSize = 11.sp
                )

                HorizontalDivider(color = CyanNeon.copy(alpha = 0.15f))

                if (isLoading) {
                    Box(modifier = Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = CyanNeon, strokeWidth = 2.dp)
                    }
                } else if (memories.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No memories stored yet.\nSay 'Remember that...' or 'یادت باشه...'",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(memories, key = { it.id }) { mem ->
                            Surface(
                                color = DarkSurface,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(0.6.dp, CyanDim.copy(alpha = 0.3f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "[${mem.category}]",
                                                color = CyanNeon,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            if (mem.created_at.isNotBlank()) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = mem.created_at,
                                                    color = TextSecondary,
                                                    fontSize = 10.sp
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = mem.content,
                                            color = TextPrimary,
                                            fontSize = 12.sp
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            scope.launch(Dispatchers.IO) {
                                                try {
                                                    val s = serverUrl.trim().removeSuffix("/")
                                                    val u = URL("$s/api/memory/${mem.id}")
                                                    val conn = u.openConnection() as HttpURLConnection
                                                    conn.requestMethod = "DELETE"
                                                    conn.connectTimeout = 4000
                                                    conn.responseCode
                                                } catch (_: Exception) {}
                                                withContext(Dispatchers.Main) {
                                                    memories = memories.filter { it.id != mem.id }
                                                }
                                            }
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete Memory",
                                            tint = Color(0xFFEF4444)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = CyanNeon.copy(alpha = 0.15f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${memories.size} memories",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                    if (memories.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val s = serverUrl.trim().removeSuffix("/")
                                        val u = URL("$s/api/memory")
                                        val conn = u.openConnection() as HttpURLConnection
                                        conn.requestMethod = "DELETE"
                                        conn.connectTimeout = 4000
                                        conn.responseCode
                                    } catch (_: Exception) {}
                                    withContext(Dispatchers.Main) {
                                        memories = emptyList()
                                    }
                                }
                            }
                        ) {
                            Text(text = "Clear All", color = Color(0xFFEF4444), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Step 10: Installed Apps Diagnostic Viewer Dialog in ENGINE SETTINGS.
 * Lists all indexed launchable applications on the phone, provides live search,
 * a 1-tap "Rescan & Sync Now" button, and Xiaomi HyperOS permission fix shortcut.
 */
@Composable
fun InstalledAppsDialog(
    serverUrl: String,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var appsList by remember { mutableStateOf(AppLauncher.dynamicAppIndex.toList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isSyncing by remember { mutableStateOf(false) }

    val filteredApps = remember(appsList, searchQuery) {
        if (searchQuery.isBlank()) appsList
        else {
            val q = searchQuery.trim().lowercase(Locale.ROOT)
            appsList.filter {
                it.label.lowercase(Locale.ROOT).contains(q) ||
                it.packageName.lowercase(Locale.ROOT).contains(q)
            }
        }
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkBackground),
            border = BorderStroke(1.dp, CyanNeon)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📱 INSTALLED APPS (${appsList.size})",
                        color = CyanNeon,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Xiaomi HyperOS Warning Banner if < 15 apps detected
                if (appsList.size < 15) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0x33EF4444)),
                        border = BorderStroke(1.dp, Color(0xFFEF4444))
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "⚠️ Xiaomi HyperOS / Android Permission Alert",
                                color = Color(0xFFEF4444),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Only ${appsList.size} apps detected. Xiaomi HyperOS may have restricted 'Get list of installed apps' or 'Display pop-up windows while running in background'.",
                                color = TextPrimary,
                                fontSize = 11.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Button(
                                onClick = {
                                    try {
                                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(intent)
                                    } catch (_: Exception) {}
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Fix Xiaomi App List & Pop-up Permissions",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Search Filter
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search by name or package...", color = TextSecondary, fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanNeon,
                        unfocusedBorderColor = CyanDim.copy(alpha = 0.4f),
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Apps List
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    items(filteredApps) { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = app.label,
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = app.packageName,
                                    color = CyanDim,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                        HorizontalDivider(color = CyanDim.copy(alpha = 0.15f))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Rescan & Sync Now Button
                Button(
                    onClick = {
                        if (!isSyncing) {
                            isSyncing = true
                            coroutineScope.launch(Dispatchers.IO) {
                                val fresh = AppLauncher.scanAllInstalledApps(context)
                                AppLauncher.syncAppsToServer(context, serverUrl)
                                withContext(Dispatchers.Main) {
                                    appsList = fresh
                                    isSyncing = false
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanNeon),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = DarkBackground, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Scanning & Syncing...", color = DarkBackground, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    } else {
                        Text(text = "🔄 Rescan & Sync Now", color = DarkBackground, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SystemHealthCheckDialog(
    serverUrl: String,
    apiKey: String,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isChecking by remember { mutableStateOf(true) }

    var pingResult by remember { mutableStateOf<Pair<Boolean, String>>(Pair(false, "Checking...")) }
    var ttsResult by remember { mutableStateOf<Pair<Boolean, String>>(Pair(false, "Checking...")) }
    var accessibilityResult by remember { mutableStateOf<Pair<Boolean, String>>(Pair(false, "Checking...")) }
    var overlayResult by remember { mutableStateOf<Pair<Boolean, String>>(Pair(false, "Checking...")) }
    var notifListenerResult by remember { mutableStateOf<Pair<Boolean, String>>(Pair(false, "Checking...")) }
    var mediaPermissionResult by remember { mutableStateOf<Pair<Boolean, String>>(Pair(false, "Checking...")) }
    var micPermissionResult by remember { mutableStateOf<Pair<Boolean, String>>(Pair(false, "Checking...")) }
    var appsIndexResult by remember { mutableStateOf<Pair<Boolean, String>>(Pair(false, "Checking...")) }
    var lastActionResult by remember { mutableStateOf("Checking...") }

    LaunchedEffect(Unit) {
        coroutineScope.launch(Dispatchers.IO) {
            // 1. Accessibility Service
            val isAccRunning = JarvisAccessibilityService.isServiceRunning()
            accessibilityResult = if (isAccRunning) {
                Pair(true, "Active (Deep In-App Automation Ready)")
            } else {
                Pair(false, "Disabled (Enable in Settings -> Accessibility)")
            }

            // 2. Overlay Permission
            val hasOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
            overlayResult = if (hasOverlay) {
                Pair(true, "Granted (Background Pop-ups Allowed)")
            } else {
                Pair(false, "Missing (Enable 'Display over other apps')")
            }

            // 3. Notification Listener
            val isNotifConnected = JarvisNotificationListener.isConnected()
            notifListenerResult = if (isNotifConnected) {
                Pair(true, "Active (Silent RemoteInput & Summary Ready)")
            } else {
                Pair(false, "Disabled (Enable Notification Listener Access)")
            }

            // 4. Permissions
            val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            micPermissionResult = if (micGranted) Pair(true, "Granted") else Pair(false, "Denied")

            val audioPermGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }
            mediaPermissionResult = if (audioPermGranted) Pair(true, "Granted (Local Music Engine Active)") else Pair(false, "Denied")

            // 5. Apps Indexer
            val appsCount = AppLauncher.dynamicAppIndex.size
            appsIndexResult = if (appsCount > 0) {
                Pair(true, "$appsCount apps indexed and launchable")
            } else {
                Pair(false, "0 apps indexed (Rescan in Settings)")
            }

            // 6. Last Dispatched Action & Result
            val lastAct = ActionDispatcher.lastDispatchedAction
            val lastRes = ActionDispatcher.lastActionResult
            val timeAgo = if (ActionDispatcher.lastActionTimestamp > 0L) {
                val secAgo = (System.currentTimeMillis() - ActionDispatcher.lastActionTimestamp) / 1000L
                "${secAgo}s ago"
            } else "None yet"
            lastActionResult = "$lastAct -> $lastRes ($timeAgo)"

            // 7. VPS /api/ping
            try {
                val s = serverUrl.trim().removeSuffix("/")
                val startMs = System.currentTimeMillis()
                val conn = (URL("$s/api/ping").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 4000
                }
                val code = conn.responseCode
                val latency = System.currentTimeMillis() - startMs
                if (code in 200..299) {
                    pingResult = Pair(true, "Online (${latency}ms)")
                } else {
                    pingResult = Pair(false, "HTTP $code")
                }
            } catch (e: Exception) {
                pingResult = Pair(false, "Unreachable: ${e.message}")
            }

            // 8. TTS /api/tts
            try {
                val s = serverUrl.trim().removeSuffix("/")
                val conn = (URL("$s/api/tts").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    connectTimeout = 5000
                    readTimeout = 8000
                    doOutput = true
                }
                if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
                val testJson = JSONObject().apply {
                    put("text", "ping")
                    put("voice", "en-GB-RyanNeural")
                }
                OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(testJson.toString()) }
                val code = conn.responseCode
                if (code in 200..299) {
                    ttsResult = Pair(true, "Edge-TTS Male Voice Active")
                } else {
                    ttsResult = Pair(false, "HTTP $code")
                }
            } catch (e: Exception) {
                ttsResult = Pair(false, "Unavailable: ${e.message}")
            }

            isChecking = false
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = DarkCard,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, CyanNeon.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🩺 SYSTEM HEALTH CHECK",
                        color = CyanNeon,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                if (isChecking) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = CyanNeon, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(text = "Running full diagnostics...", color = TextSecondary, fontSize = 13.sp)
                    }
                }

                HealthItemRow("VPS Server (/api/ping)", pingResult.first, pingResult.second)
                HealthItemRow("Edge-TTS Engine (/api/tts)", ttsResult.first, ttsResult.second)
                HealthItemRow("Accessibility Service", accessibilityResult.first, accessibilityResult.second)
                HealthItemRow("Overlay / Background Pop-up", overlayResult.first, overlayResult.second)
                HealthItemRow("Notification Listener", notifListenerResult.first, notifListenerResult.second)
                HealthItemRow("Microphone Access", micPermissionResult.first, micPermissionResult.second)
                HealthItemRow("Media / Music Access", mediaPermissionResult.first, mediaPermissionResult.second)
                HealthItemRow("Dynamic Apps Indexer", appsIndexResult.first, appsIndexResult.second)

                HorizontalDivider(color = CyanNeon.copy(alpha = 0.2f))

                Text(
                    text = "Last Action Telemetry:",
                    color = CyanDim,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = lastActionResult,
                    color = TextPrimary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )

                Spacer(modifier = Modifier.height(4.dp))

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = CyanNeon),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "Close Diagnostics", color = DarkBackground, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun HealthItemRow(title: String, isOk: Boolean, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (isOk) "✓" else "✗",
            color = if (isOk) GreenActive else RedOffline,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            modifier = Modifier.width(22.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(text = detail, color = if (isOk) TextSecondary else RedOffline.copy(alpha = 0.85f), fontSize = 10.sp)
        }
    }
}
