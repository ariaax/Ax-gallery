package com.iris.gallery.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iris.gallery.R
import kotlinx.coroutines.delay

// ---- launch animation -------------------------------------------------------------------------------------------
// Black, a faint navy light comes up behind the mark, the chrome AX rises into place, one narrow band of light
// crosses it, the wordmark settles under it, a short hold, then the whole layer fades into the gallery.
// The logo is the original ax_logo.png, untouched: only alpha, scale and position move, and the light is drawn
// over its own pixels (SrcAtop), so its colours and shape never change.
// One clock drives everything and is read only inside graphicsLayer / draw lambdas, so nothing recomposes per frame.

private val EaseOutExpo: Easing = CubicBezierEasing(0.16f, 1f, 0.30f, 1f)
private val EaseOut: Easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
private val EaseInOut: Easing = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)

private const val CLOCK_MS = 840f

/** Progress (0..1, eased) of the part of the timeline that runs from [start] to [end] milliseconds. */
private fun phase(ms: Float, start: Float, end: Float, easing: Easing): Float =
    easing.transform(((ms - start) / (end - start)).coerceIn(0f, 1f))

@Composable
fun AxPremiumIntro(onCovered: () -> Unit = {}, onFinished: () -> Unit) {
    val clock = remember { Animatable(0f) }
    val leave = remember { Animatable(0f) }
    val context = LocalContext.current
    // "Remove animations" in the system settings: show the finished composition for a moment and go.
    val reduceMotion = remember {
        runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            ) == 0f
        }.getOrDefault(false)
    }

    LaunchedEffect(Unit) {
        if (reduceMotion) clock.snapTo(1f) else clock.animateTo(1f, tween(CLOCK_MS.toInt(), easing = LinearEasing))
        // The mark and wordmark are settled and nothing moves now: build the gallery behind the layer here, so the
        // heavy first composition lands in the hold instead of in the middle of a movement.
        onCovered()
        withFrameNanos { }
        withFrameNanos { }
        delay(if (reduceMotion) 150 else 120)
        leave.animateTo(1f, tween(if (reduceMotion) 120 else 240, easing = LinearEasing))
        onFinished()
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = 1f - EaseInOut.transform(leave.value) }
            // swallow touches while the intro is visible
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}
            .drawBehind {
                // Same near-black as the window background, so the first frame matches the launch screen (no flash).
                drawRect(Color(0xFF040405))
                val glow = phase(clock.value * CLOCK_MS, 0f, 420f, EaseOut)
                if (glow > 0f) {
                    val c = Offset(size.width * 0.5f, size.height * 0.44f)
                    val r = size.width * 1.05f
                    drawRect(
                        Brush.radialGradient(
                            0f to Color(0xFF10204A).copy(alpha = 0.95f * glow),
                            0.55f to Color(0xFF070D22).copy(alpha = 0.70f * glow),
                            1f to Color.Transparent,
                            center = c,
                            radius = r,
                        ),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            // a very slight push-in while the layer leaves, so the exit has the same direction as the entrance
            modifier = Modifier.graphicsLayer {
                val s = 1f + 0.035f * EaseInOut.transform(leave.value)
                scaleX = s
                scaleY = s
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.ax_logo),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .width(250.dp)
                    .graphicsLayer {
                        val ms = clock.value * CLOCK_MS
                        val rise = phase(ms, 80f, 640f, EaseOutExpo)
                        alpha = phase(ms, 80f, 400f, EaseOut)
                        val sc = 0.90f + 0.10f * rise
                        scaleX = sc
                        scaleY = sc
                        translationY = (1f - rise) * 18.dp.toPx()
                    }
                    .drawBehind {
                        // soft cool light behind the mark, comes up with the logo
                        val a = phase(clock.value * CLOCK_MS, 120f, 560f, EaseOut)
                        if (a > 0f) {
                            drawCircle(
                                Brush.radialGradient(
                                    listOf(Color(0xFF2F6BFF).copy(alpha = 0.26f * a), Color.Transparent),
                                    center = center, radius = size.width * 0.75f,
                                ),
                                radius = size.width * 0.75f, center = center,
                            )
                        }
                    }
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        // One narrow band of light crossing the chrome, drawn only over the logo's own pixels.
                        val s = phase(clock.value * CLOCK_MS, 440f, 840f, EaseInOut)
                        if (s > 0f && s < 1f) {
                            val wv = size.width
                            val x0 = wv * (s * 1.6f - 0.3f)
                            drawRect(
                                Brush.linearGradient(
                                    0f to Color.Transparent,
                                    0.5f to Color.White.copy(alpha = 0.62f),
                                    1f to Color.Transparent,
                                    start = Offset(x0 - wv * 0.14f, 0f),
                                    end = Offset(x0 + wv * 0.14f, size.height * 0.40f),
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
                modifier = Modifier
                    .padding(top = 18.dp)
                    .graphicsLayer {
                        // letter-spacing is simulated with a horizontal scale: animating the real spacing would
                        // re-measure the text on every frame
                        val w = phase(clock.value * CLOCK_MS, 560f, 840f, EaseOut)
                        alpha = w
                        scaleX = 1.12f - 0.12f * w
                        translationY = (1f - w) * 6.dp.toPx()
                    },
            )
        }
    }
}
