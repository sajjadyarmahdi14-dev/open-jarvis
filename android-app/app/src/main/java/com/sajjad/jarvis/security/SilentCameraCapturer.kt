package com.sajjad.jarvis.security

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Background-first Silent Camera2 Photo Capturer.
 * Snaps silent front and rear photos in background (screen off or on) with ZERO Activity launches,
 * ZERO UI popups, and ZERO camera shutter sounds (where OS allows).
 */
object SilentCameraCapturer {

    private const val TAG = "SilentCameraCapturer"

    /**
     * Capture a single photo from specified lens facing (FRONT or BACK).
     */
    @SuppressLint("MissingPermission")
    suspend fun capturePhoto(
        context: Context,
        lensFacing: Int = CameraCharacteristics.LENS_FACING_FRONT
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Cannot take silent photo: CAMERA permission missing.")
            return@withContext null
        }

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return@withContext null

        val targetCameraId = try {
            cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == lensFacing
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed finding camera id for lens $lensFacing: ${e.message}")
            null
        } ?: return@withContext null

        val handlerThread = HandlerThread("SilentCameraThread_${System.currentTimeMillis()}").apply { start() }
        val handler = Handler(handlerThread.looper)

        val imageReader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 2)

        try {
            suspendCancellableCoroutine<ByteArray?> { cont ->
                val isResumed = AtomicBoolean(false)
                var cameraDevice: CameraDevice? = null
                var captureSession: CameraCaptureSession? = null

                fun cleanup() {
                    try { captureSession?.close() } catch (_: Exception) {}
                    try { cameraDevice?.close() } catch (_: Exception) {}
                    try { imageReader.close() } catch (_: Exception) {}
                    try { handlerThread.quitSafely() } catch (_: Exception) {}
                }

                cont.invokeOnCancellation {
                    cleanup()
                }

                imageReader.setOnImageAvailableListener({ reader ->
                    try {
                        val image = reader.acquireLatestImage()
                        if (image != null) {
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            image.close()
                            if (isResumed.compareAndSet(false, true)) {
                                cleanup()
                                cont.resume(bytes)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading image bytes: ${e.message}")
                        if (isResumed.compareAndSet(false, true)) {
                            cleanup()
                            cont.resume(null)
                        }
                    }
                }, handler)

                try {
                    cameraManager.openCamera(targetCameraId, object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            cameraDevice = camera
                            try {
                                val surface = imageReader.surface
                                val surfaces = listOf(surface)

                                @Suppress("DEPRECATION")
                                camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(session: CameraCaptureSession) {
                                        captureSession = session
                                        try {
                                            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                                addTarget(surface)
                                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                            }
                                            session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                                                override fun onCaptureCompleted(
                                                    session: CameraCaptureSession,
                                                    request: CaptureRequest,
                                                    result: TotalCaptureResult
                                                ) {
                                                    Log.i(TAG, "Silent capture completed for camera $targetCameraId")
                                                }
                                            }, handler)
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Failed initiating capture request: ${e.message}")
                                            if (isResumed.compareAndSet(false, true)) {
                                                cleanup()
                                                cont.resume(null)
                                            }
                                        }
                                    }

                                    override fun onConfigureFailed(session: CameraCaptureSession) {
                                        Log.w(TAG, "CameraCaptureSession onConfigureFailed")
                                        if (isResumed.compareAndSet(false, true)) {
                                            cleanup()
                                            cont.resume(null)
                                        }
                                    }
                                }, handler)
                            } catch (e: Exception) {
                                Log.e(TAG, "createCaptureSession failed: ${e.message}")
                                if (isResumed.compareAndSet(false, true)) {
                                    cleanup()
                                    cont.resume(null)
                                }
                            }
                        }

                        override fun onDisconnected(camera: CameraDevice) {
                            camera.close()
                            if (isResumed.compareAndSet(false, true)) {
                                cleanup()
                                cont.resume(null)
                            }
                        }

                        override fun onError(camera: CameraDevice, error: Int) {
                            camera.close()
                            Log.w(TAG, "openCamera onError: $error")
                            if (isResumed.compareAndSet(false, true)) {
                                cleanup()
                                cont.resume(null)
                            }
                        }
                    }, handler)
                } catch (e: Exception) {
                    Log.e(TAG, "openCamera exception: ${e.message}")
                    if (isResumed.compareAndSet(false, true)) {
                        cleanup()
                        cont.resume(null)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Silent capture exception: ${e.message}", e)
            null
        }
    }

    /**
     * Snaps photos from both Front and Back cameras sequentially in background.
     */
    suspend fun captureBoth(
        context: Context,
        onFront: ((ByteArray) -> Unit)? = null,
        onBack: ((ByteArray) -> Unit)? = null
    ) {
        val frontBytes = capturePhoto(context, CameraCharacteristics.LENS_FACING_FRONT)
        if (frontBytes != null) {
            onFront?.invoke(frontBytes)
        }
        val backBytes = capturePhoto(context, CameraCharacteristics.LENS_FACING_BACK)
        if (backBytes != null) {
            onBack?.invoke(backBytes)
        }
    }
}
