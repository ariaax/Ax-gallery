package com.iris.gallery.ui.video

import android.app.Activity
import android.content.Context
import android.content.res.Resources
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// =====================================================================================================================
// Tap / hold logic of the video screen (written from scratch)
//
//   single tap            -> show / hide the controls
//   double tap  left/right -> seek -10 s / +10 s, and every further tap in a row keeps seeking (-20, -30 ...)
//   double tap  centre     -> zoom (if enabled) or play / pause
//   press and hold         -> 2x speed until the finger lifts
//
// Everything is decided here, in one place, so a tap is never both "a single tap" and "a double tap".
// =====================================================================================================================

internal const val ZONE_LEFT = -1
internal const val ZONE_CENTER = 0
internal const val ZONE_RIGHT = 1

/** Left 35 % = back, right 35 % = forward, the middle 30 % is the centre zone. */
internal fun tapZone(x: Float, width: Float): Int = when {
    width <= 0f -> ZONE_CENTER
    x < width * 0.35f -> ZONE_LEFT
    x > width * 0.65f -> ZONE_RIGHT
    else -> ZONE_CENTER
}

/**
 * A run of seek taps. While the run is alive each tap continues from where the LAST seek was aimed, not from the
 * player's reported position (which lags behind while a seek is still being carried out - that lag is what used to make
 * a quick double tap jump the wrong way or not at all).
 */
internal class SeekSeries(private val windowMs: Long = 900L, private val stepMs: Long = 10_000L) {
    private var side = 0
    private var anchorMs = 0L
    private var lastAt = 0L
    private var totalMs = 0L

    /** Seconds shown for this run (signed, resets when the direction changes). */
    val seconds: Int get() = (totalMs / 1000L).toInt()

    fun active(now: Long): Boolean = side != 0 && now - lastAt <= windowMs

    /** Registers one tap on [newSide] (-1 / +1) and returns the position to seek to. */
    fun next(newSide: Int, playerPositionMs: Long, durationMs: Long, now: Long): Long {
        val wasActive = active(now)
        val base = if (wasActive) anchorMs else playerPositionMs.coerceAtLeast(0L)
        if (!wasActive || newSide != side) totalMs = 0L
        val raw = base + newSide * stepMs
        val target = if (durationMs > 0L) raw.coerceIn(0L, durationMs) else raw.coerceAtLeast(0L)
        totalMs += target - base
        side = newSide
        anchorMs = target
        lastAt = now
        return target
    }

    fun reset() {
        side = 0; anchorMs = 0L; lastAt = 0L; totalMs = 0L
    }
}

internal class SeekFeedbackState(val side: Int, val seconds: Int, val token: Long)

internal class VideoTapCallbacks(
    val zoneOf: (Float) -> Int,
    val seekRunActive: () -> Boolean,
    val onSingleTap: () -> Unit,
    val onSeekTap: (side: Int) -> Unit,
    val onCenterDoubleTap: (Offset) -> Unit,
    val onHoldStart: (Offset) -> Unit,
    val onHoldEnd: () -> Unit,
)

private const val RESULT_UP = 0
private const val RESULT_CANCEL = 1

internal suspend fun PointerInputScope.detectVideoTaps(cb: VideoTapCallbacks) = coroutineScope {
    val scope: CoroutineScope = this
    var pendingSingle: Job? = null
    var lastTapAt = 0L
    var lastTapPos = Offset.Zero
    val doubleTapGap = viewConfiguration.doubleTapTimeoutMillis
    val nearPx = 56.dp.toPx()

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        var upPosition = down.position
        var upTime = down.uptimeMillis

        // Phase 1: is this a quick tap, a drag (not ours) or a long press?
        val phase = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var result = RESULT_CANCEL
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } > 1) { result = RESULT_CANCEL; break }
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null) { result = RESULT_CANCEL; break }
                if (change.isConsumed && !change.changedToUp()) { result = RESULT_CANCEL; break }
                if (change.changedToUp()) {
                    if (change.isConsumed) { result = RESULT_CANCEL; break }
                    upPosition = change.position
                    upTime = change.uptimeMillis
                    result = RESULT_UP
                    break
                }
                if (change.positionChanged() && (change.position - down.position).getDistance() > slop) {
                    result = RESULT_CANCEL; break
                }
            }
            result
        }

        if (phase == null) {
            // Timed out while the finger is still down and has not moved: long press = hold for 2x speed.
            pendingSingle?.cancel(); pendingSingle = null
            lastTapAt = 0L
            cb.onHoldStart(down.position)
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.none { it.pressed }) break
                }
            } finally {
                cb.onHoldEnd()
            }
            return@awaitEachGesture
        }
        if (phase != RESULT_UP) return@awaitEachGesture

        // Phase 2: a real tap.
        val zone = cb.zoneOf(upPosition.x)

        // A seek run is going: every tap on a side keeps seeking straight away.
        if (zone != ZONE_CENTER && cb.seekRunActive()) {
            pendingSingle?.cancel(); pendingSingle = null
            lastTapAt = 0L
            cb.onSeekTap(zone)
            return@awaitEachGesture
        }

        val isSecondTap = lastTapAt != 0L &&
            upTime - lastTapAt <= doubleTapGap &&
            (upPosition - lastTapPos).getDistance() <= nearPx
        if (isSecondTap) {
            pendingSingle?.cancel(); pendingSingle = null
            lastTapAt = 0L
            if (zone == ZONE_CENTER) cb.onCenterDoubleTap(upPosition) else cb.onSeekTap(zone)
        } else {
            pendingSingle?.cancel()
            lastTapAt = upTime
            lastTapPos = upPosition
            pendingSingle = scope.launch {
                delay(doubleTapGap)
                lastTapAt = 0L
                cb.onSingleTap()
            }
        }
    }
}

// =====================================================================================================================
// Play / pause
// =====================================================================================================================

/** Toggles playback from the player's own intention (not from "is it playing right now", which is false while buffering). */
internal fun togglePlayback(player: Player): Boolean {
    return when {
        player.playbackState == Player.STATE_ENDED -> {
            player.seekTo(0L); player.play(); true
        }
        player.playbackState == Player.STATE_IDLE -> {
            player.prepare(); player.play(); true
        }
        player.playWhenReady -> {
            player.pause(); false
        }
        else -> {
            player.play(); true
        }
    }
}

// =====================================================================================================================
// Brightness
// =====================================================================================================================

/**
 * Current screen brightness as 0..1. If this window has not overridden it yet, the system setting is read with the
 * phone's real scale (many Xiaomi phones go up to 2047, not 255 - dividing by 255 made the first swipe jump to 100 %).
 */
internal fun readBrightness(context: Context, activity: Activity?): Float {
    val window = activity?.window?.attributes?.screenBrightness ?: -1f
    if (window in 0.02f..1f) return window
    val raw = runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrNull()
        ?: return 0.5f
    val declared = runCatching {
        val res = Resources.getSystem()
        val id = res.getIdentifier("config_screenBrightnessSettingMaximum", "integer", "android")
        if (id != 0) res.getInteger(id) else 255
    }.getOrDefault(255).coerceAtLeast(255)
    val max = when {
        raw <= declared -> declared
        raw <= 2047 -> 2047
        else -> 4095
    }
    return (raw.toFloat() / max).coerceIn(0.02f, 1f)
}

// =====================================================================================================================
// Visuals
// =====================================================================================================================

// One glass look for every on-video control (seek pill, brightness / volume meters, play-pause badge), kept deliberately
// light: small surfaces, plain fills, short fades. Frequently changing values are read only while drawing.
private val GlassFill = Color.Black.copy(alpha = 0.46f)
private val GlassRim = Color.White.copy(alpha = 0.18f)

/**
 * Feedback for double-tap seeking: a small glass pill next to the edge that was tapped, with three chevrons that sweep
 * once in the direction of the seek and the running total ("+10 s", "+20 s"...). It restarts on every tap of a run.
 */
@Composable
internal fun BoxScope.SeekFeedbackOverlay(feedback: SeekFeedbackState?) {
    val holder = remember { arrayOfNulls<SeekFeedbackState>(1) }
    if (feedback != null) holder[0] = feedback
    val shown = holder[0] ?: return
    val left = shown.side < 0
    AnimatedVisibility(
        visible = feedback != null,
        modifier = Modifier
            .align(if (left) AbsoluteAlignment.CenterLeft else AbsoluteAlignment.CenterRight)
            .padding(horizontal = 36.dp),
        enter = fadeIn(tween(60)),
        exit = fadeOut(tween(160)),
    ) {
        val sweep = remember { Animatable(1f) }
        LaunchedEffect(shown.token) {
            sweep.snapTo(0f)
            sweep.animateTo(1f, tween(340, easing = LinearEasing))
        }
        val shape = CutCornerShape(30)
        Row(
            modifier = Modifier
                .graphicsLayer {
                    val s = 0.92f + 0.08f * (1f - (1f - sweep.value) * (1f - sweep.value))
                    scaleX = s; scaleY = s
                }
                .clip(shape)
                .background(GlassFill)
                .border(1.dp, GlassRim, shape)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (left) SeekLabel(shown)
            Row(horizontalArrangement = Arrangement.spacedBy((-9).dp), verticalAlignment = Alignment.CenterVertically) {
                for (i in 0..2) {
                    val order = if (left) 2 - i else i
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .size(20.dp)
                            .graphicsLayer {
                                rotationZ = if (left) 180f else 0f
                                alpha = (1f - abs(sweep.value * 3f - order - 0.5f)).coerceIn(0.3f, 1f)
                            },
                    )
                }
            }
            if (!left) SeekLabel(shown)
        }
    }
}

@Composable
private fun SeekLabel(s: SeekFeedbackState) {
    Text(
        text = "${if (s.seconds < 0) "−" else "+"}${abs(s.seconds)} s",
        color = Color.White,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
    )
}

/**
 * Vertical level meter for brightness / volume: a slim glass capsule that fills from the bottom, the icon at its foot and
 * the percentage above. [progress] and [icon] are lambdas so only this small composable follows the finger.
 */
@Composable
internal fun LevelHud(
    visible: Boolean,
    icon: (Float) -> ImageVector,
    progress: () -> Float,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(70)),
        exit = fadeOut(tween(150)),
    ) {
        val p = progress().coerceIn(0f, 1f)
        val shape = CutCornerShape(30)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "${(p * 100f).roundToInt()}%",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier
                    .clip(shape)
                    .background(GlassFill)
                    .border(1.dp, GlassRim, shape)
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            )
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(150.dp)
                    .clip(shape)
                    .background(GlassFill)
                    .border(1.dp, GlassRim, shape)
                    .drawBehind {
                        val level = progress().coerceIn(0f, 1f)
                        drawRect(
                            color = Color.White.copy(alpha = 0.90f),
                            topLeft = Offset(0f, size.height * (1f - level)),
                            size = androidx.compose.ui.geometry.Size(size.width, size.height * level),
                        )
                    },
                contentAlignment = Alignment.BottomCenter,
            ) {
                Icon(
                    imageVector = icon(p),
                    contentDescription = null,
                    tint = if (p > 0.12f) Color.Black.copy(alpha = 0.75f) else Color.White,
                    modifier = Modifier.padding(bottom = 10.dp).size(20.dp),
                )
            }
        }
    }
}
