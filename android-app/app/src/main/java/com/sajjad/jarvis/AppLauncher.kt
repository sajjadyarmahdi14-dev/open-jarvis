package com.sajjad.jarvis

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

data class AppEntry(
    val label: String,
    val normalizedLabel: String,
    val packageName: String,
    val activityName: String? = null,
    val shortPackageToken: String,
    val bilingualVariants: List<String> = emptyList()
)

object AppLauncher {

    private const val TAG = "AppLauncher"

    val dynamicAppIndex = CopyOnWriteArrayList<AppEntry>()

    // Bilingual & System Alias Dictionary (Fallback / Quick Priority Layer)
    private val systemAliases = mapOf<String, List<String>>(
        "gallery" to listOf("com.miui.gallery", "com.google.android.apps.photos", "com.sec.android.gallery3d", "com.android.gallery3d"),
        "photos" to listOf("com.miui.gallery", "com.google.android.apps.photos", "com.sec.android.gallery3d"),
        "photo" to listOf("com.miui.gallery", "com.google.android.apps.photos"),
        "گالری" to listOf("com.miui.gallery", "com.google.android.apps.photos", "com.sec.android.gallery3d"),
        "عکس" to listOf("com.miui.gallery", "com.google.android.apps.photos", "com.sec.android.gallery3d"),

        "camera" to listOf("com.android.camera", "com.google.android.GoogleCamera", "org.codeaurora.snapcam", "com.sec.android.app.camera"),
        "دوربین" to listOf("com.android.camera", "com.google.android.GoogleCamera", "org.codeaurora.snapcam", "com.sec.android.app.camera"),
        "pxlcam" to listOf("com.google.android.GoogleCamera", "com.android.camera"),

        "weather" to listOf("com.miui.weather2", "com.google.android.apps.weather"),
        "آب و هوا" to listOf("com.miui.weather2", "com.google.android.apps.weather"),
        "mi browser" to listOf("com.mi.globalbrowser"),
        "browser" to listOf("com.mi.globalbrowser", "com.android.chrome"),
        "مرورگر" to listOf("com.mi.globalbrowser", "com.android.chrome"),
        "music" to listOf("com.miui.player", "com.google.android.apps.youtube.music", "com.spotify.music"),
        "موزیک" to listOf("com.miui.player", "com.google.android.apps.youtube.music", "com.spotify.music"),
        "آهنگ" to listOf("com.miui.player", "com.google.android.apps.youtube.music", "com.spotify.music"),
        "themes" to listOf("com.android.thememanager"),
        "تم" to listOf("com.android.thememanager"),
        "settings" to listOf("com.android.settings"),
        "تنظیمات" to listOf("com.android.settings"),
        "file manager" to listOf("com.mi.android.globalFileexplorer", "com.google.android.documentsui"),
        "مدیریت فایل" to listOf("com.mi.android.globalFileexplorer", "com.google.android.documentsui"),
        "فایل" to listOf("com.mi.android.globalFileexplorer", "com.google.android.documentsui"),
        "chrome" to listOf("com.android.chrome"),
        "کروم" to listOf("com.android.chrome"),
        "telegram" to listOf("org.telegram.messenger", "org.telegram.plus", "org.thunderdog.challegram"),
        "تلگرام" to listOf("org.telegram.messenger", "org.telegram.plus", "org.thunderdog.challegram"),
        "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "واتساپ" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "instagram" to listOf("com.instagram.android"),
        "اینستاگرام" to listOf("com.instagram.android")
    )

    fun normalizeAlphanumeric(s: String): String {
        return s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9\u0600-\u06FF]"), "")
    }

    /**
     * Persian-to-Latin Phonetic Transliterator.
     * Bridges Persian voice queries (e.g. "لترباکسد", "اسنپسید", "ترمیوس", "ماینکرفت")
     * into Latin script phonetics to match English Android application labels.
     */
    fun transliteratePersianToLatin(text: String): String {
        val norm = normalizeAlphanumeric(text)
        val sb = StringBuilder()
        for (c in norm) {
            val latin = when (c) {
                'آ', 'ا' -> "a"
                'ب' -> "b"
                'پ' -> "p"
                'ت', 'ط' -> "t"
                'ث', 'س', 'ص' -> "s"
                'ج' -> "j"
                'چ' -> "ch"
                'ح', 'ه' -> "h"
                'خ' -> "kh"
                'د' -> "d"
                'ذ', 'ز', 'ض', 'ظ' -> "z"
                'ر' -> "r"
                'ژ' -> "zh"
                'ش' -> "sh"
                'ع' -> "a"
                'غ', 'ق' -> "gh"
                'ف' -> "f"
                'ک' -> "k"
                'گ' -> "g"
                'ل' -> "l"
                'م' -> "m"
                'ن' -> "n"
                'و' -> "v"
                'ی', 'ي', 'ئ' -> "y"
                else -> if (c in 'a'..'z' || c in '0'..'9') c.toString() else ""
            }
            sb.append(latin)
        }
        return sb.toString()
    }

    fun levenshteinSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        val d = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) d[i][0] = i
        for (j in 0..s2.length) d[0][j] = j
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                d[i][j] = minOf(
                    d[i - 1][j] + 1,
                    d[i][j - 1] + 1,
                    d[i - 1][j - 1] + cost
                )
            }
        }
        val maxLen = maxOf(s1.length, s2.length)
        return 1.0 - (d[s1.length][s2.length].toDouble() / maxLen.toDouble())
    }

    fun generateVariants(label: String, packageName: String): List<String> {
        val variants = mutableListOf<String>()
        val l = label.lowercase(Locale.ROOT)
        val token = packageName.substringAfterLast(".").lowercase(Locale.ROOT)

        variants.add(l)
        variants.add(token)

        when {
            l.contains("music") || token.contains("music") || token.contains("player") -> {
                variants.addAll(listOf("موزیک", "آهنگ", "اهنگ", "music", "player", "اپ موزیک"))
            }
            l.contains("camera") || token.contains("camera") -> {
                variants.addAll(listOf("دوربین", "camera", "شاتر", "عکاسی"))
            }
            l.contains("gallery") || l.contains("photos") || token.contains("gallery") -> {
                variants.addAll(listOf("گالری", "عکس", "photos", "gallery"))
            }
            l.contains("telegram") || token.contains("telegram") -> {
                variants.addAll(listOf("تلگرام", "telegram"))
            }
            l.contains("whatsapp") || token.contains("whatsapp") -> {
                variants.addAll(listOf("واتساپ", "واتس اپ", "whatsapp"))
            }
            l.contains("instagram") || token.contains("instagram") -> {
                variants.addAll(listOf("اینستاگرام", "اینستا", "instagram"))
            }
            l.contains("settings") || token.contains("settings") -> {
                variants.addAll(listOf("تنظیمات", "settings"))
            }
            l.contains("notes") || token.contains("notes") || token.contains("keep") -> {
                variants.addAll(listOf("یادداشت", "نوت", "notes", "notepad"))
            }
            l.contains("clock") || token.contains("clock") || token.contains("deskclock") -> {
                variants.addAll(listOf("ساعت", "آلارم", "clock", "alarm"))
            }
            l.contains("file") || token.contains("file") || token.contains("explorer") -> {
                variants.addAll(listOf("فایل", "مدیریت فایل", "فایل منیجر", "files"))
            }
            l.contains("calculator") || token.contains("calculator") -> {
                variants.addAll(listOf("ماشین حساب", "calculator"))
            }
            l.contains("message") || token.contains("mms") || token.contains("sms") -> {
                variants.addAll(listOf("پیامک", "پیام‌ها", "پیام", "sms", "messages"))
            }
            l.contains("phone") || token.contains("dialer") -> {
                variants.addAll(listOf("تلفن", "تماس", "phone", "dialer"))
            }
            l.contains("browser") || token.contains("browser") || token.contains("chrome") -> {
                variants.addAll(listOf("مرورگر", "کروم", "browser", "chrome"))
            }
        }
        return variants.distinct()
    }

    /**
     * Complete Zero-Hardcode Device App Indexer.
     * Queries packageManager.queryIntentActivities for Intent(ACTION_MAIN, CATEGORY_LAUNCHER)
     * with MATCH_ALL to dynamically index every launchable app on the device.
     */
    fun scanAllInstalledApps(context: Context): List<AppEntry> {
        try {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PackageManager.MATCH_ALL
            } else {
                0
            }
            val launcherList = pm.queryIntentActivities(mainIntent, flags)
            val seenPackages = mutableSetOf<String>()
            val entries = mutableListOf<AppEntry>()

            for (ri in launcherList) {
                val pkg = ri.activityInfo.packageName
                if (pkg.isNullOrBlank() || seenPackages.contains(pkg)) continue
                seenPackages.add(pkg)
                val actName = ri.activityInfo.name
                val label = try {
                    ri.loadLabel(pm).toString()
                } catch (_: Exception) {
                    pkg
                }
                val norm = normalizeAlphanumeric(label)
                val token = pkg.substringAfterLast(".").lowercase(Locale.ROOT)
                val variants = generateVariants(label, pkg)
                entries.add(AppEntry(label, norm, pkg, actName, token, variants))
            }

            // Also check installed applications for launch intents
            val installed = pm.getInstalledApplications(flags)
            for (app in installed) {
                val pkg = app.packageName
                if (pkg.isNullOrBlank() || seenPackages.contains(pkg)) continue
                val launchIntent = pm.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    seenPackages.add(pkg)
                    val label = try {
                        pm.getApplicationLabel(app).toString()
                    } catch (_: Exception) {
                        pkg
                    }
                    val actName = launchIntent.component?.className
                    val norm = normalizeAlphanumeric(label)
                    val token = pkg.substringAfterLast(".").lowercase(Locale.ROOT)
                    val variants = generateVariants(label, pkg)
                    entries.add(AppEntry(label, norm, pkg, actName, token, variants))
                }
            }

            dynamicAppIndex.clear()
            dynamicAppIndex.addAll(entries)
            Log.i(TAG, "Dynamic index refreshed with ${dynamicAppIndex.size} launchable apps")
            return dynamicAppIndex
        } catch (e: Exception) {
            Log.w(TAG, "scanAllInstalledApps error: ${e.message}")
            return dynamicAppIndex
        }
    }

    /** Backward-compatible alias for scanAllInstalledApps */
    fun refreshInstalledApps(context: Context): List<AppEntry> {
        return scanAllInstalledApps(context)
    }

    /**
     * Asynchronously sync installed apps list to the remote or local VPS server.
     */
    fun syncAppsToServer(context: Context, serverUrl: String) {
        Thread {
            try {
                if (dynamicAppIndex.isEmpty()) {
                    scanAllInstalledApps(context)
                }
                val cleanUrl = serverUrl.trim().removeSuffix("/")
                val targetUrl = URL("$cleanUrl/api/device/apps")
                val conn = targetUrl.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.connectTimeout = 4000
                conn.readTimeout = 6000
                conn.doOutput = true

                val sb = StringBuilder("[")
                dynamicAppIndex.forEachIndexed { i, entry ->
                    val escapedLabel = entry.label.replace("\"", "\\\"")
                    val variantsJson = entry.bilingualVariants.joinToString(",") { """"${it.replace("\"", "\\\"")}"""" }
                    sb.append("""{"name":"$escapedLabel","label":"$escapedLabel","pkg":"${entry.packageName}","package":"${entry.packageName}","variants":[$variantsJson]}""")
                    if (i < dynamicAppIndex.size - 1) sb.append(",")
                }
                sb.append("]")

                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(sb.toString()) }
                val code = conn.responseCode
                Log.i(TAG, "syncAppsToServer completed with HTTP $code (${dynamicAppIndex.size} apps synced)")
            } catch (e: Exception) {
                Log.w(TAG, "syncAppsToServer notice: ${e.message}")
            }
        }.start()
    }

    /**
     * Resolves app with similarity scoring.
     * Returns Triple(resolvedEntry, highestScore, closestCandidate)
     */
    fun resolveAppWithScore(
        context: Context,
        query: String,
        targetPkg: String?
    ): Triple<AppEntry?, Double, AppEntry?> {
        if (dynamicAppIndex.isEmpty()) {
            scanAllInstalledApps(context)
        }

        // 1. Direct package match if specified
        if (!targetPkg.isNullOrBlank()) {
            val direct = dynamicAppIndex.firstOrNull { it.packageName.equals(targetPkg, ignoreCase = true) }
            if (direct != null) {
                return Triple(direct, 1.0, null)
            }
            // Live rescan on cache-miss
            scanAllInstalledApps(context)
            val rescanDirect = dynamicAppIndex.firstOrNull { it.packageName.equals(targetPkg, ignoreCase = true) }
            if (rescanDirect != null) {
                return Triple(rescanDirect, 1.0, null)
            }
            // Check PM directly
            try {
                val pm = context.packageManager
                val info = pm.getApplicationInfo(targetPkg, 0)
                val label = pm.getApplicationLabel(info).toString()
                val entry = AppEntry(label, normalizeAlphanumeric(label), targetPkg, null, targetPkg.substringAfterLast("."))
                return Triple(entry, 1.0, null)
            } catch (_: Exception) {}
        }

        var clean = query.trim().lowercase(Locale.ROOT)
        clean = clean.replace(Regex("^(open|launch|start|run|play|bring\\s+up|show|باز\\s*کن|اجرا\\s*کن|بالا\\s*بیار|برنامه|بازی|لطفاً|لطفا|please)\\s+"), "")
        clean = clean.replace(Regex("^(the|a|an|برنامه|بازی)\\s+"), "")
        clean = clean.replace(Regex("\\s+(باز\\s*کن|اجرا\\s*کن|بالا\\s*بیار|please)$"), "")
        clean = clean.replace(Regex("\\s+(رو|را)$"), "").trim()

        val normQuery = normalizeAlphanumeric(clean)

        // 2. Exact match on label or bilingual variants
        var matched = dynamicAppIndex.firstOrNull { entry ->
            entry.label.equals(clean, ignoreCase = true) ||
            entry.bilingualVariants.any { it.equals(clean, ignoreCase = true) }
        }
        if (matched != null) return Triple(matched, 1.0, null)

        // 3. Normalized alphanumeric match
        if (normQuery.isNotEmpty()) {
            matched = dynamicAppIndex.firstOrNull { it.normalizedLabel == normQuery }
            if (matched != null) return Triple(matched, 1.0, null)
        }

        // Cache-Miss Live Rescan: if not in memory, re-query OS package manager on the fly
        scanAllInstalledApps(context)
        matched = dynamicAppIndex.firstOrNull { entry ->
            entry.label.equals(clean, ignoreCase = true) ||
            entry.normalizedLabel == normQuery ||
            entry.bilingualVariants.any { it.equals(clean, ignoreCase = true) }
        }
        if (matched != null) return Triple(matched, 1.0, null)

        // 4. System aliases check
        val candidatePkgs = systemAliases[clean] ?: systemAliases[normQuery]
        if (candidatePkgs != null) {
            for (p in candidatePkgs) {
                val entry = dynamicAppIndex.firstOrNull { it.packageName == p }
                if (entry != null) return Triple(entry, 0.95, null)
            }
        }

        // 5. Prefix & token match
        if (normQuery.length >= 3) {
            matched = dynamicAppIndex.firstOrNull {
                it.normalizedLabel.startsWith(normQuery) || it.shortPackageToken == normQuery
            }
            if (matched != null) return Triple(matched, 0.90, null)
        }

        // 6. Substring match
        if (normQuery.length >= 3) {
            matched = dynamicAppIndex.firstOrNull {
                it.normalizedLabel.contains(normQuery) || it.shortPackageToken.contains(normQuery)
            }
            if (matched != null) return Triple(matched, 0.85, null)
        }

        // 7. Persian-to-Latin Transliteration + Levenshtein Fuzzy Match
        val transliterated = transliteratePersianToLatin(clean)
        var bestScore = 0.0
        var closestCandidate: AppEntry? = null

        for (entry in dynamicAppIndex) {
            val scoreLabel = levenshteinSimilarity(entry.normalizedLabel, normQuery)
            val scoreTranslitLabel = if (transliterated.isNotEmpty()) levenshteinSimilarity(entry.normalizedLabel, transliterated) else 0.0
            val scoreTranslitToken = if (transliterated.isNotEmpty()) levenshteinSimilarity(entry.shortPackageToken, transliterated) else 0.0
            val scoreToken = levenshteinSimilarity(entry.shortPackageToken, normQuery)

            val maxS = maxOf(scoreLabel, scoreTranslitLabel, scoreTranslitToken, scoreToken)
            if (maxS > bestScore) {
                bestScore = maxS
                closestCandidate = entry
            }
        }

        if (bestScore >= 0.65 && closestCandidate != null) {
            return Triple(closestCandidate, bestScore, closestCandidate)
        }

        return Triple(null, bestScore, closestCandidate)
    }

    /**
     * Android 14 & Xiaomi HyperOS Guaranteed Background Launching.
     * Uses getLaunchIntentForPackage with ComponentName fallback,
     * PendingIntent with background activity start mode, and overlay fallback.
     */
    fun launchPackageGuaranteed(context: Context, entry: AppEntry): Boolean {
        return try {
            val pm = context.packageManager
            var launchIntent = pm.getLaunchIntentForPackage(entry.packageName)

            if (launchIntent == null && !entry.activityName.isNullOrBlank()) {
                launchIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = ComponentName(entry.packageName, entry.activityName)
                }
            }

            if (launchIntent == null) {
                Log.w(TAG, "No launch intent found for package: ${entry.packageName}")
                return false
            }

            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

            // Android 14+ (SDK 34) Background Activity Start Guarantee
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    val options = ActivityOptions.makeBasic()
                    val method = ActivityOptions::class.java.getMethod(
                        "setPendingIntentBackgroundActivityStartMode",
                        Int::class.javaPrimitiveType
                    )
                    method.invoke(options, 1) // MODE_BACKGROUND_ACTIVITY_START_ALLOWED = 1

                    val pi = PendingIntent.getActivity(
                        context,
                        0,
                        launchIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        options.toBundle()
                    )
                    pi.send()
                } catch (t: Throwable) {
                    Log.w(TAG, "PendingIntent background launch notice: ${t.message}")
                }
            }

            // Overlay-based launching fallback (bypasses Xiaomi HyperOS background pop-up restrictions)
            launchWithOverlayFallback(context, launchIntent)

            // Direct context start fallback
            try {
                context.startActivity(launchIntent)
            } catch (_: Throwable) {}

            true
        } catch (e: Exception) {
            Log.e(TAG, "launchPackageGuaranteed failed: ${e.message}", e)
            false
        }
    }

    fun launchWithOverlayFallback(context: Context, launchIntent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(context)) {
            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                if (wm != null) {
                    val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                    }
                    val params = WindowManager.LayoutParams(
                        1, 1,
                        layoutType,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT
                    )
                    val dummyView = View(context)
                    try {
                        wm.addView(dummyView, params)
                    } catch (_: Throwable) {}

                    try {
                        context.startActivity(launchIntent)
                    } catch (_: Throwable) {}

                    try {
                        val pi = PendingIntent.getActivity(
                            context,
                            0,
                            launchIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        pi.send()
                    } catch (_: Throwable) {}

                    dummyView.postDelayed({
                        try {
                            wm.removeView(dummyView)
                        } catch (_: Throwable) {}
                    }, 500)
                    return
                }
            } catch (t: Throwable) {
                Log.w(TAG, "launchWithOverlayFallback notice: ${t.message}")
            }
        }

        // Standard direct launch path
        try {
            context.startActivity(launchIntent)
        } catch (t: Throwable) {
            Log.w(TAG, "startActivity direct notice: ${t.message}")
        }
        try {
            val pi = PendingIntent.getActivity(
                context,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            pi.send()
        } catch (t: Throwable) {
            Log.w(TAG, "PendingIntent launch notice: ${t.message}")
        }
    }

    fun tryLaunchPackage(context: Context, packageName: String, label: String): String? {
        return try {
            val entry = dynamicAppIndex.firstOrNull { it.packageName == packageName }
                ?: AppEntry(label, normalizeAlphanumeric(label), packageName, null, packageName.substringAfterLast("."))
            val ok = launchPackageGuaranteed(context, entry)
            if (ok) """{"success": true, "package": "$packageName", "label": "$label"}""" else null
        } catch (e: Exception) {
            null
        }
    }

    fun launchApp(context: Context, query: String): String {
        return try {
            val resolution = resolveAppWithScore(context, query, null)
            val matched = resolution.first
            if (matched != null) {
                val ok = launchPackageGuaranteed(context, matched)
                if (ok) return """{"success": true, "package": "${matched.packageName}", "label": "${matched.label}"}"""
            }
            """{"success": false, "error": "App not found for query: $query"}"""
        } catch (e: Exception) {
            """{"success": false, "error": "${e.message}"}"""
        }
    }

    fun listInstalledApps(context: Context): String {
        return try {
            if (dynamicAppIndex.isEmpty()) {
                scanAllInstalledApps(context)
            }
            val sb = java.lang.StringBuilder("[")
            dynamicAppIndex.forEachIndexed { index, entry ->
                val label = entry.label.replace("\"", "\\\"")
                val pkg = entry.packageName
                sb.append("""{"label": "$label", "package": "$pkg"}""")
                if (index < dynamicAppIndex.size - 1) sb.append(",")
            }
            sb.append("]")
            sb.toString()
        } catch (e: Exception) {
            "[]"
        }
    }
}
