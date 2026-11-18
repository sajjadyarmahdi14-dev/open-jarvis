package com.sajjad.jarvis

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Part B: Live Vision Engine
 *
 * Implements silent background camera frame capture (Camera2) and multimodal vision routing
 * to the backend without launching the OEM camera UI or saving photos to the gallery.
 */
object VisionEngine {

    private const val TAG = "VisionEngine"
    private const val MAX_VISION_DIMENSION = 1024
    private const val JPEG_QUALITY = 75

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    /**
     * Process a software bitmap: resize to max 1024px dimension, compress to JPEG 75%, and encode to Base64.
     */
    fun processAndEncodeBitmap(bitmap: Bitmap): String {
        val width = bitmap.width
        val height = bitmap.height
        val maxDim = Math.max(width, height)

        val scaledBitmap = if (maxDim > MAX_VISION_DIMENSION) {
            val scale = MAX_VISION_DIMENSION.toFloat() / maxDim
            val targetW = Math.round(width * scale)
            val targetH = Math.round(height * scale)
            Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        } else {
            bitmap
        }

        val outputStream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
        val byteArray = outputStream.toByteArray()

        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }

        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    /**
     * Silently captures a single camera frame from the back (or front) camera in the background
     * using Camera2, without showing any camera UI or saving any image to the user's gallery.
     */
    @SuppressLint("MissingPermission")
    suspend fun captureCameraFrameBase64(context: Context, useFrontCamera: Boolean = false): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "CAMERA permission is not granted for VisionEngine")
            return null
        }

        return withTimeoutOrNull(4500L) {
            val deferred = CompletableDeferred<String?>()
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            if (cameraManager == null) {
                return@withTimeoutOrNull null
            }

            val handlerThread = HandlerThread("JarvisVisionCapture").apply { start() }
            val handler = Handler(handlerThread.looper)

            var cameraDevice: CameraDevice? = null
            var captureSession: CameraCaptureSession? = null
            var imageReader: ImageReader? = null

            fun cleanup() {
                try { captureSession?.close() } catch (_: Exception) {}
                try { cameraDevice?.close() } catch (_: Exception) {}
                try { imageReader?.close() } catch (_: Exception) {}
                try { handlerThread.quitSafely() } catch (_: Exception) {}
            }

            try {
                val targetFacing = if (useFrontCamera) {
                    CameraCharacteristics.LENS_FACING_FRONT
                } else {
                    CameraCharacteristics.LENS_FACING_BACK
                }

                val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                    try {
                        val chars = cameraManager.getCameraCharacteristics(id)
                        chars.get(CameraCharacteristics.LENS_FACING) == targetFacing
                    } catch (_: Exception) {
                        false
                    }
                } ?: cameraManager.cameraIdList.firstOrNull()

                if (cameraId == null) {
                    Log.w(TAG, "No suitable camera ID found for vision capture")
                    cleanup()
                    return@withTimeoutOrNull null
                }

                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

                val reader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 2)
                imageReader = reader

                reader.setOnImageAvailableListener({ ir ->
                    val image = try { ir.acquireLatestImage() } catch (_: Exception) { null }
                    if (image != null) {
                        try {
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)

                            val decodedBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (decodedBitmap != null) {
                                // Rotate if sensor orientation requires
                                val orientedBitmap = if (sensorOrientation != 0) {
                                    val matrix = Matrix().apply { postRotate(sensorOrientation.toFloat()) }
                                    Bitmap.createBitmap(decodedBitmap, 0, 0, decodedBitmap.width, decodedBitmap.height, matrix, true)
                                } else {
                                    decodedBitmap
                                }

                                val base64Result = processAndEncodeBitmap(orientedBitmap)
                                if (orientedBitmap != decodedBitmap) {
                                    orientedBitmap.recycle()
                                }
                                decodedBitmap.recycle()
                                deferred.complete(base64Result)
                            } else {
                                deferred.complete(null)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Image decoding error in VisionEngine: ${e.message}")
                            deferred.complete(null)
                        } finally {
                            image.close()
                            cleanup()
                        }
                    }
                }, handler)

                cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        try {
                            camera.createCaptureSession(
                                listOf(reader.surface),
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(session: CameraCaptureSession) {
                                        captureSession = session
                                        try {
                                            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                                addTarget(reader.surface)
                                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                            }
                                            session.capture(captureBuilder.build(), null, handler)
                                        } catch (e: Exception) {
                                            Log.w(TAG, "Session capture error: ${e.message}")
                                            deferred.complete(null)
                                            cleanup()
                                        }
                                    }

                                    override fun onConfigureFailed(session: CameraCaptureSession) {
                                        Log.w(TAG, "CameraCaptureSession onConfigureFailed")
                                        deferred.complete(null)
                                        cleanup()
                                    }
                                },
                                handler
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "createCaptureSession error: ${e.message}")
                            deferred.complete(null)
                            cleanup()
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        deferred.complete(null)
                        cleanup()
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        Log.w(TAG, "CameraDevice onError: $error")
                        camera.close()
                        deferred.complete(null)
                        cleanup()
                    }
                }, handler)

            } catch (e: Exception) {
                Log.e(TAG, "captureCameraFrameBase64 exception: ${e.message}", e)
                cleanup()
                deferred.complete(null)
            }

            deferred.await()
        }
    }

    /**
     * Sends the captured frame and question to the backend multimodal vision router (POST /api/vision).
     */
    suspend fun analyzeVision(
        context: Context,
        serverUrl: String,
        imageBase64: String,
        prompt: String,
        mode: String = "camera"
    ): String? = withContext(Dispatchers.IO) {
        try {
            val url = "${serverUrl.removeSuffix("/")}/api/vision"
            val bodyJson = JSONObject().apply {
                put("image_base64", imageBase64)
                put("prompt", prompt)
                put("mode", mode)
            }
            val reqBody = bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val prefs = context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
            val apiKey = prefs.getString("api_key", "") ?: ""

            val reqBuilder = Request.Builder()
                .url(url)
                .post(reqBody)
                .addHeader("Accept", "application/json")

            if (apiKey.isNotBlank()) {
                reqBuilder.addHeader("Authorization", "Bearer $apiKey")
            }

            val response = httpClient.newCall(reqBuilder.build()).execute()
            if (response.isSuccessful) {
                val respBody = response.body?.string() ?: ""
                val json = JSONObject(respBody)
                json.optString("response", "")
            } else {
                Log.w(TAG, "analyzeVision failed with code: ${response.code}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "analyzeVision error: ${e.message}", e)
            null
        }
    }
}
