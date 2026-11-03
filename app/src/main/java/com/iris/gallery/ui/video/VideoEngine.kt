package com.iris.gallery.ui.video

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer

interface VideoEngine {
    val player: Player
    val isMuted: Boolean
        get() = player.volume == 0f
    fun setMuted(muted: Boolean) {
        player.volume = if (muted) 0f else 1f
    }
    fun toggleMute(): Boolean {
        val nextMuted = !isMuted
        setMuted(nextMuted)
        return nextMuted
    }
    fun load(uri: Uri)
    fun release()
}

/** mpv engine: better scaling, subtitles and format support than ExoPlayer. Falls back to ExoPlayer if libmpv can't start. */
fun createVideoEngine(context: Context, preferMpv: Boolean = true, force: Boolean = false): VideoEngine =
    if (!preferMpv || (!force && (!mpvEnabled(context) || mpvDisabledAfterCrash(context)))) Media3VideoEngine(context) else try {
        MpvVideoEngine(context)
    } catch (t: Throwable) {
        android.util.Log.w("AxVideo", "mpv unavailable, using ExoPlayer", t)
        Media3VideoEngine(context)
    }

/** Settings switch "mpv video player (experimental)". Off by default: ExoPlayer is the stable engine. */
fun mpvEnabled(context: Context): Boolean =
    runCatching { context.getSharedPreferences("ax_diag", Context.MODE_PRIVATE).getBoolean("mpv_enabled", false) }.getOrDefault(false)

fun setMpvEnabled(context: Context, enabled: Boolean) {
    runCatching {
        val e = context.getSharedPreferences("ax_diag", Context.MODE_PRIVATE).edit().putBoolean("mpv_enabled", enabled)
        if (enabled) e.putBoolean("mpv_disabled", false) // turning it on by hand also clears the crash lock
        e.apply()
    }
}

/** Set automatically when a native crash happened while mpv was alive; the crash report offers a "try mpv again" button. */
fun mpvDisabledAfterCrash(context: Context): Boolean =
    runCatching { context.getSharedPreferences("ax_diag", Context.MODE_PRIVATE).getBoolean("mpv_disabled", false) }.getOrDefault(false)

class MpvVideoEngine(context: Context) : VideoEngine {
    private val mpvPlayer = MpvPlayer(context)
    override val player: Player = mpvPlayer
    override fun load(uri: Uri) = mpvPlayer.load(uri)
    override fun release() = mpvPlayer.shutdown()
}

@OptIn(UnstableApi::class)
class Media3VideoEngine(context: Context) : VideoEngine {
    // Decoder fallback: if the first hardware decoder for a format fails, try the next one instead of giving up.
    private val exoPlayer = ExoPlayer.Builder(
        context,
        DefaultRenderersFactory(context).setEnableDecoderFallback(true),
    ).setLoadControl(
        // Local files never need a 50 s read-ahead: a short buffer saves memory (big win on 4K / weak phones) and
        // makes switching videos faster.
        androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(2_500, 6_000, 250, 500)
            .build()
    ).build().also { p ->
        p.setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true,
        )
        p.setHandleAudioBecomingNoisy(true)
        synchronized(live) { live.add(p) }
    }
    override val player: Player = exoPlayer
    private var currentUri: Uri? = null

    override fun load(uri: Uri) {
        if (currentUri == uri) return
        currentUri = uri
        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer.prepare()
    }

    override fun release() {
        synchronized(live) { live.remove(exoPlayer) }
        exoPlayer.release()
    }

    companion object {
        private val live = mutableSetOf<ExoPlayer>()

        /** Pauses every player this app owns (called when the app leaves the foreground). */
        fun pauseAll() {
            val copy = synchronized(live) { live.toList() }
            copy.forEach { runCatching { it.pause() } }
        }
    }
}
