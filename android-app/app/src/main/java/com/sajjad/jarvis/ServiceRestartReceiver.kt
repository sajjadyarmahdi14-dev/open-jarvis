package com.sajjad.jarvis

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * BroadcastReceiver triggered by AlarmManager or task removal to guarantee
 * instant revival of JarvisVoiceService even when swiped away from Recents.
 */
class ServiceRestartReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ServiceRestartReceiver"
        const val ACTION_RESTART_VOICE_SERVICE = "com.sajjad.jarvis.RESTART_VOICE_SERVICE"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        Log.i(TAG, "onReceive: Received broadcast to restart JarvisVoiceService! (Action: ${intent?.action})")
        try {
            val serviceIntent = Intent(context, JarvisVoiceService::class.java).apply {
                action = JarvisVoiceService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to restart JarvisVoiceService from receiver: ${t.message}", t)
        }
    }
}
