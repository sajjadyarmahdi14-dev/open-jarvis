package com.sajjad.jarvis

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.util.Log
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

data class AppEntry(
    val label: String,
    val normalizedLabel: String,
    val packageName: String,
    val shortPackageToken: String
)

object AppLauncher {

    private const val TAG = "AppLauncher"

    val dynamicAppIndex = CopyOnWriteArrayList<AppEntry>()

    // Bilingual & System Alias Dictionary (Layer B)
    private val systemAliases = mapOf<String, List<String>>(
        // Gallery (NEVER match Camera for Gallery!)
        "gallery" to listOf("com.miui.gallery", "com.google.android.apps.photos", "com.sec.android.gallery3d", "com.android.gallery3d"),
        "photos" to listOf("com.miui.gallery", "com.google.android.apps.photos", "com.sec.android.gallery3d"),
        "photo" to listOf("com.miui.gallery", "com.google.android.apps.photos"),
        "گالری" to listOf("com.miui.gallery", "com.google.android.apps.photos", "com.sec.android.gallery3d"),
        "عکس" to listOf("com.miui.gallery", "com.google.android.apps.photos", "com.sec.android.gallery3d"),

        // Camera
        "camera" to listOf("com.android.camera", "com.google.android.GoogleCamera", "org.codeaurora.snapcam", "com.sec.android.app.camera"),
        "دوربین" to listOf("com.android.camera", "com.google.android.GoogleCamera", "org.codeaurora.snapcam", "com.sec.android.app.camera"),
        "pxlcam" to listOf("com.google.android.GoogleCamera", "com.android.camera"),

        // Xiaomi & System
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
        "mi video" to listOf("com.miui.videoplayer"),
        "game center" to listOf("com.xiaomi.glgm"),
        "security" to listOf("com.miui.securitycenter"),
        "امنیت" to listOf("com.miui.securitycenter"),
        "settings" to listOf("com.android.settings"),
        "تنظیمات" to listOf("com.android.settings"),
        "cleaner" to listOf("com.miui.cleanmaster"),
        "xiaomi home" to listOf("com.xiaomi.smarthome"),
        "file manager" to listOf("com.mi.android.globalFileexplorer", "com.google.android.documentsui"),
        "فایل منیجر" to listOf("com.mi.android.globalFileexplorer", "com.google.android.documentsui"),
        "مدیریت فایل" to listOf("com.mi.android.globalFileexplorer", "com.google.android.documentsui"),
        "فایل" to listOf("com.mi.android.globalFileexplorer", "com.google.android.documentsui"),
        "calendar" to listOf("com.xiaomi.calendar", "com.google.android.calendar"),
        "تقویم" to listOf("com.xiaomi.calendar", "com.google.android.calendar"),
        "notes" to listOf("com.miui.notes", "com.google.android.keep"),
        "یادداشت" to listOf("com.miui.notes", "com.google.android.keep"),
        "notepad" to listOf("com.miui.notes", "com.google.android.keep"),
        "chrome" to listOf("com.android.chrome"),
        "کروم" to listOf("com.android.chrome"),
        "play store" to listOf("com.android.vending"),
        "گوگل پلی" to listOf("com.android.vending"),
        "youtube" to listOf("com.google.android.youtube"),
        "یوتیوب" to listOf("com.google.android.youtube"),
        "yt music" to listOf("com.google.android.apps.youtube.music"),
        "یوتیوب موزیک" to listOf("com.google.android.apps.youtube.music"),

        // Social, Media & Tools
        "telegram" to listOf("org.telegram.messenger", "org.telegram.plus", "org.thunderdog.challegram"),
        "تلگرام" to listOf("org.telegram.messenger", "org.telegram.plus", "org.thunderdog.challegram"),
        "instagram" to listOf("com.instagram.android"),
        "اینستاگرام" to listOf("com.instagram.android"),
        "اینستا" to listOf("com.instagram.android"),
        "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "واتساپ" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "واتس اپ" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "واتس‌اپ" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "snapchat" to listOf("com.snapchat.android"),
        "اسنپ چت" to listOf("com.snapchat.android"),
        "twitter" to listOf("com.twitter.android"),
        "توییتر" to listOf("com.twitter.android"),
        "x" to listOf("com.twitter.android"),
        "ایکس" to listOf("com.twitter.android"),
        "threads" to listOf("com.instagram.barcelona"),
        "تردز" to listOf("com.instagram.barcelona"),
        "igap" to listOf("net.iGap"),
        "آی گپ" to listOf("net.iGap"),
        "ای گپ" to listOf("net.iGap"),
        "netflix" to listOf("com.netflix.mediaclient"),
        "نتفلیکس" to listOf("com.netflix.mediaclient"),
        "mx player" to listOf("com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.pro"),
        "vlc" to listOf("org.videolan.vlc"),
        "zapya" to listOf("com.dewmobile.kuaiya.play"),
        "زاپیا" to listOf("com.dewmobile.kuaiya.play"),
        "adm" to listOf("com.dv.adm"),
        "video to mp3" to listOf("com.springtech.video.to.mp3", "mp3videoconverter.video.cutter"),
        "video compressor" to listOf("com.video.compressor"),
        "utorrent" to listOf("com.utorrent.client"),
        "تورنت" to listOf("com.utorrent.client"),
        "framextv" to listOf("com.framextv.app"),
        "webnovel" to listOf("com.qidian.Int.reader"),
        "وب ناول" to listOf("com.qidian.Int.reader"),
        "perfect piano" to listOf("com.gamestar.perfectpiano"),
        "پیانو" to listOf("com.gamestar.perfectpiano"),
        "ytdlnis" to listOf("com.deniscerri.ytdl"),
        "kiwi" to listOf("com.kiwibrowser.browser"),
        "holkimanyus" to listOf("com.holkimanyus"),
        "chess" to listOf("com.chess", "uk.co.aifactory.chessfree"),
        "شطرنج" to listOf("com.chess", "uk.co.aifactory.chessfree"),

        // AI, Dev & Finance
        "deepseek" to listOf("com.deepseek.chat"),
        "دیپ سیک" to listOf("com.deepseek.chat"),
        "دیپسیک" to listOf("com.deepseek.chat"),
        "termux" to listOf("com.termux"),
        "ترموکس" to listOf("com.termux"),
        "termux api" to listOf("com.termux.api"),
        "termius" to listOf("com.server.auditor.ssh.client"),
        "ترمیوس" to listOf("com.server.auditor.ssh.client"),
        "trust wallet" to listOf("com.wallet.crypto.trustapp"),
        "تراست ولت" to listOf("com.wallet.crypto.trustapp"),
        "revolut" to listOf("com.revolut.revolut"),
        "metatrader" to listOf("net.metaquotes.metatrader5"),
        "metatrader 5" to listOf("net.metaquotes.metatrader5"),
        "mt5" to listOf("net.metaquotes.metatrader5"),
        "متاتریدر" to listOf("net.metaquotes.metatrader5"),
        "bitpin" to listOf("ir.bitpin.app"),
        "بیت پین" to listOf("ir.bitpin.app"),
        "blu" to listOf("com.baman.blu"),
        "بلو" to listOf("com.baman.blu"),
        "بلوبانک" to listOf("com.baman.blu"),
        "duolingo" to listOf("com.duolingo"),
        "دولینگو" to listOf("com.duolingo"),
        "common app" to listOf("org.commonapp.mobile"),
        "connect" to listOf("com.garmin.android.apps.connectmobile"),
        "fastdic" to listOf("com.fastdic"),
        "فست دیک" to listOf("com.fastdic"),
        "دیکشنری" to listOf("com.fastdic"),

        // Iranian Apps
        "myket" to listOf("ir.mservices.market"),
        "مایکت" to listOf("ir.mservices.market"),
        "digikala" to listOf("com.digikala"),
        "دیجی کالا" to listOf("com.digikala"),
        "my irancell" to listOf("com.myirancell"),
        "ایرانسل من" to listOf("com.myirancell"),
        "ایرانسل" to listOf("com.myirancell"),
        "my mci" to listOf("ir.mci.ecareapp"),
        "همراه من" to listOf("ir.mci.ecareapp"),
        "snapp" to listOf("cab.snapp.passenger"),
        "اسنپ" to listOf("cab.snapp.passenger"),
        "torob" to listOf("ir.torob"),
        "ترب" to listOf("ir.torob"),
        "neshan" to listOf("org.rajman.neshan.traffic.tehran.navigator"),
        "نشان" to listOf("org.rajman.neshan.traffic.tehran.navigator"),
        "esam" to listOf("ir.esam"),
        "ایسام" to listOf("ir.esam"),
        "hampa" to listOf("ir.hampa"),
        "همپا" to listOf("ir.hampa"),
        "daneshjooyar" to listOf("com.daneshjooyar"),
        "دانشجویار" to listOf("com.daneshjooyar"),
        "mashhad map" to listOf("ir.mashhad.map"),
        "نقشه همراه مشهد" to listOf("ir.mashhad.map")
    )

    fun normalizeAlphanumeric(s: String): String {
        return s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9\u0600-\u06FF]"), "")
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

    /**
     * Dynamically scan and index all installed launchable applications.
     */
    fun refreshInstalledApps(context: Context): List<AppEntry> {
        try {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val launcherList = pm.queryIntentActivities(mainIntent, 0)
            val seenPackages = mutableSetOf<String>()
            val entries = mutableListOf<AppEntry>()

            for (ri in launcherList) {
                val pkg = ri.activityInfo.packageName
                if (pkg.isNullOrBlank() || seenPackages.contains(pkg)) continue
                seenPackages.add(pkg)
                val label = try {
                    ri.loadLabel(pm).toString()
                } catch (_: Exception) {
                    pkg
                }
                val norm = normalizeAlphanumeric(label)
                val token = pkg.substringAfterLast(".").lowercase(Locale.ROOT)
                entries.add(AppEntry(label, norm, pkg, token))
            }

            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in installed) {
                val pkg = app.packageName
                if (pkg.isNullOrBlank() || seenPackages.contains(pkg)) continue
                if (pm.getLaunchIntentForPackage(pkg) != null) {
                    seenPackages.add(pkg)
                    val label = try {
                        pm.getApplicationLabel(app).toString()
                    } catch (_: Exception) {
                        pkg
                    }
                    val norm = normalizeAlphanumeric(label)
                    val token = pkg.substringAfterLast(".").lowercase(Locale.ROOT)
                    entries.add(AppEntry(label, norm, pkg, token))
                }
            }

            dynamicAppIndex.clear()
            dynamicAppIndex.addAll(entries)
            Log.i(TAG, "Dynamic index refreshed with ${dynamicAppIndex.size} launchable apps")
            return dynamicAppIndex
        } catch (e: Exception) {
            Log.w(TAG, "refreshInstalledApps error: ${e.message}")
            return dynamicAppIndex
        }
    }

    /**
     * Asynchronously sync installed apps list to the remote or local VPS server.
     */
    fun syncAppsToServer(context: Context, serverUrl: String) {
        Thread {
            try {
                if (dynamicAppIndex.isEmpty()) {
                    refreshInstalledApps(context)
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
                    sb.append("""{"label":"$escapedLabel","package":"${entry.packageName}"}""")
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
     * Resolves query to an installed app package using Layer A (dynamic scan) and Layer B (aliases),
     * and executes the launch immediately using both startActivity and PendingIntent.
     */
    fun launchApp(context: Context, query: String): String {
        return try {
            val pm = context.packageManager
            if (dynamicAppIndex.isEmpty()) {
                refreshInstalledApps(context)
            }

            var clean = query.trim().lowercase(Locale.ROOT)
            val stripPrefixes = arrayOf(
                "open", "launch", "start", "run", "play", "bring up", "show",
                "باز کن", "بازکن", "اجرا کن", "اجراکن", "بالا بیار", "برنامه", "بازی",
                "لطفاً", "لطفا", "please"
            )
            for (p in stripPrefixes) {
                if (clean.startsWith(p)) {
                    clean = clean.removePrefix(p).trim()
                }
                if (clean.endsWith(p)) {
                    clean = clean.removeSuffix(p).trim()
                }
            }

            val normQuery = normalizeAlphanumeric(clean)

            // Special Case: Camera explicitly requested
            if (clean in setOf("camera", "دوربین", "pxlcam")) {
                val camPackages = listOf("com.android.camera", "com.google.android.GoogleCamera", "org.codeaurora.snapcam", "com.sec.android.app.camera")
                for (p in camPackages) {
                    val res = tryLaunchPackage(context, p, "Camera")
                    if (res != null) return res
                }
                try {
                    val cameraIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    context.startActivity(cameraIntent)
                    return """{"success": true, "intent": "STILL_IMAGE_CAMERA", "label": "Camera"}"""
                } catch (_: Exception) {}
            }

            // Layer B: System & Bilingual Aliases check
            val candidatePkgs = systemAliases[clean] ?: systemAliases[normQuery]
            if (candidatePkgs != null) {
                for (pkg in candidatePkgs) {
                    val res = tryLaunchPackage(context, pkg, clean)
                    if (res != null) return res
                }
            }

            // Layer A: Dynamic Package Index Matching
            // 1. Exact case-insensitive label match
            var matched = dynamicAppIndex.firstOrNull { it.label.equals(clean, ignoreCase = true) }

            // 2. Normalized alphanumeric match
            if (matched == null && normQuery.isNotEmpty()) {
                matched = dynamicAppIndex.firstOrNull { it.normalizedLabel == normQuery }
            }

            // 3. Prefix or token match
            if (matched == null && normQuery.length >= 3) {
                matched = dynamicAppIndex.firstOrNull {
                    it.normalizedLabel.startsWith(normQuery) || it.shortPackageToken == normQuery
                }
            }

            // 4. Substring match
            if (matched == null && normQuery.length >= 3) {
                matched = dynamicAppIndex.firstOrNull {
                    it.normalizedLabel.contains(normQuery) || it.shortPackageToken.contains(normQuery)
                }
            }

            // 5. Levenshtein Fuzzy match (threshold >= 0.72)
            if (matched == null && normQuery.length >= 3) {
                var bestScore = 0.0
                var bestCandidate: AppEntry? = null
                for (entry in dynamicAppIndex) {
                    val scoreLabel = levenshteinSimilarity(entry.normalizedLabel, normQuery)
                    val scoreToken = levenshteinSimilarity(entry.shortPackageToken, normQuery)
                    val maxScore = maxOf(scoreLabel, scoreToken)
                    if (maxScore > bestScore) {
                        bestScore = maxScore
                        bestCandidate = entry
                    }
                }
                if (bestScore >= 0.72) {
                    matched = bestCandidate
                    Log.i(TAG, "Fuzzy match found for '$query' -> '${matched?.label}' (score: $bestScore)")
                }
            }

            if (matched != null) {
                val res = tryLaunchPackage(context, matched.packageName, matched.label)
                if (res != null) return res
            }

            // If not found in dynamic index, try package directly if query looks like package name
            if (clean.contains(".")) {
                val res = tryLaunchPackage(context, clean, clean)
                if (res != null) return res
            }

            """{"success": false, "error": "App not found for query: $query"}"""
        } catch (e: Exception) {
            Log.e(TAG, "launchApp exception: ${e.message}", e)
            """{"success": false, "error": "${e.message}"}"""
        }
    }

    private fun tryLaunchPackage(context: Context, packageName: String, label: String): String? {
        return try {
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            } ?: return null

            // Background launching on Android 14+ / Xiaomi HyperOS
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

            """{"success": true, "package": "$packageName", "label": "$label"}"""
        } catch (e: Exception) {
            null
        }
    }

    fun listInstalledApps(context: Context): String {
        return try {
            if (dynamicAppIndex.isEmpty()) {
                refreshInstalledApps(context)
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
