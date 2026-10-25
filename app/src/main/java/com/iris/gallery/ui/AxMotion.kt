package com.iris.gallery.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Light touch feedback: the element shrinks a little while a finger is on it and springs back.
 * Only the draw phase reads the animated value, so nothing is re-laid-out while animating.
 */
@Composable
fun Modifier.axPressScale(source: InteractionSource, pressedScale: Float = 0.95f): Modifier {
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 520f),
        label = "axPress",
    )
    return this.graphicsLayer {
        scaleX = s
        scaleY = s
    }
}

/** Soft entrance: fades in and rises ~16dp once, in 240ms. Only active when liquid glass is on. */
@Composable
fun Modifier.axEnter(): Modifier {
    if (!AxGlass.enabled) return this
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, tween(240, easing = FastOutSlowInEasing)) }
    return this.graphicsLayer {
        val v = p.value
        alpha = v
        translationY = (1f - v) * 16.dp.toPx()
    }
}

/** Fast start, long soft landing: close to the curve iOS uses for push and modal transitions. */
val AxIosEase = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/**
 * iOS-like push: when this content first appears it glides in from the side (forward = opening a
 * folder, otherwise coming back) while fading in. Runs once, only touches the draw layer.
 */
@Composable
fun Modifier.axPushIn(forward: Boolean): Modifier {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, tween(360, easing = AxIosEase)) }
    return this.graphicsLayer {
        val v = p.value
        val dir = if (forward) 1f else -1f
        translationX = (1f - v) * size.width * (if (forward) 0.22f else 0.08f) * dir
        alpha = (v * 1.6f).coerceAtMost(1f)
    }
}
