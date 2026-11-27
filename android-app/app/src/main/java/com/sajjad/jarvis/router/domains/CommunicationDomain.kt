package com.sajjad.jarvis.router.domains

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.sajjad.jarvis.AppLauncher
import com.sajjad.jarvis.DeviceController
import com.sajjad.jarvis.LocalCommandRouter.LocalRouteResult
import com.sajjad.jarvis.router.NlpNormalizer

/**
 * CommunicationDomain (Tier 1 Zero-Token Engine + Smart AI Handoff):
 *
 * 1. Deterministic email and SMS composition when recipient and literal body are explicit.
 * 2. Messenger quick-launch.
 * 3. Smart Handoff: Escalates composition, drafting, formal wording, or complex reasoning to Tier 2 AI.
 */
object CommunicationDomain {

    private const val TAG = "CommunicationDomain"

    fun handle(context: Context, rawText: String, clean: String, lower: String, isFa: Boolean): LocalRouteResult {
        val norm = NlpNormalizer.cleanLower(rawText)

        // ---------------------------------------------------------------------
        // SMART HANDOFF CHECK: Drafting, Formal Composition, Creative Writing
        // ---------------------------------------------------------------------
        val draftingIndicators = listOf(
            "بنویس", "متن بنویس", "تنظیم نامه", "تنظیم متن", "تنظیم ایمیل", "پیش نویس", "پیش‌نویس", "عذرخواهی",
            "رسمی", "محترمانه", "توضیح بده چرا", "بگو که چرا", "دلیلش رو بگو",
            "draft", "compose", "write an email", "write a message", "formal email",
            "apologize", "apology", "explain why", "generate text"
        )
        val isQuickNote = com.sajjad.jarvis.ClipboardAndNotesEngine.extractQuickNoteText(clean, isFa) != null
        val isClipboardCmd = com.sajjad.jarvis.ClipboardAndNotesEngine.detectClipboardAction(norm) != null
        if (!isQuickNote && !isClipboardCmd && draftingIndicators.any { norm.contains(it) }) {
            Log.i(TAG, "Request contains drafting/composition indicators -> Handoff to AI")
            return LocalRouteResult(handled = false, handoffToAi = true, category = "smart_handoff_ai")
        }

        // ---------------------------------------------------------------------
        // 1. Email Open & Composition
        // ---------------------------------------------------------------------
        if (norm in listOf("open email", "open gmail", "check emails", "ایمیلام رو باز کن", "ایمیل‌ها رو باز کن", "جیمیل رو باز کن", "ایمیل من")) {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_APP_EMAIL)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            AppLauncher.launchWithOverlayFallback(context, intent)
            val reply = if (isFa) "صندوق ایمیل شما باز شد قربان." else "Opening email inbox, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "email_open")
        }

        // Direct Email Compose ("send email to <x> saying <y>" / "send email to <x> say <y>")
        val emailMatch = Regex("(?:send email to|email to|email)\\s+([^\\s]+@[^\\s]+)(?:\\s+(?:saying|say|about|with body|body|text)\\s+(.+))?").find(lower)
            ?: Regex("(?:ایمیل بزن به|ایمیل بفرست به|ارسال ایمیل به)\\s+([^\\s]+@[^\\s]+)(?:\\s+(?:با متن|بگو|متن|پیام)?\\s*(.+))?").find(clean)
        if (emailMatch != null) {
            val recipient = emailMatch.groupValues[1].trim()
            val body = emailMatch.groupValues.getOrNull(2)?.trim() ?: ""
            val autoSend = norm.contains("send email") || norm.contains("ایمیل بفرست") || norm.contains("ارسال کن")
            DeviceController.sendEmail(context, recipient, "Jarvis Message", body, autoSend = autoSend)
            val reply = if (autoSend && com.sajjad.jarvis.JarvisAccessibilityService.instance != null) {
                if (isFa) "در حال ارسال ایمیل به $recipient قربان." else "Sending email to $recipient, sir."
            } else {
                if (isFa) "پیش‌نویس ایمیل با پیام شما آماده شد قربان—روی ارسال بزنید." else "Email draft with your message is ready, sir—tap Send."
            }
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "email_compose")
        }

        // ---------------------------------------------------------------------
        // 2. Direct SMS Compose & Silent Send (Background-First)
        // ---------------------------------------------------------------------
        val smsMatchFa = Regex("^(?:پیامک بزن به|اس ام اس بده به|پیام بده به)\\s+(.+?)\\s+(?:بگو|که)\\s+(.+)$").find(clean)
        val smsMatchEn = Regex("^(?:send sms to|text)\\s+(.+?)\\s+saying\\s+(.+)$").find(lower)
        if (smsMatchFa != null || smsMatchEn != null) {
            val target = smsMatchFa?.groupValues?.get(1)?.trim() ?: smsMatchEn?.groupValues?.get(1)?.trim() ?: ""
            val message = smsMatchFa?.groupValues?.get(2)?.trim() ?: smsMatchEn?.groupValues?.get(2)?.trim() ?: ""
            if (target.isNotBlank() && message.isNotBlank()) {
                val sentSilently = DeviceController.sendSmsDirect(context, target, message)
                if (sentSilently) {
                    val reply = if (isFa) "پیامک به $target با موفقیت در پس‌زمینه ارسال شد قربان." else "SMS sent to $target in background, sir."
                    return LocalRouteResult(handled = true, spokenFeedback = reply, category = "sms_sent_silent")
                } else {
                    DeviceController.sendSms(context, target, message)
                    val reply = if (isFa) "پیامک به $target آماده ارسال شد قربان." else "SMS to $target prepared, sir."
                    return LocalRouteResult(handled = true, spokenFeedback = reply, category = "sms_compose")
                }
            }
        }

        // ---------------------------------------------------------------------
        // 3. Quick Launch Messengers
        // ---------------------------------------------------------------------
        if (norm in listOf("open telegram", "launch telegram", "تلگرام رو باز کن", "برو تلگرام")) {
            AppLauncher.launchApp(context, "telegram")
            val reply = if (isFa) "تلگرام باز شد قربان." else "Opening Telegram, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "comm_messenger")
        }
        if (norm in listOf("open whatsapp", "launch whatsapp", "واتساپ رو باز کن", "برو واتساپ")) {
            AppLauncher.launchApp(context, "whatsapp")
            val reply = if (isFa) "واتساپ باز شد قربان." else "Opening WhatsApp, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "comm_messenger")
        }

        return LocalRouteResult(handled = false)
    }
}
