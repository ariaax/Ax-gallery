package com.iris.gallery.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val IntroBg = Color(0xFF0E0F14)
private val IntroRed = Color(0xFFE5222F)

/**
 * Light launch animation (~1.5 s): "A" slides in, the red "X" spins in with a spring,
 * a ring pulses outward, a small caption fades in, then everything fades away.
 * Pure Compose drawing - no image assets.
 */
@Composable
fun AxIntro(onFinished: () -> Unit) {
    val aIn = remember { Animatable(0f) }
    val xIn = remember { Animatable(0f) }
    val ring = remember { Animatable(0f) }
    val tag = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        launch { aIn.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 220f)) }
        launch { delay(140); xIn.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 200f)) }
        launch { delay(380); ring.animateTo(1f, tween(900, easing = LinearOutSlowInEasing)) }
        launch { delay(650); tag.animateTo(1f, tween(500)) }
        delay(1300)
        fade.animateTo(0f, tween(300))
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade.value }
            .background(
                Brush.radialGradient(listOf(Color(0xFF241218), IntroBg), radius = 1100f)
            )
            // swallow touches while the intro is visible
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val p = ring.value
            if (p > 0f && p < 1f) {
                drawCircle(
                    color = IntroRed.copy(alpha = 0.55f * (1f - p)),
                    radius = size.minDimension * 0.62f * p,
                    style = Stroke(width = 3.dp.toPx()),
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.25f * (1f - p)),
                    radius = size.minDimension * 0.45f * p,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "A",
                    style = logoStyle(Color.White),
                    modifier = Modifier.graphicsLayer {
                        translationX = (1f - aIn.value) * -140.dp.toPx()
                        alpha = aIn.value.coerceIn(0f, 1f)
                    },
                )
                Text(
                    "X",
                    style = logoStyle(IntroRed),
                    modifier = Modifier.graphicsLayer {
                        rotationZ = (1f - xIn.value) * -150f
                        val s = 0.3f + 0.7f * xIn.value
                        scaleX = s
                        scaleY = s
                        alpha = xIn.value.coerceIn(0f, 1f)
                    },
                )
            }
            Text(
                "AX GALLERY",
                modifier = Modifier
                    .padding(top = 6.dp)
                    .graphicsLayer {
                        alpha = tag.value
                        translationY = (1f - tag.value) * 10.dp.toPx()
                    },
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.75f),
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Light,
                    fontSize = 14.sp,
                    letterSpacing = 7.sp,
                ),
            )
        }
    }
}

private fun logoStyle(color: Color) = TextStyle(
    color = color,
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.Black,
    fontSize = 96.sp,
    letterSpacing = 2.sp,
)
