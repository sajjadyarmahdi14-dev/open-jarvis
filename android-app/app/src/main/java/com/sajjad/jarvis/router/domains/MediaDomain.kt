package com.sajjad.jarvis.router.domains

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.sajjad.jarvis.AppLauncher
import com.sajjad.jarvis.LocalCommandRouter.LocalRouteResult
import com.sajjad.jarvis.MediaEngine
import com.sajjad.jarvis.router.NlpNormalizer
import org.json.JSONObject
import java.util.Locale

/**
 * MediaDomain (Tier 1 Zero-Token Engine):
 *
 * Full lifecycle music, audio, and video control:
 * 1. Universal 3-layer transport controls (Next, Previous, Pause, Stop, Resume, Seek, Info).
 * 2. True live device media search (MediaStore.Audio.Media & Video.Media) with Persian-to-Latin transliteration.
 * 3. Never replays old cached tracks on search mismatch; cleanly hands off to YouTube or informs the user.
 */
object MediaDomain {

    private const val TAG = "MediaDomain"

    fun handle(context: Context, rawText: String, clean: String, lower: String, isFa: Boolean): LocalRouteResult {
        val norm = NlpNormalizer.cleanLower(rawText)

        // ---------------------------------------------------------------------
        // 1. Next / Change Song Transport Controls
        // ---------------------------------------------------------------------
        val nextExactWords = setOf("بعدی", "عوض کن", "next", "skip", "رد کن")
        val nextPhrases = listOf(
            "آهنگ رو عوض کن", "موزیک رو عوض کن", "آهنگ بعدی",
            "بزن بعدی", "برو بعدی", "ترک بعدی", "آهنگ بعد", "موزیک بعد",
            "change song", "next track", "next song", "switch song", "skip track"
        )
        if (norm in nextExactWords || nextPhrases.any { norm == it || norm.contains(it) }) {
            MediaEngine.handleMediaControl(context, "next")
            val reply = if (isFa) "آهنگ بعدی پخش شد قربان." else "Skipped to next track, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "media_next")
        }

        // ---------------------------------------------------------------------
        // 2. Previous / Restart Track
        // ---------------------------------------------------------------------
        val prevExactWords = setOf("قبلی", "previous")
        val prevPhrases = listOf(
            "آهنگ قبلی", "موزیک قبلی", "برگرد قبلی", "از اول بزار",
            "از اول پخش کن", "ترک قبلی", "آهنگ قبل", "موزیک قبل",
            "back track", "restart song", "previous track", "previous song", "go back song"
        )
        if (prevExactWords.contains(norm) || prevPhrases.any { norm == it || norm.contains(it) }) {
            MediaEngine.handleMediaControl(context, "previous")
            val reply = if (isFa) "آهنگ قبلی پخش شد قربان." else "Playing previous track, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "media_prev")
        }

        // ---------------------------------------------------------------------
        // 3. Pause / Stop / Mute Media Playback
        // ---------------------------------------------------------------------
        val stopExactWords = setOf("استاپ", "استپ", "پاز", "نگهدار", "بسه", "pause")
        val stopPhrases = listOf(
            "آهنگ رو قطع کن", "موزیک رو قطع کن", "پاز کن",
            "قطع کن آهنگ رو", "موزیک رو خاموش کن",
            "قطع کن موزیک رو", "آهنگ متوقف", "موزیک قطع",
            "stop music", "stop playing", "pause music", "pause playback", "stop playback"
        )
        if (norm in stopExactWords || stopPhrases.any { norm == it || norm.contains(it) }) {
            MediaEngine.handleMediaControl(context, "pause")
            val reply = if (isFa) "پخش موسیقی متوقف شد قربان." else "Music playback paused, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "media_pause")
        }

        // ---------------------------------------------------------------------
        // 4. Resume / Play Music
        // ---------------------------------------------------------------------
        val resumeExactWords = setOf("ادامه بده", "پلی کن", "resume")
        val resumePhrases = listOf(
            "پخش کن آهنگ رو", "موزیک رو ادامه بده",
            "ادامه موزیک", "ادامه آهنگ", "پخش مجدد آهنگ", "پخش مجدد",
            "continue music", "resume music", "keep playing", "resume playback"
        )
        if (norm in resumeExactWords || resumePhrases.any { norm == it || norm.contains(it) }) {
            MediaEngine.handleMediaControl(context, "play")
            val reply = if (isFa) "پخش موسیقی ادامه یافت قربان." else "Resuming music playback, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "media_resume")
        }

        // ---------------------------------------------------------------------
        // 5. Seek Forward / Backward (<N> Seconds)
        // ---------------------------------------------------------------------
        val seekMatch = Regex("(\\d+)\\s*(ثانیه)?\\s*(بزن|برو)?\\s*(جلو|عقب)").find(norm)
            ?: Regex("(skip|jump|seek|forward|back)\\s+(\\d+)\\s*(second|seconds|sec)").find(norm)
        if (seekMatch != null) {
            val num = NlpNormalizer.extractFirstNumber(norm) ?: 15
            val isBack = norm.contains("عقب") || norm.contains("back")
            val cmd = if (isBack) "seek_backward" else "seek_forward"
            MediaEngine.handleMediaControl(context, cmd, num)
            val reply = if (isFa) {
                if (isBack) "$num ثانیه به عقب برگشتیم قربان." else "$num ثانیه به جلو رفتیم قربان."
            } else {
                if (isBack) "Skipped back $num seconds, sir." else "Fast-forwarded $num seconds, sir."
            }
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "media_seek")
        }

        // ---------------------------------------------------------------------
        // 6. Active Song / Track Info Query
        // ---------------------------------------------------------------------
        val infoWords = listOf(
            "این چه آهنگیه", "اسم این آهنگ چیه", "چه آهنگی داره پخش میشه",
            "خواننده این کیه", "اسم این آهنگ", "این چه ترانه ایه",
            "what song is playing", "what is this song", "what song is this",
            "current song", "song name", "who is singing"
        )
        if (infoWords.any { norm.contains(it) }) {
            val info = MediaEngine.getActiveMediaInfo(context)
            val reply = if (info != null && (info.first.isNotBlank() || info.second.isNotBlank())) {
                val t = info.first.ifBlank { "Unknown Track" }
                val a = info.second.ifBlank { "Unknown Artist" }
                if (isFa) "آهنگ در حال پخش $t از $a است قربان." else "Currently playing $t by $a, sir."
            } else {
                if (isFa) "در حال حاضر اطلاعات قطعه در حال پخش در دسترس نیست قربان." else "Could not detect active track metadata, sir."
            }
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "media_info")
        }

        // ---------------------------------------------------------------------
        // 7. Play Specific Song / Artist (True live MediaStore search)
        // ---------------------------------------------------------------------
        var specificSongQuery: String? = null
        if (isFa) {
            val faPrefix = Regex("^(آهنگ|موزیک|ترانه|برام)\\s+(.+?)\\s*(رو|را| رو| را)?\\s*(پخش کن|بذار|بزار|پلی کن)$").find(clean)
                ?: Regex("^پخش کن\\s+(?:آهنگ|موزیک)?\\s*(.+)$").find(clean)
            if (faPrefix != null) {
                specificSongQuery = faPrefix.groupValues[faPrefix.groupValues.size - 1].trim()
            }
        } else {
            val enPrefix = Regex("^(?:play song|play music|play track|play)\\s+(.+)$").find(lower)
            if (enPrefix != null) {
                specificSongQuery = enPrefix.groupValues[1].removePrefix("the ").trim()
            }
        }

        val genericTokens = setOf(
            "", "music", "song", "some music", "a song", "track",
            "آهنگ", "موزیک", "ترانه", "یه آهنگ", "یه موزیک", "یک آهنگ"
        )

        if (!specificSongQuery.isNullOrBlank() && specificSongQuery !in genericTokens) {
            val cleanQuery = specificSongQuery.removePrefix("the ").trim()
            val localTracks = MediaEngine.findLocalAudio(context, cleanQuery)
            if (localTracks.isNotEmpty()) {
                val topTrack = localTracks.first()
                MediaEngine.playLocalAudioInApp(context, topTrack)
                val reply = if (isFa) "در حال پخش آهنگ ${topTrack.title} قربان." else "Playing ${topTrack.title}, sir."
                return LocalRouteResult(handled = true, spokenFeedback = reply, category = "media_play_song")
            } else {
                // NEVER replay old song! Launch YouTube search intent cleanly
                val searchUri = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(cleanQuery)}")
                val ytIntent = Intent(Intent.ACTION_VIEW, searchUri).apply {
                    setPackage("com.google.android.youtube")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val launched = AppLauncher.launchWithOverlayFallback(context, ytIntent)
                val reply = if (isFa) {
                    "آهنگ $cleanQuery روی گوشی شما پیدا نشد قربان؛ در حال جستجو در یوتیوب..."
                } else {
                    "Could not find $cleanQuery locally, sir. Searching YouTube..."
                }
                return LocalRouteResult(handled = true, spokenFeedback = reply, category = "media_play_external")
            }
        }

        // ---------------------------------------------------------------------
        // 8. General Play Music (No specific query specified)
        // ---------------------------------------------------------------------
        val generalPlayWords = listOf(
            "play music", "play some music", "play song", "start music",
            "آهنگ پخش کن", "موزیک بزار", "موسیقی پخش کن", "آهنگ بزار", "یه آهنگ پخش کن", "موزیک پخش کن"
        )
        if (generalPlayWords.any { norm == it || norm.contains(it) }) {
            MediaEngine.handleMediaPlay(context, "audio", "", null, "local", false)
            val reply = if (isFa) "در حال پخش موسیقی قربان." else "Playing music, sir."
            return LocalRouteResult(handled = true, spokenFeedback = reply, category = "media_play_general")
        }

        return LocalRouteResult(handled = false)
    }
}
