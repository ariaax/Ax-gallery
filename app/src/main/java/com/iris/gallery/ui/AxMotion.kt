package com.iris.gallery.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
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
        // no off-screen buffer for the fade: much lighter on the GPU for a full-screen grid
        compositingStrategy = CompositingStrategy.ModulateAlpha
        val v = p.value
        val dir = if (forward) 1f else -1f
        translationX = (1f - v) * size.width * (if (forward) 0.22f else 0.08f) * dir
        alpha = (v * 1.6f).coerceAtMost(1f)
    }
}

/** Remembers which grid cell was tapped, so the viewer can grow out of it and shrink back into it. */
object AxHero {
    /** false while the open animation runs: heavy full-size decoding waits until it is finished. */
    val settledState = androidx.compose.runtime.mutableStateOf(true)

    private var coords: LayoutCoordinates? = null
    private var mediaId: Long = Long.MIN_VALUE

    fun mark(c: LayoutCoordinates?, id: Long) {
        coords = c
        mediaId = id
    }

    /** Bounds (in window coordinates) of the tapped cell, only if it belongs to [id] and is still on screen. */
    fun rectFor(id: Long?): Rect? {
        val c = coords ?: return null
        if (id == null || id != mediaId || !c.isAttached) return null
        val r = c.boundsInRoot()
        return if (r.width > 8f && r.height > 8f) r else null
    }
}

/**
 * Clip used while the viewer opens/closes. Local coordinates are those of the full-screen layer,
 * which is scaled down by [s0] at p = 0: a screen-wide square then looks exactly like the thumbnail.
 */
class AxHeroClip(private val p: Float, private val s0: Float, private val cornerPx: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val h = size.width + (size.height - size.width) * p
        val top = (size.height - h) / 2f
        val r = cornerPx * (1f - p) / (s0 + (1f - s0) * p)
        return Outline.Rounded(RoundRect(0f, top, size.width, top + h, CornerRadius(r, r)))
    }
}
