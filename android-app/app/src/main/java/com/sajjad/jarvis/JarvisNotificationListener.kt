package com.sajjad.jarvis

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Part A: Notification Intelligence & Zero-App-Open Direct RemoteInput Reply
 *
 * Caches active notifications, extracts RemoteInput reply actions, provides notification summaries,
 * and executes instant background replies without opening the messenger UI.
 */
class JarvisNotificationListener : NotificationListenerService() {

    data class CachedNotification(
        val key: String,
        val packageName: String,
        val appLabel: String,
        val senderTitle: String,
        val messageText: String,
        val timestamp: Long,
        val action: Notification.Action?,
        val remoteInput: RemoteInput?,
        val replyPendingIntent: PendingIntent?
    )

    companion object {
        private const val TAG = "JarvisNotification"

        @Volatile
        var instance: JarvisNotificationListener? = null
            private set

        private val cache = ConcurrentHashMap<String, CachedNotification>()

        fun isConnected(): Boolean = instance != null

        fun getCachedNotifications(): List<CachedNotification> {
            return cache.values.sortedByDescending { it.timestamp }
        }

        fun getUnreadNotificationsJson(): JSONArray {
            val array = JSONArray()
            for (item in getCachedNotifications()) {
                val obj = JSONObject().apply {
                    put("key", item.key)
                    put("package", item.packageName)
                    put("app", item.appLabel)
                    put("sender", item.senderTitle)
                    put("text", item.messageText)
                    put("time", item.timestamp)
                    put("has_reply", item.remoteInput != null)
                }
                array.put(obj)
            }
            return array
        }

        /**
         * Part A3: Zero-App-Open Direct Reply using RemoteInput
         */
        fun replyToNotification(context: Context, targetQuery: String, messageText: String): Pair<Boolean, String?> {
            val cleanMsg = UtteranceStitcher.sanitizeLiteralText(messageText)
            if (cleanMsg.isBlank()) return Pair(false, null)

            val query = targetQuery.trim().lowercase()
            val notifications = getCachedNotifications()

            // Find matching notification by sender name or app name
            val match = notifications.firstOrNull { n ->
                n.remoteInput != null && n.replyPendingIntent != null &&
                        (query.isBlank() || n.senderTitle.lowercase().contains(query) || n.appLabel.lowercase().contains(query))
            } ?: notifications.firstOrNull { it.remoteInput != null && it.replyPendingIntent != null }

            if (match == null || match.remoteInput == null || match.replyPendingIntent == null) {
                Log.w(TAG, "No RemoteInput action found for target '$targetQuery'")
                return Pair(false, null)
            }

            return try {
                val intent = Intent()
                val bundle = Bundle()
                bundle.putCharSequence(match.remoteInput.resultKey, cleanMsg)
                RemoteInput.addResultsToIntent(arrayOf(match.remoteInput), intent, bundle)
                match.replyPendingIntent.send(context, 0, intent)
                Log.i(TAG, "Direct RemoteInput reply successfully fired for sender: ${match.senderTitle} (${match.appLabel})")
                Pair(true, match.senderTitle)
            } catch (e: Exception) {
                Log.e(TAG, "Failed sending RemoteInput reply: ${e.message}", e)
                Pair(false, match.senderTitle)
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "JarvisNotificationListener connected.")
        refreshInitialNotifications()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        super.onListenerDisconnected()
        Log.w(TAG, "JarvisNotificationListener disconnected.")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        processNotification(sbn, isNewPost = true)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        cache.remove(sbn.key)
    }

    private fun refreshInitialNotifications() {
        try {
            val active = activeNotifications ?: return
            for (sbn in active) {
                processNotification(sbn, isNewPost = false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error refreshing initial notifications: ${e.message}")
        }
    }

    private fun processNotification(sbn: StatusBarNotification, isNewPost: Boolean) {
        val pkg = sbn.packageName ?: return

        // Ignore Jarvis's own notifications and ongoing system status items
        if (pkg == packageName || sbn.isOngoing) {
            return
        }

        val n = sbn.notification ?: return
        val extras = n.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim() ?: ""

        if (title.isBlank() && text.isBlank()) {
            return
        }

        val appLabel = getAppLabel(pkg)

        // Find RemoteInput action for direct reply
        var remoteInputAction: Notification.Action? = null
        var targetRemoteInput: RemoteInput? = null
        var replyPendingIntent: PendingIntent? = null

        val actions = n.actions
        if (actions != null) {
            for (act in actions) {
                val ris = act.remoteInputs
                if (ris != null && ris.isNotEmpty()) {
                    remoteInputAction = act
                    targetRemoteInput = ris[0]
                    replyPendingIntent = act.actionIntent
                    break
                }
            }
        }

        val cachedItem = CachedNotification(
            key = sbn.key,
            packageName = pkg,
            appLabel = appLabel,
            senderTitle = if (title.isNotBlank()) title else appLabel,
            messageText = text,
            timestamp = sbn.postTime,
            action = remoteInputAction,
            remoteInput = targetRemoteInput,
            replyPendingIntent = replyPendingIntent
        )

        cache[sbn.key] = cachedItem

        // Part B: If this is a newly arrived messaging notification, evaluate proactive headphone announcement
        if (isNewPost && text.isNotBlank()) {
            ProactiveMonitor.handleIncomingNotification(applicationContext, cachedItem)
        }
    }

    private fun getAppLabel(packageName: String): String {
        return try {
            val pm = packageManager
            val ai = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (_: Exception) {
            packageName.substringAfterLast('.')
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }
}
