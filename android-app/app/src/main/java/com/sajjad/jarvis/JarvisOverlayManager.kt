package com.sajjad.jarvis

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Part A: Floating Sci-Fi HUD Overlay
 *
 * A non-intrusive, floating dark-cyan pill HUD using Android WindowManager (TYPE_APPLICATION_OVERLAY)
 * that appears just below the status bar over any app without intercepting touches from background apps.
 */
class JarvisOverlayManager(private val context: Context) {

    companion object {
        private const val TAG = "JarvisOverlayManager"

        @Volatile
        var instance: JarvisOverlayManager? = null
            private set

        fun init(context: Context): JarvisOverlayManager {
            val existing = instance
            if (existing != null) return existing
            val created = JarvisOverlayManager(context.applicationContext)
            instance = created
            return created
        }
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main)

    private var overlayView: LinearLayout? = null
    private var orbView: MiniVoiceOrbView? = null
    private var subtitleText: TextView? = null

    private var isAttached = false
    private var isTemporarilyHidden = false
    private var autoHideJob: Job? = null

    init {
        instance = this
    }

    // -------------------------------------------------------------------------
    // Mini Voice Orb View
    // -------------------------------------------------------------------------

    class MiniVoiceOrbView(context: Context) : View(context) {
        private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#00E5FF") // Cyan by default
        }
        private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#4000E5FF")
        }

        private var amplitude: Float = 0f
        private var targetColor: Int = Color.parseColor("#00E5FF")

        fun setAmplitude(amp: Float) {
            amplitude = amp.coerceIn(0f, 1f)
            invalidate()
        }

        fun setStateColor(color: Int) {
            targetColor = color
            centerPaint.color = targetColor
            val alpha = (Color.alpha(targetColor) * 0.25f).toInt().coerceIn(20, 80)
            haloPaint.color = Color.argb(
                alpha,
                Color.red(targetColor),
                Color.green(targetColor),
                Color.blue(targetColor)
            )
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val baseRadius = Math.min(cx, cy) * 0.5f

            // Dynamic pulsating halo
            val haloRadius = baseRadius + (baseRadius * 0.9f * amplitude)
            canvas.drawCircle(cx, cy, haloRadius, haloPaint)

            // Inner glowing center core
            val coreRadius = baseRadius * 0.75f + (baseRadius * 0.25f * amplitude)
            canvas.drawCircle(cx, cy, coreRadius, centerPaint)
        }
    }

    // -------------------------------------------------------------------------
    // Window Attachment & View Construction
    // -------------------------------------------------------------------------

    private fun canDraw(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlayView(): LinearLayout {
        val density = context.resources.displayMetrics.density

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (14 * density).toInt(),
                (7 * density).toInt(),
                (14 * density).toInt(),
                (7 * density).toInt()
            )

            // Dark Translucent Cyan Gunmetal Pill Background
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24f * density
                setColor(Color.parseColor("#E6091420")) // Translucent gunmetal
                setStroke((1.5f * density).toInt(), Color.parseColor("#8000E5FF")) // Neon cyan glow border
            }

            // Click handling: Tap HUD pill triggers instant Barge-In or dismiss
            setOnClickListener {
                val service = JarvisVoiceService.instance
                if (service?.isTtsSpeaking == true || service?.acousticState == JarvisVoiceService.AcousticState.SPEAKING) {
                    Log.i(TAG, "HUD pill tapped during speaking: Triggering Barge-In abort!")
                    service.triggerBargeInAbort()
                } else {
                    hideOverlay()
                }
            }
        }

        // Mini Voice Orb
        val orb = MiniVoiceOrbView(context)
        val orbSize = (22 * density).toInt()
        val orbParams = LinearLayout.LayoutParams(orbSize, orbSize).apply {
            marginEnd = (10 * density).toInt()
        }
        container.addView(orb, orbParams)
        orbView = orb

        // Live Subtitle Text
        val tv = TextView(context).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Color.parseColor("#E0F7FA"))
            textSize = 12f
            typeface = Typeface.SANS_SERIF
            text = "J.A.R.V.I.S. Standby"
        }
        val textParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        container.addView(tv, textParams)
        subtitleText = tv

        return container
    }

    private fun getLayoutParams(): WindowManager.LayoutParams {
        val density = context.resources.displayMetrics.density
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (42 * density).toInt() // Positioned neatly just below the status bar
        }
    }

    // -------------------------------------------------------------------------
    // Public State Management API
    // -------------------------------------------------------------------------

    fun showOverlay() {
        if (!canDraw() || windowManager == null) return
        mainHandler.post {
            autoHideJob?.cancel()
            if (!isAttached) {
                try {
                    val view = overlayView ?: createOverlayView().also { overlayView = it }
                    view.alpha = 0f
                    view.scaleX = 0.85f
                    view.scaleY = 0.85f
                    windowManager.addView(view, getLayoutParams())
                    isAttached = true

                    view.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(220)
                        .start()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed adding overlay to WindowManager: ${e.message}")
                }
            } else {
                overlayView?.animate()?.alpha(1f)?.scaleX(1f)?.scaleY(1f)?.setDuration(150)?.start()
            }
        }
    }

    fun hideOverlay() {
        mainHandler.post {
            if (isAttached && overlayView != null) {
                overlayView?.animate()
                    ?.alpha(0f)
                    ?.scaleX(0.85f)
                    ?.scaleY(0.85f)
                    ?.setDuration(250)
                    ?.setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            try {
                                if (isAttached && overlayView != null) {
                                    windowManager?.removeView(overlayView)
                                    isAttached = false
                                }
                            } catch (_: Exception) {}
                        }
                    })
                    ?.start()
            }
        }
    }

    fun scheduleAutoHide(delayMs: Long = 2500L) {
        autoHideJob?.cancel()
        autoHideJob = scope.launch(Dispatchers.Main) {
            delay(delayMs)
            hideOverlay()
        }
    }

    /**
     * Temporarily hide HUD before zero-popup screen capture so the HUD pill itself is not captured.
     */
    fun setTemporarilyHidden(hidden: Boolean) {
        isTemporarilyHidden = hidden
        mainHandler.post {
            overlayView?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        }
    }

    /**
     * Update state and auto-show / schedule auto-hide accordingly.
     */
    fun updateState(state: String) {
        mainHandler.post {
            when (state.lowercase()) {
                "listening", "listening_wake", "listening_cmd" -> {
                    orbView?.setStateColor(Color.parseColor("#00E5FF")) // Cyan = Listening
                    showOverlay()
                }
                "thinking", "processing", "vision", "vision_camera", "vision_screen" -> {
                    orbView?.setStateColor(Color.parseColor("#FFB300")) // Amber/Blue = Thinking/Vision
                    showOverlay()
                }
                "speaking" -> {
                    orbView?.setStateColor(Color.parseColor("#00E676")) // Bright Teal = Speaking
                    showOverlay()
                }
                "agent_working" -> {
                    orbView?.setStateColor(Color.parseColor("#00B0FF")) // Electric Blue = Agent Working
                    showOverlay()
                }
                "standby", "paused", "sleep", "sleeping", "idle", "active" -> {
                    scheduleAutoHide(2500L)
                }
            }
        }
    }

    fun updateText(text: String) {
        mainHandler.post {
            if (text.isNotBlank()) {
                subtitleText?.text = text.trim()
                showOverlay()
            }
        }
    }

    fun updateAmplitude(normAmp: Float) {
        mainHandler.post {
            orbView?.setAmplitude(normAmp)
        }
    }

    fun destroy() {
        mainHandler.post {
            autoHideJob?.cancel()
            try {
                if (isAttached && overlayView != null) {
                    windowManager?.removeView(overlayView)
                    isAttached = false
                }
            } catch (_: Exception) {}
            overlayView = null
            orbView = null
            subtitleText = null
        }
    }
}
