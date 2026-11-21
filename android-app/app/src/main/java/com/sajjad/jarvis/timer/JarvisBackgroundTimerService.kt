package com.sajjad.jarvis.timer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sajjad.jarvis.JarvisVoiceService
import com.sajjad.jarvis.R
import kotlinx.coroutines.*
import java.util.Locale

/**
 * Background-first native timer service.
 * Runs timers entirely in the background without launching external OEM clock apps.
 * Features real-time countdown notification + Male Edge-TTS completion alert.
 */
class JarvisBackgroundTimerService : Service() {

    companion object {
        private const val TAG = "JarvisTimerService"
        const val CHANNEL_ID = "jarvis_timer"
        const val NOTIFICATION_ID = 202612
        const val ALERT_NOTIFICATION_ID = 202613

        const val ACTION_START_TIMER = "com.sajjad.jarvis.ACTION_START_TIMER"
        const val ACTION_CANCEL_TIMER = "com.sajjad.jarvis.ACTION_CANCEL_TIMER"
        const val ACTION_DISMISS_ALARM = "com.sajjad.jarvis.ACTION_DISMISS_ALARM"
        const val EXTRA_SECONDS = "extra_seconds"
        const val EXTRA_LABEL = "extra_label"

        @Volatile var activeTimerSeconds: Int = 0
            private set
        @Volatile var remainingSeconds: Int = 0
            private set
        @Volatile var activeLabel: String = "Timer"
            private set
        @Volatile var isTimerRunning: Boolean = false
            private set

        fun startTimer(context: Context, totalSeconds: Int, label: String = "Timer") {
            val intent = Intent(context, JarvisBackgroundTimerService::class.java).apply {
                action = ACTION_START_TIMER
                putExtra(EXTRA_SECONDS, totalSeconds)
                putExtra(EXTRA_LABEL, label)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancelTimer(context: Context) {
            val intent = Intent(context, JarvisBackgroundTimerService::class.java).apply {
                action = ACTION_CANCEL_TIMER
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var countdownJob: Job? = null
    private var notificationManager: NotificationManager? = null
    private var alertMediaPlayer: MediaPlayer? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TIMER -> {
                val sec = intent.getIntExtra(EXTRA_SECONDS, 60).coerceAtLeast(1)
                val label = intent.getStringExtra(EXTRA_LABEL) ?: "Timer"
                beginCountdown(sec, label)
            }
            ACTION_CANCEL_TIMER -> {
                cancelCurrentTimer()
            }
            ACTION_DISMISS_ALARM -> {
                stopAlertSound()
                notificationManager?.cancel(ALERT_NOTIFICATION_ID)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun beginCountdown(totalSeconds: Int, label: String) {
        countdownJob?.cancel()
        stopAlertSound()

        activeTimerSeconds = totalSeconds
        remainingSeconds = totalSeconds
        activeLabel = label
        isTimerRunning = true

        val initialNotif = buildCountdownNotification(remainingSeconds, label)
        startForeground(NOTIFICATION_ID, initialNotif)

        countdownJob = serviceScope.launch {
            while (isActive && remainingSeconds > 0) {
                delay(1000L)
                remainingSeconds--
                if (remainingSeconds > 0) {
                    val notif = buildCountdownNotification(remainingSeconds, label)
                    notificationManager?.notify(NOTIFICATION_ID, notif)
                }
            }
            if (isActive && remainingSeconds <= 0) {
                onTimerFinished(label)
            }
        }
    }

    private fun cancelCurrentTimer() {
        countdownJob?.cancel()
        countdownJob = null
        isTimerRunning = false
        remainingSeconds = 0
        stopAlertSound()
        notificationManager?.cancel(NOTIFICATION_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "Jarvis timer cancelled")
    }

    private fun onTimerFinished(label: String) {
        isTimerRunning = false
        remainingSeconds = 0
        notificationManager?.cancel(NOTIFICATION_ID)

        playAlertSound()
        triggerVibration()

        val isFa = label.any { it in '\u0600'..'\u06FF' }
        val spokenMessage = if (isFa) {
            "قربان، زمان تایمر $label شما به پایان رسید."
        } else {
            "Sir, your $label timer is up!"
        }
        JarvisVoiceService.speakBritishDirect(applicationContext, spokenMessage)

        val dismissIntent = Intent(this, JarvisBackgroundTimerService::class.java).apply {
            action = ACTION_DISMISS_ALARM
        }
        val dismissPending = PendingIntent.getService(
            this,
            101,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val finishedNotif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle("Timer Finished")
            .setContentText(spokenMessage)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss", dismissPending)
            .build()

        notificationManager?.notify(ALERT_NOTIFICATION_ID, finishedNotif)

        serviceScope.launch {
            delay(15000L)
            stopAlertSound()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun playAlertSound() {
        try {
            stopAlertSound()
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            alertMediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build()
                )
                setDataSource(applicationContext, alarmUri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "playAlertSound error: ${e.message}")
        }
    }

    private fun stopAlertSound() {
        try {
            alertMediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
            alertMediaPlayer = null
        } catch (_: Exception) {}
    }

    private fun triggerVibration() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            val pattern = longArrayOf(0, 500, 250, 500, 250, 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        } catch (_: Exception) {}
    }

    private fun buildCountdownNotification(secRemaining: Int, label: String): android.app.Notification {
        val min = secRemaining / 60
        val sec = secRemaining % 60
        val timeStr = String.format(Locale.ROOT, "%02d:%02d remaining", min, sec)

        val cancelIntent = Intent(this, JarvisBackgroundTimerService::class.java).apply {
            action = ACTION_CANCEL_TIMER
        }
        val cancelPending = PendingIntent.getService(
            this,
            102,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle("Jarvis Timer: $label")
            .setContentText(timeStr)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPending)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Jarvis Timers & Alarms",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Countdown notifications and alerts for background timers"
                setShowBadge(true)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        countdownJob?.cancel()
        serviceScope.cancel()
        stopAlertSound()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
