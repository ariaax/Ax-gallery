package com.iris.gallery.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Plain background, or (when liquid glass is on) a slow, soft "aurora" of three theme-coloured light blobs.
 * Gives the frosted glass cards something to show. The animation only redraws this layer, never recomposes.
 */
@Composable
fun Modifier.axAurora(): Modifier {
    val bg = MaterialTheme.colorScheme.background
    if (!AxGlass.enabled) return this.background(bg)
    val cs = MaterialTheme.colorScheme
    val dark = bg.luminance() < 0.5f
    val alpha = if (dark) 0.26f else 0.20f
    val c1 = cs.primary
    val c2 = cs.tertiary
    val c3 = cs.secondary
    val phase = rememberInfiniteTransition(label = "aurora").animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(36000, easing = LinearEasing), RepeatMode.Restart),
        label = "aurora phase",
    )
    return this
        .background(bg)
        .drawBehind {
            val w = size.width
            val h = size.height
            val r = max(w, h) * 0.75f
            val p = phase.value
            fun blob(color: Color, cx: Float, cy: Float, radius: Float) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = alpha), Color.Transparent),
                        center = Offset(cx, cy),
                        radius = radius,
                    ),
                    radius = radius,
                    center = Offset(cx, cy),
                )
            }
            blob(c1, w * (0.15f + 0.20f * sin(p)), h * (0.12f + 0.10f * cos(p)), r)
            blob(c2, w * (0.90f + 0.12f * cos(p * 2f)), h * (0.50f + 0.18f * sin(p)), r * 0.85f)
            blob(c3, w * (0.30f + 0.20f * sin(p * 2f + 1.5f)), h * (0.95f + 0.08f * cos(p)), r * 0.9f)
        }
}
