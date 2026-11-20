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

/**
 * Pillar 3: Rock-Solid Floating Live HUD Overlay
 *
 * Main-thread serialized, persistent WindowManager overlay with zero animation/timer race conditions.
 * Attached ONCE to WindowManager and toggled via View.VISIBLE / View.GONE.
 * Remains visible throughout LISTENING, ACTIVE_CONVERSATION, THINKING, SPEAKING, and AGENT_WORKING.
 */
class JarvisOverlayManager(private val context: Context) {

    companion object {
        private const val TAG = "JarvisOverlayManager"
        const val PREF_PIN_HUD = "pin_floating_hud"

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

    private var overlayView: LinearLayout? = null
    private var orbView: MiniVoiceOrbView? = null
    private var subtitleText: TextView? = null

    private var isAttached = false
    private var currentLayoutFlags: Int = 0

    private val hideRunnable = Runnable {
        performAutoHide()
    }

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
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#00E5FF")
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
            barPaint.color = targetColor
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
            val d = resources.displayMetrics.density
            val h = height.toFloat()
            val cy = h / 2f

            // 1. Draw glowing orb on the left
            val orbRadius = 6.5f * d
            val orbCx = orbRadius + 2f * d
            val dynamicHalo = orbRadius + (orbRadius * 0.85f * amplitude)
            canvas.drawCircle(orbCx, cy, dynamicHalo, haloPaint)
            val coreRadius = orbRadius * 0.7f + (orbRadius * 0.3f * amplitude)
            canvas.drawCircle(orbCx, cy, coreRadius, centerPaint)

            // 2. Draw 3 mini live audio equalizer bars (Fix 3: 2)
            val barW = 2.5f * d
            val barSpacing = 2.5f * d
            val startX = orbCx + orbRadius + 5f * d

            val barMultipliers = floatArrayOf(0.6f, 1.0f, 0.75f)
            val baseH = 3.5f * d
            val maxH = 15f * d

            for (i in 0 until 3) {
                val bx = startX + i * (barW + barSpacing)
                val curH = (baseH + (maxH - baseH) * amplitude * barMultipliers[i]).coerceIn(baseH, maxH)
                val top = cy - curH / 2f
                val bottom = cy + curH / 2f
                canvas.drawRoundRect(bx, top, bx + barW, bottom, barW / 2f, barW / 2f, barPaint)
            }
        }
    }

    private fun canDraw(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    private fun isPinned(): Boolean {
        val prefs = context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_PIN_HUD, false)
    }

    private fun getStatusBarHeight(): Int {
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else (28 * context.resources.displayMetrics.density).toInt()
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
                setColor(Color.parseColor("#EB08131F")) // Dark translucent gunmetal
                setStroke((1.5f * density).toInt(), Color.parseColor("#9900E5FF")) // Neon cyan glow border
            }

            // Click handling: Tap HUD pill triggers instant Barge-In, Live Session stop, or dismiss
            setOnClickListener {
                val service = JarvisVoiceService.instance
                if (service?.isTtsSpeaking == true || service?.acousticState == JarvisVoiceService.AcousticState.SPEAKING) {
                    Log.i(TAG, "HUD pill tapped during speaking: Triggering Barge-In abort!")
                    service.triggerBargeInAbort()
                } else if (com.sajjad.jarvis.live.LiveAudioSessionManager.isActive) {
                    Log.i(TAG, "HUD pill tapped during live audio listening: Stopping and asking!")
                    com.sajjad.jarvis.live.LiveAudioSessionManager.stopAndAsk(context)
                } else if (com.sajjad.jarvis.live.LiveScreenWatcher.isActive) {
                    Log.i(TAG, "HUD pill tapped during live screen watching: Stopping screen watch!")
                    com.sajjad.jarvis.live.LiveScreenWatcher.stopWatching(context)
                } else {
                    hideOverlay()
                }
            }
        }

        // Mini Voice Orb & Live Visualizer Bars
        val orb = MiniVoiceOrbView(context)
        val orbWidth = (36 * density).toInt()
        val orbHeight = (22 * density).toInt()
        val orbParams = LinearLayout.LayoutParams(orbWidth, orbHeight).apply {
            marginEnd = (10 * density).toInt()
            gravity = Gravity.CENTER_VERTICAL
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
            text = "J.A.R.V.I.S. Online"
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

    private fun getLayoutParams(isTouchable: Boolean): WindowManager.LayoutParams {
        val density = context.resources.displayMetrics.density
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        if (!isTouchable) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

        currentLayoutFlags = flags

        val topMargin = getStatusBarHeight() + (12 * density).toInt()

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = topMargin
        }
    }

    // -------------------------------------------------------------------------
    // Public State Management API (All Main-Thread Serialized)
    // -------------------------------------------------------------------------

    fun showOverlay() {
        if (!canDraw() || windowManager == null) return
        mainHandler.post {
            // Cancel any pending auto-hide or fade-out
            mainHandler.removeCallbacks(hideRunnable)

            val view = overlayView ?: createOverlayView().also { overlayView = it }

            // Ensure attached to WindowManager once
            if (!isAttached) {
                try {
                    view.visibility = View.VISIBLE
                    view.alpha = 0f
                    view.scaleX = 0.85f
                    view.scaleY = 0.85f
                    windowManager.addView(view, getLayoutParams(isTouchable = true))
                    isAttached = true
                } catch (e: Exception) {
                    Log.w(TAG, "Failed adding overlay to WindowManager: ${e.message}")
                    return@post
                }
            } else {
                // If view was GONE or animating, update layout flags to touchable
                if (view.visibility != View.VISIBLE) {
                    view.visibility = View.VISIBLE
                    try {
                        windowManager.updateViewLayout(view, getLayoutParams(isTouchable = true))
                    } catch (_: Exception) {}
                }
            }

            // Cleanly cancel previous animations and animate to full opacity
            view.animate().cancel()
            view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(180)
                .setListener(null)
                .start()
        }
    }

    fun hideOverlay() {
        mainHandler.post {
            if (isPinned()) return@post
            mainHandler.removeCallbacks(hideRunnable)
            val view = overlayView ?: return@post
            if (!isAttached || view.visibility != View.VISIBLE) return@post

            view.animate().cancel()
            view.animate()
                .alpha(0f)
                .scaleX(0.85f)
                .scaleY(0.85f)
                .setDuration(220)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        view.visibility = View.GONE
                        try {
                            if (isAttached) {
                                windowManager?.updateViewLayout(view, getLayoutParams(isTouchable = false))
                            }
                        } catch (_: Exception) {}
                    }
                })
                .start()
        }
    }

    private fun performAutoHide() {
        if (isPinned()) return
        hideOverlay()
    }

    fun scheduleAutoHide(delayMs: Long = 4500L) {
        if (isPinned()) return
        mainHandler.post {
            mainHandler.removeCallbacks(hideRunnable)
            mainHandler.postDelayed(hideRunnable, delayMs)
        }
    }

    /**
     * Temporarily hide HUD during zero-popup screen capture so the HUD pill itself is not captured.
     * Alpha set to 0f for 150ms and restored without destroying view.
     */
    fun setTemporarilyHidden(hidden: Boolean) {
        mainHandler.post {
            val view = overlayView ?: return@post
            if (hidden) {
                view.alpha = 0f
            } else {
                view.alpha = 1f
            }
        }
    }

    /**
     * Update state and auto-show / schedule auto-hide accordingly.
     * Keeps HUD continuously visible throughout LISTENING, ACTIVE_CONVERSATION, THINKING, SPEAKING, and AGENT_WORKING.
     * Only schedules auto-hide after settling back into STANDBY or SLEEPING.
     */
    fun updateState(state: String, text: String? = null) {
        mainHandler.post {
            if (text != null && text.isNotBlank()) {
                subtitleText?.text = text.trim()
            }
            when (state.lowercase()) {
                "listening", "listening_wake", "listening_cmd", "active_conversation", "live_audio_listening" -> {
                    orbView?.setStateColor(Color.parseColor("#00E5FF")) // Cyan = Listening
                    showOverlay()
                }
                "thinking", "processing", "vision", "vision_camera", "vision_screen", "live_screen_watching" -> {
                    orbView?.setStateColor(Color.parseColor("#FFB300")) // Amber = Thinking/Vision
                    showOverlay()
                }
                "speaking" -> {
                    orbView?.setStateColor(Color.parseColor("#00E676")) // Neon Green = Speaking
                    showOverlay()
                }
                "agent_working" -> {
                    orbView?.setStateColor(Color.parseColor("#00B0FF")) // Electric Blue = Agent Working
                    showOverlay()
                }
                "standby", "paused", "sleep", "sleeping", "idle", "active" -> {
                    orbView?.setStateColor(Color.parseColor("#78909C")) // Slate Grey = Standby
                    scheduleAutoHide(4500L)
                }
            }
        }
    }

    fun updateState(state: String) {
        updateState(state, null)
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
            mainHandler.removeCallbacks(hideRunnable)
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
