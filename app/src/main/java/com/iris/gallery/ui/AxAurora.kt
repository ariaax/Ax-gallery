package com.iris.gallery.ui

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.withFrameNanos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Plain background, or (when liquid glass is on) a slow, soft "aurora" of two theme-coloured light blobs.
 * Kept deliberately cheap: only two blobs, updated at ~30 fps, and the screen content sits in its own
 * cached layer so it is not re-drawn when the background moves.
 */
@Composable
fun Modifier.axAurora(): Modifier {
    val bg = MaterialTheme.colorScheme.background
    if (!AxGlass.enabled) return this.background(bg)
    val cs = MaterialTheme.colorScheme
    val dark = bg.luminance() < 0.5f
    val alpha = if (dark) 0.14f else 0.11f
    val c1 = cs.primary
    val c2 = cs.tertiary
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        var p = 0f
        val twoPi = (2 * Math.PI).toFloat()
        while (true) {
            withFrameNanos { t ->
                if (last == 0L) last = t
                val elapsed = t - last
                if (elapsed >= 33_000_000L) {
                    val dt = (elapsed / 1_000_000_000f).coerceAtMost(0.1f)
                    last = t
                    p += dt * (twoPi / 20f)
                    if (p > twoPi) p -= twoPi
                    phase.floatValue = p
                }
            }
        }
    }
    return this
        .background(bg)
        .drawBehind {
            val w = size.width
            val h = size.height
            val r = max(w, h) * 0.38f
            val p = phase.floatValue
            fun blob(color: Color, cx: Float, cy: Float) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = alpha), Color.Transparent),
                        center = Offset(cx, cy),
                        radius = r,
                    ),
                    radius = r,
                    center = Offset(cx, cy),
                )
            }
            blob(c1, w * (0.50f + 0.45f * sin(p)), h * (0.25f + 0.20f * cos(p)))
            blob(c2, w * (0.50f + 0.45f * cos(p)), h * (0.70f + 0.20f * sin(p * 2f)))
        }
        // the whole screen content is cached in its own layer
        .graphicsLayer { }
}
