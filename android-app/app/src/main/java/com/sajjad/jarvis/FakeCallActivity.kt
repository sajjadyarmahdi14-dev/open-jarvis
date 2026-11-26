package com.sajjad.jarvis

import android.app.KeyguardManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.sajjad.jarvis.router.NlpNormalizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * Feature 2: Protocol Escape — Realistic Fake Incoming Call Activity.
 *
 * Launches cleanly on top of lockscreen (showWhenLocked=true, turnScreenOn=true),
 * rings with device's default ringtone & call vibration pattern, and on Accept switches
 * AudioManager into MODE_IN_COMMUNICATION + earpiece ONLY (isSpeakerphoneOn=false)
 * playing a natural Male Edge-TTS script with live call duration timer ("00:01...").
 */
class FakeCallActivity : ComponentActivity() {

    data class FakeCallRequest(
        val delaySeconds: Int,
        val callerName: String?,
        val isPersian: Boolean
    )

    companion object {
        private const val TAG = "FakeCallActivity"
        const val EXTRA_CALLER_NAME = "extra_caller_name"
        const val EXTRA_IS_PERSIAN = "extra_is_persian"
        const val PREF_FAKE_CALL_CALLER = "fake_call_caller_name"

        private val scheduleScope by lazy { CoroutineScope(Dispatchers.Main) }
        private var scheduledCallJob: Job? = null

        /**
         * Pure-Kotlin deterministic parser for Protocol Escape / Fake Call commands.
         * Covers Persian & English triggers including custom delay ("۱۰ ثانیه دیگه زنگ بزن",
         * "call me in 15 seconds") and custom caller ("از طرف دفتر زنگ بزن", "fake call from Alex").
         */
        fun parseFakeCallIntent(rawText: String, isFa: Boolean): FakeCallRequest? {
            val clean = rawText.trim()
            if (clean.isBlank()) return null
            val normDigits = NlpNormalizer.normalizeDigits(clean)
            val norm = NlpNormalizer.cleanLower(clean)
            val lower = normDigits.lowercase(Locale.ROOT)

            val hasTrigger = norm.contains("protocol escape") ||
                norm.contains("fake call") ||
                norm.contains("call me in") ||
                norm.contains("پروتکل فرار") ||
                norm.contains("تماس جعلی") ||
                Regex("(?:ثانیه|دقیقه)\\s*دیگه\\s*زنگ\\s*بزن").containsMatchIn(normDigits) ||
                Regex("از\\s*طرف\\s+.+?\\s*زنگ\\s*بزن").containsMatchIn(normDigits) ||
                Regex("call\\s+me\\s+in\\s+\\d+\\s*(?:second|sec|seconds|minute|min|minutes)").containsMatchIn(lower)

            if (!hasTrigger) return null

            var delaySeconds = 10
            val secMatchEn = Regex("(?:in|after)\\s+(\\d+)\\s*(second|seconds|sec|minute|minutes|min)").find(lower)
            val secMatchFa = Regex("(\\d+)\\s*(ثانیه|دقیقه)\\s*(?:دیگه|دیگر)?").find(normDigits)
            when {
                secMatchEn != null -> {
                    val v = secMatchEn.groupValues[1].toIntOrNull() ?: 10
                    val unit = secMatchEn.groupValues[2]
                    delaySeconds = if (unit.startsWith("min")) v * 60 else v
                }
                secMatchFa != null -> {
                    val v = secMatchFa.groupValues[1].toIntOrNull() ?: 10
                    val unit = secMatchFa.groupValues[2]
                    delaySeconds = if (unit.contains("دقیقه")) v * 60 else v
                }
            }
            delaySeconds = delaySeconds.coerceIn(0, 600)

            var callerName: String? = null
            val callerEn = Regex("(?:from|as)\\s+([A-Za-z0-9\\s]{2,32}?)(?:\\s+in\\s+\\d+|$)").find(clean)
            val callerFa = Regex("از\\s*طرف\\s+(.+?)(?:\\s+تا|\\s+\\d+\\s*(?:ثانیه|دقیقه)|\\s+زنگ\\s*بزن|$)").find(clean)
            if (callerFa != null) {
                val cand = callerFa.groupValues[1].replace("زنگ بزن", "").trim()
                if (cand.isNotBlank()) callerName = cand
            } else if (callerEn != null) {
                val cand = callerEn.groupValues[1].trim()
                if (cand.isNotBlank() && !cand.equals("me", ignoreCase = true)) callerName = cand
            }

            return FakeCallRequest(
                delaySeconds = delaySeconds,
                callerName = callerName,
                isPersian = isFa || clean.any { it in '\u0600'..'\u06FF' }
            )
        }

        fun getConfiguredCaller(context: Context, isFa: Boolean): String {
            val prefs = context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
            val custom = prefs.getString(PREF_FAKE_CALL_CALLER, "")?.trim() ?: ""
            if (custom.isNotBlank()) return custom
            return if (isFa) "دفتر" else "Home"
        }

        fun scheduleFakeCall(
            context: Context,
            delaySeconds: Int,
            callerName: String?,
            isFa: Boolean
        ) {
            val appCtx = context.applicationContext
            val resolvedCaller = callerName?.takeIf { it.isNotBlank() } ?: getConfiguredCaller(appCtx, isFa)

            scheduledCallJob?.cancel()
            scheduledCallJob = scheduleScope.launch {
                val ms = (delaySeconds.coerceAtLeast(0)) * 1000L
                if (ms > 0L) {
                    delay(ms)
                }
                val intent = android.content.Intent(appCtx, FakeCallActivity::class.java).apply {
                    putExtra(EXTRA_CALLER_NAME, resolvedCaller)
                    putExtra(EXTRA_IS_PERSIAN, isFa)
                    addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                }
                AppLauncher.launchWithOverlayFallback(appCtx, intent)
            }
            Log.i(TAG, "Scheduled fake incoming call from '$resolvedCaller' in ${delaySeconds}s")
        }
    }

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var audioManager: AudioManager? = null
    private var earpiecePlayer: MediaPlayer? = null
    private var voiceFocusRequest: AudioFocusRequest? = null
    private var scriptJob: Job? = null
    private val scriptAudioFiles = mutableListOf<File>()

    @Volatile private var isCallEnded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            km?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val isFa = intent.getBooleanExtra(EXTRA_IS_PERSIAN, Locale.getDefault().language == "fa")
        val callerName = intent.getStringExtra(EXTRA_CALLER_NAME)
            ?: getConfiguredCaller(this, isFa)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        // Pre-warm natural Male Edge-TTS script audio clips in background so Accept is instant
        preFetchScriptClips(isFa)
        startRingingAndVibration()

        setContent {
            FakeCallScreen(
                callerName = callerName,
                isFa = isFa,
                onAccept = { handleCallAccepted(isFa) },
                onToggleSpeaker = { spk -> audioManager?.isSpeakerphoneOn = spk },
                onDeclineOrEnd = { finishCallCleanly() }
            )
        }
    }

    private fun startRingingAndVibration() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    isLooping = true
                }
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                play()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ringtone play failed: ${e.message}")
        }

        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            val pattern = longArrayOf(0L, 800L, 1000L, 800L, 1000L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration error: ${e.message}")
        }
    }

    private fun stopRingingAndVibration() {
        try { ringtone?.stop() } catch (_: Exception) {}
        ringtone = null
        try { vibrator?.cancel() } catch (_: Exception) {}
    }

    private fun preFetchScriptClips(isFa: Boolean) {
        val lines = if (isFa) {
            listOf(
                "الو سلام، خوبی؟",
                "کجایی الان؟ یه کار فوری پیش اومده، باید همین الان بیای...",
                "باشه، منتظرتم، سریع خودتو برسون."
            )
        } else {
            listOf(
                "Hey, hello? Can you hear me?",
                "Where are you right now? Something urgent came up, I need you here right away...",
                "Alright, head over now. I'll meet you there."
            )
        }

        CoroutineScope(Dispatchers.IO).launch {
            val prefs = getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
            val serverUrl = (prefs.getString("server_url", "http://104.252.175.159:8765")
                ?: "http://104.252.175.159:8765").trim().removeSuffix("/")
            val apiKey = prefs.getString("api_key", "") ?: ""
            val voice = if (isFa) "fa-IR-FaridNeural" else "en-GB-RyanNeural"
            val dir = File(cacheDir, "fakecall_cache").apply { mkdirs() }

            for (line in lines) {
                if (isCallEnded) break
                val file = fetchOrLoadTtsFile(serverUrl, apiKey, voice, line, dir)
                if (file != null) {
                    synchronized(scriptAudioFiles) {
                        scriptAudioFiles.add(file)
                    }
                }
            }
        }
    }

    private fun fetchOrLoadTtsFile(
        serverUrl: String,
        apiKey: String,
        voice: String,
        text: String,
        dir: File
    ): File? {
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest("$voice:$text".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val cached = File(dir, "$hash.mp3")
        if (cached.exists() && cached.length() > 300) return cached

        var conn: HttpURLConnection? = null
        return try {
            val url = URL("$serverUrl/api/tts")
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 12000
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "audio/mpeg")
                if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
                doOutput = true
            }
            val json = JSONObject().apply {
                put("text", text)
                put("voice", voice)
                put("rate", "+2%")
                put("pitch", "-2Hz")
            }
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(json.toString()) }
            if (conn.responseCode in 200..299) {
                val tmp = File(dir, "$hash.tmp")
                tmp.outputStream().use { out -> conn.inputStream.copyTo(out) }
                if (tmp.length() > 300) {
                    tmp.renameTo(cached)
                    cached
                } else {
                    tmp.delete()
                    null
                }
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Fake call TTS pre-fetch error: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun handleCallAccepted(isFa: Boolean) {
        stopRingingAndVibration()

        // Switch AudioManager strictly to earpiece communication mode
        try {
            audioManager?.let { am ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        .build()
                    voiceFocusRequest = req
                    am.requestAudioFocus(req)
                } else {
                    @Suppress("DEPRECATION")
                    am.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                }
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.isSpeakerphoneOn = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioManager earpiece route error: ${e.message}")
        }

        // Play natural Male Edge-TTS script over earpiece with realistic pauses
        scriptJob?.cancel()
        scriptJob = CoroutineScope(Dispatchers.Main).launch {
            delay(600L) // Brief natural pickup pause
            var idx = 0
            var waitCount = 0
            while (isActive && !isCallEnded && idx < 3) {
                val clip: File? = synchronized(scriptAudioFiles) {
                    if (idx < scriptAudioFiles.size) scriptAudioFiles[idx] else null
                }
                if (clip == null) {
                    if (waitCount++ > 20) break // Max 4s wait if offline
                    delay(200L)
                    continue
                }
                waitCount = 0
                playClipOverEarpiece(clip)
                idx++
                if (!isCallEnded && idx < 3) {
                    delay(1200L) // Realistic pause waiting for caller reply
                }
            }
        }
    }

    private suspend fun playClipOverEarpiece(file: File): Boolean = withContext(Dispatchers.Main) {
        if (isCallEnded) return@withContext false
        val done = CompletableDeferred<Boolean>()
        try {
            earpiecePlayer?.let {
                try { if (it.isPlaying) it.stop() } catch (_: Exception) {}
                try { it.reset(); it.release() } catch (_: Exception) {}
            }
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener { if (!done.isCompleted) done.complete(true) }
                setOnErrorListener { _, _, _ -> if (!done.isCompleted) done.complete(false); true }
                prepare()
                start()
            }
            earpiecePlayer = mp
            withTimeoutOrNull(12_000L) { done.await() } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "Earpiece playback error: ${e.message}")
            false
        }
    }

    private fun finishCallCleanly() {
        isCallEnded = true
        scriptJob?.cancel()
        stopRingingAndVibration()
        try {
            earpiecePlayer?.let {
                if (it.isPlaying) it.stop()
                it.reset()
                it.release()
            }
        } catch (_: Exception) {}
        earpiecePlayer = null

        try {
            audioManager?.let { am ->
                am.isSpeakerphoneOn = false
                am.mode = AudioManager.MODE_NORMAL
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    voiceFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                    voiceFocusRequest = null
                } else {
                    @Suppress("DEPRECATION")
                    am.abandonAudioFocus(null)
                }
            }
        } catch (_: Exception) {}
        finish()
    }

    override fun onDestroy() {
        isCallEnded = true
        scriptJob?.cancel()
        stopRingingAndVibration()
        try {
            earpiecePlayer?.let {
                if (it.isPlaying) it.stop()
                it.reset()
                it.release()
            }
        } catch (_: Exception) {}
        earpiecePlayer = null
        try {
            audioManager?.let { am ->
                am.isSpeakerphoneOn = false
                am.mode = AudioManager.MODE_NORMAL
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    voiceFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                    voiceFocusRequest = null
                }
            }
        } catch (_: Exception) {}
        super.onDestroy()
    }
}

@Composable
private fun FakeCallScreen(
    callerName: String,
    isFa: Boolean,
    onAccept: () -> Unit,
    onToggleSpeaker: (Boolean) -> Unit,
    onDeclineOrEnd: () -> Unit
) {
    var isAccepted by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(false) }
    var isSpeaker by remember { mutableStateOf(false) }
    var elapsedSec by remember { mutableIntStateOf(0) }

    LaunchedEffect(isAccepted) {
        if (isAccepted) {
            elapsedSec = 1
            while (true) {
                delay(1000L)
                elapsedSec++
            }
        }
    }

    val pulseAnim = rememberInfiniteTransition(label = "call_pulse")
    val pulseScale by pulseAnim.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    val timerText = String.format(Locale.US, "%02d:%02d", elapsedSec / 60, elapsedSec % 60)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0E1B2B), Color(0xFF0A101A), Color(0xFF05070B))
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp, vertical = 40.dp)
    ) {
        // Caller Identity Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (!isAccepted) {
                    if (isFa) "تماس ورودی • موبایل" else "Incoming call • Mobile"
                } else {
                    timerText
                },
                color = if (isAccepted) Color(0xFF4ADE80) else Color(0xFFCBD5E1),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(22.dp))

            Box(
                modifier = Modifier
                    .size(104.dp)
                    .scale(if (isAccepted) 1f else pulseScale)
                    .clip(CircleShape)
                    .background(Color(0xFF1E3A5F)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = "Caller",
                    tint = Color(0xFFE2E8F0),
                    modifier = Modifier.size(56.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = callerName,
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "HD Voice • SIM 1",
                color = Color(0xFF94A3B8),
                fontSize = 13.sp
            )
        }

        // Bottom Controls
        if (!isAccepted) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CallCircleAction(
                    icon = Icons.Default.CallEnd,
                    label = if (isFa) "رد تماس" else "Decline",
                    bg = Color(0xFFDC2626),
                    onClick = onDeclineOrEnd
                )
                CallCircleAction(
                    icon = Icons.Default.Call,
                    label = if (isFa) "پاسخ" else "Accept",
                    bg = Color(0xFF16A34A),
                    onClick = {
                        isAccepted = true
                        onAccept()
                    }
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    InCallSmallControl(
                        icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        label = if (isFa) "بی‌صدا" else "Mute",
                        active = isMuted,
                        onClick = { isMuted = !isMuted }
                    )
                    InCallSmallControl(
                        icon = Icons.Default.Dialpad,
                        label = if (isFa) "صفحه‌کلید" else "Keypad",
                        active = false,
                        onClick = {}
                    )
                    InCallSmallControl(
                        icon = Icons.Default.VolumeUp,
                        label = if (isFa) "بلندگو" else "Speaker",
                        active = isSpeaker,
                        onClick = {
                            isSpeaker = !isSpeaker
                            onToggleSpeaker(isSpeaker)
                        }
                    )
                }

                CallCircleAction(
                    icon = Icons.Default.CallEnd,
                    label = if (isFa) "پایان تماس" else "End Call",
                    bg = Color(0xFFDC2626),
                    onClick = onDeclineOrEnd
                )
            }
        }
    }
}

@Composable
private fun CallCircleAction(
    icon: ImageVector,
    label: String,
    bg: Color,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(bg)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(34.dp)
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(text = label, color = Color(0xFFE2E8F0), fontSize = 13.sp)
    }
}

@Composable
private fun InCallSmallControl(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(if (active) Color.White else Color(0xFF1E293B))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (active) Color(0xFF0F172A) else Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = label, color = Color(0xFFCBD5E1), fontSize = 12.sp)
    }
}
