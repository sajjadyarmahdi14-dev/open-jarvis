package com.sajjad.jarvis

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Live BroadcastReceiver for dynamic package installation, replacement, and removal.
 * Automatically refreshes the local in-memory app index and synchronizes the updated
 * app catalog to the backend VPS server (/api/device/apps) in real time.
 */
class PackageChangeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PackageChangeReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val dataString = intent.dataString ?: ""
        Log.i(TAG, "Dynamic package event detected: action=$action, data=$dataString")

        if (action == Intent.ACTION_PACKAGE_ADDED ||
            action == Intent.ACTION_PACKAGE_REPLACED ||
            action == Intent.ACTION_PACKAGE_REMOVED
        ) {
            val prefs = context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
            val serverUrl = prefs.getString("server_url", "http://104.252.175.159:8765") ?: "http://104.252.175.159:8765"

            // Re-index all launchable packages
            AppLauncher.refreshInstalledApps(context)
            // Immediately sync updated catalog to VPS
            AppLauncher.syncAppsToServer(context, serverUrl)
            Log.i(TAG, "Refreshed and synced ${AppLauncher.dynamicAppIndex.size} apps to $serverUrl following $action")
        }
    }
}
