package com.sajjad.jarvis

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
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
    val isUser: Boolean,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

class MainActivity : ComponentActivity(), JarvisVoiceService.VoiceEventListener {

    private var serverUrlState = mutableStateOf("http://104.252.175.159:8765")
    private var activeModelState = mutableStateOf("nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free")
    private var apiKeyState = mutableStateOf("")
    private var isConnectedState = mutableStateOf(false)
    private var isServiceActiveState = mutableStateOf(true)
    private var voiceStateText = mutableStateOf("STANDBY: Say \"Jarvis\"")
    private var audioAmplitudeState = mutableFloatStateOf(0.0f)
    private var messagesState = mutableStateListOf<ChatMessage>()
    private var isProcessingState = mutableStateOf(false)

    private var healthCheckJob: Job? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        startVoiceService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        loadPreferences()
        requestPermissionsAndSetup()
        startVoiceService()
        startHealthCheckLoop()
        registerUiUpdateReceiver()

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
                                onToggleService = { enable ->
                                    isServiceActiveState.value = enable
                                    if (enable) startVoiceService() else stopVoiceService()
                                },
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
                "listening_cmd" -> "LISTENING..."
                "processing" -> "THINKING..."
                "speaking" -> "SPEAKING..."
                "paused", "sleep" -> "SLEEPING / MUTED"
                else -> "STANDBY: Say \"Jarvis\""
            }
        }
    }

    override fun onSpeechStarted() {
        runOnUiThread {
            voiceStateText.value = "LISTENING..."
        }
    }

    override fun onSpeechEnded() {
        runOnUiThread {
            voiceStateText.value = "THINKING..."
        }
    }

    override fun onPartialSpeech(text: String) {}

    override fun onFinalSpeech(userText: String) {
        runOnUiThread {
            if (userText.isNotBlank()) {
                messagesState.add(ChatMessage(isUser = true, text = userText))
            }
        }
    }

    override fun onAssistantResponse(response: String) {
        runOnUiThread {
            if (response.isNotBlank()) {
                messagesState.add(ChatMessage(isUser = false, text = response))
            }
        }
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
            val wakeStatus = intent?.getStringExtra("wake_status")
            val amp = intent?.getFloatExtra("amplitude", -1f) ?: -1f

            if (!userText.isNullOrBlank()) {
                messagesState.add(ChatMessage(isUser = true, text = userText))
            }
            if (!assistantReply.isNullOrBlank()) {
                messagesState.add(ChatMessage(isUser = false, text = assistantReply))
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
        JarvisVoiceService.forceStartListening(this)
        voiceStateText.value = "LISTENING..."
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

    private fun sendMessage(text: String) {
        val clean = text.trim()
        if (clean.isBlank() || isProcessingState.value) return

        messagesState.add(ChatMessage(isUser = true, text = clean))
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
                        messagesState.add(ChatMessage(isUser = false, text = reply))
                        isProcessingState.value = false
                        voiceStateText.value = "STANDBY: Say \"Jarvis\""
                    }

                    // Execute client actions
                    if (clientActions != null) {
                        for (i in 0 until clientActions.length()) {
                            val act = clientActions.optJSONObject(i) ?: continue
                            executeClientAction(act)
                        }
                    }

                    // Read out reply via British TTS
                    JarvisVoiceService.instance?.speakBritish(reply)
                } else {
                    withContext(Dispatchers.Main) {
                        messagesState.add(ChatMessage(isUser = false, text = "Server returned HTTP ${conn.responseCode}"))
                        isProcessingState.value = false
                        voiceStateText.value = "STANDBY: Say \"Jarvis\""
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    messagesState.add(ChatMessage(isUser = false, text = "Connection error: ${e.message}"))
                    isProcessingState.value = false
                    voiceStateText.value = "STANDBY: Say \"Jarvis\""
                }
            }
        }
    }

    private fun executeClientAction(act: JSONObject) {
        try {
            when (act.optString("type")) {
                "launch_app" -> {
                    val target = act.optString("target") ?: act.optString("package") ?: ""
                    if (target.isNotBlank()) AppLauncher.launchApp(applicationContext, target)
                }
                "torch" -> {
                    val state = act.optString("state", "toggle")
                    val turnOn = state == "on" || state == "true"
                    JarvisVoiceService.instance?.setTorchState(turnOn)
                }
                "open_url" -> {
                    val urlStr = act.optString("url", "")
                    if (urlStr.isNotBlank()) {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(urlStr)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        AppLauncher.launchWithOverlayFallback(this, intent)
                    }
                }
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

    private fun requestPermissionsAndSetup() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
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
    onToggleService: (Boolean) -> Unit,
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
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Title & Connection Indicator
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "J.A.R.V.I.S.",
                        color = CyanNeon,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 2.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isConnected) GreenActive else RedOffline)
                    )
                }
                val host = serverUrl.removePrefix("http://").removePrefix("https://")
                Text(
                    text = host,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Model Badge, 24/7 Switch, and Settings Button
            Row(verticalAlignment = Alignment.CenterVertically) {
                val modelShort = if (activeModel.contains("nemotron")) "Nemotron" else activeModel.take(10)
                Surface(
                    color = DarkCard,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(0.5.dp, CyanDim.copy(alpha = 0.3f))
                ) {
                    Text(
                        text = modelShort,
                        color = CyanDim,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // 24/7 Switch
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Switch(
                        checked = isServiceActive,
                        onCheckedChange = onToggleService,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = CyanNeon,
                            checkedTrackColor = CyanDim.copy(alpha = 0.3f),
                            uncheckedThumbColor = TextSecondary,
                            uncheckedTrackColor = DarkCard
                        ),
                        modifier = Modifier.height(28.dp)
                    )
                    Text(
                        text = "24/7",
                        color = if (isServiceActive) CyanNeon else TextSecondary,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = CyanNeon.copy(alpha = 0.85f)
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
    onBatterySetup: () -> Unit
) {
    var urlText by remember { mutableStateOf(currentUrl) }
    var modelText by remember { mutableStateOf(currentModel) }
    var apiKeyText by remember { mutableStateOf(currentApiKey) }
    var isPasswordVisible by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    var isTestingConnection by remember { mutableStateOf(false) }
    var testDialogTitle by remember { mutableStateOf<String?>(null) }
    var testDialogMessage by remember { mutableStateOf<String?>(null) }
    var testDialogIsError by remember { mutableStateOf(false) }

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
                            IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                Icon(
                                    imageVector = if (isPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = if (isPasswordVisible) "Hide API Key" else "Show API Key",
                                    tint = CyanDim
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
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Test Connection",
                            tint = CyanNeon,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Test AI Connection", color = CyanNeon, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
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
                    color = if (testDialogIsError) RedOffline else GreenActive,
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
}
}
