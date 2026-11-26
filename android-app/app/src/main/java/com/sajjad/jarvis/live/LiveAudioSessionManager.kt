package com.sajjad.jarvis.live

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import com.sajjad.jarvis.JarvisOverlayManager
import com.sajjad.jarvis.JarvisVoiceService
import com.sajjad.jarvis.MediaEngine
import com.sajjad.jarvis.ProVoiceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID

/**
 * LiveAudioSessionManager (OpenJarvis v2.5.0-android):
 *
 * Real-time continuous audio listening session manager.
 * Captures live audio from phone (music, videos, reels, podcasts, voice notes)
 * using dual-source strategy (Mode A: Internal MediaProjection, Mode B: Seamless Mic Fallback).
 *
 * Chunks audio into 10-second segments, uploads in real-time to VPS backend,
 * coordinates multi-layer song identification and grounded Q&A.
 */
object LiveAudioSessionManager {

    private const val TAG = "LiveAudioSession"
    private const val SAMPLE_RATE = 16000
    private const val CHUNK_SECONDS = 10
    private const val MAX_SESSION_SECONDS = 90
    private const val BYTES_PER_SAMPLE = 2 // 16-bit PCM Mono
    private const val CHUNK_SIZE_BYTES = SAMPLE_RATE * BYTES_PER_SAMPLE * CHUNK_SECONDS // ~320,000 bytes

    @Volatile
    var isActive: Boolean = false
        private set

    @Volatile
    var currentSessionId: String? = null
        private set

    var mediaProjection: MediaProjection? = null

    private val scope = CoroutineScope(Dispatchers.IO)
    private var tickerJob: Job? = null
    private var internalAudioRecord: AudioRecord? = null
    private var internalAudioJob: Job? = null

    private val pcmAccumulator = ByteArrayOutputStream()
    private var sessionStartTimeMs: Long = 0L
    private var pendingQuestion: String? = null
    private var onAnswerCallback: ((String) -> Unit)? = null

    /**
     * Start live audio listening session.
     */
    fun startListening(
        context: Context,
        question: String? = null,
        onAnswer: ((String) -> Unit)? = null
    ) {
        if (isActive) {
            Log.w(TAG, "Audio listening session already active (session=$currentSessionId)")
            return
        }

        isActive = true
        currentSessionId = "audio_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        sessionStartTimeMs = System.currentTimeMillis()
        pendingQuestion = question?.trim()
        onAnswerCallback = onAnswer
        pcmAccumulator.reset()

        val sessId = currentSessionId!!

        // Query active media metadata from Android MediaSessionManager (Spotify, YouTube, VLC, etc.)
        val activeMedia = MediaEngine.getActiveMediaInfo(context)
        val mediaMetaJson = JSONObject().apply {
            if (activeMedia != null) {
                put("title", activeMedia.first)
                put("artist", activeMedia.second)
            }
        }

        // 1. Notify VPS of session start
        scope.launch {
            try {
                val server = getServerUrl(context)
                val u = URL("${server.removeSuffix("/")}/api/audio/session/start")
                val conn = (u.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 4000
                    readTimeout = 4000
                    doOutput = true
                }
                val body = JSONObject().apply {
                    put("session_id", sessId)
                    put("media_metadata", mediaMetaJson)
                }.toString()
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                Log.i(TAG, "Live audio session start reported to VPS: HTTP $code (session=$sessId)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to report session start to VPS: ${e.message}")
            }
        }

        // 2. Start Dual-Source Audio Capture
        startDualCapture(context)

        // 3. Show HUD status & start 1-second ticker
        startHudTicker(context)

        Log.i(TAG, "LiveAudioSessionManager started (session=$sessId, hasQuestion=${pendingQuestion != null})")
    }

    /**
     * Stop listening, flush remaining audio chunk, and ask question (or enter conversation mode).
     */
    fun stopAndAsk(context: Context, immediateQuestion: String? = null) {
        if (!isActive) return
        isActive = false

        val sessId = currentSessionId ?: return
        val finalQuestion = immediateQuestion?.trim() ?: pendingQuestion

        stopCapture()
        tickerJob?.cancel()
        tickerJob = null

        JarvisOverlayManager.instance?.updateState("thinking", "Analyzing audio...")

        // Flush final chunk and finish session
        scope.launch {
            val remainingPcm = synchronized(pcmAccumulator) {
                val b = pcmAccumulator.toByteArray()
                pcmAccumulator.reset()
                b
            }

            var finalAnswer: String? = null
            var identifiedSong: JSONObject? = null

            // Upload remaining audio chunk if at least 500ms
            if (remainingPcm.size >= (SAMPLE_RATE * BYTES_PER_SAMPLE / 2)) {
                val wav = pcmToWav(remainingPcm, SAMPLE_RATE)
                uploadChunk(context, sessId, wav)
            }

            // Call finish endpoint on VPS
            try {
                val server = getServerUrl(context)
                val u = URL("${server.removeSuffix("/")}/api/audio/session/finish")
                val conn = (u.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 12000
                    readTimeout = 15000
                    doOutput = true
                }
                val body = JSONObject().apply {
                    put("session_id", sessId)
                    if (!finalQuestion.isNullOrBlank()) {
                        put("question", finalQuestion)
                    }
                    put("language", if (isPersian(finalQuestion ?: "")) "fa" else "en")
                }.toString()
                conn.outputStream.use { it.write(body.toByteArray()) }

                if (conn.responseCode in 200..299) {
                    val respStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val respJson = JSONObject(respStr)
                    finalAnswer = respJson.optString("answer", "")
                    identifiedSong = respJson.optJSONObject("identified_song")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to finish audio session: ${e.message}", e)
            }

            withContext(Dispatchers.Main) {
                val isFa = isPersian(finalQuestion ?: "")
                if (!finalAnswer.isNullOrBlank()) {
                    // Direct answer ready!
                    JarvisVoiceService.instance?.let { svc ->
                        svc.speakBritish(finalAnswer!!)
                        JarvisOverlayManager.instance?.updateText(finalAnswer!!)
                        JarvisOverlayManager.instance?.updateState("speaking")
                    }
                    onAnswerCallback?.invoke(finalAnswer!!)
                } else if (!finalQuestion.isNullOrBlank()) {
                    // Question provided but finish returned no direct answer; ask Q&A endpoint
                    askQuestion(context, finalQuestion, isFa)
                } else {
                    // No initial question provided: chime and prompt user conversationally
                    val songTitle = identifiedSong?.optString("title", "")
                    val songArtist = identifiedSong?.optString("artist", "")
                    val promptMsg = if (!songTitle.isNullOrBlank()) {
                        if (isFa) "این آهنگ «$songTitle»${if (!songArtist.isNullOrBlank()) " از $songArtist" else ""} است قربان. چه سوالی درباره آن دارید؟"
                        else "Identified \"$songTitle\"${if (!songArtist.isNullOrBlank()) " by $songArtist" else ""}, sir. What would you like to know?"
                    } else {
                        if (isFa) "گوش دادم قربان، چی می‌خوای بدونی؟"
                        else "I've listened to it, sir. What would you like to know?"
                    }
                    JarvisVoiceService.instance?.let { svc ->
                        svc.speakBritish(promptMsg)
                        JarvisOverlayManager.instance?.updateText(promptMsg)
                        JarvisOverlayManager.instance?.updateState("active_conversation")
                    }
                }
            }
        }
    }

    /**
     * Ask a question about the active or finished audio session.
     */
    fun askQuestion(context: Context, question: String, isFa: Boolean = true) {
        val sessId = currentSessionId ?: return
        scope.launch {
            var answerText: String? = null
            try {
                val server = getServerUrl(context)
                val u = URL("${server.removeSuffix("/")}/api/audio/session/ask")
                val conn = (u.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 10000
                    readTimeout = 15000
                    doOutput = true
                }
                val body = JSONObject().apply {
                    put("session_id", sessId)
                    put("question", question)
                    put("language", if (isFa) "fa" else "en")
                }.toString()
                conn.outputStream.use { it.write(body.toByteArray()) }

                if (conn.responseCode in 200..299) {
                    val respStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val respJson = JSONObject(respStr)
                    answerText = respJson.optString("response", "")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio session ask failed: ${e.message}", e)
            }

            withContext(Dispatchers.Main) {
                val reply = if (!answerText.isNullOrBlank()) answerText!!
                            else (if (isFa) "متأسفانه امکان پاسخگویی درباره این صوت فراهم نشد قربان." else "Could not analyze the audio content, sir.")
                JarvisVoiceService.instance?.let { svc ->
                    svc.speakBritish(reply)
                    JarvisOverlayManager.instance?.updateText(reply)
                    JarvisOverlayManager.instance?.updateState("speaking")
                }
                onAnswerCallback?.invoke(reply)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Dual-Source Audio Capture (Mode A & Mode B)
    // -------------------------------------------------------------------------
    private fun startDualCapture(context: Context) {
        var modeAActive = false

        // Mode A: Internal System Audio Capture (MediaProjection API 29+)
        val proj = mediaProjection
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && proj != null) {
            try {
                val config = AudioPlaybackCaptureConfiguration.Builder(proj)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                val rec = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBuf * 2, 8192))
                    .build()

                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    rec.startRecording()
                    internalAudioRecord = rec
                    modeAActive = true
                    Log.i(TAG, "Mode A: Internal System Audio Capture active via MediaProjection")

                    internalAudioJob = scope.launch {
                        val buf = ShortArray(1600) // 100ms
                        while (isActive && rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                            val read = rec.read(buf, 0, buf.size)
                            if (read > 0) {
                                onPcmSamplesCaptured(context, buf, read)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Mode A internal audio init failed, falling back to Mode B: ${e.message}")
            }
        }

        // Mode B: Seamless Mic Capture Fallback (Default & Always Ready!)
        if (!modeAActive) {
            Log.i(TAG, "Mode B: Seamless Mic Capture active via ProVoiceEngine tap")
            ProVoiceEngine.liveAudioChunkListener = { buffer, count ->
                if (isActive) {
                    onPcmSamplesCaptured(context, buffer, count)
                }
            }
        }
    }

    private fun stopCapture() {
        ProVoiceEngine.liveAudioChunkListener = null
        try {
            internalAudioRecord?.stop()
            internalAudioRecord?.release()
        } catch (_: Exception) {}
        internalAudioRecord = null
        internalAudioJob?.cancel()
        internalAudioJob = null
    }

    private fun onPcmSamplesCaptured(context: Context, samples: ShortArray, count: Int) {
        val byteBuf = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            byteBuf.putShort(samples[i])
        }
        val pcmBytes = byteBuf.array()

        var chunkToUpload: ByteArray? = null
        synchronized(pcmAccumulator) {
            pcmAccumulator.write(pcmBytes)
            if (pcmAccumulator.size() >= CHUNK_SIZE_BYTES) {
                chunkToUpload = pcmAccumulator.toByteArray()
                pcmAccumulator.reset()
            }
        }

        if (chunkToUpload != null && isActive) {
            val sessId = currentSessionId ?: return
            val wav = pcmToWav(chunkToUpload!!, SAMPLE_RATE)
            uploadChunk(context, sessId, wav)
        }
    }

    private fun uploadChunk(context: Context, sessionId: String, wavBytes: ByteArray) {
        scope.launch {
            try {
                val server = getServerUrl(context)
                val boundary = "===JarvisAudioChunkBoundary==="
                val u = URL("${server.removeSuffix("/")}/api/audio/session/chunk")
                val conn = (u.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    connectTimeout = 8000
                    readTimeout = 10000
                    doOutput = true
                }

                conn.outputStream.use { os ->
                    val dos = DataOutputStream(os)
                    // session_id field
                    dos.writeBytes("--$boundary\r\n")
                    dos.writeBytes("Content-Disposition: form-data; name=\"session_id\"\r\n\r\n")
                    dos.writeBytes(sessionId)
                    dos.writeBytes("\r\n")

                    // audio file field
                    dos.writeBytes("--$boundary\r\n")
                    dos.writeBytes("Content-Disposition: form-data; name=\"audio\"; filename=\"chunk.wav\"\r\n")
                    dos.writeBytes("Content-Type: audio/wav\r\n\r\n")
                    dos.write(wavBytes)
                    dos.writeBytes("\r\n")

                    dos.writeBytes("--$boundary--\r\n")
                    dos.flush()
                }

                val code = conn.responseCode
                Log.i(TAG, "Audio chunk uploaded: HTTP $code (${wavBytes.size} bytes)")
            } catch (e: Exception) {
                Log.w(TAG, "Audio chunk upload error: ${e.message}")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Floating HUD Status Ticker & 90s Cap
    // -------------------------------------------------------------------------
    private fun startHudTicker(context: Context) {
        tickerJob?.cancel()
        tickerJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                val elapsedSec = ((System.currentTimeMillis() - sessionStartTimeMs) / 1000L).coerceAtLeast(0L)
                val timeStr = String.format(Locale.ROOT, "%02d:%02d", elapsedSec / 60, elapsedSec % 60)
                val isFa = isPersian(pendingQuestion ?: "")

                val hudMsg = if (isFa) "در حال گوش دادن به صوت ($timeStr) — برای توقف ضربه بزنید"
                             else "Listening to audio ($timeStr) — Tap to stop & ask"

                JarvisOverlayManager.instance?.updateState("live_audio_listening", hudMsg)

                if (elapsedSec >= MAX_SESSION_SECONDS) {
                    Log.i(TAG, "Max session time ($MAX_SESSION_SECONDS s) reached, auto-stopping.")
                    stopAndAsk(context)
                    break
                }
                delay(1000)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Pure Kotlin WAV Encoder
    // -------------------------------------------------------------------------
    fun pcmToWav(pcmData: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val totalAudioLen = pcmData.size.toLong()
        val totalDataLen = totalAudioLen + 36
        val channels = 1
        val byteRate = (sampleRate * channels * 2).toLong()

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0 // subchunk1size (16 for PCM)
        header[20] = 1; header[21] = 0 // audio format (1 = PCM)
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * 2).toByte(); header[33] = 0 // block align
        header[34] = 16; header[35] = 0 // bits per sample
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        return header + pcmData
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
