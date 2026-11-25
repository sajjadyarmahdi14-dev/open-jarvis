package com.sajjad.jarvis.security

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.sajjad.jarvis.JarvisAccessibilityService
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Iron Man Anti-Theft & Security Protocol Engine.
 *
 * Implements 6 deterministic zero-token protocols:
 * 1. Lock My Phone
 * 2. All Systems Down (Ghost Lockdown)
 * 3. Protocol Blackbox (Silent Front/Back Photos + 5s Rolling Audio Loop + Location + Intruder Watch)
 * 4. Protocol Siren (100% Vol Alarm + Torch Strobe + Screen Lock + Photo)
 * 5. Voice Screen Unlock (Passphrase Verification + Keypad/Swipe-up automation)
 * 6. Protocol Stand Down (Passphrase Verification + Disarm)
 */
object SecurityProtocolEngine {

    private const val TAG = "SecurityProtocolEngine"
    private const val PREFS_NAME = "jarvis_security_prefs"

    private const val KEY_PASSPHRASE_HASH = "security_passphrase_hash"
    private const val KEY_PASSPHRASE_PLAIN = "security_passphrase_plain"
    private const val KEY_LOCKSCREEN_PIN = "security_lockscreen_pin"
    private const val KEY_FAILED_ATTEMPTS = "failed_attempts_count"

    enum class SecurityState {
        NORMAL,
        ALL_SYSTEMS_DOWN,
        BLACKBOX,
        SIREN,
        AWAITING_PASSPHRASE_FOR_UNLOCK,
        AWAITING_PASSPHRASE_FOR_STANDDOWN
    }

    @Volatile
    var currentState: SecurityState = SecurityState.NORMAL
        private set

    /**
     * When true, JarvisAccessibilityService slams shut any Power Off menu or Quick Settings panel
     * to prevent thieves from powering down the phone or disabling Airplane mode.
     */
    @Volatile
    var isLockdownActive: Boolean = false
        private set

    private val scope = CoroutineScope(Dispatchers.IO)

    private var audioLoopJob: Job? = null
    private var locationLoopJob: Job? = null
    private var sirenAudioTrack: AudioTrack? = null
    private var sirenJob: Job? = null
    private val isSirenPlaying = AtomicBoolean(false)

    private val audio5sBuffer = ByteArrayOutputStream()
    private val audioBufferLock = Any()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // -------------------------------------------------------------------------
    // Passphrase & PIN Management
    // -------------------------------------------------------------------------

    fun setPassphrase(context: Context, passphrase: String) {
        val clean = cleanPassphrase(passphrase)
        val hash = sha256(clean)
        getPrefs(context).edit()
            .putString(KEY_PASSPHRASE_HASH, hash)
            .putString(KEY_PASSPHRASE_PLAIN, clean)
            .apply()
        Log.i(TAG, "Security passphrase updated.")
    }

    fun hasPassphrase(context: Context): Boolean {
        val p = getPrefs(context).getString(KEY_PASSPHRASE_HASH, "") ?: ""
        return p.isNotBlank()
    }

    fun getPlainPassphrase(context: Context): String {
        return getPrefs(context).getString(KEY_PASSPHRASE_PLAIN, "") ?: ""
    }

    fun setLockscreenPin(context: Context, pin: String) {
        getPrefs(context).edit()
            .putString(KEY_LOCKSCREEN_PIN, pin.trim())
            .apply()
    }

    fun getLockscreenPin(context: Context): String {
        return getPrefs(context).getString(KEY_LOCKSCREEN_PIN, "") ?: ""
    }

    fun verifyPassphrase(context: Context, candidate: String): Boolean {
        val storedPlain = getPrefs(context).getString(KEY_PASSPHRASE_PLAIN, "")?.trim() ?: ""
        val storedHash = getPrefs(context).getString(KEY_PASSPHRASE_HASH, "") ?: ""
        if (storedHash.isBlank() && storedPlain.isBlank()) {
            // No passphrase set yet: default allow or require setup
            return false
        }

        val cleanCandidate = cleanPassphrase(candidate)
        val candidateHash = sha256(cleanCandidate)

        // 1. Direct hash match
        if (storedHash.isNotBlank() && candidateHash == storedHash) return true

        // 2. Exact plain match
        if (storedPlain.isNotBlank() && cleanCandidate == storedPlain) return true

        // 3. Normalized digits match
        val normCandidate = normalizeDigits(cleanCandidate)
        val normStored = normalizeDigits(storedPlain)
        if (normStored.isNotBlank() && normCandidate == normStored) return true

        // 4. Fuzzy Levenshtein match (distance <= 2 for voice phonetic tolerance)
        if (normStored.length >= 4 && levenshtein(normCandidate, normStored) <= 2) {
            return true
        }

        return false
    }

    // -------------------------------------------------------------------------
    // Protocol 1: Lock My Phone
    // -------------------------------------------------------------------------
    fun lockPhone(context: Context, isFa: Boolean = false) {
        scope.launch(Dispatchers.Main) {
            val locked = executeNativeScreenLock(context)
            val reply = if (locked) {
                if (isFa) "گوشی قفل شد قربان." else "Screen locked, sir."
            } else {
                if (isFa) "امکان قفل مستقیم صفحه فراهم نشد قربان." else "Unable to lock screen, sir."
            }
            JarvisVoiceService.instance?.speakBritish(reply)
        }
    }

    // -------------------------------------------------------------------------
    // Protocol 2: All Systems Down (Ghost Lockdown)
    // -------------------------------------------------------------------------
    fun executeAllSystemsDown(context: Context, isFa: Boolean = false) {
        currentState = SecurityState.ALL_SYSTEMS_DOWN
        isLockdownActive = true

        scope.launch {
            // 1. Immediately pause media
            try { MediaEngine.handleMediaControl(context, "pause") } catch (_: Exception) {}

            // 2. Mute all audio streams completely
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.let {
                it.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                it.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
                it.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
                it.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0)
                it.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0)
            }

            // 3. Hide Floating HUD completely (Ghost state)
            withContext(Dispatchers.Main) {
                JarvisOverlayManager.instance?.hideOverlay()
            }

            // 4. Lock screen silently
            executeNativeScreenLock(context)

            Log.i(TAG, "Protocol: All Systems Down activated. Ghost Lockdown engaged.")
        }
    }

    // -------------------------------------------------------------------------
    // Protocol 3: Protocol Blackbox (Red Alert)
    // -------------------------------------------------------------------------
    fun executeBlackboxProtocol(context: Context, isFa: Boolean = false) {
        currentState = SecurityState.BLACKBOX
        isLockdownActive = true

        scope.launch {
            // 1. Execute All Systems Down base
            executeAllSystemsDown(context, isFa)

            // 2. Immediately snap silent front + back Camera2 photos
            captureBothSilentPhotosAndUpload(context, reason = "blackbox_initial")

            // 3. Start 5-second rolling audio loop
            startRollingAudioEvidenceLoop(context)

            // 4. Start 15-second location breadcrumb loop
            startLocationBreadcrumbLoop(context)

            Log.i(TAG, "Protocol: Blackbox engaged. Silent evidence vault loop running.")
        }
    }

    // -------------------------------------------------------------------------
    // Protocol 4: Protocol Siren
    // -------------------------------------------------------------------------
    fun executeSirenProtocol(context: Context, isFa: Boolean = false) {
        currentState = SecurityState.SIREN
        isLockdownActive = true

        scope.launch {
            // 1. Lock screen
            executeNativeScreenLock(context)

            // 2. Trigger silent photo & location upload in background
            captureBothSilentPhotosAndUpload(context, reason = "siren_activation")
            uploadCurrentLocationOnce(context)

            // 3. Start piercing dual-tone siren at 100% volume
            startPiercingSiren(context)

            // 4. Strobe rear flashlight
            startFlashlightStrobe(context)

            Log.i(TAG, "Protocol: Siren engaged at maximum volume with strobe torch.")
        }
    }

    // -------------------------------------------------------------------------
    // Protocol 5: Voice Screen Unlock
    // -------------------------------------------------------------------------
    fun requestUnlockPhone(context: Context, isFa: Boolean = false) {
        if (!hasPassphrase(context)) {
            val reply = if (isFa) {
                "ابتدا در تنظیمات امنیتی جارویس، رمز عبور صوتی را مشخص کنید قربان."
            } else {
                "Please configure your secret security passphrase in Jarvis settings first, sir."
            }
            JarvisVoiceService.instance?.speakBritish(reply)
            return
        }

        currentState = SecurityState.AWAITING_PASSPHRASE_FOR_UNLOCK
        val prompt = if (isFa) "اسم رمز را بگویید قربان." else "State your passphrase, sir."
        JarvisVoiceService.instance?.speakBritish(prompt)
    }

    fun handlePassphraseForUnlock(context: Context, candidateUtterance: String, isFa: Boolean = false): Boolean {
        val verified = verifyPassphrase(context, candidateUtterance)
        if (verified) {
            currentState = SecurityState.NORMAL
            isLockdownActive = false
            stopSirenAndTorch(context)

            scope.launch {
                val pin = getLockscreenPin(context)
                wakeAndUnlockScreen(context, pin)
                val reply = if (isFa) "قفل دستگاه باز شد قربان." else "Device unlocked, sir."
                withContext(Dispatchers.Main) {
                    JarvisVoiceService.instance?.speakBritish(reply)
                }
            }
            return true
        } else {
            // Intruder suspected on wrong passphrase
            onIntruderAttemptDetected(context)
            val reply = if (isFa) "کد رمز نامعتبر است." else "Invalid passphrase."
            JarvisVoiceService.instance?.speakBritish(reply)
            return false
        }
    }

    // -------------------------------------------------------------------------
    // Protocol 6: Protocol Stand Down
    // -------------------------------------------------------------------------
    fun requestStandDown(context: Context, isFa: Boolean = false) {
        if (!hasPassphrase(context)) {
            // If no passphrase set, execute directly
            executeStandDownInternal(context, isFa)
            return
        }
        currentState = SecurityState.AWAITING_PASSPHRASE_FOR_STANDDOWN
        val prompt = if (isFa) "برای لغو وضعیت اضطراری، اسم رمز را بگویید." else "State your passphrase to disarm protocols, sir."
        JarvisVoiceService.instance?.speakBritish(prompt)
    }

    fun handlePassphraseForStandDown(context: Context, candidate: String, isFa: Boolean = false): Boolean {
        val verified = verifyPassphrase(context, candidate)
        if (verified) {
            executeStandDownInternal(context, isFa)
            return true
        } else {
            onIntruderAttemptDetected(context)
            val reply = if (isFa) "کد رمز نادرست است؛ پروتکل امنیتی ادامه دارد." else "Invalid passphrase. Security protocols remain engaged."
            JarvisVoiceService.instance?.speakBritish(reply)
            return false
        }
    }

    private fun executeStandDownInternal(context: Context, isFa: Boolean = false) {
        currentState = SecurityState.NORMAL
        isLockdownActive = false

        // 1. Stop audio & location loops
        audioLoopJob?.cancel()
        audioLoopJob = null
        locationLoopJob?.cancel()
        locationLoopJob = null
        ProVoiceEngine.securityAudioListener = null

        // 2. Stop siren and torch
        stopSirenAndTorch(context)

        // 3. Restore audio volumes to normal
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        am?.let {
            val maxMusic = it.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            it.setStreamVolume(AudioManager.STREAM_MUSIC, (maxMusic * 0.7f).toInt(), 0)
        }

        // 4. Spoken confirmation
        val reply = if (isFa) {
            "وضعیت اضطراری لغو شد. تمام سیستم‌ها در حالت آماده‌باش عادی قرار گرفتند قربان."
        } else {
            "All security protocols disengaged, sir. Systems standing by."
        }
        JarvisVoiceService.instance?.speakBritish(reply)
        JarvisOverlayManager.instance?.updateState("standby")
        Log.i(TAG, "Stand Down executed successfully.")
    }

    // -------------------------------------------------------------------------
    // 5-Second Rolling Audio Evidence Vault Loop
    // -------------------------------------------------------------------------

    fun startRollingAudioEvidenceLoop(context: Context) {
        if (audioLoopJob != null && audioLoopJob?.isActive == true) return

        synchronized(audioBufferLock) {
            audio5sBuffer.reset()
        }

        // Tap directly into ProVoiceEngine continuous 16kHz PCM stream
        ProVoiceEngine.securityAudioListener = { buffer, count ->
            synchronized(audioBufferLock) {
                for (i in 0 until count) {
                    val s = buffer[i].toInt()
                    audio5sBuffer.write(s and 0xFF)
                    audio5sBuffer.write((s shr 8) and 0xFF)
                }
            }
        }

        audioLoopJob = scope.launch {
            val outboxDir = File(context.filesDir, "security_outbox").apply { if (!exists()) mkdirs() }
            val sessionTag = "sec_${System.currentTimeMillis()}"

            while (isActive && (currentState == SecurityState.BLACKBOX || currentState == SecurityState.SIREN)) {
                delay(5000L) // 5.0 seconds rolling slice

                val pcmData = synchronized(audioBufferLock) {
                    val bytes = audio5sBuffer.toByteArray()
                    audio5sBuffer.reset()
                    bytes
                }

                if (pcmData.size >= 16000 * 2) { // At least 1 second
                    val wavBytes = pcmToWav(pcmData, 16000)
                    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val filename = "JARVIS_SEC_${timeStamp}_5s.wav"

                    // Attempt immediate upload to VPS
                    val uploaded = uploadEvidenceToVps(context, wavBytes, filename, "audio", sessionTag)
                    if (!uploaded) {
                        // Persist to local disk outbox queue
                        try {
                            val tempFile = File(outboxDir, filename)
                            FileOutputStream(tempFile).use { it.write(wavBytes) }
                            Log.w(TAG, "Network offline: 5s audio queued to local vault: ${tempFile.name}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error caching audio to outbox: ${e.message}")
                        }
                    } else {
                        // Auto-flush local outbox when network is verified alive
                        flushOutboxQueue(context, outboxDir)
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // 15-Second Location Breadcrumb Loop
    // -------------------------------------------------------------------------

    fun startLocationBreadcrumbLoop(context: Context) {
        if (locationLoopJob != null && locationLoopJob?.isActive == true) return

        locationLoopJob = scope.launch {
            while (isActive && (currentState == SecurityState.BLACKBOX || currentState == SecurityState.SIREN)) {
                uploadCurrentLocationOnce(context)
                delay(15000L) // 15 seconds
            }
        }
    }

    fun uploadCurrentLocationOnce(context: Context) {
        scope.launch {
            try {
                val loc = getBestLocation(context)
                if (loc != null) {
                    val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                    val batPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

                    val payload = JSONObject().apply {
                        put("latitude", loc.latitude)
                        put("longitude", loc.longitude)
                        put("accuracy", loc.accuracy.toDouble())
                        put("speed", loc.speed.toDouble())
                        put("battery", batPct)
                        put("device_id", Build.MODEL)
                    }

                    val server = getServerUrl(context)
                    val url = "${server.removeSuffix("/")}/api/security/location"
                    val req = Request.Builder()
                        .url(url)
                        .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()

                    httpClient.newCall(req).execute().use { resp ->
                        Log.i(TAG, "Location breadcrumb sent to VPS: HTTP ${resp.code} (${loc.latitude}, ${loc.longitude})")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed uploading location: ${e.message}")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Intruder Detector Handler
    // -------------------------------------------------------------------------

    fun onIntruderAttemptDetected(context: Context) {
        scope.launch {
            val prefs = getPrefs(context)
            val failed = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
            prefs.edit().putInt(KEY_FAILED_ATTEMPTS, failed).apply()

            Log.w(TAG, "Intruder detector alert #$failed! Capturing silent front photo + location + audio slice.")

            // 1. Silent front camera photo (without waking screen)
            val frontBytes = SilentCameraCapturer.capturePhoto(context, CameraCharacteristics.LENS_FACING_FRONT)
            if (frontBytes != null) {
                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val filename = "JARVIS_INTRUDER_${timeStamp}_front.jpg"
                uploadEvidenceToVps(context, frontBytes, filename, "photo", "intruder_alert")
            }

            // 2. Upload location
            uploadCurrentLocationOnce(context)

            // 3. Upload latest 5s audio buffer
            val pcmData = synchronized(audioBufferLock) {
                audio5sBuffer.toByteArray()
            }
            if (pcmData.size >= 16000 * 2) {
                val wavBytes = pcmToWav(pcmData, 16000)
                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                uploadEvidenceToVps(context, wavBytes, "JARVIS_INTRUDER_${timeStamp}_audio.wav", "audio", "intruder_alert")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Ad-Hoc Silent Evidence Capture
    // -------------------------------------------------------------------------

    fun captureBothSilentPhotosAndUpload(context: Context, reason: String = "manual_request") {
        scope.launch {
            SilentCameraCapturer.captureBoth(
                context,
                onFront = { bytes ->
                    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val fn = "JARVIS_EVID_${timeStamp}_front.jpg"
                    uploadEvidenceToVps(context, bytes, fn, "photo", reason)
                },
                onBack = { bytes ->
                    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val fn = "JARVIS_EVID_${timeStamp}_back.jpg"
                    uploadEvidenceToVps(context, bytes, fn, "photo", reason)
                }
            )
        }
    }

    // -------------------------------------------------------------------------
    // Siren & Flashlight Strobe Implementation
    // -------------------------------------------------------------------------

    private fun startPiercingSiren(context: Context) {
        if (isSirenPlaying.getAndSet(true)) return

        scope.launch {
            try {
                // Max volume on Alarm & Music
                val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                am?.let {
                    val maxAlarm = it.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                    it.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarm, 0)
                    val maxMusic = it.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    it.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0)
                }

                val sampleRate = 24000
                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBuf * 2, 8192))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                sirenAudioTrack = track
                track.play()

                // Procedural high-piercing sweeping dual-tone siren (800Hz to 2400Hz)
                val chunkSamples = sampleRate / 4 // 250ms chunks
                val shortBuf = ShortArray(chunkSamples)
                var phase = 0.0

                sirenJob = launch {
                    while (isSirenPlaying.get()) {
                        for (hz in 800..2400 step 40) {
                            if (!isSirenPlaying.get()) break
                            val radPerSample = 2.0 * Math.PI * hz / sampleRate
                            for (i in 0 until chunkSamples) {
                                shortBuf[i] = (Math.sin(phase) * 32767).toInt().toShort()
                                phase += radPerSample
                            }
                            track.write(shortBuf, 0, chunkSamples)
                        }
                        for (hz in 2400 downTo 800 step 40) {
                            if (!isSirenPlaying.get()) break
                            val radPerSample = 2.0 * Math.PI * hz / sampleRate
                            for (i in 0 until chunkSamples) {
                                shortBuf[i] = (Math.sin(phase) * 32767).toInt().toShort()
                                phase += radPerSample
                            }
                            track.write(shortBuf, 0, chunkSamples)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Siren AudioTrack error: ${e.message}")
            }
        }
    }

    private fun startFlashlightStrobe(context: Context) {
        val cam = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        val backCamId = try {
            cam.cameraIdList.firstOrNull { id ->
                cam.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
        } catch (_: Exception) { null } ?: return

        scope.launch {
            var state = false
            while (isSirenPlaying.get()) {
                state = !state
                try {
                    cam.setTorchMode(backCamId, state)
                } catch (_: Exception) {}
                delay(200L)
            }
            try { cam.setTorchMode(backCamId, false) } catch (_: Exception) {}
        }
    }

    private fun stopSirenAndTorch(context: Context) {
        isSirenPlaying.set(false)
        sirenJob?.cancel()
        sirenJob = null
        try {
            sirenAudioTrack?.stop()
            sirenAudioTrack?.release()
        } catch (_: Exception) {}
        sirenAudioTrack = null

        // Turn off torch
        val cam = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        try {
            val backCamId = cam?.cameraIdList?.firstOrNull { id ->
                cam.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
            if (backCamId != null) {
                cam.setTorchMode(backCamId, false)
            }
        } catch (_: Exception) {}
    }

    // -------------------------------------------------------------------------
    // Screen Lock & Voice Unlock Helpers
    // -------------------------------------------------------------------------

    private fun executeNativeScreenLock(context: Context): Boolean {
        // 1. Primary: JarvisAccessibilityService.GLOBAL_ACTION_LOCK_SCREEN (Android 9+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val access = JarvisAccessibilityService.instance
            if (access != null) {
                val ok = access.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                if (ok) return true
            }
        }

        // 2. Fallback: DevicePolicyManager.lockNow()
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        if (dpm != null && JarvisDeviceAdminReceiver.isAdminActive(context)) {
            try {
                dpm.lockNow()
                return true
            } catch (e: Exception) {
                Log.w(TAG, "DevicePolicyManager.lockNow() failed: ${e.message}")
            }
        }

        return false
    }

    @SuppressLint("WakelockTimeout")
    private suspend fun wakeAndUnlockScreen(context: Context, pin: String) = withContext(Dispatchers.Main) {
        // 1. Wake screen
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wl = pm?.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "jarvis:SecurityWakeLock"
        )
        wl?.acquire(3000L)

        delay(400L)

        // 2. Swipe up and enter PIN via Accessibility
        val access = JarvisAccessibilityService.instance
        if (access != null) {
            access.unlockWithPin(pin)
        }
    }

    // -------------------------------------------------------------------------
    // VPS Network Upload & Outbox Queue
    // -------------------------------------------------------------------------

    private fun uploadEvidenceToVps(
        context: Context,
        fileBytes: ByteArray,
        filename: String,
        type: String,
        sessionTag: String
    ): Boolean {
        return try {
            val server = getServerUrl(context)
            val url = "${server.removeSuffix("/")}/api/security/evidence"

            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val batPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

            val loc = getBestLocation(context)

            val mime = if (type == "photo") "image/jpeg" else "audio/wav"
            val filePart = MultipartBody.Part.createFormData(
                "file",
                filename,
                fileBytes.toRequestBody(mime.toMediaType())
            )

            val builder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addPart(filePart)
                .addFormDataPart("evidence_type", type)
                .addFormDataPart("session_id", sessionTag)
                .addFormDataPart("battery", batPct.toString())

            if (loc != null) {
                builder.addFormDataPart("latitude", loc.latitude.toString())
                builder.addFormDataPart("longitude", loc.longitude.toString())
            }

            val request = Request.Builder()
                .url(url)
                .post(builder.build())
                .build()

            httpClient.newCall(request).execute().use { resp ->
                resp.isSuccessful
            }
        } catch (e: Exception) {
            Log.w(TAG, "uploadEvidenceToVps network notice: ${e.message}")
            false
        }
    }

    private fun flushOutboxQueue(context: Context, outboxDir: File) {
        val files = outboxDir.listFiles() ?: return
        if (files.isEmpty()) return

        for (file in files) {
            try {
                val bytes = file.readBytes()
                val type = if (file.name.endsWith(".jpg")) "photo" else "audio"
                val ok = uploadEvidenceToVps(context, bytes, file.name, type, "outbox_flush")
                if (ok) {
                    file.delete()
                    Log.i(TAG, "Flushed queued evidence file to VPS: ${file.name}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error flushing file ${file.name}: ${e.message}")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private fun getServerUrl(context: Context): String {
        val prefs = context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
        return prefs.getString("server_url", "http://104.252.175.159:8765") ?: "http://104.252.175.159:8765"
    }

    @SuppressLint("MissingPermission")
    private fun getBestLocation(context: Context): Location? {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val gps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        val net = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        return if (gps != null && net != null) {
            if (gps.time > net.time) gps else net
        } else gps ?: net
    }

    private fun cleanPassphrase(input: String): String {
        return input.trim().lowercase(Locale.ROOT)
            .replace(Regex("[,.!?،؟\\s]+"), "")
    }

    private fun normalizeDigits(text: String): String {
        var res = text
        val faDigits = arrayOf("۰", "۱", "۲", "۳", "۴", "۵", "۶", "۷", "۸", "۹")
        val enDigits = arrayOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9")
        for (i in 0..9) {
            res = res.replace(faDigits[i], enDigits[i])
        }
        return res
    }

    private fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun levenshtein(lhs: CharSequence, rhs: CharSequence): Int {
        val lhsLength = lhs.length
        val rhsLength = rhs.length
        var cost = Array(lhsLength + 1) { it }
        var newCost = Array(lhsLength + 1) { 0 }

        for (i in 1..rhsLength) {
            newCost[0] = i
            for (j in 1..lhsLength) {
                val match = if (lhs[j - 1] == rhs[i - 1]) 0 else 1
                val costReplace = cost[j - 1] + match
                val costInsert = cost[j] + 1
                val costDelete = newCost[j - 1] + 1
                newCost[j] = minOf(costInsert, costDelete, costReplace)
            }
            val swap = cost
            cost = newCost
            newCost = swap
        }
        return cost[lhsLength]
    }

    private fun pcmToWav(pcmData: ByteArray, sampleRate: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val totalDataLen = pcmData.size + 36
        val byteRate = (sampleRate * channels * bitsPerSample / 8).toLong()

        val header = ByteArray(44)
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
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * bitsPerSample / 8).toByte()
        header[33] = 0
        header[34] = bitsPerSample.toByte()
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (pcmData.size and 0xff).toByte()
        header[41] = ((pcmData.size shr 8) and 0xff).toByte()
        header[42] = ((pcmData.size shr 16) and 0xff).toByte()
        header[43] = ((pcmData.size shr 24) and 0xff).toByte()

        val out = ByteArrayOutputStream(44 + pcmData.size)
        out.write(header)
        out.write(pcmData)
        return out.toByteArray()
    }
}
