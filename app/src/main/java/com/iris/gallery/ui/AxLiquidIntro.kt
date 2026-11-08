package com.iris.gallery.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.iris.gallery.R
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
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin


// ---- launch animation: black liquid, then the chrome AX mark with a light sweep ----

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
    val sweep = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        cover.animateTo(1f, tween(700, easing = LinearEasing))
        // Fully covered: build the gallery behind the liquid (heavy first composition and data load).
        onCovered()
        withFrameNanos { }
        withFrameNanos { }
        // light sweeps across the chrome mark, then the liquid drains
        sweep.animateTo(1f, tween(640, easing = LinearEasing))
        delay(120)
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

        // body: near-black with a cold steel-blue lift at the bottom (the same black as the launcher icon)
        drawPath(
            bodyPath,
            Brush.verticalGradient(
                0f to Color(0xFF030408),
                0.55f to Color(0xFF060A14),
                1f to Color(0xFF0B1A3A),
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
                            Color(0xFFDCE8FF).copy(alpha = 0.22f * k),
                            style = Stroke(width = max(1.2.dp.toPx(), w * d.width * 0.12f), cap = StrokeCap.Round),
                        )
                    }
                }
            }
            // glowing rim on the leading edge
            drawPath(bottomPath, Color(0xFF7FA8FF).copy(alpha = 0.30f), style = Stroke(14.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(bottomPath, Color(0xFFE8F0FF).copy(alpha = 0.95f), style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        // thin bright rim on the trailing edge while draining
        if (draining && q < 1f) {
            drawPath(topPath, Color(0xFFDCE8FF).copy(alpha = 0.75f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
    // The chrome AX mark and a quiet wordmark under it.
    // Everything is read only while drawing (graphicsLayer / drawWithContent lambdas), so this never recomposes.
    Column(
        modifier = Modifier.graphicsLayer {
            val appear = smooth((cover.value - 0.70f) / 0.30f)
            val leave = smooth(reveal.value / 0.35f)
            alpha = appear * (1f - leave)
            val sc = 0.92f + 0.08f * appear + 0.08f * leave
            scaleX = sc
            scaleY = sc
            translationY = (1f - appear) * 16.dp.toPx() - leave * 14.dp.toPx()
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The chrome "AX" mark. A diagonal light band sweeps across it once it has appeared.
        Image(
            painter = painterResource(R.drawable.ax_logo),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .width(250.dp)
                .drawBehind {
                    val a = smooth((cover.value - 0.72f) / 0.28f)
                    drawCircle(
                        Brush.radialGradient(
                            listOf(Color(0xFF2F6BFF).copy(alpha = 0.30f * a), Color.Transparent),
                            center = center, radius = size.width * 0.75f,
                        ),
                        radius = size.width * 0.75f, center = center,
                    )
                }
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val t = smooth(sweep.value)
                    val pos = t * 1.5f - 0.25f
                    if (sweep.value > 0f && sweep.value < 1f) {
                        val wv = size.width
                        val x0 = wv * pos
                        drawRect(
                            Brush.linearGradient(
                                0f to Color.Transparent,
                                0.5f to Color.White.copy(alpha = 0.85f),
                                1f to Color.Transparent,
                                start = Offset(x0 - wv * 0.18f, 0f),
                                end = Offset(x0 + wv * 0.18f, size.height * 0.45f),
                            ),
                            blendMode = BlendMode.SrcAtop,
                        )
                    }
                },
        )
        Text(
            text = "AX  GALLERY",
            style = TextStyle(
                fontWeight = FontWeight.Light,
                fontSize = 14.sp,
                letterSpacing = 9.sp,
                color = Color.White.copy(alpha = 0.92f),
            ),
            modifier = Modifier.padding(top = 18.dp),
        )
    }    }
}
