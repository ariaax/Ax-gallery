package com.iris.gallery.ui

import android.os.Build
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/** Global switch for the Liquid Glass look (a settings toggle can drive this later). */
object AxGlass {
    @Volatile var enabled: Boolean = true
}

/**
 * Shared state between the content that gets blurred (the "source") and the glass bar that sits on top.
 * The source records itself into [layer]; right after drawing, it draws a blurred copy of the part of
 * itself that lies under the bar, so the blur follows scrolling automatically.
 */
@Stable
class AxGlassState(val layer: GraphicsLayer, val blurLayer: GraphicsLayer) {
    var sourceOrigin by mutableStateOf(Offset.Zero)
    var barOrigin by mutableStateOf(Offset.Zero)
    var barSize by mutableStateOf(Size.Zero)
    var cornerRadiusPx by mutableStateOf(0f)
}

@Composable
fun rememberAxGlassState(): AxGlassState {
    val layer = rememberGraphicsLayer()
    val blurLayer = rememberGraphicsLayer()
    return remember(layer, blurLayer) { AxGlassState(layer, blurLayer) }
}

private val blurSupported: Boolean get() = Build.VERSION.SDK_INT >= 31

/** Put this on the big content area that should show up blurred behind the glass bar. */
fun Modifier.axGlassSource(state: AxGlassState): Modifier = this
    .onGloballyPositioned { state.sourceOrigin = it.positionInRoot() }
    .drawWithContent {
        val src = state.layer
        src.record(this, layoutDirection, IntSize(size.width.toInt(), size.height.toInt())) {
            this@drawWithContent.drawContent()
        }
        drawLayer(src)

        val barSize = state.barSize
        if (AxGlass.enabled && blurSupported && barSize.width > 1f && barSize.height > 1f) {
            val local = state.barOrigin - state.sourceOrigin
            val blur = state.blurLayer
            blur.renderEffect = BlurEffect(28f, 28f, TileMode.Clamp)
            blur.clip = true
            blur.setRoundRectOutline(Offset.Zero, barSize, state.cornerRadiusPx)
            blur.record(this, layoutDirection, IntSize(barSize.width.toInt(), barSize.height.toInt())) {
                translate(-local.x, -local.y) { drawLayer(src) }
            }
            translate(local.x, local.y) { drawLayer(blur) }
        }
    }

/**
 * Floating glass pill (TikTok-style) with a sliding glass "lens" on the selected tab.
 */
@Composable
fun AxGlassNavBar(
    state: AxGlassState,
    count: Int,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (index: Int, selected: Boolean) -> Unit,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val barHeight = 64.dp
    val itemWidth = 76.dp
    val shape = RoundedCornerShape(32.dp)
    val density = LocalDensity.current
    val tint = if (dark) Color.Black.copy(alpha = if (blurSupported) 0.28f else 0.82f)
    else Color.White.copy(alpha = if (blurSupported) 0.34f else 0.88f)
    val edge = Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = if (dark) 0.55f else 0.85f),
            Color.White.copy(alpha = 0.06f),
            Color.White.copy(alpha = if (dark) 0.30f else 0.55f),
        )
    )
    val lensOffset by animateDpAsState(
        targetValue = itemWidth * selectedIndex,
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        label = "glass lens offset",
    )

    Box(
        modifier = modifier
            .width(itemWidth * count + 12.dp)
            .height(barHeight)
            .onGloballyPositioned {
                state.barOrigin = it.positionInRoot()
                state.barSize = Size(it.size.width.toFloat(), it.size.height.toFloat())
                state.cornerRadiusPx = with(density) { 32.dp.toPx() }
            }
            .shadow(10.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
            .clip(shape)
            .background(tint)
            .drawBehind {
                // soft top highlight, like light catching the glass edge
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = if (dark) 0.14f else 0.30f), Color.Transparent),
                        endY = size.height * 0.55f,
                    )
                )
            }
            .border(1.2.dp, edge, shape),
    ) {
        // glass lens on the selected tab
        Box(
            Modifier
                .offset(x = lensOffset + 6.dp, y = 6.dp)
                .width(itemWidth)
                .height(barHeight - 12.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(Color.White.copy(alpha = if (dark) 0.20f else 0.38f))
        )
        Row(Modifier.padding(horizontal = 6.dp).fillMaxHeight()) {
            for (i in 0 until count) {
                Box(
                    modifier = Modifier
                        .width(itemWidth)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    val iconColor = (if (dark) Color.White else Color.Black).copy(alpha = if (i == selectedIndex) 1f else 0.72f)
                    CompositionLocalProvider(LocalContentColor provides iconColor) {
                        icon(i, i == selectedIndex)
                    }
                }
            }
        }
    }
}
