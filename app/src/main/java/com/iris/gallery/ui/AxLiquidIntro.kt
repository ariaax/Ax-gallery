package com.iris.gallery.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/**
 * Launch animation (~1.2 s): a glowing blue liquid pours in from the TOP of the screen until it covers
 * everything, then drains downwards and uncovers the gallery. Drawn with one Canvas (two wavy paths),
 * the animation values are only read while drawing, so nothing recomposes per frame.
 */
@Composable
fun AxLiquidIntro(onFinished: () -> Unit) {
    val cover = remember { Animatable(0f) }
    val reveal = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        cover.animateTo(1f, tween(520, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)))
        delay(70)
        reveal.animateTo(1f, tween(560, easing = CubicBezierEasing(0.5f, 0f, 0.3f, 1f)))
        onFinished()
    }

    val bottomPath = remember { Path() }
    val bodyPath = remember { Path() }

    Canvas(
        Modifier
            .fillMaxSize()
            // swallow touches while the intro is visible
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
    ) {
        val c = cover.value
        val r = reveal.value
        val w = size.width
        val h = size.height
        val amp = 56.dp.toPx()

        // plain black behind the liquid until it has covered everything (the app is not visible yet)
        if (c < 1f) drawRect(Color.Black)

        val steps = 36
        val bottomBase = -amp + c * (h + 2f * amp)
        val topBase = -amp + r * (h + 2f * amp)
        val phaseB = c * 5f
        val phaseT = r * 5f + 1.3f

        fun edgeY(x: Float, base: Float, phase: Float): Float {
            val t = x / w
            return base + amp * 0.55f * sin(t * 5f * PI.toFloat() + phase) +
                amp * 0.30f * sin(t * 11f * PI.toFloat() - phase * 1.6f)
        }

        bottomPath.reset()
        bodyPath.reset()
        bottomPath.moveTo(0f, edgeY(0f, bottomBase, phaseB))
        for (i in 1..steps) {
            val x = w * i / steps
            bottomPath.lineTo(x, edgeY(x, bottomBase, phaseB))
        }
        // liquid body = area between the (draining) top edge and the (advancing) bottom edge
        bodyPath.moveTo(0f, edgeY(0f, topBase, phaseT))
        for (i in 1..steps) {
            val x = w * i / steps
            bodyPath.lineTo(x, edgeY(x, topBase, phaseT))
        }
        for (i in steps downTo 0) {
            val x = w * i / steps
            bodyPath.lineTo(x, edgeY(x, bottomBase, phaseB))
        }
        bodyPath.close()

        val startY = topBase - amp
        val endY = (bottomBase + amp).coerceAtLeast(startY + 1f)
        drawPath(
            bodyPath,
            Brush.verticalGradient(
                listOf(Color(0xFF07124A), Color(0xFF1238E0), Color(0xFF2F7BFF), Color(0xFF59C8FF)),
                startY = startY,
                endY = endY,
            ),
        )
        // glowing rim on the leading (bottom) edge
        if (c < 1f) {
            drawPath(bottomPath, Color(0xFF59C8FF).copy(alpha = 0.28f), style = Stroke(14.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(bottomPath, Color(0xFFBFF1FF), style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
