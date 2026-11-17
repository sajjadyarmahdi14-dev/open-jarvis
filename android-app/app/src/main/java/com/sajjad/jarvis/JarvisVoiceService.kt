package com.sajjad.jarvis

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class JarvisVoiceService : Service(), TextToSpeech.OnInitListener {

    interface VoiceEventListener {
        fun onWakeStateChanged(state: String) // "listening_wake", "listening_cmd", "processing", "paused"
        fun onSpeechStarted()
        fun onSpeechEnded()
        fun onPartialSpeech(text: String)
        fun onFinalSpeech(userText: String)
        fun onAssistantResponse(response: String)
        fun onTtsStarted()
        fun onTtsFinished()
    }

    companion object {
        const val TAG = "JarvisVoiceService"
        const val CHANNEL_ID = "jarvis_voice_service_channel"
        const val NOTIFICATION_ID = 2002

        const val ACTION_START = "com.sajjad.jarvis.VOICE_START"
        const val ACTION_STOP = "com.sajjad.jarvis.VOICE_STOP"
        const val ACTION_TOGGLE_MUTE = "com.sajjad.jarvis.VOICE_TOGGLE_MUTE"
        const val ACTION_FORCE_LISTEN = "com.sajjad.jarvis.VOICE_FORCE_LISTEN"

        private const val API_CHAT_URL = "http://127.0.0.1:8765/api/chat"
        private const val DEBOUNCE_SILENCE_MS = 2800L

        var serviceListener: VoiceEventListener? = null
        var isServiceRunning = false
        var isServiceMuted = false

        private var wakeLock: PowerManager.WakeLock? = null

        fun acquireWakeLock(context: Context) {
            try {
                if (wakeLock == null) {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                    wakeLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "Jarvis::AlwaysOnVoiceLock"
                    ).apply {
                        setReferenceCounted(false)
                    }
                }
                if (wakeLock?.isHeld != true) {
                    wakeLock?.acquire()
                    Log.i(TAG, "AlwaysOnVoiceLock acquired")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to acquire wake lock: ${e.message}")
            }
        }

        fun releaseWakeLock() {
            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                    Log.i(TAG, "AlwaysOnVoiceLock released")
                }
            } catch (_: Exception) {}
        }

        fun forceStartListening(context: Context) {
            val intent = Intent(context, JarvisVoiceService::class.java).apply {
                action = ACTION_FORCE_LISTEN
            }
            context.startService(intent)
        }
    }

    enum class VoiceMode {
        STANDBY_WAKE_WORD,
        ACTIVE_COMMAND_CAPTURE,
        PROCESSING
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false
    private var isTtsSpeaking = false

    private var currentMode = VoiceMode.STANDBY_WAKE_WORD
    private val utteranceBuffer = StringBuilder()
    private var debounceJob: Job? = null
    private var restartListeningJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        Log.i(TAG, "JarvisVoiceService onCreate")

        acquireWakeLock(this)
        createNotificationChannel()
        initTextToSpeech()

        startAsForeground()
        scheduleRestartListening(600L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Service stop requested")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_MUTE -> {
                isServiceMuted = !isServiceMuted
                updateNotification()
                if (isServiceMuted) {
                    stopRecognizer()
                    serviceListener?.onWakeStateChanged("paused")
                } else {
                    currentMode = VoiceMode.STANDBY_WAKE_WORD
                    scheduleRestartListening(200L)
                }
                return START_STICKY
            }
            ACTION_FORCE_LISTEN -> {
                if (!isServiceMuted && !isTtsSpeaking) {
                    currentMode = VoiceMode.ACTIVE_COMMAND_CAPTURE
                    utteranceBuffer.clear()
                    playActivationChime()
                    serviceListener?.onWakeStateChanged("listening_cmd")
                    startRecognizer()
                }
                return START_STICKY
            }
            else -> {
                acquireWakeLock(this)
                startAsForeground()
                if (!isServiceMuted && !isTtsSpeaking) {
                    scheduleRestartListening(400L)
                }
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -----------------------------------------------------------------------
    // Foreground Notification
    // -----------------------------------------------------------------------

    private fun startAsForeground() {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                startForeground(NOTIFICATION_ID, notification, fgsType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground error: ${t.message}", t)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "J.A.R.V.I.S. Voice Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps J.A.R.V.I.S. always-on voice recognition and audio responses active"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pOpenIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteIntent = Intent(this, JarvisVoiceService::class.java).apply {
            action = ACTION_TOGGLE_MUTE
        }
        val pMuteIntent = PendingIntent.getService(
            this, 1, muteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, JarvisVoiceService::class.java).apply {
            action = ACTION_STOP
        }
        val pStopIntent = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = when {
            isServiceMuted -> getString(R.string.voice_service_paused)
            currentMode == VoiceMode.PROCESSING -> getString(R.string.voice_service_processing)
            currentMode == VoiceMode.ACTIVE_COMMAND_CAPTURE -> "Listening for command..."
            else -> getString(R.string.voice_service_listening)
        }

        val muteLabel = if (isServiceMuted) getString(R.string.voice_action_unmute) else getString(R.string.voice_action_mute)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.voice_service_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentIntent(pOpenIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_jarvis, muteLabel, pMuteIntent)
            .addAction(R.drawable.ic_jarvis, getString(R.string.voice_action_stop), pStopIntent)
            .build()
    }

    private fun updateNotification() {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {}
    }

    // -----------------------------------------------------------------------
    // Text-To-Speech (British Male Neural / UK System Voice)
    // -----------------------------------------------------------------------

    private fun initTextToSpeech() {
        try {
            textToSpeech = TextToSpeech(applicationContext, this)
        } catch (e: Exception) {
            Log.w(TAG, "TextToSpeech init failed: ${e.message}")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            try {
                textToSpeech?.language = Locale.UK
                textToSpeech?.setPitch(0.92f)
                textToSpeech?.setSpeechRate(1.03f)

                val voices = textToSpeech?.voices
                if (voices != null) {
                    val britishMale = voices.firstOrNull { v ->
                        val name = v.name.lowercase(Locale.ROOT)
                        (v.locale == Locale.UK || v.locale.language == "en") &&
                        (name.contains("male") || name.contains("rjs") || name.contains("gbd") || name.contains("en-gb"))
                    }
                    if (britishMale != null) {
                        textToSpeech?.voice = britishMale
                    }
                }
            } catch (_: Throwable) {}

            textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isTtsSpeaking = true
                    stopRecognizer()
                    serviceListener?.onTtsStarted()
                }

                override fun onDone(utteranceId: String?) {
                    isTtsSpeaking = false
                    serviceListener?.onTtsFinished()
                    currentMode = VoiceMode.STANDBY_WAKE_WORD
                    scheduleRestartListening(400L)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    isTtsSpeaking = false
                    serviceListener?.onTtsFinished()
                    currentMode = VoiceMode.STANDBY_WAKE_WORD
                    scheduleRestartListening(400L)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    isTtsSpeaking = false
                    serviceListener?.onTtsFinished()
                    currentMode = VoiceMode.STANDBY_WAKE_WORD
                    scheduleRestartListening(400L)
                }
            })
            isTtsReady = true
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        scope.launch(Dispatchers.Main) {
            if (isTtsReady) {
                isTtsSpeaking = true
                stopRecognizer()

                val hasPersian = text.any { it in '\u0600'..'\u06FF' }
                if (hasPersian) {
                    val persianLocale = Locale("fa", "IR")
                    val result = textToSpeech?.setLanguage(persianLocale)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        textToSpeech?.language = Locale.UK
                    }
                } else {
                    textToSpeech?.language = Locale.UK
                    textToSpeech?.setPitch(0.92f)
                    textToSpeech?.setSpeechRate(1.03f)
                }

                val params = Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "JARVIS_VOICE_" + System.currentTimeMillis())
                }
                textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "JARVIS_VOICE")
            }
        }
    }

    private fun playActivationChime() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 180)
            scope.launch {
                delay(300)
                try { tg.release() } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    // -----------------------------------------------------------------------
    // Continuous Speech Recognizer & Patient Utterance Accumulator
    // -----------------------------------------------------------------------

    private fun scheduleRestartListening(delayMs: Long = 400L) {
        if (isServiceMuted || isTtsSpeaking) return
        restartListeningJob?.cancel()
        restartListeningJob = scope.launch {
            delay(delayMs)
            if (isActive && !isServiceMuted && !isTtsSpeaking) {
                startRecognizer()
            }
        }
    }

    private fun startRecognizer() {
        if (isServiceMuted || isTtsSpeaking) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Record audio permission not granted")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "Speech recognition not available on device")
            return
        }

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        val st = if (currentMode == VoiceMode.ACTIVE_COMMAND_CAPTURE) "listening_cmd" else "listening_wake"
                        serviceListener?.onWakeStateChanged(st)
                        updateNotification()
                    }

                    override fun onBeginningOfSpeech() {
                        serviceListener?.onSpeechStarted()
                    }

                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        serviceListener?.onSpeechEnded()
                        // If in active command mode and user paused, do not close command yet!
                        // Immediately keep recognizer alive if debounce timer hasn't fired
                        if (currentMode == VoiceMode.ACTIVE_COMMAND_CAPTURE) {
                            scheduleRestartListening(250L)
                        }
                    }

                    override fun onError(error: Int) {
                        // In standby mode, silence timeouts are completely normal — silently restart after 400ms!
                        if (currentMode == VoiceMode.STANDBY_WAKE_WORD) {
                            scheduleRestartListening(400L)
                            return
                        }

                        // In active command capture mode, if buffer has text, let the debounce timer finish!
                        if (currentMode == VoiceMode.ACTIVE_COMMAND_CAPTURE && utteranceBuffer.isNotBlank()) {
                            // Don't cancel debounce; keep waiting for 2.8s total silence or restart
                            scheduleRestartListening(350L)
                            return
                        }

                        // Empty command timed out: return to standby
                        currentMode = VoiceMode.STANDBY_WAKE_WORD
                        serviceListener?.onWakeStateChanged("listening_wake")
                        updateNotification()
                        scheduleRestartListening(400L)
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.trim() ?: return

                        if (currentMode == VoiceMode.STANDBY_WAKE_WORD) {
                            if (containsWakeWord(text)) {
                                val remainder = stripWakeWord(text)
                                playActivationChime()
                                currentMode = VoiceMode.ACTIVE_COMMAND_CAPTURE
                                utteranceBuffer.clear()
                                if (remainder.length > 1) {
                                    utteranceBuffer.append(remainder)
                                }
                                resetDebounceTimer()
                                serviceListener?.onWakeStateChanged("listening_cmd")
                                serviceListener?.onPartialSpeech(utteranceBuffer.toString())
                                updateNotification()
                            }
                        } else if (currentMode == VoiceMode.ACTIVE_COMMAND_CAPTURE) {
                            val clean = stripWakeWord(text)
                            if (clean.isNotBlank()) {
                                utteranceBuffer.clear()
                                utteranceBuffer.append(clean)
                                resetDebounceTimer()
                                serviceListener?.onPartialSpeech(clean)
                            }
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.trim() ?: ""

                        if (currentMode == VoiceMode.STANDBY_WAKE_WORD) {
                            if (containsWakeWord(text)) {
                                val remainder = stripWakeWord(text)
                                playActivationChime()
                                currentMode = VoiceMode.ACTIVE_COMMAND_CAPTURE
                                utteranceBuffer.clear()
                                if (remainder.isNotBlank()) {
                                    utteranceBuffer.append(remainder)
                                    resetDebounceTimer()
                                    serviceListener?.onPartialSpeech(remainder)
                                } else {
                                    // Just "Jarvis" was spoken
                                    speak("Yes, sir?")
                                    resetDebounceTimer()
                                }
                                serviceListener?.onWakeStateChanged("listening_cmd")
                                updateNotification()
                            } else {
                                scheduleRestartListening(400L)
                            }
                        } else if (currentMode == VoiceMode.ACTIVE_COMMAND_CAPTURE) {
                            val clean = stripWakeWord(text)
                            if (clean.isNotBlank()) {
                                utteranceBuffer.clear()
                                utteranceBuffer.append(clean)
                                resetDebounceTimer()
                                serviceListener?.onPartialSpeech(clean)
                            }
                            // Keep recognizer running until 2.8s timer fires
                            scheduleRestartListening(250L)
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            // Configure Patient Silence Thresholds on RecognizerIntent
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-US", "fa-IR"))
                // Fix voice loop & cut-off: Patient silence thresholds
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 4000L)
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startListening error: ${e.message}")
            scheduleRestartListening(1500L)
        }
    }

    private fun stopRecognizer() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (_: Exception) {}
    }

    private fun resetDebounceTimer() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_SILENCE_MS)
            commitUtterance()
        }
    }

    private fun commitUtterance() {
        val finalCommand = utteranceBuffer.toString().trim()
        utteranceBuffer.clear()

        if (finalCommand.isBlank()) {
            currentMode = VoiceMode.STANDBY_WAKE_WORD
            serviceListener?.onWakeStateChanged("listening_wake")
            updateNotification()
            scheduleRestartListening(400L)
            return
        }

        currentMode = VoiceMode.PROCESSING
        stopRecognizer()
        serviceListener?.onWakeStateChanged("processing")
        serviceListener?.onFinalSpeech(finalCommand)
        updateNotification()

        dispatchVoiceCommand(finalCommand)
    }

    // -----------------------------------------------------------------------
    // Execute Voice Commands & App Launches Directly from Service
    // -----------------------------------------------------------------------

    private fun dispatchVoiceCommand(command: String) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                executeChatRequest(command)
            }

            if (result != null) {
                // 1. Process client_actions (e.g. app launching with FLAG_ACTIVITY_NEW_TASK)
                val actions = result.optJSONArray("client_actions")
                if (actions != null) {
                    for (i in 0 until actions.length()) {
                        val act = actions.optJSONObject(i) ?: continue
                        if (act.optString("type") == "launch_app") {
                            val target = act.optString("target", "")
                            if (target.isNotBlank()) {
                                AppLauncher.launchApp(applicationContext, target)
                            }
                        }
                    }
                }

                // 2. Play British TTS response
                val reply = result.optString("response", "").trim()
                if (reply.isNotBlank()) {
                    serviceListener?.onAssistantResponse(reply)
                    speak(reply)
                } else {
                    currentMode = VoiceMode.STANDBY_WAKE_WORD
                    serviceListener?.onWakeStateChanged("listening_wake")
                    updateNotification()
                    scheduleRestartListening(400L)
                }
            } else {
                speak("I was unable to connect to the core system, sir.")
                currentMode = VoiceMode.STANDBY_WAKE_WORD
                serviceListener?.onWakeStateChanged("listening_wake")
                updateNotification()
                scheduleRestartListening(600L)
            }
        }
    }

    private fun executeChatRequest(message: String): JSONObject? {
        return try {
            val url = URL(API_CHAT_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 8000
            conn.readTimeout = 25000
            conn.doOutput = true

            val jsonReq = JSONObject().apply {
                put("message", message)
                put("tts", false) // Service plays TTS directly
            }

            OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                writer.write(jsonReq.toString())
                writer.flush()
            }

            val code = conn.responseCode
            if (code in 200..299) {
                BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { reader ->
                    val sb = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        sb.append(line)
                    }
                    JSONObject(sb.toString())
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "executeChatRequest error: ${e.message}")
            null
        }
    }

    private fun containsWakeWord(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return lower.contains("jarvis") || text.contains("جارویس")
    }

    private fun stripWakeWord(text: String): String {
        var cleaned = text
        val patterns = arrayOf(
            "(?i)^\\s*(hey|hi|hello|ok|okay|dear)?\\s*jarvis[,.!?\\s]*",
            "(?i)[,.!?\\s]*jarvis[,.!?\\s]*",
            "^\\s*(سلام|هی|درود)?\\s*جارویس[,.!?\\s]*",
            "[,.!?\\s]*جارویس[,.!?\\s]*"
        )
        for (pattern in patterns) {
            cleaned = cleaned.replaceFirst(Regex(pattern), " ")
        }
        return cleaned.trim()
    }

    override fun onDestroy() {
        isServiceRunning = false
        debounceJob?.cancel()
        restartListeningJob?.cancel()
        stopRecognizer()
        speechRecognizer?.destroy()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        releaseWakeLock()
        super.onDestroy()
    }
}
