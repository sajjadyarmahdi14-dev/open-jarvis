package com.sajjad.jarvis.security

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Iron Man Anti-Theft Device Admin Receiver.
 * Monitors failed lockscreen unlock attempts (PIN / Pattern / Password / Biometric)
 * to trigger silent front-camera intruder photo, 5s audio chunk, and location upload.
 */
class JarvisDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "JarvisDeviceAdmin"

        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context.applicationContext, JarvisDeviceAdminReceiver::class.java)
        }

        fun isAdminActive(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            return dpm?.isAdminActive(getComponentName(context)) == true
        }
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Jarvis Anti-Theft Device Administrator enabled.")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "Jarvis Anti-Theft Device Administrator disabled.")
    }

    override fun onPasswordFailed(context: Context, intent: Intent) {
        super.onPasswordFailed(context, intent)
        Log.w(TAG, "Intruder warning: Failed lockscreen authentication attempt detected!")
        SecurityProtocolEngine.onIntruderAttemptDetected(context)
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent) {
        super.onPasswordSucceeded(context, intent)
        Log.i(TAG, "Lockscreen unlock succeeded.")
    }
}
