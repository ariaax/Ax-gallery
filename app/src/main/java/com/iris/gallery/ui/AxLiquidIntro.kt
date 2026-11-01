package com.iris.gallery.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
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
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/** One "drip": a column where the liquid runs further down than around it. All values are fractions of the screen. */
private class Drip(val x: Float, val width: Float, val length: Float, val delay: Float)

private val DRIPS = arrayOf(
    Drip(0.07f, 0.075f, 0.50f, 0.00f),
    Drip(0.24f, 0.095f, 0.34f, 0.12f),
    Drip(0.42f, 0.070f, 0.66f, 0.04f),
    Drip(0.60f, 0.100f, 0.42f, 0.17f),
    Drip(0.78f, 0.080f, 0.58f, 0.08f),
    Drip(0.94f, 0.085f, 0.30f, 0.21f),
)

private fun smooth(t: Float): Float {
    val c = t.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}

/** Leading (bottom) edge while pouring, as a fraction of the screen height. p = 0..1. */
private fun coverBottom(fx: Float, p: Float): Float {
    val base = smooth((p - 0.28f) / 0.72f).pow(1.35f) * 1.2f
    var s = 0f
    for (d in DRIPS) {
        val g = smooth((p - d.delay) / 0.62f)
        val u = (fx - d.x) / d.width
        s += d.length * g * exp(-u * u)
    }
    val settle = 1f - smooth((p - 0.9f) / 0.1f)
    val wob = (0.006f * sin(fx * 17f + p * 9f) + 0.004f * sin(fx * 31f - p * 13f)) * settle
    return base + s + wob
}

/** Trailing (top) edge while draining; the liquid clings a little longer at the drip columns. q = 0..1. */
private fun revealTop(fx: Float, q: Float): Float {
    val r = smooth(q) * 1.45f
    val sq = sin(PI.toFloat() * q.coerceIn(0f, 1f))
    var s = 0f
    for (d in DRIPS) {
        val u = (fx - d.x) / (d.width * 1.9f)
        s += (0.05f + 0.10f * d.length) * sq * exp(-u * u)
    }
    val wob = (0.006f * sin(fx * 17f + q * 9f + 1.7f) + 0.004f * sin(fx * 31f - q * 13f)) * sq
    return max(r - s + wob, 0f)
}

/**
 * Launch animation: glowing blue liquid runs down from the TOP of the screen in drips, joins up and covers
 * everything; the gallery is built behind it; then the liquid drains away downwards.
 * One Canvas, two paths. The animation values are only read while drawing, so nothing recomposes per frame.
 */
@Composable
fun AxLiquidIntro(onCovered: () -> Unit = {}, onFinished: () -> Unit) {
    val cover = remember { Animatable(0f) }
    val reveal = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        cover.animateTo(1f, tween(700, easing = LinearEasing))
        // Fully covered: build the gallery behind the liquid (heavy first composition and data load).
        onCovered()
        withFrameNanos { }
        withFrameNanos { }
        delay(180)
        reveal.animateTo(1f, tween(620, easing = LinearEasing))
        onFinished()
    }

    val bottomPath = remember { Path() }
    val topPath = remember { Path() }
    val bodyPath = remember { Path() }
    val streak = remember { Path() }

    Canvas(
        Modifier
            .fillMaxSize()
            // swallow touches while the intro is visible
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
    ) {
        val p = cover.value
        val q = reveal.value
        val w = size.width
        val h = size.height

        // plain black behind the liquid until it has covered everything (the app is not visible yet)
        if (p < 1f) drawRect(Color(0xFF040405))

        val steps = 72
        val covering = p < 1f
        val draining = q > 0f

        bottomPath.reset()
        topPath.reset()
        bodyPath.reset()
        for (i in 0..steps) {
            val fx = i.toFloat() / steps
            val x = w * fx
            val y = if (draining) h * 1.1f else h * coverBottom(fx, p)
            if (i == 0) bottomPath.moveTo(x, y) else bottomPath.lineTo(x, y)
            val ty = if (draining) h * revealTop(fx, q) else -h * 0.05f
            if (i == 0) topPath.moveTo(x, ty) else topPath.lineTo(x, ty)
        }
        bodyPath.addPath(topPath)
        for (i in steps downTo 0) {
            val fx = i.toFloat() / steps
            bodyPath.lineTo(w * fx, if (draining) h * 1.1f else h * coverBottom(fx, p))
        }
        bodyPath.close()

        // body: deep navy at the top of the screen, bright azure at the bottom
        drawPath(
            bodyPath,
            Brush.verticalGradient(
                0f to Color(0xFF06103F),
                0.35f to Color(0xFF1234E1),
                0.70f to Color(0xFF2F7BFF),
                1f to Color(0xFF63D2FF),
                startY = 0f,
                endY = h,
            ),
        )

        // soft diagonal sheen that drifts across the liquid while it moves
        val f = (0.30f + 0.40f * p + 0.25f * q).coerceIn(0.2f, 0.85f)
        drawPath(
            bodyPath,
            Brush.linearGradient(
                0f to Color.Transparent,
                (f - 0.14f) to Color.Transparent,
                f to Color.White.copy(alpha = 0.11f),
                (f + 0.14f) to Color.Transparent,
                1f to Color.Transparent,
                start = Offset(0f, 0f),
                end = Offset(w, h * 0.5f),
            ),
        )

        // light running down each drip (only while it is still pouring)
        if (covering) {
            val k = 1f - smooth((p - 0.45f) / 0.35f)
            if (k > 0.02f) {
                for (d in DRIPS) {
                    val sx = (d.x - 0.30f * d.width)
                    val endY = h * coverBottom(sx, p) - h * 0.05f
                    if (endY > h * 0.06f) {
                        streak.reset()
                        streak.moveTo(w * sx, h * 0.04f)
                        streak.lineTo(w * sx, endY)
                        drawPath(
                            streak,
                            Color(0xFFC8E6FF).copy(alpha = 0.22f * k),
                            style = Stroke(width = max(1.2.dp.toPx(), w * d.width * 0.12f), cap = StrokeCap.Round),
                        )
                    }
                }
            }
            // glowing rim on the leading edge
            drawPath(bottomPath, Color(0xFF59C8FF).copy(alpha = 0.30f), style = Stroke(14.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(bottomPath, Color(0xFFBFF1FF).copy(alpha = 0.95f), style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        // thin bright rim on the trailing edge while draining
        if (draining && q < 1f) {
            drawPath(topPath, Color(0xFFBFF1FF).copy(alpha = 0.75f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
