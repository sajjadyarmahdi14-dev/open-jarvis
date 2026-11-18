package com.sajjad.jarvis

import android.Manifest
import android.app.SearchManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

data class LocalAudioTrack(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val path: String?
)

data class LocalVideoTrack(
    val id: Long,
    val uri: Uri,
    val title: String,
    val duration: Long,
    val dateAdded: Long,
    val path: String?
)

/**
 * Universal Media Engine for J.A.R.V.I.S.
 *
 * Covers:
 * 1. Live MediaStore indexing for local audio & video
 * 2. Direct URI playback with read permissions (FLAG_GRANT_READ_URI_PERMISSION)
 * 3. Built-in native background fallback player (JarvisLocalAudioPlayer)
 * 4. Smart routing (local first vs explicit streaming apps)
 * 5. Complete media key dispatch (ACTION_DOWN + ACTION_UP)
 * 6. Active playback verification loop & Accessibility auto-play
 */
object MediaEngine {

    private const val TAG = "MediaEngine"
    private val scope = CoroutineScope(Dispatchers.Main)

    // -------------------------------------------------------------------------
    // 1. Live MediaStore Querying: Audio & Video
    // -------------------------------------------------------------------------

    fun findLocalAudio(
        context: Context,
        query: String?,
        artist: String? = null,
        shuffleAll: Boolean = false
    ): List<LocalAudioTrack> {
        val tracks = mutableListOf<LocalAudioTrack>()
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        if (!hasPermission) {
            Log.w(TAG, "Audio read permission not granted for findLocalAudio")
            return tracks
        }

        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.DURATION} > 15000"
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        try {
            context.contentResolver.query(uri, projection, selection, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val title = cursor.getString(titleCol) ?: "Unknown Track"
                    val art = cursor.getString(artistCol) ?: "Unknown Artist"
                    val alb = cursor.getString(albumCol) ?: "Unknown Album"
                    val dur = cursor.getLong(durCol)
                    val path = cursor.getString(dataCol)
                    val trackUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

                    tracks.add(LocalAudioTrack(id, trackUri, title, art, alb, dur, path))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "findLocalAudio error: ${e.message}", e)
        }

        if (tracks.isEmpty()) return tracks

        val cleanQuery = query?.trim()?.lowercase(Locale.ROOT)
        val cleanArtist = artist?.trim()?.lowercase(Locale.ROOT)

        val genericQueries = setOf(
            "", "music", "play music", "play", "song", "play song", "songs",
            "اهنگ", "آهنگ", "موزیک", "اهنگ پخش کن", "پخش آهنگ", "آهنگ پخش کن",
            "یک آهنگ پخش کن", "یه اهنگ بزار", "موزیک بزار", "پخش موزیک", "موزیک پخش کن"
        )

        if (cleanQuery.isNullOrBlank() || cleanQuery in genericQueries) {
            return if (shuffleAll) tracks.shuffled() else tracks
        }

        val filtered = tracks.filter { track ->
            val t = track.title.lowercase(Locale.ROOT)
            val a = track.artist.lowercase(Locale.ROOT)
            val al = track.album.lowercase(Locale.ROOT)
            val p = (track.path ?: "").lowercase(Locale.ROOT)

            if (!cleanArtist.isNullOrBlank() && !a.contains(cleanArtist)) {
                return@filter false
            }

            t.contains(cleanQuery) || a.contains(cleanQuery) || al.contains(cleanQuery) || p.contains(cleanQuery)
        }

        if (filtered.isNotEmpty()) return filtered

        val queryWords = cleanQuery.split(" ", "_", "-").filter { it.length > 2 }
        if (queryWords.isNotEmpty()) {
            val partial = tracks.filter { track ->
                val full = "${track.title} ${track.artist} ${track.album}".lowercase(Locale.ROOT)
                queryWords.any { word -> full.contains(word) }
            }
            if (partial.isNotEmpty()) return partial
        }

        return tracks
    }

    fun findLocalVideo(
        context: Context,
        query: String?,
        latest: Boolean = false
    ): LocalVideoTrack? {
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        if (!hasPermission) {
            Log.w(TAG, "Video read permission not granted for findLocalVideo")
            return null
        }

        val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DATA
        )
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        val videos = mutableListOf<LocalVideoTrack>()
        try {
            context.contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val title = cursor.getString(titleCol) ?: "Video"
                    val dur = cursor.getLong(durCol)
                    val dateAdded = cursor.getLong(dateCol)
                    val path = cursor.getString(dataCol)
                    val videoUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    videos.add(LocalVideoTrack(id, videoUri, title, dur, dateAdded, path))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "findLocalVideo error: ${e.message}", e)
        }

        if (videos.isEmpty()) return null

        val cleanQuery = query?.trim()?.lowercase(Locale.ROOT)
        if (latest || cleanQuery.isNullOrBlank() || cleanQuery in setOf("latest", "last", "video", "آخرین ویدیو", "ویدیو", "پخش ویدیو")) {
            return videos.firstOrNull()
        }

        return videos.firstOrNull {
            it.title.lowercase(Locale.ROOT).contains(cleanQuery) || (it.path ?: "").lowercase(Locale.ROOT).contains(cleanQuery)
        } ?: videos.firstOrNull()
    }

    // -------------------------------------------------------------------------
    // 2. Direct URI Playback with Read Grants
    // -------------------------------------------------------------------------

    fun playLocalAudioInApp(context: Context, track: LocalAudioTrack, targetApp: String? = null): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(track.uri, "audio/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                if (!targetApp.isNullOrBlank()) {
                    setPackage(targetApp)
                }
            }
            AppLauncher.launchWithOverlayFallback(context, intent)
            Log.i(TAG, "playLocalAudioInApp dispatched: uri=${track.uri} package=$targetApp")
            true
        } catch (e: Exception) {
            Log.e(TAG, "playLocalAudioInApp failed: ${e.message}")
            false
        }
    }

    fun playLocalVideoInApp(context: Context, video: LocalVideoTrack, targetApp: String? = null): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(video.uri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                if (!targetApp.isNullOrBlank()) {
                    setPackage(targetApp)
                }
            }
            if (targetApp.isNullOrBlank()) {
                val preferredVideoPlayers = listOf(
                    "org.videolan.vlc",
                    "com.mxtech.videoplayer.ad",
                    "com.mxtech.videoplayer.pro",
                    "com.miui.videoplayer"
                )
                val pm = context.packageManager
                for (pkg in preferredVideoPlayers) {
                    try {
                        pm.getPackageInfo(pkg, 0)
                        intent.setPackage(pkg)
                        break
                    } catch (_: Exception) {}
                }
            }
            AppLauncher.launchWithOverlayFallback(context, intent)
            Log.i(TAG, "playLocalVideoInApp dispatched: uri=${video.uri}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "playLocalVideoInApp failed: ${e.message}")
            false
        }
    }

    // -------------------------------------------------------------------------
    // 3. Complete Media Key Dispatch (ACTION_DOWN + ACTION_UP)
    // -------------------------------------------------------------------------

    fun dispatchMediaKey(context: Context, keyCode: Int) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val up = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            am.dispatchMediaKeyEvent(down)
            am.dispatchMediaKeyEvent(up)
            Log.i(TAG, "Dispatched media key code: $keyCode (ACTION_DOWN + ACTION_UP)")
        } catch (e: Exception) {
            Log.e(TAG, "dispatchMediaKey failed: ${e.message}")
        }
    }

    /**
     * Layer 3: Instant Subtle Capture Chime (90ms soft sci-fi dual-tone sine chirp at ~25% volume)
     */
    fun playSubtleCaptureChime() {
        scope.launch(Dispatchers.IO) {
            try {
                val sampleRate = 44100
                val durationMs = 90
                val numSamples = (sampleRate * durationMs) / 1000
                val buffer = ShortArray(numSamples)

                for (i in 0 until numSamples) {
                    val t = i.toDouble() / sampleRate
                    val progress = i.toDouble() / numSamples
                    val freq = 880.0 + 880.0 * progress
                    val envelope = Math.sin(Math.PI * progress)
                    val sample = (Math.sin(2.0 * Math.PI * freq * t) * 0.25 * envelope * Short.MAX_VALUE).toInt()
                    buffer[i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }

                val audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(buffer.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                audioTrack.write(buffer, 0, buffer.size)
                audioTrack.play()
                delay(120)
                audioTrack.release()
            } catch (e: Exception) {
                Log.w(TAG, "playSubtleCaptureChime error: ${e.message}")
            }
        }
    }

    // -------------------------------------------------------------------------
    // 4. Active Playback Verification Loop & Accessibility Auto-Play
    // -------------------------------------------------------------------------

    suspend fun verifyAndForcePlayback(
        context: Context,
        localTrack: LocalAudioTrack?,
        appName: String,
        onResult: ((Boolean, String) -> Unit)? = null
    ) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Step 1: Wait 1500ms and check audioManager.isMusicActive
        delay(1500)
        if (am.isMusicActive || JarvisLocalAudioPlayer.isPlaying()) {
            Log.i(TAG, "Playback verified active in $appName on initial check")
            onResult?.invoke(true, "Playing in $appName")
            return
        }

        // Step 2: If false, trigger Accessibility auto-play (click first playable result or play/shuffle button)
        Log.i(TAG, "Music not active yet. Triggering Accessibility auto-play click in $appName...")
        val accessibilityClicked = JarvisAccessibilityService.instance?.clickPlayableMediaOrPlayButton() == true
        Log.i(TAG, "Accessibility auto-play clicked: $accessibilityClicked")

        // Step 3: Wait another 1500ms, dispatch KEYCODE_MEDIA_PLAY (DOWN + UP), and re-check
        delay(1500)
        dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY)
        delay(500)

        if (am.isMusicActive || JarvisLocalAudioPlayer.isPlaying()) {
            Log.i(TAG, "Playback verified active in $appName after Accessibility/MediaKey trigger")
            onResult?.invoke(true, "Playing in $appName")
            return
        }

        // Step 4: If local audio file and isMusicActive is STILL false, take over with JarvisLocalAudioPlayer!
        if (localTrack != null) {
            Log.w(TAG, "OEM app $appName did not start playback. Taking over natively via JarvisLocalAudioPlayer!")
            JarvisLocalAudioPlayer.playTrack(context, localTrack)
            delay(500)
            if (JarvisLocalAudioPlayer.isPlaying()) {
                onResult?.invoke(true, "Playing ${localTrack.title} via J.A.R.V.I.S. Local Audio Player")
                return
            }
        }

        Log.w(TAG, "Playback verification failed in $appName")
        onResult?.invoke(false, "Opened $appName, but playback could not be started automatically")
    }

    // -------------------------------------------------------------------------
    // 5. Smart App Routing (Local First vs Explicit Streaming)
    // -------------------------------------------------------------------------

    fun handleMediaPlay(
        context: Context,
        mediaType: String = "audio",
        query: String? = null,
        artist: String? = null,
        targetApp: String? = null,
        shuffle: Boolean = false,
        onVerification: ((Boolean, String) -> Unit)? = null
    ) {
        val cleanQuery = query?.trim() ?: ""
        val cleanApp = targetApp?.trim()?.lowercase(Locale.ROOT) ?: ""

        val isExplicitStreaming = cleanApp in listOf("youtube", "yt_music", "youtube_music", "spotify", "netflix") ||
                cleanQuery.contains("youtube", ignoreCase = true) ||
                cleanQuery.contains("spotify", ignoreCase = true) ||
                cleanQuery.contains("یوتیوب") ||
                cleanQuery.contains("اسپاتیفای")

        val isVideo = mediaType.equals("video", ignoreCase = true) ||
                cleanQuery.contains("video", ignoreCase = true) ||
                cleanQuery.contains("ویدیو")

        // 1. Video Playback
        if (isVideo) {
            if (isExplicitStreaming || cleanApp.contains("youtube") || cleanApp.contains("netflix")) {
                playStreamingVideo(context, cleanQuery, cleanApp, onVerification)
            } else {
                val isLatest = cleanQuery.contains("latest") || cleanQuery.contains("آخرین")
                val localVid = findLocalVideo(context, cleanQuery, latest = isLatest)
                if (localVid != null) {
                    val appPkg = when (cleanApp) {
                        "vlc" -> "org.videolan.vlc"
                        "mx_player", "mxplayer" -> "com.mxtech.videoplayer.ad"
                        "mi_video" -> "com.miui.videoplayer"
                        else -> null
                    }
                    playLocalVideoInApp(context, localVid, appPkg)
                    scope.launch {
                        verifyAndForcePlayback(context, null, appPkg ?: "Video Player", onVerification)
                    }
                } else {
                    playStreamingVideo(context, cleanQuery, "youtube", onVerification)
                }
            }
            return
        }

        // 2. Audio Playback: Explicit Streaming
        if (isExplicitStreaming) {
            playStreamingMusic(context, cleanQuery, artist, cleanApp, onVerification)
            return
        }

        // 3. Audio Playback: Strict Local First Routing
        val localTracks = findLocalAudio(context, cleanQuery, artist, shuffleAll = shuffle)
        if (localTracks.isNotEmpty()) {
            val chosenTrack = localTracks.first()
            val oemPlayer = "com.miui.player"
            playLocalAudioInApp(context, chosenTrack, oemPlayer)
            scope.launch {
                verifyAndForcePlayback(context, chosenTrack, "Music", onVerification)
            }
        } else {
            // ONLY if no matching song exists on local storage -> automatically fall back to YouTube Music
            Log.i(TAG, "No local tracks found for '$cleanQuery'. Falling back to YouTube Music / YouTube.")
            playStreamingMusic(context, cleanQuery, artist, "yt_music", onVerification)
        }
    }

    private fun playStreamingMusic(
        context: Context,
        query: String,
        artist: String?,
        app: String,
        onVerification: ((Boolean, String) -> Unit)? = null
    ) {
        val cleanQuery = if (!artist.isNullOrBlank() && !query.contains(artist, ignoreCase = true)) {
            "$query $artist".trim()
        } else {
            query.trim()
        }
        val targetPkg = when {
            app.contains("spotify") -> "com.spotify.music"
            app.contains("yt_music") || app.contains("youtube_music") -> "com.google.android.apps.youtube.music"
            else -> {
                val pm = context.packageManager
                try {
                    pm.getPackageInfo("com.google.android.apps.youtube.music", 0)
                    "com.google.android.apps.youtube.music"
                } catch (_: Exception) {
                    "com.google.android.youtube"
                }
            }
        }

        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(SearchManager.QUERY, cleanQuery)
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage(targetPkg)
        }

        AppLauncher.launchWithOverlayFallback(context, intent)
        scope.launch {
            verifyAndForcePlayback(context, null, targetPkg, onVerification)
        }
    }

    private fun playStreamingVideo(
        context: Context,
        query: String,
        app: String,
        onVerification: ((Boolean, String) -> Unit)? = null
    ) {
        val targetPkg = if (app.contains("netflix")) "com.netflix.mediaclient" else "com.google.android.youtube"
        val cleanQuery = query.replace("youtube", "", ignoreCase = true).replace("یوتیوب", "").trim()
        val searchUri = if (cleanQuery.isNotBlank()) {
            Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(cleanQuery)}")
        } else {
            Uri.parse("https://www.youtube.com")
        }
        val intent = Intent(Intent.ACTION_VIEW, searchUri).apply {
            setPackage(targetPkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        AppLauncher.launchWithOverlayFallback(context, intent)
        scope.launch {
            verifyAndForcePlayback(context, null, targetPkg, onVerification)
        }
    }

    // -------------------------------------------------------------------------
    // 6. Media Transport Controls & Volume Management
    // -------------------------------------------------------------------------

    fun handleMediaControl(context: Context, command: String, seconds: Int = 30) {
        val cmd = command.trim().lowercase(Locale.ROOT)
        when (cmd) {
            "play", "resume" -> {
                if (JarvisLocalAudioPlayer.isPlaying()) {
                    JarvisLocalAudioPlayer.resume(context)
                } else {
                    dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY)
                }
            }
            "pause" -> {
                JarvisLocalAudioPlayer.pause()
                dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PAUSE)
            }
            "stop" -> {
                JarvisLocalAudioPlayer.stop()
                dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_STOP)
            }
            "next", "skip" -> {
                if (JarvisLocalAudioPlayer.isPlaying()) {
                    JarvisLocalAudioPlayer.next(context)
                } else {
                    dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
                }
            }
            "previous", "prev" -> {
                if (JarvisLocalAudioPlayer.isPlaying()) {
                    JarvisLocalAudioPlayer.previous(context)
                } else {
                    dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                }
            }
            "seek_forward", "fast_forward" -> {
                if (JarvisLocalAudioPlayer.isPlaying()) {
                    JarvisLocalAudioPlayer.seekBy(seconds * 1000)
                } else {
                    dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
                }
            }
            "seek_backward", "rewind" -> {
                if (JarvisLocalAudioPlayer.isPlaying()) {
                    JarvisLocalAudioPlayer.seekBy(-seconds * 1000)
                } else {
                    dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_REWIND)
                }
            }
            "fullscreen" -> {
                JarvisAccessibilityService.instance?.let {
                    val root = it.rootInActiveWindow
                    val fsNode = it.findNodeByPredicate(root) { n ->
                        val desc = (n.contentDescription?.toString() ?: "").lowercase(Locale.ROOT)
                        val id = (n.viewIdResourceName ?: "").lowercase(Locale.ROOT)
                        desc.contains("fullscreen") || desc.contains("تمام صفحه") || id.contains("fullscreen")
                    }
                    if (fsNode != null) {
                        it.clickNodeOrParent(fsNode)
                    }
                }
            }
        }
    }

    fun handleVolumeControl(
        context: Context,
        action: String,
        percent: Int? = null,
        streamName: String? = "media"
    ) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = when (streamName?.lowercase(Locale.ROOT)) {
            "ring", "ringer" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "call", "voice" -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }
        val maxVol = am.getStreamMaxVolume(stream)
        when (action.lowercase(Locale.ROOT)) {
            "up" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "down" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "mute" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                } else {
                    am.setStreamVolume(stream, 0, AudioManager.FLAG_SHOW_UI)
                }
            }
            "unmute" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                } else {
                    am.setStreamVolume(stream, maxVol / 2, AudioManager.FLAG_SHOW_UI)
                }
            }
            "set_percent", "set" -> {
                if (percent != null) {
                    val target = ((percent.coerceIn(0, 100) / 100.0) * maxVol).toInt()
                    am.setStreamVolume(stream, target, AudioManager.FLAG_SHOW_UI)
                }
            }
        }
    }
}

/**
 * Built-in native Android MediaPlayer queue engine with AudioFocusRequest support.
 * Guarantees local music playback if external OEM music players refuse to start.
 */
object JarvisLocalAudioPlayer {

    private const val TAG = "JarvisLocalAudioPlayer"
    private var mediaPlayer: MediaPlayer? = null
    private var currentQueue: List<LocalAudioTrack> = emptyList()
    private var currentIndex: Int = -1
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    fun playQueue(context: Context, tracks: List<LocalAudioTrack>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        currentQueue = tracks
        currentIndex = startIndex.coerceIn(0, tracks.size - 1)
        playTrack(context, currentQueue[currentIndex])
    }

    fun playTrack(context: Context, track: LocalAudioTrack) {
        stop()
        try {
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            requestAudioFocus()

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(context, track.uri)
                prepare()
                start()
                setOnCompletionListener {
                    next(context)
                }
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "MediaPlayer error: what=$what extra=$extra")
                    true
                }
            }
            Log.i(TAG, "JarvisLocalAudioPlayer playing: '${track.title}' by '${track.artist}'")
        } catch (e: Exception) {
            Log.e(TAG, "JarvisLocalAudioPlayer failed to play '${track.title}': ${e.message}")
        }
    }

    fun pause() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.pause()
            }
        } catch (_: Exception) {}
    }

    fun resume(context: Context? = null) {
        try {
            requestAudioFocus()
            mediaPlayer?.let {
                if (!it.isPlaying) it.start()
            }
        } catch (_: Exception) {}
    }

    fun next(context: Context? = null) {
        if (currentQueue.isEmpty()) return
        currentIndex = (currentIndex + 1) % currentQueue.size
        context?.let { playTrack(it, currentQueue[currentIndex]) }
    }

    fun previous(context: Context? = null) {
        if (currentQueue.isEmpty()) return
        currentIndex = if (currentIndex - 1 < 0) currentQueue.size - 1 else currentIndex - 1
        context?.let { playTrack(it, currentQueue[currentIndex]) }
    }

    fun seekBy(ms: Int) {
        try {
            mediaPlayer?.let {
                val newPos = (it.currentPosition + ms).coerceIn(0, it.duration)
                it.seekTo(newPos)
            }
        } catch (_: Exception) {}
    }

    fun stop() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
            mediaPlayer = null
            abandonAudioFocus()
        } catch (_: Exception) {}
    }

    fun isPlaying(): Boolean = try { mediaPlayer?.isPlaying == true } catch (_: Exception) { false }

    fun getCurrentTrack(): LocalAudioTrack? = if (currentIndex in currentQueue.indices) currentQueue[currentIndex] else null

    private fun requestAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                .build()
            focusRequest = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
    }
}
