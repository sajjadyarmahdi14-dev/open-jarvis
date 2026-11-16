package com.sajjad.jarvis

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class JarvisForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "jarvis_service_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.sajjad.jarvis.ACTION_START"
        const val ACTION_STOP = "com.sajjad.jarvis.ACTION_STOP"
        const val ACTION_VOICE = "com.sajjad.jarvis.ACTION_VOICE"
        const val EXTRA_START_VOICE = "EXTRA_START_VOICE"

        private var wakeLock: PowerManager.WakeLock? = null

        fun acquireWakeLock(context: Context) {
            try {
                if (wakeLock == null) {
                    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                    wakeLock = powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "jarvis:background_wake_lock"
                    ).apply {
                        setReferenceCounted(false)
                    }
                }
                if (wakeLock?.isHeld != true) {
                    wakeLock?.acquire(30 * 60 * 1000L /* 30 minutes safe timeout */)
                }
            } catch (_: Exception) {}
        }

        fun releaseWakeLock() {
            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
            } catch (_: Exception) {}
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()
        } catch (t: Throwable) {
            Log.e("JarvisForegroundService", "createNotificationChannel failed", t)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return try {
            when (intent?.action) {
                ACTION_STOP -> {
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    START_NOT_STICKY
                }
                else -> {
                    acquireWakeLock(this)
                    val notification = buildNotification()
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            var fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                            }
                            startForeground(NOTIFICATION_ID, notification, fgsType)
                        } else {
                            startForeground(NOTIFICATION_ID, notification)
                        }
                    } catch (t: Throwable) {
                        Log.e("JarvisForegroundService", "startForeground failed: ${t.message}", t)
                    }
                    START_STICKY
                }
            }
        } catch (t: Throwable) {
            Log.e("JarvisForegroundService", "onStartCommand failed", t)
            START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Jarvis Omni-Control Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Jarvis AI Omni-Control and automation active in background"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpenIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val voiceIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_VOICE
            putExtra(EXTRA_START_VOICE, true)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingVoiceIntent = PendingIntent.getActivity(
            this,
            1,
            voiceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, JarvisForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStopIntent = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🤖 Jarvis Omni-Control Active")
            .setContentText("Tap to Open")
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentIntent(pendingOpenIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_jarvis, "🎤 صحبت کنید", pendingVoiceIntent)
            .addAction(R.drawable.ic_jarvis, "⏹ بستن", pendingStopIntent)
            .build()
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }
}
