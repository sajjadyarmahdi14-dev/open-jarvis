package com.sajjad.jarvis.timer

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sajjad.jarvis.JarvisVoiceService
import com.sajjad.jarvis.R
import java.util.Calendar
import java.util.Locale

/**
 * Exact Alarm & Timer BroadcastReceiver.
 * Coordinates background exact alarm scheduling and fires audible Edge-TTS announcements.
 */
class JarvisAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "JarvisAlarmReceiver"
        const val ACTION_ALARM_FIRED = "com.sajjad.jarvis.ACTION_ALARM_FIRED"
        const val ACTION_TIMER_EXPIRED = "com.sajjad.jarvis.ACTION_TIMER_EXPIRED"
        const val ACTION_DISMISS_ALARM = "com.sajjad.jarvis.ACTION_DISMISS_ALARM"

        const val EXTRA_ALARM_HOUR = "extra_alarm_hour"
        const val EXTRA_ALARM_MINUTE = "extra_alarm_minute"
        const val EXTRA_ALARM_LABEL = "extra_alarm_label"

        private var activeAlarmPlayer: MediaPlayer? = null

        fun scheduleExactAlarm(context: Context, hour: Int, minute: Int, label: String = "Alarm") {
            try {
                val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    if (timeInMillis <= System.currentTimeMillis()) {
                        add(Calendar.DAY_OF_YEAR, 1)
                    }
                }

                val intent = Intent(context, JarvisAlarmReceiver::class.java).apply {
                    action = ACTION_ALARM_FIRED
                    putExtra(EXTRA_ALARM_HOUR, hour)
                    putExtra(EXTRA_ALARM_MINUTE, minute)
                    putExtra(EXTRA_ALARM_LABEL, label)
                }
                val pending = PendingIntent.getBroadcast(
                    context,
                    2001,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pending)
                } else {
                    am.setExact(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pending)
                }
                Log.i(TAG, "Scheduled exact background alarm for $hour:${String.format(Locale.ROOT, "%02d", minute)} (triggerMs=${cal.timeInMillis})")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to schedule exact alarm: ${e.message}")
            }
        }

        fun stopAlarmTone() {
            try {
                activeAlarmPlayer?.let {
                    if (it.isPlaying) it.stop()
                    it.release()
                }
                activeAlarmPlayer = null
            } catch (_: Exception) {}
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ALARM_FIRED -> {
                val hour = intent.getIntExtra(EXTRA_ALARM_HOUR, 8)
                val minute = intent.getIntExtra(EXTRA_ALARM_MINUTE, 0)
                val label = intent.getStringExtra(EXTRA_ALARM_LABEL) ?: "Alarm"
                triggerAlarmAlert(context, hour, minute, label)
            }
            ACTION_DISMISS_ALARM -> {
                stopAlarmTone()
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.cancel(JarvisBackgroundTimerService.ALERT_NOTIFICATION_ID)
            }
        }
    }

    private fun triggerAlarmAlert(context: Context, hour: Int, minute: Int, label: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                JarvisBackgroundTimerService.CHANNEL_ID,
                "Jarvis Timers & Alarms",
                NotificationManager.IMPORTANCE_HIGH
            )
            nm?.createNotificationChannel(channel)
        }

        playTone(context)
        vibrate(context)

        val timeFormatted = String.format(Locale.ROOT, "%d:%02d", hour, minute)
        val isFa = label.any { it in '\u0600'..'\u06FF' }
        val msg = if (isFa) {
            "قربان، ساعت $timeFormatted است و آلارم شما به صدا درآمده است."
        } else {
            "Sir, it is $timeFormatted! Your alarm is going off."
        }
        JarvisVoiceService.speakBritishDirect(context, msg)

        val dismissIntent = Intent(context, JarvisAlarmReceiver::class.java).apply {
            action = ACTION_DISMISS_ALARM
        }
        val dismissPending = PendingIntent.getBroadcast(
            context,
            2002,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, JarvisBackgroundTimerService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle("Jarvis Alarm: $timeFormatted")
            .setContentText(msg)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss", dismissPending)
            .build()

        nm?.notify(JarvisBackgroundTimerService.ALERT_NOTIFICATION_ID, notif)
    }

    private fun playTone(context: Context) {
        try {
            stopAlarmTone()
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            activeAlarmPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build()
                )
                setDataSource(context, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "playTone error: ${e.message}")
        }
    }

    private fun vibrate(context: Context) {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            val pattern = longArrayOf(0, 600, 300, 600, 300, 1200)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        } catch (_: Exception) {}
    }
}
