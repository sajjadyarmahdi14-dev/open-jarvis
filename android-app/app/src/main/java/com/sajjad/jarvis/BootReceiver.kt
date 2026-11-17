package com.sajjad.jarvis

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Automatically starts JarvisVoiceService on device reboot or package update,
 * enabling true 24/7 "Always-On" voice capabilities without requiring the user
 * to manually launch the app first.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "JarvisBootReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "Boot event received: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED) {

            context?.let { ctx ->
                val serviceIntent = Intent(ctx, JarvisVoiceService::class.java).apply {
                    this.action = JarvisVoiceService.ACTION_START
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ctx.startForegroundService(serviceIntent)
                    } else {
                        ctx.startService(serviceIntent)
                    }
                    Log.i(TAG, "JarvisVoiceService automatically started on $action")
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to start JarvisVoiceService on boot: ${t.message}", t)
                }
            }
        }
    }
}
