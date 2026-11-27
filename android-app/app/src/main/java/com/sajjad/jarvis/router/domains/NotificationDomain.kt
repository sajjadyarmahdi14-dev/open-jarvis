package com.sajjad.jarvis.router.domains

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.sajjad.jarvis.JarvisNotificationListener
import com.sajjad.jarvis.LocalCommandRouter.LocalRouteResult
import com.sajjad.jarvis.router.NlpNormalizer

/**
 * NotificationDomain (Tier 1 Zero-Token Engine):
 *
 * Full lifecycle notification management leveraging JarvisNotificationListener:
 * 1. Read latest notification, unread count, or notifications by app/sender.
 * 2. Dismiss / Cancel single or all notifications without touching the screen.
 * 3. Zero-App-Open direct reply via RemoteInput.
 * 4. Open active notification via PendingIntent.
 * 5. Mute / DND notification toggles.
 */
object NotificationDomain {

    private const val TAG = "NotificationDomain"

    fun handle(context: Context, rawText: String, clean: String, lower: String, isFa: Boolean): LocalRouteResult {
        val norm = NlpNormalizer.cleanLower(rawText)

        // ---------------------------------------------------------------------
        // 1. Dismiss / Clear / Cancel Notifications
        // ---------------------------------------------------------------------
        val dismissAllWords = listOf(
            "همه نوتیفها رو پاک کن", "همه اعلان‌ها رو پاک کن", "همه نوتیف‌ها رو ببند",
            "clear all notifications", "dismiss all notifications", "delete all notifications"
        )
        if (dismissAllWords.any { norm.contains(it) }) {
            val ok = JarvisNotificationListener.dismissAllNotifications()
            val reply = if (ok) {
                if (isFa) "تمام اعلان‌ها پاک شدند قربان." else "All notifications cleared, sir."
            } else {
                if (isFa) "دسترسی به اعلان‌ها فعال نیست قربان." else "Notification Listener access is not active, sir."
            }
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "notif_dismiss_all")
        }

        val dismissExactWords = setOf(
            "ردش کن", "بیخیالش شو", "ببندش", "پاکش کن", "dismiss", "ignore it"
        )
        val dismissSinglePhrases = listOf(
            "نوتیف رو ببند", "اعلان رو پاک کن",
            "نوتیف رو رد کن", "اعلان رو رد کن", "پیام رو ببند",
            "dismiss notification", "clear notification", "close notification"
        )
        if (norm in dismissExactWords || dismissSinglePhrases.any { norm == it || norm.contains(it) }) {
            val ok = JarvisNotificationListener.dismissLatestNotification()
            val reply = if (ok) {
                if (isFa) "اعلان بسته شد قربان." else "Notification dismissed, sir."
            } else {
                if (isFa) "اعلانی برای بستن پیدا نشد قربان." else "No active notification to dismiss, sir."
            }
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "notif_dismiss")
        }

        // ---------------------------------------------------------------------
        // 2. Open Active Notification
        // ---------------------------------------------------------------------
        val openPhrases = listOf(
            "نوتیف رو باز کن", "اعلان رو باز کن", "پیام رو باز کن",
            "open notification", "open latest notification", "open message"
        )
        if (norm == "بازش کن" || openPhrases.any { norm == it || norm.contains(it) }) {
            val ok = JarvisNotificationListener.openLatestNotification(context)
            val reply = if (ok) {
                if (isFa) "اعلان باز شد قربان." else "Opening notification, sir."
            } else {
                if (isFa) "اعلان فعالی برای باز کردن وجود ندارد قربان." else "No active notification found to open, sir."
            }
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "notif_open")
        }

        // ---------------------------------------------------------------------
        // 3. Direct Reply to Notification via RemoteInput
        // ---------------------------------------------------------------------
        // Persian: "جوابش رو بده بگو <text>", "به <name> جواب بده بگو <text>"
        // English: "reply saying <text>", "reply to <name> saying <text>"
        val replyMatchFa = Regex("(?:به\\s+(.+?)\\s+)?(?:جوابش رو بده|جواب بده|پاسخ بده)\\s+(?:بگو|که)?\\s*(.+)").find(clean)
        val replyMatchEn = Regex("reply(?:\\s+to\\s+(.+?))?\\s+saying\\s+(.+)").find(lower)

        if (replyMatchFa != null || replyMatchEn != null) {
            val target = replyMatchFa?.groupValues?.get(1)?.trim() ?: replyMatchEn?.groupValues?.get(1)?.trim() ?: ""
            val message = replyMatchFa?.groupValues?.get(2)?.trim() ?: replyMatchEn?.groupValues?.get(2)?.trim() ?: ""

            if (message.isNotBlank()) {
                val (ok, sender) = JarvisNotificationListener.replyToNotification(context, target, message)
                val reply = if (ok) {
                    if (isFa) "پاسخ به ${sender ?: target} ارسال شد قربان: $message" else "Replied to ${sender ?: target}, sir: $message"
                } else {
                    if (isFa) "اعلان فعالی برای ارسال پاسخ یافت نشد قربان." else "Could not find active notification to reply to, sir."
                }
                return LocalRouteResult(handled = true, spokenFeedback = reply, category = "notif_reply")
            }
        }

        // ---------------------------------------------------------------------
        // 4. Read Unread Count
        // ---------------------------------------------------------------------
        val countWords = listOf(
            "چند تا پیام دارم", "تعداد پیام‌ها", "تعداد نوتیف‌ها", "چند تا اعلان دارم",
            "how many notifications", "unread notification count", "count notifications"
        )
        if (countWords.any { norm.contains(it) }) {
            val count = JarvisNotificationListener.getCachedNotifications().size
            val reply = if (isFa) {
                if (count == 0) "هیچ اعلان جدیدی وجود ندارد قربان." else "شما $count اعلان فعال دارید قربان."
            } else {
                if (count == 0) "You have no unread notifications, sir." else "You have $count active notifications, sir."
            }
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "notif_count")
        }

        // ---------------------------------------------------------------------
        // 5. Read Latest Notification or Specific App Notifications
        // ---------------------------------------------------------------------
        val readWords = listOf(
            "read notification", "read latest notification", "read notifications",
            "آخرین پیام رو بخون", "نوتیف رو بخون", "اعلان جدید چیه", "پیام‌های جدید رو بخون", "پیامهای جدید رو بخون"
        )
        if (readWords.any { norm.contains(it) }) {
            val recents = JarvisNotificationListener.getCachedNotifications()
            val reply = if (recents.isNotEmpty()) {
                val top = recents.first()
                if (isFa) "آخرین اعلان از ${top.senderTitle} (${top.appLabel}): ${top.messageText} است قربان."
                else "Latest notification from ${top.senderTitle} (${top.appLabel}): ${top.messageText}, sir."
            } else {
                if (isFa) "هیچ اعلان جدیدی وجود ندارد قربان." else "No unread notifications received, sir."
            }
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "notif_read")
        }

        // ---------------------------------------------------------------------
        // 6. Mute / Silence Notifications
        // ---------------------------------------------------------------------
        if (norm.contains("نوتیفها رو سایلنت کن") || norm.contains("اعلان‌ها رو سایلنت کن") || norm.contains("mute notifications") || norm.contains("silence notifications")) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val dndGranted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && nm != null && nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                true
            } else {
                false
            }
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            try {
                am?.setStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION, 0, 0)
            } catch (_: Exception) {}
            val reply = if (dndGranted) {
                if (isFa) "اعلان‌ها بی‌صدا شدند قربان." else "Notifications silenced, sir."
            } else {
                if (isFa) "صدای اعلان‌ها کم شد قربان؛ برای حالت مزاحم نشوید دسترسی لازم است." else "Notification volume muted, sir. Grant Do Not Disturb access for full DND silence."
            }
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "notif_mute")
        }

        return LocalRouteResult(handled = false)
    }
}
