package com.sajjad.jarvis

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Built-in native Android MediaPlayer engine with full MediaSessionCompat & NotificationCompat.MediaStyle support.
 * Guarantees zero "ghost player" conflicts by enforcing strict AudioFocus discipline.
 */
object JarvisLocalAudioPlayer {

    private const val TAG = "JarvisLocalAudioPlayer"
    private const val CHANNEL_ID = "jarvis_media_playback"
    private const val NOTIFICATION_ID = 202611

    const val ACTION_LOCAL_MEDIA_CONTROL = "com.sajjad.jarvis.LOCAL_MEDIA_CONTROL"
    const val EXTRA_MEDIA_CMD = "extra_media_cmd"

    private var mediaPlayer: MediaPlayer? = null
    private var currentQueue: List<LocalAudioTrack> = emptyList()
    private var currentIndex: Int = -1
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var appContext: Context? = null
    private var mediaSession: MediaSessionCompat? = null
    private var notificationManager: NotificationManager? = null

    @Volatile private var isDucked: Boolean = false
    @Volatile private var wasPlayingBeforeTransientLoss: Boolean = false

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.i(TAG, "AudioFocus permanent loss: pausing player and releasing focus")
                wasPlayingBeforeTransientLoss = false
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.i(TAG, "AudioFocus transient loss: pausing player")
                wasPlayingBeforeTransientLoss = isPlaying()
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.i(TAG, "AudioFocus transient can duck: ducking to 20%")
                setVolumeInternal(0.20f)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.i(TAG, "AudioFocus gained: restoring volume")
                setVolumeInternal(if (isDucked) 0.12f else 1.0f)
                if (wasPlayingBeforeTransientLoss) {
                    wasPlayingBeforeTransientLoss = false
                    appContext?.let { resume(it) }
                }
            }
        }
    }

    private fun initMediaSession(context: Context) {
        if (mediaSession != null) return
        val ctx = context.applicationContext
        appContext = ctx
        notificationManager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Jarvis Media Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Lockscreen and drawer controls for Jarvis music playback"
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }

        mediaSession = MediaSessionCompat(ctx, "JarvisMediaSession").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    resume(appContext)
                }

                override fun onPause() {
                    pause()
                }

                override fun onSkipToNext() {
                    next(appContext)
                }

                override fun onSkipToPrevious() {
                    previous(appContext)
                }

                override fun onStop() {
                    stop()
                }

                override fun onSeekTo(pos: Long) {
                    seekTo(pos.toInt())
                }
            })
            isActive = true
        }
    }

    fun playQueue(context: Context, tracks: List<LocalAudioTrack>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        initMediaSession(context)
        currentQueue = tracks
        currentIndex = startIndex.coerceIn(0, tracks.size - 1)
        playTrack(context, currentQueue[currentIndex])
    }

    fun playTrack(context: Context, track: LocalAudioTrack) {
        stopPlaybackOnly()
        initMediaSession(context)
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
            setVolumeInternal(if (isDucked) 0.12f else 1.0f)
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING, 0L)
            updateMetadata(track)
            postMediaNotification(context, track, isPlaying = true)
            Log.i(TAG, "JarvisLocalAudioPlayer playing: '${track.title}' by '${track.artist}'")
        } catch (e: Exception) {
            Log.e(TAG, "JarvisLocalAudioPlayer failed to play '${track.title}': ${e.message}")
            updatePlaybackState(PlaybackStateCompat.STATE_ERROR, 0L)
        }
    }

    fun pause() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.pause()
                    updatePlaybackState(PlaybackStateCompat.STATE_PAUSED, it.currentPosition.toLong())
                    getCurrentTrack()?.let { track ->
                        appContext?.let { ctx -> postMediaNotification(ctx, track, isPlaying = false) }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    fun resume(context: Context? = null) {
        try {
            val ctx = context ?: appContext
            requestAudioFocus()
            mediaPlayer?.let {
                if (!it.isPlaying) {
                    it.start()
                    setVolumeInternal(if (isDucked) 0.12f else 1.0f)
                    updatePlaybackState(PlaybackStateCompat.STATE_PLAYING, it.currentPosition.toLong())
                    getCurrentTrack()?.let { track ->
                        ctx?.let { c -> postMediaNotification(c, track, isPlaying = true) }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    fun next(context: Context? = null) {
        if (currentQueue.isEmpty()) return
        currentIndex = (currentIndex + 1) % currentQueue.size
        val ctx = context ?: appContext
        ctx?.let { playTrack(it, currentQueue[currentIndex]) }
    }

    fun previous(context: Context? = null) {
        if (currentQueue.isEmpty()) return
        currentIndex = if (currentIndex - 1 < 0) currentQueue.size - 1 else currentIndex - 1
        val ctx = context ?: appContext
        ctx?.let { playTrack(it, currentQueue[currentIndex]) }
    }

    fun seekBy(ms: Int) {
        try {
            mediaPlayer?.let {
                val newPos = (it.currentPosition + ms).coerceIn(0, it.duration)
                it.seekTo(newPos)
                val state = if (it.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
                updatePlaybackState(state, newPos.toLong())
            }
        } catch (_: Exception) {}
    }

    fun seekTo(positionMs: Int) {
        try {
            mediaPlayer?.let {
                val newPos = positionMs.coerceIn(0, it.duration)
                it.seekTo(newPos)
                val state = if (it.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
                updatePlaybackState(state, newPos.toLong())
            }
        } catch (_: Exception) {}
    }

    fun setDucked(ducked: Boolean) {
        isDucked = ducked
        if (ducked) {
            setVolumeInternal(0.12f)
        } else {
            setVolumeInternal(1.0f)
        }
    }

    private fun setVolumeInternal(vol: Float) {
        try {
            mediaPlayer?.setVolume(vol, vol)
        } catch (_: Exception) {}
    }

    private fun stopPlaybackOnly() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
            mediaPlayer = null
        } catch (_: Exception) {}
    }

    fun stop() {
        stopPlaybackOnly()
        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED, 0L)
        abandonAudioFocus()
        notificationManager?.cancel(NOTIFICATION_ID)
    }

    fun release() {
        stop()
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
            mediaSession = null
        } catch (_: Exception) {}
    }

    fun isPlaying(): Boolean = try { mediaPlayer?.isPlaying == true } catch (_: Exception) { false }

    fun getCurrentTrack(): LocalAudioTrack? = if (currentIndex in currentQueue.indices) currentQueue[currentIndex] else null

    private fun updatePlaybackState(state: Int, position: Long) {
        val actions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SEEK_TO

        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(state, position, 1.0f)
        mediaSession?.setPlaybackState(stateBuilder.build())
    }

    private fun updateMetadata(track: LocalAudioTrack) {
        val metaBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track.album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, track.duration)
        mediaSession?.setMetadata(metaBuilder.build())
    }

    private fun postMediaNotification(context: Context, track: LocalAudioTrack, isPlaying: Boolean) {
        try {
            val sessionToken = mediaSession?.sessionToken ?: return
            val nm = notificationManager ?: context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            fun buildPendingIntent(cmd: String, requestCode: Int): PendingIntent {
                val intent = Intent(context, JarvisVoiceService::class.java).apply {
                    action = ACTION_LOCAL_MEDIA_CONTROL
                    putExtra(EXTRA_MEDIA_CMD, cmd)
                }
                return PendingIntent.getService(
                    context,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }

            val prevPending = buildPendingIntent("prev", 1)
            val playPausePending = buildPendingIntent(if (isPlaying) "pause" else "play", 2)
            val nextPending = buildPendingIntent("next", 3)
            val stopPending = buildPendingIntent("stop", 4)

            val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            val playPauseTitle = if (isPlaying) "Pause" else "Play"

            val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(sessionToken)
                .setShowActionsInCompactView(0, 1, 2)
                .setShowCancelButton(true)
                .setCancelButtonIntent(stopPending)

            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_jarvis)
                .setContentTitle(track.title)
                .setContentText(track.artist)
                .setSubText(track.album)
                .setStyle(mediaStyle)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(isPlaying)
                .addAction(android.R.drawable.ic_media_previous, "Prev", prevPending)
                .addAction(playPauseIcon, playPauseTitle, playPausePending)
                .addAction(android.R.drawable.ic_media_next, "Next", nextPending)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
                .build()

            nm.notify(NOTIFICATION_ID, notif)
        } catch (e: Exception) {
            Log.w(TAG, "postMediaNotification error: ${e.message}")
        }
    }

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
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            focusRequest = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(audioFocusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(audioFocusChangeListener)
        }
    }
}
