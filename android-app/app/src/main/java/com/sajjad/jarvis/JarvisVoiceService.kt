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
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
        private const val STANDBY_SILENCE_WINDOW_MS = 800L  // Fast wake word cutoff
        private const val COMMAND_SILENCE_WINDOW_MS = 1200L // 1.2s silence cutoff (Pillar 3)
        private const val MAX_COMMAND_DURATION_MS = 6000L   // Hard 6.0s max recording duration (Pillar 3)
        private const val PRE_ROLL_DURATION_MS = 400
        private const val CHUNK_SAMPLES = 1600 // 100ms at 16kHz
        private const val MIN_SPEECH_RMS = 300.0

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

    @Volatile private var isTtsSpeaking = false
    @Volatile private var activeMediaPlayer: MediaPlayer? = null
    private val audioPlayMutex = Mutex()

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    @Volatile private var isRecordingLoopActive = false
    @Volatile private var isUtteranceActive = false

    @Volatile private var currentVadMode = VadMode.STANDBY_WAKE_WORD
    @Volatile private var awakeUntilTimestamp = 0L

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

    private fun getServerUrl(): String {
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
    // Continuous AudioRecord + Two-Stage RMS VAD Loop
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
                    // Try VOICE_RECOGNITION source first, fallback to MIC
                    record = try {
                        AudioRecord(
                            MediaRecorder.AudioSource.VOICE_RECOGNITION,
                            SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufferSize
                        )
                    } catch (_: Exception) {
                        try {
                            AudioRecord(
                                MediaRecorder.AudioSource.MIC,
                                SAMPLE_RATE,
                                AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT,
                                bufferSize
                            )
                        } catch (_: Exception) {
                            null
                        }
                    }

                    val activeRecord = record
                    if (activeRecord == null || activeRecord.state != AudioRecord.STATE_INITIALIZED) {
                        Log.e(TAG, "AudioRecord state not initialized, retrying in 1s...")
                        record?.release()
                        delay(1000)
                        continue
                    }

                    audioRecord = activeRecord
                    activeRecord.startRecording()
                    isRecordingLoopActive = true
                    Log.i(TAG, "Continuous AudioRecord running (zero beeps, persistent mic in background)")

                    val readBuffer = ShortArray(CHUNK_SAMPLES)
                    val preRollSamples = (SAMPLE_RATE * PRE_ROLL_DURATION_MS) / 1000 // 6400 samples
                    val preRoll = ShortRingBuffer(preRollSamples)
                    val utteranceStream = ByteArrayOutputStream()

                    var ambientRms = 180.0
                    var lastSpeechTimestamp = 0L
                    var utteranceStartTime = 0L
                    var consecutiveZeroReads = 0

                    withContext(Dispatchers.Main) {
                        val st = if (isServiceMuted) "paused" else "listening_wake"
                        serviceListener?.onWakeStateChanged(st)
                        updateNotification()
                    }

                    while (isActive && isRecordingLoopActive) {
                        if (isServiceMuted || isTtsSpeaking) {
                            // Drain hardware audio buffer to prevent room echo accumulation
                            activeRecord.read(readBuffer, 0, CHUNK_SAMPLES)
                            preRoll.clear()
                            utteranceStream.reset()
                            isUtteranceActive = false
                            delay(20)
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

                        // Revert ACTIVE_COMMAND to STANDBY_WAKE_WORD after 15s awake timeout
                        if (currentVadMode == VadMode.ACTIVE_COMMAND && now > awakeUntilTimestamp) {
                            currentVadMode = VadMode.STANDBY_WAKE_WORD
                            withContext(Dispatchers.Main) {
                                val st = if (isServiceMuted) "paused" else "listening_wake"
                                serviceListener?.onWakeStateChanged(st)
                                updateNotification()
                            }
                        }

                        val silenceCutoffMs = if (currentVadMode == VadMode.STANDBY_WAKE_WORD) {
                            STANDBY_SILENCE_WINDOW_MS // Fast 800ms for wake word
                        } else {
                            COMMAND_SILENCE_WINDOW_MS // Deterministic 1.2s for questions
                        }

                        // Calculate RMS of audio chunk
                        var sum = 0.0
                        for (i in 0 until readCount) {
                            val s = readBuffer[i].toDouble()
                            sum += s * s
                        }
                        val chunkRms = Math.sqrt(sum / readCount)
                        val speechThreshold = maxOf(ambientRms * 2.1, MIN_SPEECH_RMS)

                        // Compute normalized amplitude 0f..1f and broadcast live for glowing orb
                        val normAmp = ((chunkRms - ambientRms) / 2000.0).coerceIn(0.0, 1.0).toFloat()
                        if (normAmp > 0.05f) {
                            broadcastUiUpdate(amplitude = normAmp)
                            withContext(Dispatchers.Main) {
                                serviceListener?.onAmplitudeChanged(normAmp)
                            }
                        }

                        if (chunkRms >= speechThreshold) {
                            lastSpeechTimestamp = now
                            if (!isUtteranceActive) {
                                isUtteranceActive = true
                                utteranceStartTime = now
                                utteranceStream.reset()
                                utteranceStream.write(preRoll.toByteArray())
                                withContext(Dispatchers.Main) {
                                    serviceListener?.onSpeechStarted()
                                    val st = if (currentVadMode == VadMode.ACTIVE_COMMAND) "listening_cmd" else "listening_wake"
                                    serviceListener?.onWakeStateChanged(st)
                                    updateNotification("Listening...")
                                }
                            }
                            utteranceStream.write(shortsToBytes(readBuffer, readCount))
                        } else {
                            if (isUtteranceActive) {
                                utteranceStream.write(shortsToBytes(readBuffer, readCount))
                            } else {
                                ambientRms = ambientRms * 0.95 + chunkRms * 0.05
                                preRoll.write(readBuffer, readCount)
                            }
                        }

                        // Deterministic Non-Hanging Guarantee (Hard 6s Max & 1.2s Silence Cutoff)
                        if (isUtteranceActive) {
                            val sessionDuration = now - utteranceStartTime
                            val silenceDuration = now - lastSpeechTimestamp
                            val maxDuration = if (currentVadMode == VadMode.ACTIVE_COMMAND) MAX_COMMAND_DURATION_MS else 3000L
                            val shouldStop = (sessionDuration >= maxDuration) || (silenceDuration >= silenceCutoffMs)

                            if (shouldStop) {
                                isUtteranceActive = false
                                val pcmData = utteranceStream.toByteArray()
                                utteranceStream.reset()
                                preRoll.clear()

                                withContext(Dispatchers.Main) {
                                    serviceListener?.onSpeechEnded()
                                }

                                val minBytes = if (currentVadMode == VadMode.STANDBY_WAKE_WORD) 9600 else 14400
                                if (pcmData.size >= minBytes) {
                                    val wavBytes = pcmToWav(pcmData, SAMPLE_RATE)
                                    dispatchVadUtterance(wavBytes)
                                } else {
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
        if (isServiceMuted || isTtsSpeaking) return
        playActivationChime()
        currentVadMode = VadMode.ACTIVE_COMMAND
        awakeUntilTimestamp = System.currentTimeMillis() + 15000L
        isUtteranceActive = true
        scope.launch(Dispatchers.Main) {
            serviceListener?.onWakeStateChanged("listening_cmd")
            serviceListener?.onSpeechStarted()
            updateNotification("Listening for command...")
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
    }

    private fun dispatchVadUtterance(wavBytes: ByteArray) {
        scope.launch {
            serviceListener?.onWakeStateChanged("processing")
            broadcastUiUpdate(wakeStatus = "processing")
            updateNotification("Processing speech...")

            val (batPct, isCharging) = getBatteryTelemetry()

            val result = withContext(Dispatchers.IO) {
                sendVadUtterance(wavBytes, batPct, isCharging)
            }

            if (result != null) {
                val status = result.optString("status", "")
                val action = result.optString("action", "")
                val transcript = result.optString("transcription", "")
                val response = result.optString("response", "")
                val clientActions = result.optJSONArray("client_actions")

                if (transcript.isNotBlank()) {
                    serviceListener?.onFinalSpeech(transcript)
                    broadcastUiUpdate(userText = transcript)
                }

                // If server ignored the speech (ambient room noise in standby without wake word), discard silently!
                if (status == "ignored" || status == "no_speech") {
                    val st = "active"
                    serviceListener?.onWakeStateChanged(st)
                    broadcastUiUpdate(wakeStatus = st)
                    updateNotification("J.A.R.V.I.S. Standby — Say 'Jarvis' anytime")
                    return@launch
                }

                val cleanT = transcript.trim().lowercase(Locale.ROOT)
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

                // 100% Native execution of client actions (App, Torch, Alarm, Call, SMS, Volume)
                if (clientActions != null) {
                    for (i in 0 until clientActions.length()) {
                        val act = clientActions.optJSONObject(i) ?: continue
                        executeNativeClientAction(act)
                    }
                }

                if (response.isNotBlank()) {
                    serviceListener?.onAssistantResponse(response)
                    broadcastUiUpdate(assistantReply = response)
                    speakBritish(response)
                } else {
                    val st = if (currentVadMode == VadMode.ACTIVE_COMMAND) "listening_cmd" else "active"
                    serviceListener?.onWakeStateChanged(st)
                    broadcastUiUpdate(wakeStatus = st)
                    updateNotification()
                }
            } else {
                val st = if (currentVadMode == VadMode.ACTIVE_COMMAND) "listening_cmd" else "active"
                serviceListener?.onWakeStateChanged(st)
                broadcastUiUpdate(wakeStatus = st)
                updateNotification()
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
        try {
            when (act.optString("type")) {
                "launch_app" -> {
                    val target = act.optString("target") ?: act.optString("package") ?: ""
                    if (target.isNotBlank()) {
                        AppLauncher.launchApp(applicationContext, target)
                    }
                }
                "torch" -> {
                    val state = act.optString("state", "toggle")
                    val turnOn = when (state) {
                        "on" -> true
                        "off" -> false
                        else -> !isTorchOn
                    }
                    setTorchState(turnOn)
                }
                "alarm" -> {
                    val h = act.optInt("hour", 7)
                    val m = act.optInt("minute", 0)
                    val msg = act.optString("message", "Jarvis Alarm")
                    val alarmIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_HOUR, h)
                        putExtra(AlarmClock.EXTRA_MINUTES, m)
                        putExtra(AlarmClock.EXTRA_MESSAGE, msg)
                        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    AppLauncher.launchWithOverlayFallback(this, alarmIntent)
                }
                "call" -> {
                    var num = act.optString("number", "").trim()
                    val name = act.optString("name", "").trim()
                    if (num.isBlank() && name.isNotBlank()) {
                        num = findContactNumber(name) ?: ""
                    }
                    if (num.isNotBlank()) {
                        val cleanPhone = num.replace(" ", "").replace("-", "")
                        val callIntent = if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                            Intent(Intent.ACTION_CALL, Uri.parse("tel:$cleanPhone"))
                        } else {
                            Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleanPhone"))
                        }.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        AppLauncher.launchWithOverlayFallback(this, callIntent)
                    } else {
                        val dialerIntent = Intent(Intent.ACTION_DIAL).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        AppLauncher.launchWithOverlayFallback(this, dialerIntent)
                        val isFa = name.any { it in '\u0600'..'\u06FF' }
                        val dialerMsg = if (isFa) "صفحه تماس برای $name باز شد قربان." else "Opening dialer for $name, sir."
                        serviceListener?.onAssistantResponse(dialerMsg)
                        speakBritish(dialerMsg)
                    }
                }
                "sms" -> {
                    val num = act.optString("number", "").trim()
                    val body = act.optString("message", "").trim()
                    val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$num")).apply {
                        putExtra("sms_body", body)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    AppLauncher.launchWithOverlayFallback(this, smsIntent)
                }
                "volume" -> {
                    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    when (act.optString("action")) {
                        "up" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                        "down" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                        "mute" -> am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
                        "set" -> am.setStreamVolume(AudioManager.STREAM_MUSIC, act.optInt("level", 10), AudioManager.FLAG_SHOW_UI)
                    }
                }
                "open_url" -> {
                    val urlStr = act.optString("url", "").trim()
                    if (urlStr.isNotBlank()) {
                        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(urlStr)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        AppLauncher.launchWithOverlayFallback(this, viewIntent)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "executeNativeClientAction error: ${e.message}")
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
            onFinished?.invoke()
            return
        }

        scope.launch(Dispatchers.Main) {
            audioPlayMutex.withLock {
                // 1. Immediately stop any current audio playback to prevent overlapping double voice!
                stopCurrentPlayback()

                isTtsSpeaking = true
                serviceListener?.onTtsStarted()
                updateNotification("Speaking...")

                val summaryText = getSpokenSummary(text)
                val hasPersian = summaryText.any { it in '\u0600'..'\u06FF' }

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
        // Echo decay delay (600ms) before reopening mic
        delay(600)
        isTtsSpeaking = false
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

    private fun speakViaUkMaleTTS(text: String, isPersian: Boolean, onFinished: (() -> Unit)?) {
        if (textToSpeech == null || !isTtsReady) {
            scope.launch { finishSpeaking(onFinished) }
            return
        }

        try {
            if (isPersian) {
                val res = textToSpeech?.setLanguage(Locale("fa", "IR"))
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    textToSpeech?.language = Locale.UK
                }
            } else {
                textToSpeech?.language = Locale.UK
                textToSpeech?.setPitch(0.90f)
                textToSpeech?.setSpeechRate(1.05f)
            }

            textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    scope.launch { finishSpeaking(onFinished) }
                }
                override fun onError(utteranceId: String?) {
                    scope.launch { finishSpeaking(onFinished) }
                }
            })
            val utteranceId = "JARVIS_UK_" + System.currentTimeMillis()
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
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
        super.onDestroy()
    }
}
