package com.sajjad.jarvis

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * VoiceRecorderEngine (OpenJarvis v2.5.2-android):
 *
 * Genuine zero-token, background-first Voice Recorder.
 * Reuses ProVoiceEngine's continuous 16kHz PCM stream to guarantee ZERO mic conflicts on Android 14.
 * Writes PCM data to disk in real-time, packages into valid 44-byte RIFF/WAV, and publishes to MediaStore.
 */
object VoiceRecorderEngine {

    private const val TAG = "VoiceRecorderEngine"
    private const val SAMPLE_RATE = 16000
    private const val CHANNELS = 1
    private const val BITS_PER_SAMPLE = 16

    data class VoiceRecordResult(
        val file: File?,
        val durationSeconds: Int,
        val filePath: String
    )

    @Volatile
    var isRecording: Boolean = false
        private set

    @Volatile
    private var recordingStartTimeMs: Long = 0L

    private var tempPcmFile: File? = null
    private var pcmOutputStream: FileOutputStream? = null
    private var totalBytesWritten: Long = 0L

    private val scope = CoroutineScope(Dispatchers.IO)
    private var tickerJob: Job? = null

    /**
     * Start voice recording memo.
     */
    fun startRecording(context: Context, isFa: Boolean = false): Boolean {
        if (isRecording) {
            Log.w(TAG, "Voice recording already in progress.")
            return false
        }

        try {
            val cacheDir = context.cacheDir
            tempPcmFile = File(cacheDir, "jarvis_temp_rec_${System.currentTimeMillis()}.pcm")
            pcmOutputStream = FileOutputStream(tempPcmFile)
            totalBytesWritten = 0L
            recordingStartTimeMs = System.currentTimeMillis()
            isRecording = true

            // Tap directly into ProVoiceEngine continuous 16kHz PCM stream
            ProVoiceEngine.voiceRecordListener = { buffer, count ->
                if (isRecording) {
                    try {
                        val byteBuf = ByteArray(count * 2)
                        for (i in 0 until count) {
                            val sample = buffer[i].toInt()
                            byteBuf[i * 2] = (sample and 0xFF).toByte()
                            byteBuf[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
                        }
                        synchronized(this) {
                            pcmOutputStream?.write(byteBuf)
                            totalBytesWritten += byteBuf.size
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error writing PCM frame: ${e.message}")
                    }
                }
            }

            // Start 1-second ticker to update Floating HUD pill and in-app status
            tickerJob?.cancel()
            tickerJob = scope.launch {
                while (isActive && isRecording) {
                    val elapsedSec = ((System.currentTimeMillis() - recordingStartTimeMs) / 1000).toInt()
                    val formatted = String.format(Locale.US, "%02d:%02d", elapsedSec / 60, elapsedSec % 60)
                    val hudText = if (isFa) "در حال ضبط صدا ($formatted) [توقف]"
                                  else "Recording Voice Memo ($formatted) [Stop]"

                    withContext(Dispatchers.Main) {
                        JarvisOverlayManager.instance?.updateVoiceRecorderPill(hudText)
                    }
                    delay(1000L)
                }
            }

            val startAck = if (isFa) "شروع ضبط یادداشت صوتی قربان." else "Recording voice memo now, sir."
            JarvisVoiceService.instance?.speakBritish(startAck)
            Log.i(TAG, "VoiceRecorderEngine started recording.")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start voice recording: ${e.message}", e)
            isRecording = false
            return false
        }
    }

    /**
     * Stop voice recording memo and finalize WAV file in MediaStore / Recordings.
     */
    fun stopRecording(context: Context, isFa: Boolean = false, onComplete: ((VoiceRecordResult) -> Unit)? = null): VoiceRecordResult? {
        if (!isRecording) {
            Log.w(TAG, "stopRecording called but not currently recording.")
            return null
        }

        isRecording = false
        tickerJob?.cancel()
        tickerJob = null

        // Detach listener
        ProVoiceEngine.voiceRecordListener = null

        val durationSec = ((System.currentTimeMillis() - recordingStartTimeMs) / 1000).toInt().coerceAtLeast(1)

        synchronized(this) {
            try {
                pcmOutputStream?.flush()
                pcmOutputStream?.close()
                pcmOutputStream = null
            } catch (e: Exception) {
                Log.w(TAG, "Error closing PCM stream: ${e.message}")
            }
        }

        // Restore Floating HUD state
        JarvisOverlayManager.instance?.updateState("standby")

        val pcmFile = tempPcmFile
        if (pcmFile == null || !pcmFile.exists() || pcmFile.length() == 0L) {
            Log.e(TAG, "Temp PCM file missing or empty.")
            tempPcmFile = null
            val failReply = if (isFa) "ضبط صدا ناموفق بود قربان." else "Voice memo recording failed, sir."
            JarvisVoiceService.instance?.speakBritish(failReply)
            return null
        }

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "JARVIS_REC_$timeStamp.wav"

        // Target directory: Environment.DIRECTORY_RECORDINGS/Jarvis
        val recordingsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RECORDINGS), "Jarvis").apply {
            if (!exists()) mkdirs()
        }
        val targetWavFile = File(recordingsDir, fileName)

        try {
            writeWavFile(pcmFile, targetWavFile, totalBytesWritten)
            pcmFile.delete()
            tempPcmFile = null

            // Insert into MediaStore
            publishToMediaStore(context, targetWavFile, fileName, durationSec)

            val result = VoiceRecordResult(
                file = targetWavFile,
                durationSeconds = durationSec,
                filePath = targetWavFile.absolutePath
            )

            val durationStr = if (durationSec < 60) "$durationSec ثانیه" else "${durationSec / 60} دقیقه"
            val durEn = if (durationSec < 60) "$durationSec seconds" else "${durationSec / 60} minutes"
            val confirmation = if (isFa) {
                "یادداشت صوتی ($durationStr) در پوشه ضبط‌ها ذخیره شد قربان."
            } else {
                "Voice memo saved ($durEn) to Recordings, sir."
            }

            JarvisVoiceService.instance?.speakBritish(confirmation)
            onComplete?.invoke(result)
            Log.i(TAG, "Voice memo saved successfully to ${targetWavFile.absolutePath} ($durationSec sec)")
            return result
        } catch (e: Exception) {
            Log.e(TAG, "Failed writing final WAV file: ${e.message}", e)
            val errReply = if (isFa) "خطا در ذخیره‌سازی فایل صوتی قربان." else "Error saving voice memo file, sir."
            JarvisVoiceService.instance?.speakBritish(errReply)
            return null
        }
    }

    private fun writeWavFile(rawPcmFile: File, wavOutputFile: File, pcmDataLength: Long) {
        val totalDataLen = pcmDataLength + 36
        val byteRate = (SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8).toLong()

        val header = ByteArray(44)
        // RIFF/WAVE header
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        // 'fmt ' chunk
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16 // 4 bytes: size of 'fmt ' chunk
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // format = 1 (PCM)
        header[21] = 0
        header[22] = CHANNELS.toByte()
        header[23] = 0
        header[24] = (SAMPLE_RATE and 0xff).toByte()
        header[25] = ((SAMPLE_RATE shr 8) and 0xff).toByte()
        header[26] = ((SAMPLE_RATE shr 16) and 0xff).toByte()
        header[27] = ((SAMPLE_RATE shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (CHANNELS * BITS_PER_SAMPLE / 8).toByte() // block align
        header[33] = 0
        header[34] = BITS_PER_SAMPLE.toByte()
        header[35] = 0
        // 'data' chunk
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (pcmDataLength and 0xff).toByte()
        header[41] = ((pcmDataLength shr 8) and 0xff).toByte()
        header[42] = ((pcmDataLength shr 16) and 0xff).toByte()
        header[43] = ((pcmDataLength shr 24) and 0xff).toByte()

        wavOutputFile.outputStream().use { out ->
            out.write(header)
            rawPcmFile.inputStream().use { inp ->
                inp.copyTo(out)
            }
        }
    }

    private fun publishToMediaStore(context: Context, wavFile: File, fileName: String, durationSec: Int) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_RECORDINGS}/Jarvis")
                    put(MediaStore.Audio.Media.DURATION, durationSec * 1000)
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                }
                context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            } else {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(wavFile.absolutePath),
                    arrayOf("audio/wav"),
                    null
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error publishing to MediaStore: ${e.message}")
        }
    }
}
