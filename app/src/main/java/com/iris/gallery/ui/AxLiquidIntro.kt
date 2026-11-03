package com.iris.gallery.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin


// ---- collage pieces, drawn in a 200 x 150 unit box ----
private fun torn(l: Float, t: Float, r: Float, b: Float): Path {
    fun j(i: Int) = (sin(i * 12.9898f) * 4.3758f).let { it - kotlin.math.floor(it) } * 3.6f - 1.8f
    val p = Path()
    var i = 0
    var x = l
    p.moveTo(l, t + j(i++))
    while (x < r) { x = minOf(x + 9f, r); p.lineTo(x, t + j(i++)) }
    var y = t
    while (y < b) { y = minOf(y + 9f, b); p.lineTo(r + j(i++), y) }
    x = r
    while (x > l) { x = maxOf(x - 9f, l); p.lineTo(x, b + j(i++)) }
    y = b
    while (y > t) { y = maxOf(y - 9f, t); p.lineTo(l + j(i++), y) }
    p.close()
    return p
}

private fun rectPath(l: Float, t: Float, r: Float, b: Float) = Path().apply { addRect(androidx.compose.ui.geometry.Rect(l, t, r, b)) }

private val discPath = Path().apply { addOval(androidx.compose.ui.geometry.Rect(62f - 38f, 76f - 38f, 62f + 38f, 76f + 38f)) }
private val dotPath = Path().apply { addOval(androidx.compose.ui.geometry.Rect(22f, 22f, 38f, 38f)) }
private val bluePath = torn(112f, 12f, 188f, 60f)
private val paperPath = torn(28f, 18f, 150f, 128f)
private val pinkPath = torn(120f, 64f, 188f, 132f)
private val stripPath = rectPath(116f, 88f, 192f, 108f)
private val tapePath = rectPath(58f, 10f, 94f, 24f)
private val trianglePath = Path().apply { moveTo(172f, 16f); lineTo(184f, 38f); lineTo(160f, 38f); close() }
private val letterAPath = Path().apply {
    fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
    moveTo(40f, 114f); lineTo(74f, 32f); lineTo(98f, 32f); lineTo(132f, 114f)
    lineTo(111f, 114f); lineTo(105f, 98f); lineTo(68f, 98f); lineTo(62f, 114f); close()
    moveTo(86f, 52f); lineTo(97f, 82f); lineTo(75f, 82f); close()
}

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

    Box(
        Modifier
            .fillMaxSize()
            // swallow touches while the intro is visible
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
        contentAlignment = Alignment.Center,
    ) {
    Canvas(Modifier.fillMaxSize()) {
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
    // The mark: a frosted squircle with a geometric "A" whose crossbar is a camera-lens ring, and the name under it.
    // Everything is read only while drawing (graphicsLayer / Canvas lambdas), so this never recomposes.
    Column(
        modifier = Modifier.graphicsLayer {
            val appear = smooth((cover.value - 0.70f) / 0.30f)
            val leave = smooth(reveal.value / 0.35f)
            alpha = appear * (1f - leave)
            val sc = 0.86f + 0.14f * appear + 0.10f * leave
            scaleX = sc
            scaleY = sc
            translationY = (1f - appear) * 24.dp.toPx() - leave * 18.dp.toPx()
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.size(300.dp, 225.dp)) {
            val u = size.width / 200f
            val cv = cover.value
            val rv = reveal.value
            val leave = smooth(rv / 0.35f)
            // every cut-out drops in on its own beat, like pieces being pasted down one after another
            fun piece(
                idx: Int, pivot: Offset, rot: Float, path: Path, color: Color,
                shadow: Boolean = true, dxFrom: Float = 0f, dyFrom: Float = -46f,
            ) {
                val ap = smooth((cv - 0.66f - idx * 0.035f) / 0.20f)
                if (ap <= 0f) return
                val a = ap * (1f - leave)
                val fall = 1f - ap
                withTransform({
                    translate(dxFrom * fall * u, dyFrom * fall * u)
                    scale(u, u, Offset.Zero)
                    rotate(rot - 9f * fall, pivot)
                }) {
                    if (shadow) {
                        withTransform({ translate(2.2f, 3.2f) }) { drawPath(path, Color.Black.copy(alpha = 0.30f * a)) }
                    }
                    drawPath(path, color.copy(alpha = a))
                }
            }
            // 1 blue paper strip, 2 cream torn paper
            piece(0, Offset(150f, 36f), 12f, bluePath, Color(0xFF2447D8), dxFrom = 40f, dyFrom = 0f)
            piece(1, Offset(89f, 73f), -5f, paperPath, Color(0xFFF3E7CE))
            // coral (printed on the paper) disc with a halftone shade
            piece(2, Offset(62f, 70f), 0f, discPath, Color(0xFFFF6B4A), dxFrom = -30f, dyFrom = 0f)
            run {
                val raw = smooth((cv - 0.66f - 2 * 0.035f) / 0.20f)
                val ap = raw * (1f - leave)
                val slide = -30f * (1f - raw)
                if (ap > 0f) {
                    var gy = 30f
                    while (gy < 112f) {
                        var gx = 20f
                        while (gx < 104f) {
                            val dx = gx - 62f; val dy = gy - 76f
                            if (dx * dx + dy * dy < 34f * 34f && dx + dy > 6f) {
                                val k = ((dx + dy) / 70f).coerceIn(0.2f, 1f)
                                drawCircle(Color(0xFFB8321A).copy(alpha = ap * 0.85f), radius = 2.4f * k * u, center = Offset((gx + slide) * u, gy * u))
                            }
                            gx += 7f
                        }
                        gy += 7f
                    }
                }
            }
            // 3 pink tile for the x, 4 the black cut-out A
            piece(3, Offset(154f, 98f), 6f, pinkPath, Color(0xFFFF8FB1), dxFrom = 30f, dyFrom = -20f)
            piece(4, Offset(86f, 75f), -5f, letterAPath, Color(0xFF141414))
            // 5/6 the x: two crossing strips of different paper
            piece(5, Offset(154f, 98f), 45f, stripPath, Color(0xFFFFC928), dxFrom = 20f, dyFrom = -40f)
            piece(6, Offset(154f, 98f), -45f, stripPath, Color(0xFF18B6A4), dxFrom = -10f, dyFrom = -40f)
            // 7 small cut shapes, 8 tape
            piece(7, Offset(30f, 30f), 0f, dotPath, Color(0xFF141414), dxFrom = 0f, dyFrom = -30f)
            piece(7, Offset(172f, 28f), 14f, trianglePath, Color(0xFFFFC928), dxFrom = 0f, dyFrom = -30f)
            piece(8, Offset(76f, 17f), -18f, tapePath, Color(0xFFFFF3A0).copy(alpha = 0.85f), shadow = false, dyFrom = -20f)
        }
        // typewriter label stuck under the collage
        Text(
            text = "GALLERY",
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                letterSpacing = 6.sp,
                color = Color(0xFF141414),
            ),
            modifier = Modifier
                .padding(top = 6.dp)
                .graphicsLayer { rotationZ = -2f }
                .background(Color(0xFFF3E7CE))
                .padding(horizontal = 12.dp, vertical = 3.dp),
        )
    }
    }
}
