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

fun createVideoEngine(context: Context): VideoEngine = Media3VideoEngine(context)

@OptIn(UnstableApi::class)
class Media3VideoEngine(context: Context) : VideoEngine {
    // Decoder fallback: if the first hardware decoder for a format fails, try the next one instead of giving up.
    private val exoPlayer = ExoPlayer.Builder(
        context,
        DefaultRenderersFactory(context).setEnableDecoderFallback(true),
    ).setLoadControl(
        // Playback starts after only 0.25 s of data. The buffer keeps 40 s ahead and 10 s behind the play head, so the
        // double-tap +-10 s jumps land inside data that is already loaded (instant) instead of reloading from storage.
        // A byte cap protects weak phones from huge 4K files.
        androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 40_000, 250, 500)
            .setBackBuffer(10_000, true)
            .setTargetBufferBytes(64 * 1024 * 1024)
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
