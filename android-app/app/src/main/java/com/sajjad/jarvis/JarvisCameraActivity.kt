package com.sajjad.jarvis

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.media.AudioManager
import android.media.MediaScannerConnection
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.ViewGroup
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * JarvisCameraActivity: Native CameraX Pro HUD Activity.
 *
 * Guaranteed real gallery photo/video capture and full camera controls:
 * - Direct MediaStore saving (photos & videos immediately visible in Gallery / Google Photos)
 * - Timer (0s, 3s, 5s, 10s) with sci-fi audio-visual countdown
 * - Zoom (1x, 2x, 5x, 8x)
 * - Flash (Off, Auto, On, Torch)
 * - Lens flip (Back / Front)
 * - Photo / Video mode toggle
 * - Sleek Iron Man Neon Cyan HUD viewfinder overlay
 */
class JarvisCameraActivity : ComponentActivity() {

    companion object {
        private const val TAG = "JarvisCameraActivity"
        const val EXTRA_CAMERA_ACTION = "extra_camera_action"
        var instance: JarvisCameraActivity? = null
            private set
    }

    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private lateinit var cameraExecutor: ExecutorService

    // Reactive Compose State
    private val lensFacingState = mutableStateOf(CameraSelector.LENS_FACING_BACK)
    private val flashModeState = mutableStateOf(ImageCapture.FLASH_MODE_OFF)
    private val isTorchEnabledState = mutableStateOf(false)
    private val timerSecondsState = mutableStateOf(0)
    private val zoomRatioState = mutableFloatStateOf(1.0f)
    private val isVideoModeState = mutableStateOf(false)
    private val isRecordingState = mutableStateOf(false)
    private val recordingDurationSec = mutableIntStateOf(0)
    private val countdownNumberState = mutableIntStateOf(0)
    private val showShutterFlash = mutableStateOf(false)
    private val activeFilterState = mutableStateOf("normal")

    private var previewView: PreviewView? = null
    @Volatile private var pendingAction: JSONObject? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        cameraExecutor = Executors.newSingleThreadExecutor()

        checkCameraPermissions()

        setContent {
            CameraScreenContent()
        }

        handleIncomingAction(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingAction(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
        try {
            activeRecording?.stop()
            activeRecording = null
        } catch (_: Exception) {}
        cameraExecutor.shutdown()
    }

    private fun checkCameraPermissions() {
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 101)
        }
    }

    fun applyCameraControl(act: JSONObject) {
        val mode = act.optString("mode", "").lowercase(Locale.ROOT)
        val lens = act.optString("lens", "").lowercase(Locale.ROOT)
        val timer = act.optInt("timer_seconds", -1)
        val zoom = act.optDouble("zoom", act.optDouble("target_zoom", act.optDouble("targetZoom", -1.0)))
        val zoomStr = act.optString("zoom", act.optString("target_zoom", act.optString("targetZoom", ""))).lowercase(Locale.ROOT)
        val flash = act.optString("flash", "").lowercase(Locale.ROOT)
        val filter = act.optString("effect", act.optString("filter", "")).lowercase(Locale.ROOT)
        val capture = act.optBoolean("capture", false)

        lifecycleScope.launch {
            if (filter.isNotBlank()) {
                activeFilterState.value = filter
            }
            if (mode == "video") {
                isVideoModeState.value = true
            } else if (mode == "photo") {
                isVideoModeState.value = false
            } else if (mode == "stop_video") {
                stopVideoRecording()
                return@launch
            } else if (mode == "close") {
                finish()
                return@launch
            }

            if (lens == "front") {
                lensFacingState.value = CameraSelector.LENS_FACING_FRONT
                bindCameraUseCases()
            } else if (lens == "back") {
                lensFacingState.value = CameraSelector.LENS_FACING_BACK
                bindCameraUseCases()
            }

            if (timer >= 0) {
                timerSecondsState.value = timer
            }

            var zoomAdjusted = false
            if (zoom > 0) {
                setZoomRatio(zoom.toFloat())
                zoomAdjusted = true
            } else if (zoomStr == "zoom_in") {
                setZoomRatio((zoomRatioState.floatValue * 1.5f).coerceAtMost(8.0f))
                zoomAdjusted = true
            } else if (zoomStr == "zoom_out") {
                setZoomRatio((zoomRatioState.floatValue / 1.5f).coerceAtLeast(1.0f))
                zoomAdjusted = true
            } else if (zoomStr == "reset") {
                setZoomRatio(1.0f)
            } else {
                val parsed = zoomStr.replace("x", "").trim().toFloatOrNull()
                if (parsed != null && parsed > 0f) {
                    setZoomRatio(parsed)
                    zoomAdjusted = true
                }
            }

            when (flash) {
                "on" -> {
                    flashModeState.value = ImageCapture.FLASH_MODE_ON
                    imageCapture?.flashMode = ImageCapture.FLASH_MODE_ON
                    isTorchEnabledState.value = false
                    camera?.cameraControl?.enableTorch(false)
                }
                "off" -> {
                    flashModeState.value = ImageCapture.FLASH_MODE_OFF
                    imageCapture?.flashMode = ImageCapture.FLASH_MODE_OFF
                    isTorchEnabledState.value = false
                    camera?.cameraControl?.enableTorch(false)
                }
                "auto" -> {
                    flashModeState.value = ImageCapture.FLASH_MODE_AUTO
                    imageCapture?.flashMode = ImageCapture.FLASH_MODE_AUTO
                    isTorchEnabledState.value = false
                    camera?.cameraControl?.enableTorch(false)
                }
                "torch" -> {
                    isTorchEnabledState.value = true
                    camera?.cameraControl?.enableTorch(true)
                }
            }

            if (capture) {
                // If zoom was adjusted, wait 400ms for optical/digital zoom transition to settle!
                if (zoomAdjusted) {
                    delay(400)
                }
                triggerCapture()
            }
        }
    }

    private fun handleIncomingAction(intent: Intent?) {
        val actionJson = intent?.getStringExtra(EXTRA_CAMERA_ACTION)
        val directZoom = intent?.getFloatExtra("target_zoom", intent.getFloatExtra("targetZoom", 0f)) ?: 0f
        if (directZoom > 0f) {
            setZoomRatio(directZoom)
        }
        if (!actionJson.isNullOrBlank()) {
            try {
                val act = JSONObject(actionJson)
                if (directZoom > 0f && !act.has("zoom")) {
                    act.put("zoom", directZoom.toDouble())
                }
                if (cameraProvider == null || (imageCapture == null && videoCapture == null)) {
                    Log.i(TAG, "Camera not bound yet. Storing pending command: $act")
                    pendingAction = act
                } else {
                    applyCameraControl(act)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing incoming camera action: ${e.message}")
            }
        }
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        val previewV = previewView ?: return

        provider.unbindAll()

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacingState.value)
            .build()

        preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewV.surfaceProvider)
        }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(flashModeState.value)
            .build()

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD))
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        try {
            camera = provider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageCapture,
                videoCapture
            )
        } catch (exc: Exception) {
            Log.w(TAG, "Simultaneous 3-usecase binding unsupported, falling back to 2 use cases: ${exc.message}")
            provider.unbindAll()
            try {
                camera = if (isVideoModeState.value) {
                    provider.bindToLifecycle(this, cameraSelector, preview, videoCapture)
                } else {
                    provider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Camera fallback binding failed: ${e2.message}", e2)
            }
        }

        camera?.cameraControl?.setZoomRatio(zoomRatioState.floatValue)
        if (isTorchEnabledState.value) {
            camera?.cameraControl?.enableTorch(true)
        }

        // Check and execute pending action once camera hardware is warm and active
        val pending = pendingAction
        if (pending != null) {
            pendingAction = null
            lifecycleScope.launch {
                delay(400)
                applyCameraControl(pending)
            }
        }
    }

    private fun setZoomRatio(ratio: Float) {
        val zoomState = camera?.cameraInfo?.zoomState?.value
        val minRatio = zoomState?.minZoomRatio ?: 1.0f
        val maxRatio = zoomState?.maxZoomRatio ?: 8.0f
        val clamped = ratio.coerceIn(minRatio, maxRatio)
        zoomRatioState.floatValue = clamped
        camera?.cameraControl?.setZoomRatio(clamped)
    }

    fun triggerCapture() {
        val timer = timerSecondsState.value
        if (timer > 0) {
            lifecycleScope.launch {
                for (s in timer downTo 1) {
                    countdownNumberState.intValue = s
                    playToneBeep(s == 1)
                    delay(1000)
                }
                countdownNumberState.intValue = 0
                executeShutterAction()
            }
        } else {
            executeShutterAction()
        }
    }

    private fun executeShutterAction() {
        if (isVideoModeState.value) {
            if (isRecordingState.value) {
                stopVideoRecording()
            } else {
                startVideoRecording()
            }
        } else {
            takePhotoToGallery()
        }
    }

    private fun applyColorMatrixFilter(src: Bitmap, filterName: String): Bitmap {
        val cm = ColorMatrix()
        when (filterName.lowercase(Locale.ROOT)) {
            "bw", "grayscale", "black_white", "black_and_white", "mono" -> {
                cm.setSaturation(0f)
            }
            "sepia" -> {
                cm.setSaturation(0f)
                val sepia = ColorMatrix(floatArrayOf(
                    0.393f, 0.769f, 0.189f, 0f, 0f,
                    0.349f, 0.686f, 0.168f, 0f, 0f,
                    0.272f, 0.534f, 0.131f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                ))
                cm.postConcat(sepia)
            }
            "vivid" -> {
                cm.setSaturation(1.6f)
            }
            "cool" -> {
                val cool = ColorMatrix(floatArrayOf(
                    0.9f, 0f, 0f, 0f, 0f,
                    0f, 0.95f, 0f, 0f, 0f,
                    0f, 0f, 1.25f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                ))
                cm.postConcat(cool)
            }
            "warm" -> {
                val warm = ColorMatrix(floatArrayOf(
                    1.25f, 0f, 0f, 0f, 0f,
                    0f, 1.05f, 0f, 0f, 0f,
                    0f, 0f, 0.85f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                ))
                cm.postConcat(warm)
            }
            "vintage" -> {
                cm.setSaturation(0.7f)
                val vintage = ColorMatrix(floatArrayOf(
                    1.1f, 0f, 0f, 0f, 20f,
                    0f, 1.0f, 0f, 0f, 10f,
                    0f, 0f, 0.8f, 0f, -10f,
                    0f, 0f, 0f, 1f, 0f
                ))
                cm.postConcat(vintage)
            }
            else -> return src
        }
        val result = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(result)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(cm)
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return result
    }

    private fun takePhotoToGallery() {
        val capture = imageCapture
        if (capture == null) {
            Log.e(TAG, "Photo capture aborted: imageCapture is null")
            Toast.makeText(this, "Camera not ready yet", Toast.LENGTH_SHORT).show()
            JarvisVoiceService.speakBritishDirect(this, "Camera is not ready yet, sir.")
            return
        }

        // Sci-Fi flash animation & sound
        showShutterFlash.value = true
        lifecycleScope.launch {
            delay(120)
            showShutterFlash.value = false
        }
        playShutterChime()

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "JARVIS_$timestamp"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$filename.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Jarvis")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        capture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val savedUri = outputFileResults.savedUri
                    Log.i(TAG, "Photo saved to Gallery: $savedUri")
                    val currentFilter = activeFilterState.value
                    if (savedUri != null && currentFilter.isNotBlank() && currentFilter != "normal" && currentFilter != "none") {
                        try {
                            val bitmap = contentResolver.openInputStream(savedUri)?.use {
                                BitmapFactory.decodeStream(it)
                            }
                            if (bitmap != null) {
                                val filtered = applyColorMatrixFilter(bitmap, currentFilter)
                                contentResolver.openOutputStream(savedUri, "wt")?.use { out ->
                                    filtered.compress(Bitmap.CompressFormat.JPEG, 92, out)
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed applying filter $currentFilter: ${e.message}")
                        }
                    }

                    val fileSize = savedUri?.let { uri ->
                        try {
                            contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
                        } catch (_: Exception) { 0L }
                    } ?: 0L

                    if (savedUri != null) {
                        MediaScannerConnection.scanFile(this@JarvisCameraActivity, arrayOf(savedUri.path ?: ""), arrayOf("image/jpeg"), null)
                    }

                    lifecycleScope.launch {
                        val msg = if (fileSize > 0) "Photo saved to Gallery ($currentFilter)" else "Photo captured"
                        Toast.makeText(this@JarvisCameraActivity, msg, Toast.LENGTH_SHORT).show()
                        JarvisVoiceService.speakBritishDirect(
                            this@JarvisCameraActivity,
                            "Photo captured and saved to gallery, sir."
                        )
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                    lifecycleScope.launch {
                        Toast.makeText(this@JarvisCameraActivity, "Photo error: ${exception.message}", Toast.LENGTH_LONG).show()
                        JarvisVoiceService.speakBritishDirect(
                            this@JarvisCameraActivity,
                            "Photo capture failed: ${exception.message}"
                        )
                    }
                }
            }
        )
    }

    private fun startVideoRecording() {
        val videoCap = videoCapture
        if (videoCap == null) {
            Log.e(TAG, "Video recording aborted: videoCapture is null")
            Toast.makeText(this, "Video recording not ready yet", Toast.LENGTH_SHORT).show()
            JarvisVoiceService.speakBritishDirect(this, "Video recording is not ready yet, sir.")
            return
        }
        if (isRecordingState.value) return

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "JARVIS_VID_$timestamp"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$filename.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Jarvis")
            }
        }

        val mediaStoreOutput = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        try {
            var recordingBuilder = videoCap.output.prepareRecording(this, mediaStoreOutput)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                recordingBuilder = recordingBuilder.withAudioEnabled()
            }

            activeRecording = recordingBuilder.start(cameraExecutor) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        isRecordingState.value = true
                        startRecordingTimer()
                    }
                    is VideoRecordEvent.Finalize -> {
                        isRecordingState.value = false
                        if (!recordEvent.hasError()) {
                            val uri = recordEvent.outputResults.outputUri
                            Log.i(TAG, "Video saved to Gallery: $uri")
                            MediaScannerConnection.scanFile(this@JarvisCameraActivity, arrayOf(uri.path ?: ""), arrayOf("video/mp4"), null)
                            lifecycleScope.launch {
                                Toast.makeText(this@JarvisCameraActivity, "Video saved to Gallery", Toast.LENGTH_SHORT).show()
                                JarvisVoiceService.speakBritishDirect(
                                    this@JarvisCameraActivity,
                                    "Video saved to gallery, sir."
                                )
                            }
                        } else {
                            Log.e(TAG, "Video recording error: ${recordEvent.error}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "startVideoRecording error: ${e.message}")
        }
    }

    fun stopVideoRecording() {
        try {
            activeRecording?.stop()
            activeRecording = null
            isRecordingState.value = false
        } catch (e: Exception) {
            Log.w(TAG, "stopVideoRecording error: ${e.message}")
        }
    }

    private fun startRecordingTimer() {
        recordingDurationSec.intValue = 0
        lifecycleScope.launch {
            while (isRecordingState.value) {
                delay(1000)
                recordingDurationSec.intValue++
            }
        }
    }

    private fun playToneBeep(isFinal: Boolean) {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            val tone = if (isFinal) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP
            tg.startTone(tone, 150)
            lifecycleScope.launch {
                delay(200)
                tg.release()
            }
        } catch (_: Exception) {}
    }

    private fun playShutterChime() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
            tg.startTone(ToneGenerator.TONE_PROP_PROMPT, 100)
            lifecycleScope.launch {
                delay(150)
                tg.release()
            }
        } catch (_: Exception) {}
    }

    // -------------------------------------------------------------------------
    // Jetpack Compose Sci-Fi HUD Viewfinder
    // -------------------------------------------------------------------------

    @Composable
    private fun CameraScreenContent() {
        val cyanNeon = Color(0xFF00E5FF)
        val darkBg = Color(0xFF0A0F1D)

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            // Camera Preview Layer
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        previewView = this
                    }.also {
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            cameraProvider = cameraProviderFuture.get()
                            bindCameraUseCases()
                        }, ContextCompat.getMainExecutor(ctx))
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Iron Man Crosshair & HUD Reticle Canvas
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2
                val cy = size.height / 2
                val reticleRadius = 48.dp.toPx()

                // Center Reticle
                drawCircle(
                    color = cyanNeon.copy(alpha = 0.4f),
                    radius = reticleRadius,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.5.dp.toPx())
                )
                // Crosshairs
                drawLine(
                    color = cyanNeon.copy(alpha = 0.5f),
                    start = Offset(cx - 24.dp.toPx(), cy),
                    end = Offset(cx + 24.dp.toPx(), cy),
                    strokeWidth = 1.5.dp.toPx()
                )
                drawLine(
                    color = cyanNeon.copy(alpha = 0.5f),
                    start = Offset(cx, cy - 24.dp.toPx()),
                    end = Offset(cx, cy + 24.dp.toPx()),
                    strokeWidth = 1.5.dp.toPx()
                )

                // Corner Brackets
                val bLen = 28.dp.toPx()
                val bMargin = 32.dp.toPx()
                val strokeW = 2.dp.toPx()
                val bColor = cyanNeon.copy(alpha = 0.45f)

                // Top-Left
                drawLine(bColor, Offset(bMargin, bMargin), Offset(bMargin + bLen, bMargin), strokeW)
                drawLine(bColor, Offset(bMargin, bMargin), Offset(bMargin, bMargin + bLen), strokeW)

                // Top-Right
                drawLine(bColor, Offset(size.width - bMargin, bMargin), Offset(size.width - bMargin - bLen, bMargin), strokeW)
                drawLine(bColor, Offset(size.width - bMargin, bMargin), Offset(size.width - bMargin, bMargin + bLen), strokeW)

                // Bottom-Left
                val bBottom = size.height - 180.dp.toPx()
                drawLine(bColor, Offset(bMargin, bBottom), Offset(bMargin + bLen, bBottom), strokeW)
                drawLine(bColor, Offset(bMargin, bBottom), Offset(bMargin, bBottom - bLen), strokeW)

                // Bottom-Right
                drawLine(bColor, Offset(size.width - bMargin, bBottom), Offset(size.width - bMargin - bLen, bBottom), strokeW)
                drawLine(bColor, Offset(size.width - bMargin, bBottom), Offset(size.width - bMargin, bBottom - bLen), strokeW)
            }

            // Top Bar Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { finish() },
                    modifier = Modifier.size(36.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Flash Mode Toggle Button
                    HudPillButton(
                        text = when (flashModeState.value) {
                            ImageCapture.FLASH_MODE_ON -> "⚡ ON"
                            ImageCapture.FLASH_MODE_AUTO -> "⚡ AUTO"
                            else -> if (isTorchEnabledState.value) "🔦 TORCH" else "⚡ OFF"
                        },
                        isSelected = flashModeState.value != ImageCapture.FLASH_MODE_OFF || isTorchEnabledState.value
                    ) {
                        cycleFlashMode()
                    }

                    // Timer Button
                    HudPillButton(
                        text = if (timerSecondsState.value == 0) "⏱ OFF" else "⏱ ${timerSecondsState.value}s",
                        isSelected = timerSecondsState.value > 0
                    ) {
                        cycleTimer()
                    }

                    // Flip Camera Lens
                    IconButton(
                        onClick = { flipCameraLens() },
                        modifier = Modifier.size(36.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(imageVector = Icons.Default.Cameraswitch, contentDescription = "Flip Lens", tint = cyanNeon)
                    }
                }
            }

            // Pulsing Countdown Overlay
            if (countdownNumberState.intValue > 0) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${countdownNumberState.intValue}",
                        color = cyanNeon,
                        fontSize = 110.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Video Recording Timer Overlay
            if (isRecordingState.value) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 80.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                            .border(1.dp, Color.Red, RoundedCornerShape(16.dp))
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(Color.Red, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        val sec = recordingDurationSec.intValue % 60
                        val min = recordingDurationSec.intValue / 60
                        Text(
                            text = "REC %02d:%02d".format(min, sec),
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            // Bottom Control Center
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(bottom = 28.dp, top = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Zoom Pills (1x, 2x, 5x, 8x)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(bottom = 14.dp)
                ) {
                    listOf(1.0f, 2.0f, 5.0f, 8.0f).forEach { z ->
                        val isSelected = Math.abs(zoomRatioState.floatValue - z) < 0.2f
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (isSelected) cyanNeon.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.5f))
                                .border(1.dp, if (isSelected) cyanNeon else Color.White.copy(alpha = 0.3f), CircleShape)
                                .clickable { setZoomRatio(z) }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "${z.toInt()}x",
                                color = if (isSelected) cyanNeon else Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                // Mode Selector Tabs (PHOTO / VIDEO)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Text(
                        text = "PHOTO",
                        color = if (!isVideoModeState.value) cyanNeon else Color.Gray,
                        fontSize = 14.sp,
                        fontWeight = if (!isVideoModeState.value) FontWeight.Bold else FontWeight.Normal,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clickable {
                            if (isRecordingState.value) stopVideoRecording()
                            isVideoModeState.value = false
                        }
                    )
                    Text(
                        text = "VIDEO",
                        color = if (isVideoModeState.value) cyanNeon else Color.Gray,
                        fontSize = 14.sp,
                        fontWeight = if (isVideoModeState.value) FontWeight.Bold else FontWeight.Normal,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clickable {
                            isVideoModeState.value = true
                        }
                    )
                }

                // Shutter Button
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .border(3.dp, cyanNeon, CircleShape)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(
                            if (isVideoModeState.value) {
                                if (isRecordingState.value) Color.Red else Color.Red.copy(alpha = 0.85f)
                            } else {
                                cyanNeon
                            }
                        )
                        .clickable { triggerCapture() },
                    contentAlignment = Alignment.Center
                ) {
                    if (isRecordingState.value) {
                        // Stop square icon
                        Box(modifier = Modifier.size(24.dp).background(Color.White, RoundedCornerShape(4.dp)))
                    }
                }
            }

            // White Screen Flash Animation on Shutter
            AnimatedVisibility(
                visible = showShutterFlash.value,
                enter = fadeIn(animationSpec = tween(40)),
                exit = fadeOut(animationSpec = tween(100))
            ) {
                Box(modifier = Modifier.fillMaxSize().background(Color.White))
            }
        }
    }

    private fun cycleFlashMode() {
        when {
            flashModeState.value == ImageCapture.FLASH_MODE_OFF && !isTorchEnabledState.value -> {
                flashModeState.value = ImageCapture.FLASH_MODE_AUTO
                imageCapture?.flashMode = ImageCapture.FLASH_MODE_AUTO
            }
            flashModeState.value == ImageCapture.FLASH_MODE_AUTO -> {
                flashModeState.value = ImageCapture.FLASH_MODE_ON
                imageCapture?.flashMode = ImageCapture.FLASH_MODE_ON
            }
            flashModeState.value == ImageCapture.FLASH_MODE_ON -> {
                flashModeState.value = ImageCapture.FLASH_MODE_OFF
                imageCapture?.flashMode = ImageCapture.FLASH_MODE_OFF
                isTorchEnabledState.value = true
                camera?.cameraControl?.enableTorch(true)
            }
            else -> {
                flashModeState.value = ImageCapture.FLASH_MODE_OFF
                imageCapture?.flashMode = ImageCapture.FLASH_MODE_OFF
                isTorchEnabledState.value = false
                camera?.cameraControl?.enableTorch(false)
            }
        }
    }

    private fun cycleTimer() {
        timerSecondsState.value = when (timerSecondsState.value) {
            0 -> 3
            3 -> 5
            5 -> 10
            else -> 0
        }
    }

    private fun flipCameraLens() {
        lensFacingState.value = if (lensFacingState.value == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        bindCameraUseCases()
    }
}

@Composable
private fun HudPillButton(text: String, isSelected: Boolean, onClick: () -> Unit) {
    val cyanNeon = Color(0xFF00E5FF)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) cyanNeon.copy(alpha = 0.25f) else Color.Black.copy(alpha = 0.5f))
            .border(1.dp, if (isSelected) cyanNeon else Color.White.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            color = if (isSelected) cyanNeon else Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace
        )
    }
}
