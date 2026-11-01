package com.iris.gallery.ui.video

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode

/**
 * A media3 [Player] whose engine is libmpv. Because it speaks the normal Player interface, the whole video screen
 * (controls, seek bar, gestures, PlayerView) works on it without knowing which engine is underneath.
 *
 * Threading: mpv reports events from its own thread; everything is posted to the main thread before it touches state.
 */
@OptIn(UnstableApi::class)
class MpvPlayer(context: Context) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val mpv = MPV()

    @Volatile private var released = false
    private var initFailed = false

    // ---- mirrors of what mpv is doing (main thread only) ----
    private var playWhenReady = false
    private var fileLoaded = false
    private var loading = false
    private var eof = false
    private var pausedForCache = false
    private var durationMs = C.TIME_UNSET
    private var videoW = 0
    private var videoH = 0
    private var repeatMode = Player.REPEAT_MODE_OFF
    private var speed = 1f
    private var volume = 1f
    private var error: PlaybackException? = null
    private var firstFrameShown = false
    private var firstFramePending = false
    private var currentUri: Uri? = null
    private var seekTargetMs = -1L
    private var lastPosMs = 0L

    /** While the seek bar is being dragged: jump to the nearest keyframe (fast). Off = frame-exact. */
    @Volatile var fastSeek = false

    // ---- surface / file hand-over ----
    private var surfaceView: SurfaceView? = null
    private var surfaceReady = false
    private var pendingTarget: String? = null
    private var pendingFd: Int = -1
    private var openFd: Int = -1

    // ---- audio focus + "headphones unplugged" ----
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
        )
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                main.post { if (!released) pause() }
            }
        }
        .build()
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (!released) pause()
        }
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            if (released) return
            runCatching {
                mpv.attachSurface(holder.surface)
                // forces mpv to render into the surface even before a file is playing
                mpv.setOptionString("force-window", "yes")
                surfaceReady = true
                if (pendingTarget != null) startPending() else mpv.setPropertyString("vo", "gpu")
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            if (released) return
            runCatching { mpv.setPropertyString("android-surface-size", "${width}x$height") }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            if (released) return
            surfaceReady = false
            runCatching {
                mpv.setPropertyString("vo", "null")
                mpv.setPropertyString("force-window", "no")
                mpv.detachSurface()
            }
        }
    }

    // ------------------------------------------------------------------ mpv -> state

    private val observer = object : MPV.EventObserver {
        override fun eventProperty(property: String) {
            main.post {
                if (released) return@post
                if (property == "duration") { durationMs = C.TIME_UNSET; invalidateState() }
            }
        }

        override fun eventProperty(property: String, value: Long) {
            main.post {
                if (released) return@post
                when (property) {
                    "dwidth" -> { videoW = value.toInt(); invalidateState() }
                    "dheight" -> { videoH = value.toInt(); invalidateState() }
                }
            }
        }

        override fun eventProperty(property: String, value: Boolean) {
            main.post {
                if (released) return@post
                when (property) {
                    "pause" -> if (fileLoaded && playWhenReady == value) {
                        // mpv paused/resumed by itself (e.g. at the end of the file)
                        playWhenReady = !value
                        invalidateState()
                    }
                    "eof-reached" -> if (eof != value) { eof = value; invalidateState() }
                    "paused-for-cache" -> if (pausedForCache != value) { pausedForCache = value; invalidateState() }
                    "seeking" -> if (!value && seekTargetMs >= 0) { seekTargetMs = -1; invalidateState() }
                }
            }
        }

        override fun eventProperty(property: String, value: String) {}

        override fun eventProperty(property: String, value: Double) {
            main.post {
                if (released) return@post
                if (property == "duration") { durationMs = (value * 1000.0).toLong(); invalidateState() }
            }
        }

        override fun eventProperty(property: String, value: MPVNode) {}

        override fun event(eventId: Int, data: MPVNode) {
            val detail = runCatching { data.toJson() }.getOrNull()
            main.post {
                if (released) return@post
                when (eventId) {
                    8 -> onFileLoaded()                    // MPV_EVENT_FILE_LOADED
                    7 -> onEndFile(detail)                 // MPV_EVENT_END_FILE
                    21 -> {                                // MPV_EVENT_PLAYBACK_RESTART
                        seekTargetMs = -1
                        if (!firstFrameShown && fileLoaded) { firstFrameShown = true; firstFramePending = true }
                        invalidateState()
                    }
                }
            }
        }
    }

    init {
        try {
            mpv.create(appContext)
            // phone-friendly defaults (same family of options the official mpv-android uses)
            mpv.setOptionString("profile", "fast")
            mpv.setOptionString("vo", "gpu")
            mpv.setOptionString("gpu-context", "android")
            mpv.setOptionString("opengl-es", "yes")
            mpv.setOptionString("hwdec", "mediacodec,mediacodec-copy")
            mpv.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
            mpv.setOptionString("ao", "audiotrack,opensles")
            mpv.setOptionString("audio-set-media-role", "yes")
            // better scaling than the "fast" profile when a frame has to be resized
            mpv.setOptionString("scale", "spline36")
            mpv.setOptionString("dscale", "mitchell")
            mpv.setOptionString("cscale", "spline36")
            // keep the last frame at the end so the user can seek back / replay
            mpv.setOptionString("keep-open", "yes")
            mpv.setOptionString("pause", "yes")
            mpv.setOptionString("input-default-bindings", "no")
            mpv.setOptionString("osc", "no")
            mpv.setOptionString("save-position-on-quit", "no")
            mpv.setOptionString("demuxer-max-bytes", "${64 * 1024 * 1024}")
            mpv.setOptionString("demuxer-max-back-bytes", "${64 * 1024 * 1024}")
            mpv.init()
            mpv.setOptionString("force-window", "no")
            mpv.setOptionString("idle", "once")
            mpv.addObserver(observer)
            mpv.observeProperty("duration", 5)          // double
            mpv.observeProperty("pause", 3)             // flag
            mpv.observeProperty("eof-reached", 3)       // flag
            mpv.observeProperty("paused-for-cache", 3)  // flag
            mpv.observeProperty("seeking", 3)           // flag
            mpv.observeProperty("dwidth", 4)            // int64
            mpv.observeProperty("dheight", 4)           // int64
            ContextCompat.registerReceiver(
                appContext, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            synchronized(live) { live.add(this) }
        } catch (t: Throwable) {
            initFailed = true
            released = true
            throw t
        }
    }

    private fun onFileLoaded() {
        fileLoaded = true
        loading = false
        eof = false
        error = null
        runCatching {
            mpv.setPropertyBoolean("pause", !playWhenReady)
            mpv.setPropertyString("loop-file", if (repeatMode == Player.REPEAT_MODE_ONE) "inf" else "no")
            mpv.setPropertyDouble("speed", speed.toDouble())
            mpv.setPropertyDouble("volume", (volume * 100f).toDouble())
        }
        // the previous file's descriptor is no longer needed
        if (openFd >= 0 && openFd != pendingFd) closeFd(openFd)
        openFd = pendingFd
        invalidateState()
    }

    private fun onEndFile(detail: String?) {
        if (!fileLoaded && loading) {
            loading = false
            error = PlaybackException(
                "mpv could not play this file" + (detail?.let { ": ${it.take(120)}" } ?: ""),
                null,
                PlaybackException.ERROR_CODE_DECODING_FAILED,
            )
            invalidateState()
        }
    }

    // ------------------------------------------------------------------ public helpers

    /** Starts playing [uri] (as soon as the video surface exists). */
    fun load(uri: Uri) {
        if (released || currentUri == uri) return
        currentUri = uri
        fileLoaded = false
        loading = true
        eof = false
        error = null
        durationMs = C.TIME_UNSET
        seekTargetMs = -1
        lastPosMs = 0
        firstFrameShown = false
        videoW = 0
        videoH = 0
        pendingTarget = resolveTarget(uri)
        if (surfaceReady) startPending()
        invalidateState()
    }

    fun shutdown() {
        if (released) return
        released = true
        synchronized(live) { live.remove(this) }
        runCatching { appContext.unregisterReceiver(noisyReceiver) }
        runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
        runCatching { surfaceView?.holder?.removeCallback(surfaceCallback) }
        surfaceView = null
        runCatching {
            mpv.setPropertyString("vo", "null")
            mpv.detachSurface()
        }
        runCatching { mpv.removeObserver(observer) }
        runCatching { mpv.destroy() }
        if (openFd >= 0) closeFd(openFd)
        if (pendingFd >= 0 && pendingFd != openFd) closeFd(pendingFd)
        openFd = -1
        pendingFd = -1
    }

    // ------------------------------------------------------------------ internals

    private fun resolveTarget(uri: Uri): String {
        if (uri.scheme == "content") {
            val pfd = runCatching { appContext.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
            if (pfd != null) {
                val fd = pfd.detachFd()
                pendingFd = fd
                return "fd://$fd"
            }
        }
        pendingFd = -1
        return if (uri.scheme == "file") (uri.path ?: uri.toString()) else uri.toString()
    }

    private fun closeFd(fd: Int) {
        runCatching { android.os.ParcelFileDescriptor.adoptFd(fd).close() }
    }

    private fun startPending() {
        val target = pendingTarget ?: return
        pendingTarget = null
        runCatching {
            mpv.setPropertyBoolean("pause", !playWhenReady)
            mpv.command("loadfile", target)
        }
    }

    private fun positionMs(): Long {
        if (seekTargetMs >= 0) return seekTargetMs
        if (released || !fileLoaded) return lastPosMs
        if (eof && durationMs > 0) return durationMs
        val t = runCatching { mpv.getPropertyDouble("time-pos") }.getOrNull()
        if (t != null) lastPosMs = (t * 1000.0).toLong()
        return lastPosMs
    }

    // ------------------------------------------------------------------ SimpleBasePlayer

    override fun getState(): State {
        val uri = currentUri
        val playlist = if (uri == null) emptyList() else listOf(
            MediaItemData.Builder(uri.toString())
                .setMediaItem(MediaItem.fromUri(uri))
                .setDurationUs(if (durationMs > 0) durationMs * 1000L else C.TIME_UNSET)
                .setIsSeekable(true)
                .build()
        )
        val playbackState = when {
            uri == null || error != null || initFailed -> Player.STATE_IDLE
            !fileLoaded -> Player.STATE_BUFFERING
            eof -> Player.STATE_ENDED
            pausedForCache -> Player.STATE_BUFFERING
            else -> Player.STATE_READY
        }
        val firstFrame = firstFramePending
        firstFramePending = false
        return State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackState)
            .setPlayerError(error)
            .setIsLoading(loading)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(PositionSupplier { positionMs() })
            .setPlaybackParameters(PlaybackParameters(speed))
            .setRepeatMode(repeatMode)
            .setVolume(volume)
            .setVideoSize(VideoSize(videoW, videoH))
            .setNewlyRenderedFirstFrame(firstFrame)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        if (playWhenReady) {
            runCatching { audioManager.requestAudioFocus(focusRequest) }
            if (eof && fileLoaded) {
                eof = false
                runCatching { mpv.command("seek", "0", "absolute+exact") }
            }
        } else {
            runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
        }
        if (fileLoaded) runCatching { mpv.setPropertyBoolean("pause", !playWhenReady) }
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> {
        playWhenReady = false
        if (fileLoaded) runCatching { mpv.setPropertyBoolean("pause", true) }
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        shutdown()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (fileLoaded && !released) {
            val target = positionMs.coerceAtLeast(0L)
            seekTargetMs = target
            eof = false
            val mode = if (fastSeek) "absolute+keyframes" else "absolute+exact"
            runCatching { mpv.command("seek", (target / 1000.0).toString(), mode) }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        this.repeatMode = repeatMode
        if (!released) runCatching { mpv.setPropertyString("loop-file", if (repeatMode == Player.REPEAT_MODE_ONE) "inf" else "no") }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        speed = playbackParameters.speed
        if (!released) runCatching { mpv.setPropertyDouble("speed", speed.toDouble()) }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVolume(volume: Float): ListenableFuture<*> {
        this.volume = volume
        if (!released) runCatching { mpv.setPropertyDouble("volume", (volume * 100f).toDouble()) }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
        val view = videoOutput as? SurfaceView ?: return Futures.immediateVoidFuture()
        if (view === surfaceView) return Futures.immediateVoidFuture()
        detachSurfaceView()
        surfaceView = view
        view.holder.addCallback(surfaceCallback)
        // the surface may already exist (view attached before this call)
        if (view.holder.surface?.isValid == true) {
            surfaceCallback.surfaceCreated(view.holder)
            val frame = view.holder.surfaceFrame
            surfaceCallback.surfaceChanged(view.holder, 0, frame.width(), frame.height())
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
        if (videoOutput == null || videoOutput === surfaceView) detachSurfaceView()
        return Futures.immediateVoidFuture()
    }

    private fun detachSurfaceView() {
        val view = surfaceView ?: return
        runCatching { view.holder.removeCallback(surfaceCallback) }
        if (surfaceReady && !released) surfaceCallback.surfaceDestroyed(view.holder)
        surfaceView = null
    }

    companion object {
        private val COMMANDS: Player.Commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_SET_SPEED_AND_PITCH,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_SET_REPEAT_MODE,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_VOLUME,
                Player.COMMAND_SET_VOLUME,
                Player.COMMAND_SET_VIDEO_SURFACE,
                Player.COMMAND_RELEASE,
            )
            .build()

        private val live = mutableSetOf<MpvPlayer>()

        /** Pauses every mpv player this app owns (called when the app leaves the foreground). */
        fun pauseAll() {
            val copy = synchronized(live) { live.toList() }
            copy.forEach { p -> runCatching { p.pause() } }
        }
    }
}
