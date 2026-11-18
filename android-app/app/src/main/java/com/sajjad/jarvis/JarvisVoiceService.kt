package com.sajjad.jarvis

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.TimeUnit

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
        fun onAmplitudeChanged(amplitude: Float)
    }

    enum class VadMode {
        STANDBY_WAKE_WORD, // Fast 800ms silence cutoff for wake word ("Jarvis")
        ACTIVE_COMMAND,    // Deterministic 1.2s silence cutoff with hard 6.0s max
        SLEEP_WAKEWORD_ONLY // Standby mode ignoring room noise until "Jarvis" is called
    }

    companion object {
        const val TAG = "JarvisVoiceService"
        const val CHANNEL_ID = "jarvis_voice_service_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.sajjad.jarvis.VOICE_START"
        const val ACTION_STOP = "com.sajjad.jarvis.VOICE_STOP"
        const val ACTION_TOGGLE_MUTE = "com.sajjad.jarvis.VOICE_TOGGLE_MUTE"
        const val ACTION_FORCE_LISTEN = "com.sajjad.jarvis.VOICE_FORCE_LISTEN"
        const val ACTION_TRIGGER_LISTEN = "com.openjarvis.ACTION_TRIGGER_LISTEN"

        private const val SAMPLE_RATE = 16000
        private const val STANDBY_SILENCE_WINDOW_MS = 3500L // Full 3.5s silence tracker to avoid cutting off natural speech
        private const val COMMAND_SILENCE_WINDOW_MS = 3500L // 3.5s patient silence cutoff allowing natural pauses
        private const val MAX_COMMAND_DURATION_MS = 60000L  // Hard 60.0s max recording duration for long-form speech
        private const val PRE_ROLL_DURATION_MS = 400
        private const val CHUNK_SAMPLES = 1600 // 100ms at 16kHz
        private const val MIN_SPEECH_RMS = 300.0

        fun isPersianOrArabic(text: String): Boolean {
            val regex = Regex("[\\u0600-\\u06FF]")
            return regex.containsMatchIn(text)
        }

        fun createSpeechRecognizerIntent(language: String? = null): Intent {
            return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                val targetLang = if (!language.isNullOrBlank()) language else Locale.getDefault().toLanguageTag()
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, targetLang)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, targetLang)
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 60000L)
            }
        }

        var serviceListener: VoiceEventListener? = null
        var isServiceRunning = false
        var isServiceMuted = false
        var instance: JarvisVoiceService? = null

        private var wakeLock: PowerManager.WakeLock? = null
        private var wifiLock: WifiManager.WifiLock? = null

        fun acquireLocks(context: Context) {
            try {
                if (wakeLock == null) {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                    wakeLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "Jarvis::BackgroundVoiceWakeLock"
                    ).apply {
                        setReferenceCounted(false)
                    }
                }
                if (wakeLock?.isHeld != true) {
                    wakeLock?.acquire()
                    Log.i(TAG, "BackgroundVoiceWakeLock acquired")
                }
            } catch (e: Exception) {
                Log.w(TAG, "WakeLock acquire error: ${e.message}")
            }

            try {
                if (wifiLock == null) {
                    val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    } else {
                        @Suppress("DEPRECATION")
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF
                    }
                    wifiLock = wm.createWifiLock(mode, "Jarvis::BackgroundWifiLock").apply {
                        setReferenceCounted(false)
                    }
                }
                if (wifiLock?.isHeld != true) {
                    wifiLock?.acquire()
                    Log.i(TAG, "BackgroundWifiLock acquired")
                }
            } catch (e: Exception) {
                Log.w(TAG, "WifiLock acquire error: ${e.message}")
            }
        }

        fun releaseLocks() {
            try {
                if (wakeLock?.isHeld == true) wakeLock?.release()
            } catch (_: Exception) {}
            try {
                if (wifiLock?.isHeld == true) wifiLock?.release()
            } catch (_: Exception) {}
        }

        fun acquireWakeLock(context: Context) {
            acquireLocks(context)
        }

        fun releaseWakeLock() {
            releaseLocks()
        }

        fun forceStartListening(context: Context) {
            val intent = Intent(context, JarvisVoiceService::class.java).apply {
                action = ACTION_FORCE_LISTEN
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun speakBritishDirect(context: Context, text: String, onFinished: (() -> Unit)? = null) {
            instance?.speakBritish(text, onFinished)
        }

        fun streamChatDirect(context: Context, text: String) {
            instance?.streamChatAndPlay(text)
        }

        fun handleVisionActionDirect(mode: String, prompt: String) {
            instance?.handleVisionAction(mode, prompt)
        }
    }

    class ShortRingBuffer(private val capacity: Int) {
        private val buffer = ShortArray(capacity)
        private var writePos = 0
        private var count = 0

        @Synchronized
        fun write(data: ShortArray, length: Int) {
            for (i in 0 until length) {
                buffer[writePos] = data[i]
                writePos = (writePos + 1) % capacity
                if (count < capacity) count++
            }
        }

        @Synchronized
        fun toByteArray(): ByteArray {
            val out = ByteArray(count * 2)
            var readPos = if (count < capacity) 0 else writePos
            for (i in 0 until count) {
                val sample = buffer[readPos]
                out[i * 2] = (sample.toInt() and 0xFF).toByte()
                out[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
                readPos = (readPos + 1) % capacity
            }
            return out
        }

        @Synchronized
        fun clear() {
            writePos = 0
            count = 0
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false

    enum class AcousticState {
        IDLE,
        LISTENING,
        PROCESSING,
        SPEAKING
    }

    @Volatile private var acousticState = AcousticState.IDLE
    @Volatile private var isProcessing = false
    @Volatile private var isTtsSpeaking = false
    @Volatile private var activeMediaPlayer: MediaPlayer? = null
    private val audioPlayMutex = Mutex()

    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private var automaticGainControl: AutomaticGainControl? = null
    private var recordingJob: Job? = null
    @Volatile private var isRecordingLoopActive = false
    @Volatile private var isUtteranceActive = false

    @Volatile private var currentVadMode = VadMode.STANDBY_WAKE_WORD
    @Volatile private var awakeUntilTimestamp = 0L
    @Volatile private var accumulatedTranscript = ""
    private val streamingHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private var activeStreamCall: Call? = null
    private var fillerJob: Job? = null

    private var isTorchOn = false

    private val packageReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            if (action == Intent.ACTION_PACKAGE_ADDED ||
                action == Intent.ACTION_PACKAGE_REPLACED ||
                action == Intent.ACTION_PACKAGE_REMOVED) {
                Log.i(TAG, "Package change detected: $action. Refreshing installed apps index...")
                AppLauncher.refreshInstalledApps(applicationContext)
                AppLauncher.syncAppsToServer(applicationContext, getServerUrl())
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        isServiceRunning = true
        Log.i(TAG, "JarvisVoiceService onCreate")

        acquireLocks(this)
        createNotificationChannel()
        initTextToSpeech()
        JarvisOverlayManager.init(applicationContext)

        // Acquire persistent non-reference-counted PARTIAL_WAKE_LOCK so CPU and AudioRecord stay alive indefinitely
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jarvis::AlwaysOnVoiceLock").apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire()
                Log.i(TAG, "Persistent AlwaysOnVoiceLock acquired")
            }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock acquire error: ${e.message}")
        }

        // Show verification toast on main thread
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                Toast.makeText(applicationContext, "Jarvis 24/7 Background Service Active", Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {}
        }

        // Dynamic Real-Time App Discovery Engine: initial index and sync
        AppLauncher.refreshInstalledApps(applicationContext)
        AppLauncher.syncAppsToServer(applicationContext, getServerUrl())

        // Register dynamic receiver for real-time app installs/updates
        val packageFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageReceiver, packageFilter, ContextCompat.RECEIVER_EXPORTED)

        // Sanitize active model preference
        try {
            val prefs = getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
            val storedM = prefs.getString("active_model", null)
            if (storedM == "openrouter/free" || storedM.isNullOrBlank()) {
                prefs.edit().putString("active_model", "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free").apply()
            }
        } catch (_: Exception) {}

        startAsForeground()
        startContinuousAudioRecord()
        preCacheCommonAudio()
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
                val st = if (isServiceMuted) "paused" else "listening_wake"
                serviceListener?.onWakeStateChanged(st)
                return START_STICKY
            }
            ACTION_FORCE_LISTEN, ACTION_TRIGGER_LISTEN -> {
                triggerForceListen()
                return START_STICKY
            }
            else -> {
                acquireLocks(this)
                try {
                    val prefs = getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
                    val storedM = prefs.getString("active_model", null)
                    if (storedM == "openrouter/free" || storedM.isNullOrBlank()) {
                        prefs.edit().putString("active_model", "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free").apply()
                    }
                } catch (_: Exception) {}

                startAsForeground()
                if (!isRecordingLoopActive) {
                    startContinuousAudioRecord()
                }
                return START_STICKY
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "onTaskRemoved: App swiped away. Scheduling instant restart via ServiceRestartReceiver!")
        try {
            val broadcastIntent = Intent(applicationContext, ServiceRestartReceiver::class.java).apply {
                action = ServiceRestartReceiver.ACTION_RESTART_VOICE_SERVICE
            }
            val pi = PendingIntent.getBroadcast(
                applicationContext,
                9998,
                broadcastIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am?.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 500L, pi)
            } else {
                am?.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 500L, pi)
            }
            val restartIntent = Intent(applicationContext, JarvisVoiceService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartIntent)
            } else {
                startService(restartIntent)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "onTaskRemoved restart notice: ${t.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun setVadMode(mode: VadMode) {
        currentVadMode = mode
        val st = when (mode) {
            VadMode.ACTIVE_COMMAND -> "listening_cmd"
            VadMode.SLEEP_WAKEWORD_ONLY -> "paused"
            VadMode.STANDBY_WAKE_WORD -> "active"
        }
        serviceListener?.onWakeStateChanged(st)
        broadcastUiUpdate(wakeStatus = st)
        Log.i(TAG, "VAD mode switched to $mode ($st)")
    }

    fun setTorchState(turnOn: Boolean) {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val flashCameraId = cm.cameraIdList.firstOrNull { id ->
                try {
                    val chars = cm.getCameraCharacteristics(id)
                    chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                } catch (_: Exception) {
                    false
                }
            } ?: cm.cameraIdList.firstOrNull()
            if (flashCameraId != null) {
                isTorchOn = turnOn
                cm.setTorchMode(flashCameraId, isTorchOn)
                Log.i(TAG, "Native torch set to $isTorchOn (camera: $flashCameraId)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "setTorchState error: ${e.message}")
        }
    }

    // -----------------------------------------------------------------------
    // Configurable Server URL (Local Termux vs Remote VPS)
    // -----------------------------------------------------------------------

    fun getServerUrl(): String {
        val prefs = getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
        val raw = prefs.getString("server_url", "http://104.252.175.159:8765")?.trim() ?: "http://104.252.175.159:8765"
        // Auto-migrate legacy localhost default to 24/7 remote VPS
        if (raw.isBlank() || raw == "http://127.0.0.1:8765" || raw == "http://localhost:8765") {
            prefs.edit().putString("server_url", "http://104.252.175.159:8765").apply()
            return "http://104.252.175.159:8765"
        }
        return raw.removeSuffix("/")
    }

    private fun getBatteryTelemetry(): Pair<Int, Boolean> {
        return try {
            val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = registerReceiver(null, ifilter)
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                             status == BatteryManager.BATTERY_STATUS_FULL
            Pair(pct, isCharging)
        } catch (_: Exception) {
            Pair(-1, false)
        }
    }

    // -----------------------------------------------------------------------
    // Hardware Acoustic Audio Processors
    // -----------------------------------------------------------------------

    private fun attachAudioProcessors(audioSessionId: Int) {
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                    enabled = true
                    Log.i(TAG, "Hardware NoiseSuppressor attached and enabled.")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed attaching NoiseSuppressor: ${e.message}")
        }
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                acousticEchoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply {
                    enabled = true
                    Log.i(TAG, "Hardware AcousticEchoCanceler attached and enabled.")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed attaching AcousticEchoCanceler: ${e.message}")
        }
        try {
            if (AutomaticGainControl.isAvailable()) {
                automaticGainControl = AutomaticGainControl.create(audioSessionId)?.apply {
                    enabled = true
                    Log.i(TAG, "Hardware AutomaticGainControl attached and enabled.")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed attaching AutomaticGainControl: ${e.message}")
        }
    }

    private fun releaseAudioProcessors() {
        try {
            noiseSuppressor?.release()
            noiseSuppressor = null
        } catch (_: Exception) {}
        try {
            acousticEchoCanceler?.release()
            acousticEchoCanceler = null
        } catch (_: Exception) {}
        try {
            automaticGainControl?.release()
            automaticGainControl = null
        } catch (_: Exception) {}
    }

    // -----------------------------------------------------------------------
    // Continuous AudioRecord + Adaptive SNR Voice Gate Loop
    // -----------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun startContinuousAudioRecord() {
        if (recordingJob?.isActive == true) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO permission not granted yet")
            return
        }

        recordingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val minBufSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val bufferSize = maxOf(minBufSize, CHUNK_SAMPLES * 2 * 4)

                var record: AudioRecord? = null
                try {
                    // 1. Hardware Noise Isolation (Beamforming):
                    // MUST use VOICE_COMMUNICATION for hardware dual-mic beamforming noise suppression
                    record = try {
                        AudioRecord(
                            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                            SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufferSize
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "AudioRecord initialization error: ${e.message}", e)
                        null
                    }

                    val activeRecord = record
                    if (activeRecord == null || activeRecord.state != AudioRecord.STATE_INITIALIZED) {
                        Log.e(TAG, "AudioRecord state not initialized, retrying in 1s...")
                        record?.release()
                        delay(1000)
                        continue
                    }

                    audioRecord = activeRecord
                    // Attach Android native hardware audio processors directly to AudioRecord session
                    attachAudioProcessors(activeRecord.audioSessionId)
                    activeRecord.startRecording()
                    isRecordingLoopActive = true
                    Log.i(TAG, "Hardware beamforming active (VOICE_COMMUNICATION, session=${activeRecord.audioSessionId})")

                    val readBuffer = ShortArray(CHUNK_SAMPLES)
                    val preRollSamples = (SAMPLE_RATE * PRE_ROLL_DURATION_MS) / 1000 // 6400 samples
                    val preRoll = ShortRingBuffer(preRollSamples)
                    val utteranceStream = ByteArrayOutputStream()

                    var ambientNoiseFloor = 180.0
                    var ttsEchoBaseline = 150.0
                    var bargeInSustainedMs = 0L
                    var lastSpeechTimestamp = 0L
                    var utteranceStartTime = 0L
                    var consecutiveZeroReads = 0

                    withContext(Dispatchers.Main) {
                        val st = if (isServiceMuted) "paused" else "listening_wake"
                        serviceListener?.onWakeStateChanged(st)
                        updateNotification()
                    }

                    while (isActive && isRecordingLoopActive) {
                        // During PROCESSING or MUTED, flush incoming audio bytes
                        if (isServiceMuted || isProcessing || acousticState == AcousticState.PROCESSING) {
                            activeRecord.read(readBuffer, 0, CHUNK_SAMPLES)
                            preRoll.clear()
                            utteranceStream.reset()
                            isUtteranceActive = false
                            delay(20)
                            continue
                        }

                        // Layer 4: Smart Near-Field Barge-In Monitor during TTS speaking
                        if (isTtsSpeaking || acousticState == AcousticState.SPEAKING) {
                            val readCount = activeRecord.read(readBuffer, 0, CHUNK_SAMPLES)
                            if (readCount > 0) {
                                var sum = 0.0
                                for (i in 0 until readCount) {
                                    val s = readBuffer[i].toDouble()
                                    sum += s * s
                                }
                                val currentRms = Math.sqrt(sum / readCount)

                                if (currentRms < ttsEchoBaseline * 1.8) {
                                    ttsEchoBaseline = ttsEchoBaseline * 0.92 + currentRms * 0.08
                                }

                                val isBargeInEnergy = (currentRms > ambientNoiseFloor * 3.0) && (currentRms > ttsEchoBaseline * 2.2)
                                if (isBargeInEnergy) {
                                    bargeInSustainedMs += (CHUNK_SAMPLES * 1000L / SAMPLE_RATE)
                                    if (bargeInSustainedMs >= 350L) {
                                        Log.i(TAG, "Near-field barge-in triggered! Sustained vocal energy: RMS=$currentRms, ambient=$ambientNoiseFloor, echo=$ttsEchoBaseline")
                                        bargeInSustainedMs = 0L
                                        withContext(Dispatchers.Main) {
                                            triggerBargeInAbort()
                                        }
                                    }
                                } else if (currentRms <= ttsEchoBaseline * 1.5) {
                                    bargeInSustainedMs = maxOf(0L, bargeInSustainedMs - 50L)
                                }
                            }
                            preRoll.clear()
                            utteranceStream.reset()
                            isUtteranceActive = false
                            continue
                        }

                        val readCount = activeRecord.read(readBuffer, 0, CHUNK_SAMPLES)
                        if (readCount <= 0) {
                            consecutiveZeroReads++
                            if (consecutiveZeroReads > 30) {
                                Log.w(TAG, "AudioRecord stalled in background, recycling...")
                                break
                            }
                            delay(20)
                            continue
                        }
                        consecutiveZeroReads = 0

                        val now = System.currentTimeMillis()

                        // Revert ACTIVE_COMMAND to STANDBY_WAKE_WORD after awake timeout, but NEVER interrupt an ongoing speech utterance!
                        if (currentVadMode == VadMode.ACTIVE_COMMAND && !isUtteranceActive && now > awakeUntilTimestamp) {
                            currentVadMode = VadMode.STANDBY_WAKE_WORD
                            withContext(Dispatchers.Main) {
                                val st = if (isServiceMuted) "paused" else "listening_wake"
                                serviceListener?.onWakeStateChanged(st)
                                updateNotification()
                            }
                        }

                        // 2. Dynamic SNR Voice Activity Detection (VAD):
                        // Continuously calculate the Root Mean Square (RMS) of the audio chunks
                        var sum = 0.0
                        for (i in 0 until readCount) {
                            val s = readBuffer[i].toDouble()
                            sum += s * s
                        }
                        val currentRms = Math.sqrt(sum / readCount)

                        // Compute normalized amplitude 0f..1f and broadcast live for glowing UI orb
                        val normAmp = ((currentRms - ambientNoiseFloor) / 2000.0).coerceIn(0.0, 1.0).toFloat()
                        if (normAmp > 0.05f) {
                            broadcastUiUpdate(amplitude = normAmp)
                            withContext(Dispatchers.Main) {
                                serviceListener?.onAmplitudeChanged(normAmp)
                            }
                        }

                        // Speech Trigger: Speech is ONLY detected when current_rms > (ambient_noise_floor * 1.8)
                        val isSpeechDetected = currentRms > (ambientNoiseFloor * 1.8)

                        if (!isUtteranceActive) {
                            if (isSpeechDetected) {
                                isUtteranceActive = true
                                acousticState = AcousticState.LISTENING
                                utteranceStartTime = now
                                lastSpeechTimestamp = now
                                awakeUntilTimestamp = now + 60000L
                                utteranceStream.reset()
                                utteranceStream.write(preRoll.toByteArray())
                                utteranceStream.write(shortsToBytes(readBuffer, readCount))
                                withContext(Dispatchers.Main) {
                                    serviceListener?.onSpeechStarted()
                                    val st = if (currentVadMode == VadMode.ACTIVE_COMMAND) "listening_cmd" else "listening_wake"
                                    serviceListener?.onWakeStateChanged(st)
                                    updateNotification("Listening...")
                                }
                            } else {
                                // Calculate Ambient Floor: When idle, continuously calculate RMS to establish ambient_noise_floor
                                if (currentRms < ambientNoiseFloor * 1.5) {
                                    ambientNoiseFloor = ambientNoiseFloor * 0.95 + currentRms * 0.05
                                } else {
                                    ambientNoiseFloor = ambientNoiseFloor * 0.99 + currentRms * 0.01
                                }
                                ambientNoiseFloor = ambientNoiseFloor.coerceIn(30.0, 3000.0)
                                preRoll.write(readBuffer, readCount)
                            }
                        } else {
                            // 3. Smart End-of-Speech Detection (The 3.5s Rule):
                            // Once speech has triggered, continuously buffer the audio.
                            utteranceStream.write(shortsToBytes(readBuffer, readCount))

                            if (isSpeechDetected) {
                                // User actively speaking: reset silence timer
                                lastSpeechTimestamp = now
                            }

                            // Layer 1: Semantic Endpointing (Adaptive Silence Cutoff instead of fixed 3.5s)
                            val sessionDuration = now - utteranceStartTime
                            val silenceDuration = now - lastSpeechTimestamp
                            val adaptiveCutoffMs = SemanticEndpointer.getAdaptiveSilenceTimeoutMs(accumulatedTranscript)
                            val shouldStop = (silenceDuration >= adaptiveCutoffMs) || (sessionDuration >= SemanticEndpointer.MAX_COMMAND_DURATION_MS)

                            if (shouldStop) {
                                // Layer 3: Instant Acoustic Chime (<20ms soft sci-fi dual-tone sine chirp)
                                MediaEngine.playSubtleCaptureChime()

                                isUtteranceActive = false
                                isProcessing = true
                                acousticState = AcousticState.PROCESSING

                                val pcmData = utteranceStream.toByteArray()
                                utteranceStream.reset()
                                preRoll.clear()

                                withContext(Dispatchers.Main) {
                                    serviceListener?.onSpeechEnded()
                                    serviceListener?.onWakeStateChanged("processing")
                                    broadcastUiUpdate(wakeStatus = "processing")
                                    updateNotification("Processing speech...")
                                }

                                if (pcmData.size >= 9600) {
                                    val wavBytes = pcmToWav(pcmData, SAMPLE_RATE)
                                    dispatchVadUtterance(wavBytes)
                                } else {
                                    isProcessing = false
                                    acousticState = AcousticState.IDLE
                                    withContext(Dispatchers.Main) {
                                        val st = if (currentVadMode == VadMode.ACTIVE_COMMAND) "listening_cmd" else "listening_wake"
                                        serviceListener?.onWakeStateChanged(st)
                                        updateNotification()
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "AudioRecord loop exception: ${e.message}", e)
                } finally {
                    releaseAudioProcessors()
                    try {
                        record?.stop()
                        record?.release()
                    } catch (_: Exception) {}
                    audioRecord = null
                    isRecordingLoopActive = false
                }
                delay(500)
            }
        }
    }

    private fun triggerForceListen() {
        if (isServiceMuted) return
        if (isTtsSpeaking || acousticState == AcousticState.SPEAKING) {
            triggerBargeInAbort()
            return
        }
        if (isProcessing) return
        playActivationChime()
        currentVadMode = VadMode.ACTIVE_COMMAND
        awakeUntilTimestamp = System.currentTimeMillis() + 15000L
        isUtteranceActive = true
        acousticState = AcousticState.LISTENING
        scope.launch(Dispatchers.Main) {
            serviceListener?.onWakeStateChanged("listening_cmd")
            serviceListener?.onSpeechStarted()
            updateNotification("Listening for command...")
        }
    }

    /**
     * Layer 4: Immediate Graceful Barge-In Abort
     */
    fun triggerBargeInAbort() {
        try {
            activeStreamCall?.cancel()
            activeStreamCall = null
            fillerJob?.cancel()
            fillerJob = null
            stopCurrentPlayback()
            isTtsSpeaking = false
            isProcessing = false
            isUtteranceActive = true
            acousticState = AcousticState.LISTENING
            currentVadMode = VadMode.ACTIVE_COMMAND
            awakeUntilTimestamp = System.currentTimeMillis() + 15000L
            serviceListener?.onTtsFinished()
            serviceListener?.onSpeechStarted()
            serviceListener?.onWakeStateChanged("listening_cmd")
            broadcastUiUpdate(wakeStatus = "listening_cmd")
            updateNotification("Listening for command...")
        } catch (e: Exception) {
            Log.w(TAG, "triggerBargeInAbort error: ${e.message}")
        }
    }

    private fun pcmToWav(pcmData: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val totalDataLen = pcmData.size + 36
        val byteRate = sampleRate * 1 * 16 / 8 // 32000
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

    private fun shortsToBytes(shorts: ShortArray, count: Int): ByteArray {
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val s = shorts[i].toInt()
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    // -----------------------------------------------------------------------
    // Utterance Dispatch to Backend & 100% Native Action Execution
    // -----------------------------------------------------------------------

    fun broadcastUiUpdate(
        userText: String? = null,
        assistantReply: String? = null,
        wakeStatus: String? = null,
        amplitude: Float? = null
    ) {
        try {
            val intent = Intent("com.openjarvis.ACTION_UI_UPDATE").apply {
                userText?.let { putExtra("user_text", it) }
                assistantReply?.let { putExtra("assistant_reply", it) }
                wakeStatus?.let { putExtra("wake_status", it) }
                amplitude?.let { putExtra("amplitude", it) }
                setPackage(packageName)
            }
            sendBroadcast(intent)
        } catch (_: Throwable) {}

        // Part A: Update Floating Sci-Fi HUD Overlay
        try {
            userText?.let { JarvisOverlayManager.instance?.updateText(it) }
            assistantReply?.let { JarvisOverlayManager.instance?.updateText(it) }
            wakeStatus?.let { JarvisOverlayManager.instance?.updateState(it) }
            amplitude?.let { JarvisOverlayManager.instance?.updateAmplitude(it) }
        } catch (_: Throwable) {}
    }

    private fun dispatchVadUtterance(wavBytes: ByteArray) {
        scope.launch {
            try {
                serviceListener?.onWakeStateChanged("processing")
                broadcastUiUpdate(wakeStatus = "processing")
                updateNotification("Processing speech...")

                val (batPct, isCharging) = getBatteryTelemetry()
                ProactiveMonitor.checkBatteryAlert(applicationContext, batPct, isCharging)

                val result = withContext(Dispatchers.IO) {
                    sendVadUtterance(wavBytes, batPct, isCharging)
                }

                if (result != null) {
                    val status = result.optString("status", "")
                    val action = result.optString("action", "")
                    var transcript = result.optString("transcription", "")
                    val response = result.optString("response", "")
                    val clientActions = result.optJSONArray("client_actions")

                    // Multi-Clause Utterance Accumulator: stitch partial chunks
                    if (accumulatedTranscript.isNotBlank() && transcript.isNotBlank()) {
                        transcript = UtteranceStitcher.stitchUtterances(accumulatedTranscript, transcript)
                    }

                    if (transcript.isNotBlank()) {
                        serviceListener?.onFinalSpeech(transcript)
                        broadcastUiUpdate(userText = transcript)
                    }

                    // If server ignored the speech (ambient room noise in standby without wake word), discard silently!
                    if (status == "ignored" || status == "no_speech") {
                        isProcessing = false
                        acousticState = AcousticState.IDLE
                        val st = "active"
                        serviceListener?.onWakeStateChanged(st)
                        broadcastUiUpdate(wakeStatus = st)
                        updateNotification("J.A.R.V.I.S. Standby — Say 'Jarvis' anytime")
                        return@launch
                    }

                    // Incomplete-Clause Guard: if trailing conjunction/preposition, buffer and keep listening
                    if (status != "sleep" && transcript.isNotBlank() && UtteranceStitcher.hasIncompleteClause(transcript)) {
                        Log.i(TAG, "Incomplete clause guard tripped for '$transcript'. Buffering and extending pause wait.")
                        accumulatedTranscript = transcript
                        currentVadMode = VadMode.ACTIVE_COMMAND
                        awakeUntilTimestamp = System.currentTimeMillis() + 15000L
                        isProcessing = false
                        acousticState = AcousticState.IDLE
                        val st = "listening_cmd"
                        serviceListener?.onWakeStateChanged(st)
                        broadcastUiUpdate(wakeStatus = st)
                        updateNotification("Listening for completion...")
                        return@launch
                    }

                    accumulatedTranscript = ""

                    val cleanT = transcript.trim().lowercase(Locale.ROOT)

                    // Part C: Ghost Protocol (<300ms fast path, completely silent!)
                    if (cleanT.contains("ghost protocol") || cleanT.contains("پروتکل مخفی") || cleanT.contains("پروتکل شبح")) {
                        ProtocolEngine.executeGhostProtocol(applicationContext)
                        isProcessing = false
                        acousticState = AcousticState.IDLE
                        val st = "active"
                        serviceListener?.onWakeStateChanged(st)
                        broadcastUiUpdate(wakeStatus = st)
                        return@launch
                    }

                    val sleepTriggers = listOf(
                        "stop listening", "bye jarvis", "goodbye jarvis", "go to sleep",
                        "jarvis sleep", "sleep mode", "standby", "that's all jarvis", "mute yourself",
                        "دیگه گوش نده", "گوش نده", "خداحافظ جارویس", "فعلا جارویس", "جارویس بخواب",
                        "بخواب", "استندبای", "خداحافظ"
                    )
                    val isSleep = status == "sleep" || action == "sleep" || sleepTriggers.any { cleanT.contains(it) }

                    // Sleep / Standby Mode Transition (Pillar 4)
                    if (isSleep) {
                        currentVadMode = VadMode.SLEEP_WAKEWORD_ONLY
                        awakeUntilTimestamp = 0L
                        updateNotification("J.A.R.V.I.S. Sleeping — Say 'Jarvis' to wake")
                        serviceListener?.onWakeStateChanged("paused")
                        val isFa = cleanT.any { it in '\u0600'..'\u06FF' }
                        val sleepMsg = if (response.isNotBlank()) response
                                      else if (isFa) "به حالت استندبای می‌روم قربان. هر زمان نیاز داشتید جارویس را صدا بزنید."
                                      else "Going into standby mode, sir. Say Jarvis whenever you need me."
                        serviceListener?.onAssistantResponse(sleepMsg)
                        broadcastUiUpdate(assistantReply = sleepMsg, wakeStatus = "paused")
                        speakBritish(sleepMsg)
                        return@launch
                    } else if (status == "awake" || action == "wake_alone") {
                        currentVadMode = VadMode.ACTIVE_COMMAND
                        awakeUntilTimestamp = System.currentTimeMillis() + 15000L
                    } else if (status == "executed") {
                        currentVadMode = VadMode.STANDBY_WAKE_WORD
                        awakeUntilTimestamp = 0L
                    }

                    // Native Kotlin Fast-Path for Flashlight / Torch
                    val torchTerms = listOf("flashlight", "torch", "چراغ قوه", "فلش")
                    if (torchTerms.any { cleanT.contains(it) }) {
                        val offTerms = listOf("off", "خاموش", "ببند", "قطع", "disable", "stop")
                        val shouldTurnOn = !offTerms.any { cleanT.contains(it) }
                        setTorchState(shouldTurnOn)
                    }

                    // Native Kotlin Fast-Path for Camera (Always launch native camera app fully!)
                    val camTerms = listOf("open camera", "launch camera", "take photo", "take a picture", "take picture", "دوربین رو باز کن", "دوربین باز کن", "عکس بگیر", "یه عکس بگیر")
                    if (camTerms.any { cleanT.contains(it) }) {
                        DeviceController.openCamera(applicationContext)
                    }

                    // 100% Native execution of client actions (App, Torch, Alarm, Call, SMS, Volume, Media)
                    if (clientActions != null) {
                        var deferredMediaAction: JSONObject? = null
                        for (i in 0 until clientActions.length()) {
                            val act = clientActions.optJSONObject(i) ?: continue
                            val type = act.optString("type", "")
                            if (type in listOf("media_play", "play_song", "play_music")) {
                                deferredMediaAction = act
                            } else {
                                executeNativeClientAction(act)
                            }
                        }

                        // Audio Ducking / Mute Guard: Wait for Jarvis voice confirmation to finish speaking
                        // BEFORE launching media playback, so Jarvis's voice doesn't clash with or pause the music player!
                        if (deferredMediaAction != null) {
                            if (response.isNotBlank()) {
                                serviceListener?.onAssistantResponse(response)
                                broadcastUiUpdate(assistantReply = response)
                                speakBritish(response) {
                                    executeNativeClientAction(deferredMediaAction)
                                }
                            } else {
                                executeNativeClientAction(deferredMediaAction)
                            }
                            return@launch
                        }
                    }

                    if (response.isNotBlank()) {
                        serviceListener?.onAssistantResponse(response)
                        broadcastUiUpdate(assistantReply = response)
                        speakBritish(response)
                    } else {
                        isProcessing = false
                        acousticState = AcousticState.IDLE
                        val st = if (currentVadMode == VadMode.ACTIVE_COMMAND) "listening_cmd" else "active"
                        serviceListener?.onWakeStateChanged(st)
                        broadcastUiUpdate(wakeStatus = st)
                        updateNotification()
                    }
                } else {
                    isProcessing = false
                    acousticState = AcousticState.IDLE
                    val st = if (currentVadMode == VadMode.ACTIVE_COMMAND) "listening_cmd" else "active"
                    serviceListener?.onWakeStateChanged(st)
                    broadcastUiUpdate(wakeStatus = st)
                    updateNotification()
                }
            } catch (e: Exception) {
                Log.e(TAG, "dispatchVadUtterance error: ${e.message}", e)
                isProcessing = false
                acousticState = AcousticState.IDLE
            }
        }
    }

    private fun sendVadUtterance(wavData: ByteArray, batteryPct: Int, isCharging: Boolean): JSONObject? {
        return try {
            val server = getServerUrl()
            val url = URL("$server/api/voice/vad_utterance?battery=$batteryPct&charging=$isCharging")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "audio/wav")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("X-Device-Battery", batteryPct.toString())
            conn.setRequestProperty("X-Device-Charging", isCharging.toString())
            val prefs = getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
            val apiKey = prefs.getString("api_key", "") ?: ""
            if (apiKey.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
            }
            conn.connectTimeout = 5000
            conn.readTimeout = 15000
            conn.doOutput = true
            conn.outputStream.use { it.write(wavData) }
            val code = conn.responseCode
            if (code in 200..299) {
                val respStr = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                JSONObject(respStr)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "sendVadUtterance error: ${e.message}")
            null
        }
    }

    private fun findContactNumber(contactName: String): String? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val cleanQuery = contactName.trim().lowercase(Locale.ROOT)
        var matchedNumber: String? = null
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        try {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                var exactMatch: String? = null
                var partialMatch: String? = null
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx)?.lowercase(Locale.ROOT) ?: continue
                    val number = cursor.getString(numIdx) ?: continue
                    if (name == cleanQuery) {
                        exactMatch = number
                        break
                    } else if (name.contains(cleanQuery) || cleanQuery.contains(name)) {
                        if (partialMatch == null) {
                            partialMatch = number
                        }
                    }
                }
                matchedNumber = exactMatch ?: partialMatch
            }
        } catch (e: Exception) {
            Log.w(TAG, "Contacts query exception: ${e.message}")
        }
        return matchedNumber
    }

    private fun executeNativeClientAction(act: JSONObject) {
        val type = act.optString("type", "")
        if (type == "vision_camera") {
            val prompt = act.optString("prompt", "What do you see?")
            handleVisionAction("camera", prompt)
            return
        } else if (type == "vision_screen") {
            val prompt = act.optString("prompt", "What is on the screen?")
            handleVisionAction("screen", prompt)
            return
        } else if (type == "summarize_notifications") {
            handleSummarizeNotificationsAction()
            return
        } else if (type == "morning_briefing") {
            ProactiveMonitor.generateMorningBriefing(applicationContext, getServerUrl()) { briefing ->
                serviceListener?.onAssistantResponse(briefing)
                broadcastUiUpdate(assistantReply = briefing)
                speakBritish(briefing)
            }
            return
        }

        DeviceController.executeClientAction(applicationContext, act) { screenText ->
            scope.launch {
                sendScreenSummaryRequest(screenText)
            }
        }
    }

    /**
     * Part A: Summarize unread cached notifications via backend briefing API
     */
    fun handleSummarizeNotificationsAction() {
        scope.launch {
            try {
                val unread = JarvisNotificationListener.getUnreadNotificationsJson()
                if (unread.length() == 0) {
                    val emptyMsg = if (Locale.getDefault().language == "fa") "هیچ اعلان جدیدی وجود ندارد قربان."
                                   else "You have no unread notifications, sir."
                    serviceListener?.onAssistantResponse(emptyMsg)
                    broadcastUiUpdate(assistantReply = emptyMsg)
                    speakBritish(emptyMsg)
                    return@launch
                }
                updateNotification("Summarizing notifications...")
                serviceListener?.onWakeStateChanged("processing")
                broadcastUiUpdate(wakeStatus = "processing")

                val summary = withContext(Dispatchers.IO) {
                    try {
                        val url = URL("${getServerUrl().removeSuffix("/")}/api/notifications/summarize")
                        val conn = url.openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                        conn.connectTimeout = 5000
                        conn.readTimeout = 12000
                        conn.doOutput = true

                        val req = JSONObject().apply {
                            put("notifications", unread)
                        }
                        OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(req.toString()) }

                        if (conn.responseCode in 200..299) {
                            val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                            JSONObject(resp).optString("summary", "")
                        } else null
                    } catch (e: Exception) {
                        Log.w(TAG, "Notification summary request failed: ${e.message}")
                        null
                    }
                }

                val finalResp = if (!summary.isNullOrBlank()) {
                    summary
                } else {
                    val count = unread.length()
                    if (Locale.getDefault().language == "fa") "شما $count اعلان جدید دارید قربان."
                    else "You have $count unread notifications, sir."
                }
                serviceListener?.onAssistantResponse(finalResp)
                broadcastUiUpdate(assistantReply = finalResp)
                speakBritish(finalResp)
            } catch (e: Exception) {
                Log.e(TAG, "handleSummarizeNotificationsAction error: ${e.message}", e)
            }
        }
    }

    /**
     * Part B: Handle Live Vision Action (Silent Camera Frame or Zero-Popup Screen)
     */
    fun handleVisionAction(mode: String, prompt: String) {
        scope.launch {
            try {
                acousticState = AcousticState.PROCESSING
                isProcessing = true
                serviceListener?.onWakeStateChanged("vision")
                broadcastUiUpdate(
                    userText = prompt,
                    wakeStatus = "vision"
                )
                JarvisOverlayManager.instance?.updateState("vision")
                JarvisOverlayManager.instance?.updateText(
                    if (mode == "camera") "Analyzing camera visual scene..." else "Analyzing screen graphics..."
                )
                updateNotification("J.A.R.V.I.S. Vision Active...")

                val base64Image: String? = if (mode == "camera") {
                    withContext(Dispatchers.IO) {
                        VisionEngine.captureCameraFrameBase64(applicationContext, useFrontCamera = false)
                    }
                } else {
                    val deferredScreen = CompletableDeferred<String?>()
                    JarvisAccessibilityService.instance?.captureScreenBase64 { b64 ->
                        deferredScreen.complete(b64)
                    } ?: deferredScreen.complete(null)
                    deferredScreen.await()
                }

                if (base64Image.isNullOrBlank()) {
                    Log.w(TAG, "Vision capture returned empty image for mode: $mode")
                    val isFa = isPersianOrArabic(prompt)
                    val errMsg = if (isFa) "متاسفانه امکان دریافت تصویر وجود نداشت قربان." else "Visual capture could not be acquired, sir."
                    speakBritish(errMsg)
                    return@launch
                }

                val serverUrl = getServerUrl()
                val answer = withContext(Dispatchers.IO) {
                    VisionEngine.analyzeVision(applicationContext, serverUrl, base64Image, prompt, mode)
                }

                val finalAnswer = if (!answer.isNullOrBlank()) {
                    answer
                } else {
                    val isFa = isPersianOrArabic(prompt)
                    if (isFa) "تحلیل تصویر با خطا مواجه شد قربان." else "I was unable to analyze the visual scene, sir."
                }

                serviceListener?.onAssistantResponse(finalAnswer)
                broadcastUiUpdate(
                    assistantReply = finalAnswer,
                    wakeStatus = "speaking"
                )
                JarvisOverlayManager.instance?.updateText(finalAnswer)
                speakBritish(finalAnswer)

            } catch (e: Exception) {
                Log.e(TAG, "handleVisionAction error: ${e.message}", e)
                speakBritish("Vision processing encountered an error, sir.")
            }
        }
    }

    private suspend fun sendScreenSummaryRequest(screenText: String) {
        withContext(Dispatchers.IO) {
            try {
                val server = getServerUrl()
                val url = URL("$server/api/chat")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.setRequestProperty("Accept", "application/json")
                val prefs = getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
                val apiKey = prefs.getString("api_key", "") ?: ""
                if (apiKey.isNotBlank()) {
                    conn.setRequestProperty("Authorization", "Bearer $apiKey")
                }
                conn.connectTimeout = 8000
                conn.readTimeout = 20000
                conn.doOutput = true

                val body = JSONObject().apply {
                    put("message", "Screen Content Analysis:\n$screenText\nPlease summarize what is currently on the screen or answer based on it.")
                    put("tts", true)
                }
                java.io.OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()) }

                val code = conn.responseCode
                if (code in 200..299) {
                    val respStr = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val json = JSONObject(respStr)
                    val reply = json.optString("response", "")
                    if (reply.isNotBlank()) {
                        withContext(Dispatchers.Main) {
                            serviceListener?.onAssistantResponse(reply)
                            broadcastUiUpdate(assistantReply = reply)
                            speakBritish(reply)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "sendScreenSummaryRequest failed: ${e.message}")
            }
            Unit
        }
    }

    // -----------------------------------------------------------------------
    // Layer 2: End-to-End Streaming Pipeline & Incremental Audio Playback
    // -----------------------------------------------------------------------

    fun streamChatAndPlay(prompt: String) {
        if (prompt.isBlank()) {
            isProcessing = false
            acousticState = AcousticState.IDLE
            return
        }

        val cleanP = prompt.trim().lowercase(Locale.ROOT)
        // Part C: Ghost Protocol (<300ms fast path, completely silent!)
        if (cleanP.contains("ghost protocol") || cleanP.contains("پروتکل مخفی") || cleanP.contains("پروتکل شبح")) {
            ProtocolEngine.executeGhostProtocol(applicationContext)
            isProcessing = false
            acousticState = AcousticState.IDLE
            broadcastUiUpdate(wakeStatus = "active")
            return
        }

        isProcessing = true
        acousticState = AcousticState.PROCESSING
        serviceListener?.onWakeStateChanged("processing")
        broadcastUiUpdate(wakeStatus = "processing")
        updateNotification("J.A.R.V.I.S. Thinking...")

        // Layer 3: Verbal Filler Micro-Ack for Long/Complex Queries (>1600ms)
        fillerJob?.cancel()
        fillerJob = scope.launch(Dispatchers.Main) {
            delay(1600L)
            if (isProcessing && !isTtsSpeaking) {
                val isFa = isPersianOrArabic(prompt)
                val filler = if (isFa) {
                    listOf("یه لحظه...", "الان بررسی می‌کنم قربان...", "چشم قربان...").random()
                } else {
                    listOf("One moment, sir.", "Looking into that, sir.", "Right away, sir.").random()
                }
                Log.i(TAG, "Streaming latency > 1600ms. Speaking verbal filler: '$filler'")
                textToSpeech?.let { tts ->
                    if (isFa) {
                        tts.language = Locale("fa", "IR")
                    } else {
                        tts.language = Locale.UK
                    }
                    tts.speak(filler, TextToSpeech.QUEUE_FLUSH, null, "FILLER_${System.currentTimeMillis()}")
                }
            }
        }

        scope.launch(Dispatchers.IO) {
            var call: Call? = null
            try {
                val server = getServerUrl()
                val url = "$server/api/chat/stream"
                val (batPct, isCharging) = getBatteryTelemetry()
                ProactiveMonitor.checkBatteryAlert(applicationContext, batPct, isCharging)

                val bodyJson = JSONObject().apply {
                    put("message", prompt)
                    put("tts", false)
                    put("device_battery", batPct)
                    put("device_charging", isCharging)
                }
                val reqBody = bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

                val reqBuilder = Request.Builder()
                    .url(url)
                    .post(reqBody)
                    .addHeader("Accept", "application/x-ndjson, text/event-stream, application/json")

                val prefs = getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
                val apiKey = prefs.getString("api_key", "") ?: ""
                if (apiKey.isNotBlank()) {
                    reqBuilder.addHeader("Authorization", "Bearer $apiKey")
                }

                val request = reqBuilder.build()
                call = streamingHttpClient.newCall(request)
                activeStreamCall = call

                val response = call.execute()
                if (!response.isSuccessful) {
                    Log.w(TAG, "Stream failed with code ${response.code}")
                    withContext(Dispatchers.Main) {
                        fillerJob?.cancel()
                        speakBritish("I was unable to complete the request, sir.")
                    }
                    return@launch
                }

                var isFirstSentence = true
                val responseStream = response.body?.byteStream()
                if (responseStream == null) {
                    withContext(Dispatchers.Main) {
                        fillerJob?.cancel()
                        finishSpeaking(null)
                    }
                    return@launch
                }

                responseStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (rawLine in lines) {
                        val line = rawLine.trim()
                        if (line.isBlank()) continue
                        val jsonStr = if (line.startsWith("data:")) line.removePrefix("data:").trim() else line
                        val chunkObj = try { JSONObject(jsonStr) } catch (_: Exception) { null } ?: continue

                        val type = chunkObj.optString("type", "")
                        if (type == "sentence") {
                            val sentence = chunkObj.optString("text", "").trim()
                            if (sentence.isNotBlank()) {
                                withContext(Dispatchers.Main) {
                                    fillerJob?.cancel()
                                    isProcessing = false
                                    isTtsSpeaking = true
                                    acousticState = AcousticState.SPEAKING
                                    serviceListener?.onAssistantResponse(sentence)
                                    broadcastUiUpdate(assistantReply = sentence, wakeStatus = "speaking")
                                    updateNotification("Speaking...")

                                    playIncrementalTtsSentence(sentence, isFirstSentence)
                                    isFirstSentence = false
                                }
                            }
                        } else if (type == "actions") {
                            val actions = chunkObj.optJSONArray("client_actions")
                            if (actions != null) {
                                withContext(Dispatchers.Main) {
                                    for (i in 0 until actions.length()) {
                                        val act = actions.optJSONObject(i) ?: continue
                                        executeNativeClientAction(act)
                                    }
                                }
                            }
                        } else if (type == "done") {
                            Log.i(TAG, "Stream complete signal received.")
                        }
                    }
                }
            } catch (e: Exception) {
                if (call?.isCanceled() == true) {
                    Log.i(TAG, "Stream call canceled due to barge-in.")
                } else {
                    Log.w(TAG, "streamChatAndPlay error: ${e.message}")
                    withContext(Dispatchers.Main) {
                        fillerJob?.cancel()
                        finishSpeaking(null)
                    }
                }
            } finally {
                activeStreamCall = null
            }
        }
    }

    private fun playIncrementalTtsSentence(sentence: String, isFirstSentence: Boolean) {
        val tts = textToSpeech
        if (tts == null || !isTtsReady) {
            speakBritish(sentence)
            return
        }

        try {
            val isFa = isPersianOrArabic(sentence)
            if (isFa) {
                val persianLocale = Locale("fa", "IR")
                if (tts.isLanguageAvailable(persianLocale) >= TextToSpeech.LANG_AVAILABLE) {
                    tts.language = persianLocale
                }
            } else {
                tts.language = Locale.UK
                tts.setPitch(0.90f)
                tts.setSpeechRate(1.05f)
            }

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isTtsSpeaking = true
                    acousticState = AcousticState.SPEAKING
                    serviceListener?.onTtsStarted()
                }
                override fun onDone(utteranceId: String?) {
                    scope.launch {
                        delay(350)
                        if (acousticState == AcousticState.SPEAKING) {
                            isTtsSpeaking = false
                            acousticState = AcousticState.IDLE
                            serviceListener?.onTtsFinished()
                            val st = if (isServiceMuted) "paused" else if (currentVadMode == VadMode.ACTIVE_COMMAND) "listening_cmd" else "listening_wake"
                            serviceListener?.onWakeStateChanged(st)
                            broadcastUiUpdate(wakeStatus = st)
                            updateNotification()
                        }
                    }
                }
                override fun onError(utteranceId: String?) {
                    scope.launch {
                        delay(350)
                        if (acousticState == AcousticState.SPEAKING) {
                            isTtsSpeaking = false
                            acousticState = AcousticState.IDLE
                            serviceListener?.onTtsFinished()
                        }
                    }
                }
            })

            val utteranceId = "JARVIS_STREAM_" + System.currentTimeMillis()
            val queueMode = if (isFirstSentence) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts.speak(sentence, queueMode, null, utteranceId)
        } catch (e: Exception) {
            Log.w(TAG, "playIncrementalTtsSentence error: ${e.message}")
        }
    }

    // -----------------------------------------------------------------------
    // Pre-Cached Instant British Audio & Single-Queue Player
    // -----------------------------------------------------------------------

    private fun preCacheCommonAudio() {
        scope.launch(Dispatchers.IO) {
            val phrases = listOf(
                Pair("At your service, sir. I'm listening.", "wake_ack.mp3"),
                Pair("Right away, sir.", "action_ack.mp3"),
                Pair("All systems are online, sir.", "online_ack.mp3"),
                Pair("Yes, sir?", "yes_sir.mp3"),
                Pair("در خدمت شما هستم قربان. گوش می‌دهم.", "wake_ack_fa.mp3"),
                Pair("Going into standby mode, sir. Just say Jarvis whenever you need me.", "standby_ack.mp3"),
                Pair("به حالت استندبای می‌روم قربان. هر زمان نیاز داشتید جارویس را صدا بزنید.", "standby_ack_fa.mp3")
            )
            val cacheFolder = File(cacheDir, "jarvis_voice_cache").apply { mkdirs() }
            val server = getServerUrl()
            for ((text, filename) in phrases) {
                val destFile = File(cacheFolder, filename)
                if (destFile.exists() && destFile.length() > 500) continue
                try {
                    val isFa = text.any { it in '\u0600'..'\u06FF' }
                    val voice = if (isFa) "fa-IR-FaridNeural" else "en-GB-RyanNeural"
                    val encText = URLEncoder.encode(text, "UTF-8")
                    val encVoice = URLEncoder.encode(voice, "UTF-8")
                    val url = URL("$server/api/tts/audio?text=$encText&voice=$encVoice&rate=%2B6%25&pitch=-2Hz")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 3000
                    conn.readTimeout = 15000
                    if (conn.responseCode in 200..299) {
                        destFile.outputStream().use { out -> conn.inputStream.copyTo(out) }
                        Log.i(TAG, "Pre-cached British audio for '$text'")
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun getSpokenSummary(text: String): String {
        val clean = text.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
        val sentences = clean.split(Regex("(?<=[.!?؛؟])\\s+"))
        return if (sentences.size > 2) {
            sentences.take(2).joinToString(" ").trim()
        } else {
            clean
        }
    }

    fun speakBritish(text: String, onFinished: (() -> Unit)? = null) {
        if (text.isBlank()) {
            isProcessing = false
            acousticState = AcousticState.IDLE
            onFinished?.invoke()
            return
        }

        scope.launch(Dispatchers.Main) {
            audioPlayMutex.withLock {
                // 1. Immediately stop any current audio playback to prevent overlapping double voice!
                stopCurrentPlayback()

                isTtsSpeaking = true
                isProcessing = false
                acousticState = AcousticState.SPEAKING
                serviceListener?.onTtsStarted()
                updateNotification("Speaking...")

                val summaryText = getSpokenSummary(text)
                val replyText = summaryText
                val hasPersian = isPersianOrArabic(replyText)

                // Check local pre-cache first (<10ms instant playback)
                val cacheFolder = File(cacheDir, "jarvis_voice_cache")
                val trimmed = summaryText.trim().trimEnd('.', '!', '?')
                val cachedFile = when {
                    trimmed.startsWith("At your service") -> File(cacheFolder, "wake_ack.mp3")
                    trimmed.startsWith("Right away") -> File(cacheFolder, "action_ack.mp3")
                    trimmed.startsWith("All systems are online") -> File(cacheFolder, "online_ack.mp3")
                    trimmed.startsWith("Yes, sir") -> File(cacheFolder, "yes_sir.mp3")
                    trimmed.startsWith("در خدمت شما هستم") -> File(cacheFolder, "wake_ack_fa.mp3")
                    trimmed.startsWith("Going into standby") -> File(cacheFolder, "standby_ack.mp3")
                    trimmed.startsWith("به حالت استندبای") -> File(cacheFolder, "standby_ack_fa.mp3")
                    else -> null
                }

                if (cachedFile != null && cachedFile.exists() && cachedFile.length() > 500) {
                    val played = playLocalMp3File(cachedFile)
                    if (played) {
                        finishSpeaking(onFinished)
                        return@withLock
                    }
                }

                // Fetch dynamic neural British voice via edge-tts with 15s timeout (NO Google female voice fallback!)
                val voiceParam = if (hasPersian) "fa-IR-FaridNeural" else "en-GB-RyanNeural"
                val rateParam = if (hasPersian) "+0%" else "+6%"
                val pitchParam = "-2Hz"

                val audioSuccess = withContext(Dispatchers.IO) {
                    playViaMediaPlayer(summaryText, voiceParam, rateParam, pitchParam)
                }

                if (!audioSuccess) {
                    // Only use UK male TTS fallback if available, never default female voice
                    speakViaUkMaleTTS(summaryText, hasPersian, onFinished)
                } else {
                    finishSpeaking(onFinished)
                }
            }
        }
    }

    private suspend fun finishSpeaking(onFinished: (() -> Unit)?) {
        // Layer 4: Echo-decay cooldown on natural finish (350ms) before reopening mic
        delay(350)
        isTtsSpeaking = false
        isProcessing = false
        acousticState = AcousticState.IDLE
        serviceListener?.onTtsFinished()
        val st = if (isServiceMuted) "paused" else if (currentVadMode == VadMode.ACTIVE_COMMAND) "listening_cmd" else "listening_wake"
        serviceListener?.onWakeStateChanged(st)
        updateNotification()
        onFinished?.invoke()
    }

    private fun stopCurrentPlayback() {
        try {
            activeMediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Exception) {}
        activeMediaPlayer = null
        try {
            textToSpeech?.stop()
        } catch (_: Exception) {}
    }

    private suspend fun playLocalMp3File(file: File): Boolean = withContext(Dispatchers.Main) {
        val completed = CompletableDeferred<Boolean>()
        try {
            val mp = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .build()
                )
                setOnCompletionListener {
                    completed.complete(true)
                }
                setOnErrorListener { _, _, _ ->
                    completed.complete(false)
                    true
                }
                prepare()
                start()
            }
            activeMediaPlayer = mp
            completed.await()
        } catch (e: Exception) {
            completed.complete(false)
            false
        } finally {
            try {
                activeMediaPlayer?.release()
                activeMediaPlayer = null
            } catch (_: Exception) {}
        }
    }

    private suspend fun playViaMediaPlayer(
        text: String,
        voice: String,
        rate: String,
        pitch: String
    ): Boolean = withContext(Dispatchers.IO) {
        var tempFile: File? = null
        try {
            val server = getServerUrl()
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val encodedVoice = URLEncoder.encode(voice, "UTF-8")
            val encodedRate = URLEncoder.encode(rate, "UTF-8")
            val encodedPitch = URLEncoder.encode(pitch, "UTF-8")
            val url = URL("$server/api/tts/audio?text=$encodedText&voice=$encodedVoice&rate=$encodedRate&pitch=$encodedPitch")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 15000 // Generous 15s timeout for complete neural synthesis
            conn.requestMethod = "GET"
            if (conn.responseCode !in 200..299) {
                return@withContext false
            }

            tempFile = File.createTempFile("jarvis_tts_", ".mp3", cacheDir)
            tempFile.outputStream().use { out ->
                conn.inputStream.copyTo(out)
            }

            if (tempFile.length() < 100) {
                tempFile.delete()
                return@withContext false
            }

            val completed = CompletableDeferred<Boolean>()
            withContext(Dispatchers.Main) {
                try {
                    val mp = MediaPlayer().apply {
                        setDataSource(tempFile.absolutePath)
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .build()
                        )
                        setOnCompletionListener {
                            completed.complete(true)
                        }
                        setOnErrorListener { _, _, _ ->
                            completed.complete(false)
                            true
                        }
                        prepare()
                        start()
                    }
                    activeMediaPlayer = mp
                } catch (e: Exception) {
                    completed.complete(false)
                }
            }
            completed.await()
        } catch (e: Exception) {
            Log.w(TAG, "playViaMediaPlayer error: ${e.message}")
            false
        } finally {
            try {
                activeMediaPlayer?.release()
                activeMediaPlayer = null
            } catch (_: Exception) {}
            try { tempFile?.delete() } catch (_: Exception) {}
        }
    }

    private fun isPersianOrArabic(text: String): Boolean {
        val regex = Regex("[\\u0600-\\u06FF]")
        return regex.containsMatchIn(text)
    }

    private fun speakViaUkMaleTTS(text: String, isPersian: Boolean, onFinished: (() -> Unit)?) {
        val tts = textToSpeech
        if (tts == null || !isTtsReady) {
            scope.launch { finishSpeaking(onFinished) }
            return
        }

        try {
            val replyText = text
            if (isPersianOrArabic(replyText)) {
                val persianLocale = Locale("fa", "IR")
                if (tts.isLanguageAvailable(persianLocale) >= TextToSpeech.LANG_AVAILABLE) {
                    tts.language = persianLocale
                } else {
                    // Fallback if the specific device lacks Persian TTS (inform user via Toast/Log)
                    Log.w(TAG, "Persian TTS language is not available on this device")
                    Toast.makeText(applicationContext, "Persian TTS not supported on device", Toast.LENGTH_SHORT).show()
                }
            } else {
                tts.language = Locale.UK // Or Locale.US
                tts.setPitch(0.90f)
                tts.setSpeechRate(1.05f)
            }

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    scope.launch { finishSpeaking(onFinished) }
                }
                override fun onError(utteranceId: String?) {
                    scope.launch { finishSpeaking(onFinished) }
                }
            })
            val utteranceId = "JARVIS_VOICE_" + System.currentTimeMillis()
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } catch (e: Exception) {
            Log.e(TAG, "speakViaUkMaleTTS error: ${e.message}")
            scope.launch { finishSpeaking(onFinished) }
        }
    }

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
                textToSpeech?.setPitch(0.90f)
                textToSpeech?.setSpeechRate(1.05f)

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
            isTtsReady = true
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
    // Foreground Notification (Status Bar Persistence in Xiaomi HyperOS)
    // -----------------------------------------------------------------------

    private fun startAsForeground() {
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                } catch (t: Throwable) {
                    Log.w(TAG, "startForeground with microphone type failed: ${t.message}. Falling back.")
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground fatal error: ${t.message}", t)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "J.A.R.V.I.S. Voice Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps J.A.R.V.I.S. always-on voice recognition and audio responses active in background"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(overrideText: String? = null): Notification {
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

        val contentText = overrideText ?: when {
            isServiceMuted -> "J.A.R.V.I.S. Sleeping — Say 'Jarvis' to wake"
            currentVadMode == VadMode.ACTIVE_COMMAND -> "🟢 Listening for command..."
            else -> "J.A.R.V.I.S. Standby — Say 'Jarvis' anytime"
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

    private fun updateNotification(overrideText: String? = null) {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification(overrideText))
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        isServiceRunning = false
        isRecordingLoopActive = false
        recordingJob?.cancel()
        try {
            unregisterReceiver(packageReceiver)
        } catch (_: Exception) {}
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        stopCurrentPlayback()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        releaseLocks()
        JarvisOverlayManager.instance?.destroy()
        super.onDestroy()
    }
}
