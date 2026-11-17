package com.sajjad.jarvis

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    }

    companion object {
        const val TAG = "JarvisVoiceService"
        const val CHANNEL_ID = "jarvis_voice_service_channel"
        const val NOTIFICATION_ID = 2002

        const val ACTION_START = "com.sajjad.jarvis.VOICE_START"
        const val ACTION_STOP = "com.sajjad.jarvis.VOICE_STOP"
        const val ACTION_TOGGLE_MUTE = "com.sajjad.jarvis.VOICE_TOGGLE_MUTE"
        const val ACTION_FORCE_LISTEN = "com.sajjad.jarvis.VOICE_FORCE_LISTEN"

        private const val SAMPLE_RATE = 16000
        private const val VAD_SILENCE_WINDOW_MS = 2800L
        private const val PRE_ROLL_DURATION_MS = 400
        private const val CHUNK_SAMPLES = 1600 // 100ms at 16kHz
        private const val MIN_SPEECH_RMS = 300.0

        var serviceListener: VoiceEventListener? = null
        var isServiceRunning = false
        var isServiceMuted = false
        var instance: JarvisVoiceService? = null

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

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    @Volatile private var isRecordingLoopActive = false
    @Volatile private var isUtteranceActive = false
    private var nativeTtsCallback: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        isServiceRunning = true
        Log.i(TAG, "JarvisVoiceService onCreate")

        acquireWakeLock(this)
        createNotificationChannel()
        initTextToSpeech()

        startAsForeground()
        startContinuousAudioRecord()
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
            ACTION_FORCE_LISTEN -> {
                triggerForceListen()
                return START_STICKY
            }
            else -> {
                acquireWakeLock(this)
                startAsForeground()
                startContinuousAudioRecord()
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -----------------------------------------------------------------------
    // Continuous AudioRecord + Smart RMS VAD
    // -----------------------------------------------------------------------

    private fun startContinuousAudioRecord() {
        if (recordingJob?.isActive == true) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO permission not granted")
            return
        }

        recordingJob = scope.launch(Dispatchers.IO) {
            val minBufSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBufSize, CHUNK_SAMPLES * 2 * 4)

            try {
                @SuppressLint("MissingPermission")
                val record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord failed to initialize")
                    return@launch
                }

                audioRecord = record
                record.startRecording()
                isRecordingLoopActive = true
                Log.i(TAG, "Continuous AudioRecord session started smoothly (zero beeps, persistent mic)")

                val readBuffer = ShortArray(CHUNK_SAMPLES)
                val preRollSamples = (SAMPLE_RATE * PRE_ROLL_DURATION_MS) / 1000 // 6400 samples
                val preRoll = ShortRingBuffer(preRollSamples)
                val utteranceStream = ByteArrayOutputStream()

                var ambientRms = 180.0
                var lastSpeechTimestamp = 0L

                withContext(Dispatchers.Main) {
                    val st = if (isServiceMuted) "paused" else "listening_wake"
                    serviceListener?.onWakeStateChanged(st)
                    updateNotification()
                }

                while (isActive && isRecordingLoopActive) {
                    if (isServiceMuted || isTtsSpeaking) {
                        preRoll.clear()
                        utteranceStream.reset()
                        isUtteranceActive = false
                        delay(80)
                        continue
                    }

                    val readCount = record.read(readBuffer, 0, CHUNK_SAMPLES)
                    if (readCount <= 0) {
                        delay(15)
                        continue
                    }

                    // Calculate RMS of this chunk
                    var sum = 0.0
                    for (i in 0 until readCount) {
                        val s = readBuffer[i].toDouble()
                        sum += s * s
                    }
                    val chunkRms = Math.sqrt(sum / readCount)
                    val speechThreshold = maxOf(ambientRms * 2.3, MIN_SPEECH_RMS)

                    val now = System.currentTimeMillis()

                    if (chunkRms >= speechThreshold) {
                        lastSpeechTimestamp = now
                        if (!isUtteranceActive) {
                            isUtteranceActive = true
                            utteranceStream.reset()
                            val preRollBytes = preRoll.toByteArray()
                            utteranceStream.write(preRollBytes)
                            withContext(Dispatchers.Main) {
                                serviceListener?.onSpeechStarted()
                                serviceListener?.onWakeStateChanged("listening_cmd")
                                updateNotification("Listening...")
                            }
                        }

                        // Write chunk PCM samples to stream
                        val chunkBytes = shortsToBytes(readBuffer, readCount)
                        utteranceStream.write(chunkBytes)
                    } else {
                        // Chunk is below speech threshold (silence or ambient)
                        if (isUtteranceActive) {
                            val chunkBytes = shortsToBytes(readBuffer, readCount)
                            utteranceStream.write(chunkBytes)

                            if (now - lastSpeechTimestamp >= VAD_SILENCE_WINDOW_MS) {
                                // 2.8 continuous seconds of silence reached! Finalize utterance!
                                isUtteranceActive = false
                                val pcmData = utteranceStream.toByteArray()
                                utteranceStream.reset()
                                preRoll.clear()

                                withContext(Dispatchers.Main) {
                                    serviceListener?.onSpeechEnded()
                                }

                                // If duration is >= 400ms (16kHz mono 16-bit = 12800 bytes)
                                if (pcmData.size >= 12800) {
                                    val wavBytes = pcmToWav(pcmData, SAMPLE_RATE)
                                    dispatchVadUtterance(wavBytes)
                                } else {
                                    withContext(Dispatchers.Main) {
                                        serviceListener?.onWakeStateChanged("listening_wake")
                                        updateNotification()
                                    }
                                }
                            }
                        } else {
                            // Idle ambient: update rolling ambient noise floor
                            ambientRms = ambientRms * 0.95 + chunkRms * 0.05
                            preRoll.write(readBuffer, readCount)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "AudioRecord loop exception: ${e.message}", e)
            } finally {
                try {
                    audioRecord?.stop()
                    audioRecord?.release()
                } catch (_: Exception) {}
                audioRecord = null
                isRecordingLoopActive = false
            }
        }
    }

    private fun triggerForceListen() {
        if (isServiceMuted || isTtsSpeaking) return
        playActivationChime()
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
        header.putInt(16) // Subchunk1Size for PCM
        header.putShort(1.toShort()) // AudioFormat 1 = PCM
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
    // Utterance Dispatch to Backend & Execution
    // -----------------------------------------------------------------------

    private fun dispatchVadUtterance(wavBytes: ByteArray) {
        scope.launch {
            serviceListener?.onWakeStateChanged("processing")
            updateNotification("Processing speech...")

            val result = withContext(Dispatchers.IO) {
                sendVadUtterance(wavBytes)
            }

            if (result != null) {
                val transcript = result.optString("transcription", "")
                val response = result.optString("response", "")
                val clientActions = result.optJSONArray("client_actions")

                if (transcript.isNotBlank()) {
                    serviceListener?.onFinalSpeech(transcript)
                }

                // Process client actions (e.g. app launch, torch, volume)
                if (clientActions != null) {
                    for (i in 0 until clientActions.length()) {
                        val act = clientActions.optJSONObject(i) ?: continue
                        if (act.optString("type") == "launch_app") {
                            val target = act.optString("target", "")
                            if (target.isNotBlank()) {
                                AppLauncher.launchApp(applicationContext, target)
                            }
                        }
                    }
                }

                if (response.isNotBlank()) {
                    serviceListener?.onAssistantResponse(response)
                    speakBritish(response)
                } else {
                    serviceListener?.onWakeStateChanged("listening_wake")
                    updateNotification()
                }
            } else {
                serviceListener?.onWakeStateChanged("listening_wake")
                updateNotification()
            }
        }
    }

    private fun sendVadUtterance(wavData: ByteArray): JSONObject? {
        return try {
            val url = URL("http://127.0.0.1:8765/api/voice/vad_utterance")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "audio/wav")
            conn.setRequestProperty("Accept", "application/json")
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

    // -----------------------------------------------------------------------
    // Authentic British Neural Voice Player (with UK Male TTS Fallback)
    // -----------------------------------------------------------------------

    fun speakBritish(text: String, onFinished: (() -> Unit)? = null) {
        if (text.isBlank()) {
            onFinished?.invoke()
            return
        }

        scope.launch(Dispatchers.Main) {
            isTtsSpeaking = true
            serviceListener?.onTtsStarted()
            updateNotification("Speaking...")

            val hasPersian = text.any { it in '\u0600'..'\u06FF' }
            val voiceParam = if (hasPersian) "fa-IR-FaridNeural" else "en-GB-RyanNeural"
            val rateParam = if (hasPersian) "+0%" else "+6%"
            val pitchParam = "-2Hz"

            val audioSuccess = withContext(Dispatchers.IO) {
                playViaMediaPlayer(text, voiceParam, rateParam, pitchParam)
            }

            if (!audioSuccess) {
                // Fallback immediately to native Android TextToSpeech
                speakViaNativeTTS(text, hasPersian, onFinished)
            } else {
                isTtsSpeaking = false
                serviceListener?.onTtsFinished()
                val st = if (isServiceMuted) "paused" else "listening_wake"
                serviceListener?.onWakeStateChanged(st)
                updateNotification()
                onFinished?.invoke()
            }
        }
    }

    private suspend fun playViaMediaPlayer(
        text: String,
        voice: String,
        rate: String,
        pitch: String
    ): Boolean = withContext(Dispatchers.IO) {
        var mediaPlayer: MediaPlayer? = null
        var tempFile: File? = null
        try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val encodedVoice = URLEncoder.encode(voice, "UTF-8")
            val encodedRate = URLEncoder.encode(rate, "UTF-8")
            val encodedPitch = URLEncoder.encode(pitch, "UTF-8")
            val url = URL("http://127.0.0.1:8765/api/tts/audio?text=$encodedText&voice=$encodedVoice&rate=$encodedRate&pitch=$encodedPitch")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout = 5000
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
                    mediaPlayer = MediaPlayer().apply {
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
                } catch (e: Exception) {
                    completed.complete(false)
                }
            }
            completed.await()
        } catch (e: Exception) {
            Log.w(TAG, "playViaMediaPlayer error: ${e.message}")
            false
        } finally {
            try { mediaPlayer?.release() } catch (_: Exception) {}
            try { tempFile?.delete() } catch (_: Exception) {}
        }
    }

    private fun speakViaNativeTTS(text: String, isPersian: Boolean, onFinished: (() -> Unit)?) {
        if (textToSpeech == null || !isTtsReady) {
            isTtsSpeaking = false
            serviceListener?.onTtsFinished()
            val st = if (isServiceMuted) "paused" else "listening_wake"
            serviceListener?.onWakeStateChanged(st)
            updateNotification()
            onFinished?.invoke()
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

            val utteranceId = "JARVIS_NATIVE_" + System.currentTimeMillis()
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }

            nativeTtsCallback = onFinished
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        } catch (e: Exception) {
            Log.e(TAG, "speakViaNativeTTS error: ${e.message}")
            isTtsSpeaking = false
            serviceListener?.onTtsFinished()
            val st = if (isServiceMuted) "paused" else "listening_wake"
            serviceListener?.onWakeStateChanged(st)
            updateNotification()
            onFinished?.invoke()
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

            textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isTtsSpeaking = true
                    serviceListener?.onTtsStarted()
                }

                override fun onDone(utteranceId: String?) {
                    isTtsSpeaking = false
                    serviceListener?.onTtsFinished()
                    val st = if (isServiceMuted) "paused" else "listening_wake"
                    serviceListener?.onWakeStateChanged(st)
                    updateNotification()
                    nativeTtsCallback?.invoke()
                    nativeTtsCallback = null
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    isTtsSpeaking = false
                    serviceListener?.onTtsFinished()
                    val st = if (isServiceMuted) "paused" else "listening_wake"
                    serviceListener?.onWakeStateChanged(st)
                    updateNotification()
                    nativeTtsCallback?.invoke()
                    nativeTtsCallback = null
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    isTtsSpeaking = false
                    serviceListener?.onTtsFinished()
                    val st = if (isServiceMuted) "paused" else "listening_wake"
                    serviceListener?.onWakeStateChanged(st)
                    updateNotification()
                    nativeTtsCallback?.invoke()
                    nativeTtsCallback = null
                }
            })
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
            isServiceMuted -> getString(R.string.voice_service_paused)
            isUtteranceActive -> "Listening for command..."
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
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        releaseWakeLock()
        super.onDestroy()
    }
}
