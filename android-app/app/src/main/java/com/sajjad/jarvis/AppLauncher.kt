package com.sajjad.jarvis

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import java.util.Locale

object AppLauncher {

    val appAliases = mapOf(
        "تلگرام" to "telegram",
        "واتساپ" to "whatsapp",
        "واتس اپ" to "whatsapp",
        "واتس‌اپ" to "whatsapp",
        "اینستاگرام" to "instagram",
        "اینستا" to "instagram",
        "یوتیوب" to "youtube",
        "یوتوب" to "youtube",
        "ماینکرفت" to "minecraft",
        "ماینکرافت" to "minecraft",
        "دوربین" to "camera",
        "عکس" to "camera",
        "تنظیمات" to "settings",
        "گالری" to "gallery",
        "جیمیل" to "gmail",
        "ایمیل" to "gmail",
        "کروم" to "chrome",
        "مرورگر" to "chrome",
        "ماشین حساب" to "calculator",
        "ساعت" to "clock",
        "آلارم" to "clock",
        "نقشه" to "maps",
        "مخاطبین" to "contacts",
        "پیامک" to "messages",
        "پیام‌ها" to "messages",
        "پیام ها" to "messages",
        "تلفن" to "phone",
        "تماس" to "phone",
        "ترموکس" to "termux",
        "فایل" to "files",
        "فایل‌ها" to "files",
        "مدیریت فایل" to "files",
        "موسیقی" to "spotify",
        "آهنگ" to "spotify"
    )

    fun launchApp(context: Context, query: String): String {
        return try {
            var clean = query.trim().lowercase(Locale.ROOT)
            val stripPrefixes = arrayOf(
                "open", "launch", "start", "run", "play",
                "باز کن", "بازکن", "اجرا کن", "اجراکن", "برنامه", "بازی"
            )
            for (p in stripPrefixes) {
                if (clean.startsWith(p)) {
                    clean = clean.removePrefix(p).trim()
                }
            }
            val normalized = appAliases[clean] ?: appAliases[query.trim()] ?: clean
            val pm = context.packageManager

            // 1. 100% Reliable Native Camera App Launch (Xiaomi HyperOS & Android)
            if (clean == "camera" || clean == "دوربین" || normalized == "camera" || clean == "photo" || clean == "عکس") {
                val cameraPackages = arrayOf(
                    "com.android.camera",
                    "com.google.android.GoogleCamera",
                    "org.codeaurora.snapcam",
                    "com.sec.android.app.camera"
                )
                for (pkg in cameraPackages) {
                    val launchIntent = pm.getLaunchIntentForPackage(pkg)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launchIntent)
                        return """{"success": true, "package": "$pkg", "label": "Camera"}"""
                    }
                }
                try {
                    val cameraIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(cameraIntent)
                    return """{"success": true, "intent": "STILL_IMAGE_CAMERA", "label": "Camera"}"""
                } catch (_: Exception) {}
            }

            // 2. Query all launcher apps
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val launchableApps = pm.queryIntentActivities(mainIntent, 0)

            var matched = launchableApps.firstOrNull {
                val label = it.loadLabel(pm).toString().lowercase(Locale.ROOT)
                label == normalized || label == clean
            }
            if (matched == null) {
                matched = launchableApps.firstOrNull {
                    val label = it.loadLabel(pm).toString().lowercase(Locale.ROOT)
                    label.startsWith(normalized) || label.startsWith(clean)
                }
            }
            if (matched == null) {
                matched = launchableApps.firstOrNull {
                    val label = it.loadLabel(pm).toString().lowercase(Locale.ROOT)
                    label.contains(normalized) || label.contains(clean)
                }
            }
            if (matched == null) {
                matched = launchableApps.firstOrNull {
                    val pkg = it.activityInfo.packageName.lowercase(Locale.ROOT)
                    pkg.contains(normalized) || pkg.contains(clean)
                }
            }

            if (matched != null) {
                val pkg = matched.activityInfo.packageName
                val label = matched.loadLabel(pm).toString()
                val launchIntent = pm.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    return """{"success": true, "package": "$pkg", "label": "$label"}"""
                }
            }
            """{"success": false, "error": "App not found for query: $query"}"""
        } catch (e: Exception) {
            """{"success": false, "error": "${e.message}"}"""
        }
    }

    fun listInstalledApps(context: Context): String {
        return try {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val launchableApps = pm.queryIntentActivities(mainIntent, 0)
            val sb = java.lang.StringBuilder("[")
            launchableApps.forEachIndexed { index, resolveInfo ->
                val label = resolveInfo.loadLabel(pm).toString().replace("\"", "\\\"")
                val pkg = resolveInfo.activityInfo.packageName
                sb.append("""{"label": "$label", "package": "$pkg"}""")
                if (index < launchableApps.size - 1) sb.append(",")
            }
            sb.append("]")
            sb.toString()
        } catch (e: Exception) {
            "[]"
        }
    }
}
