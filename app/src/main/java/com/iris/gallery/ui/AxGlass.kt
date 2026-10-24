package com.iris.gallery.ui

import android.os.Build
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.asComposeRenderEffect
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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.unit.dp

/** Global switch for the Liquid Glass look (a settings toggle can drive this later). */
object AxGlass {
    /** Compose state, so flipping the setting updates the UI immediately. */
    var enabled: Boolean by mutableStateOf(true)
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
    /** Extra glass panels (viewer toolbars, filmstrip, round buttons...). */
    val regions = mutableStateListOf<AxGlassRegion>()
    /** Turn off the (costly) live blur, e.g. while a video plays: video surfaces can't be captured anyway. */
    var blurEnabled by mutableStateOf(true)
}

@Stable
class AxGlassRegion(val layer: GraphicsLayer) {
    var origin by mutableStateOf(Offset.Zero)
    var size by mutableStateOf(Size.Zero)
    var cornerPx by mutableStateOf(0f)
}

@Composable
fun rememberAxGlassState(): AxGlassState {
    val layer = rememberGraphicsLayer()
    val blurLayer = rememberGraphicsLayer()
    return remember(layer, blurLayer) { AxGlassState(layer, blurLayer) }
}

private val blurSupported: Boolean get() = Build.VERSION.SDK_INT >= 31

// Immutable, so one shared instance avoids allocating a new effect on every frame.
// Blur + strong saturation boost: the bar picks up (and intensifies) the colours of whatever is behind it.
private val NavBlurEffect: androidx.compose.ui.graphics.RenderEffect by lazy {
    if (Build.VERSION.SDK_INT >= 31) {
        val matrix = android.graphics.ColorMatrix().apply { setSaturation(1.5f) }
        val blur = android.graphics.RenderEffect.createBlurEffect(30f, 30f, android.graphics.Shader.TileMode.CLAMP)
        android.graphics.RenderEffect
            .createColorFilterEffect(android.graphics.ColorMatrixColorFilter(matrix), blur)
            .asComposeRenderEffect()
    } else {
        BlurEffect(28f, 28f, TileMode.Clamp)
    }
}
private val PanelBlurEffect = BlurEffect(24f, 24f, TileMode.Clamp)

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
            blur.renderEffect = NavBlurEffect
            blur.clip = true
            blur.setRoundRectOutline(Offset.Zero, barSize, state.cornerRadiusPx)
            blur.record(this, layoutDirection, IntSize(barSize.width.toInt(), barSize.height.toInt())) {
                translate(-local.x, -local.y) { drawLayer(src) }
            }
            translate(local.x, local.y) { drawLayer(blur) }
        }

        if (AxGlass.enabled && blurSupported && state.blurEnabled) {
            for (r in state.regions) {
                val rs = r.size
                if (rs.width > 1f && rs.height > 1f) {
                    val local = r.origin - state.sourceOrigin
                    val blur = r.layer
                    blur.renderEffect = PanelBlurEffect
                    blur.clip = true
                    blur.setRoundRectOutline(Offset.Zero, rs, r.cornerPx)
                    blur.record(this, layoutDirection, IntSize(rs.width.toInt(), rs.height.toInt())) {
                        translate(-local.x, -local.y) { drawLayer(src) }
                    }
                    translate(local.x, local.y) { drawLayer(blur) }
                }
            }
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
    val tint = if (dark) Color.Black.copy(alpha = if (blurSupported) 0.26f else 0.82f)
    else Color.White.copy(alpha = if (blurSupported) 0.22f else 0.88f)
    val accent = MaterialTheme.colorScheme.primary
    val edge = Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = if (dark) 0.55f else 0.85f),
            accent.copy(alpha = 0.20f),
            Color.White.copy(alpha = if (dark) 0.35f else 0.60f),
        )
    )
    val lensOffset by animateDpAsState(
        targetValue = itemWidth * selectedIndex,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 380f),
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
            .shadow(10.dp, shape, clip = false, ambientColor = accent.copy(alpha = 0.25f), spotColor = accent.copy(alpha = 0.25f))
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
            .border(1.6.dp, edge, shape),
    ) {
        // glass lens on the selected tab
        Box(
            Modifier
                .offset(x = lensOffset + 6.dp, y = 6.dp)
                .width(itemWidth)
                .height(barHeight - 12.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(accent.copy(alpha = 0.18f))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = if (dark) 0.22f else 0.45f),
                            Color.White.copy(alpha = if (dark) 0.06f else 0.15f),
                        )
                    )
                )
                .border(
                    1.dp,
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.08f))),
                    RoundedCornerShape(26.dp),
                )
        )
        Row(Modifier.padding(horizontal = 6.dp).fillMaxHeight()) {
            for (i in 0 until count) {
                val itemSource = remember { MutableInteractionSource() }
                val pressed by itemSource.collectIsPressedAsState()
                val pressScale by animateFloatAsState(
                    if (pressed) 0.86f else 1f,
                    spring(dampingRatio = 0.5f, stiffness = 500f),
                    label = "tab press",
                )
                Box(
                    modifier = Modifier
                        .width(itemWidth)
                        .fillMaxHeight()
                        .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
                        .clickable(
                            interactionSource = itemSource,
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

/**
 * Turns any box (toolbar, filmstrip, round button...) into a dark glass panel that blurs whatever the
 * [axGlassSource] draws behind it. Use with a transparent Surface / plain container.
 */
@Composable
fun Modifier.axGlassPanel(state: AxGlassState, corner: Dp): Modifier {
    val layer = rememberGraphicsLayer()
    val region = remember(layer) { AxGlassRegion(layer) }
    val density = LocalDensity.current
    DisposableEffect(region) {
        state.regions.add(region)
        onDispose { state.regions.remove(region) }
    }
    val shape = RoundedCornerShape(corner)
    val tint = Color.Black.copy(alpha = if (blurSupported && state.blurEnabled) 0.34f else 0.58f)
    val edge = Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = 0.50f),
            Color.White.copy(alpha = 0.05f),
            Color.White.copy(alpha = 0.28f),
        )
    )
    return this
        .onGloballyPositioned {
            region.origin = it.positionInRoot()
            region.size = Size(it.size.width.toFloat(), it.size.height.toFloat())
            region.cornerPx = with(density) { corner.toPx() }
        }
        .clip(shape)
        .background(tint)
        .drawBehind {
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.13f), Color.Transparent),
                    endY = size.height * 0.55f,
                )
            )
        }
        .border(1.dp, edge, shape)
}

private val HeartPink = Color(0xFFFF3B6B)
private val HeartOrange = Color(0xFFFF9A5A)

/**
 * Favorite button: bouncy pop, expanding ring, little sparks and a haptic tick when it becomes a favorite.
 */
@Composable
fun AxFavoriteButton(
    isFav: Boolean,
    label: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val progress = remember { Animatable(1f) }
    val scale = remember { Animatable(1f) }
    var first by remember { mutableStateOf(true) }
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(isFav) {
        if (first) {
            first = false
            return@LaunchedEffect
        }
        if (isFav) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            launch {
                progress.snapTo(0f)
                progress.animateTo(1f, tween(650, easing = LinearOutSlowInEasing))
            }
            scale.snapTo(0.5f)
            scale.animateTo(1f, spring(dampingRatio = 0.32f, stiffness = Spring.StiffnessLow))
        } else {
            scale.snapTo(0.75f)
            scale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium))
        }
    }

    Box(modifier.height(if (compact) 36.dp else 48.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.requiredSize(72.dp)) {
            val p = progress.value
            if (p < 1f) {
                val c = center
                val inv = 1f - p
                // soft glow
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(HeartPink.copy(alpha = 0.45f * inv), Color.Transparent),
                        center = c,
                        radius = 30.dp.toPx(),
                    ),
                    radius = 30.dp.toPx(),
                    center = c,
                )
                // expanding ring
                drawCircle(
                    color = HeartPink.copy(alpha = 0.85f * inv),
                    radius = (10.dp.toPx() + 24.dp.toPx() * p),
                    center = c,
                    style = Stroke(width = (2.5.dp.toPx() * inv).coerceAtLeast(0.5f)),
                )
                // sparks
                val count = 10
                for (i in 0 until count) {
                    val angle = (i * (2.0 * Math.PI / count)) + 0.3
                    val dist = 12.dp.toPx() + 22.dp.toPx() * p
                    val pos = Offset(c.x + (cos(angle) * dist).toFloat(), c.y + (sin(angle) * dist).toFloat())
                    drawCircle(
                        color = (if (i % 2 == 0) HeartPink else HeartOrange).copy(alpha = inv),
                        radius = (3.2.dp.toPx() * inv).coerceAtLeast(0.4f),
                        center = pos,
                    )
                }
            }
        }
        IconButton(onClick = onClick, modifier = if (compact) Modifier.size(36.dp) else Modifier) {
            Icon(
                imageVector = if (isFav) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = label,
                tint = if (isFav) HeartPink else Color.White,
                modifier = Modifier.size(if (compact) 22.dp else 26.dp).scale(scale.value),
            )
        }
    }
}


/**
 * Icy-white halo along the top or bottom edge of the screen (iOS-style scroll-edge glow).
 * Fades in once, and plays a single soft light sweep whenever [sweepKey] changes (e.g. tab switch).
 */
@Composable
fun AxEdgeHalo(top: Boolean, sweepKey: Any, modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val base = if (dark) Color(0xFFE6F1FF) else Color(0xFFB4D2FF)
    val peak = if (dark) 0.17f else 0.26f
    val appear = remember { Animatable(0f) }
    val sweep = remember { Animatable(1f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(700)) }
    LaunchedEffect(sweepKey) {
        sweep.snapTo(0f)
        sweep.animateTo(1f, tween(1100, easing = FastOutSlowInEasing))
    }
    val inset = if (top) WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val total = inset + (if (top) 92.dp else 128.dp)
    Box(
        modifier
            .fillMaxWidth()
            .height(total)
            .drawBehind {
                val a = appear.value
                val c0 = base.copy(alpha = peak * a)
                val c1 = base.copy(alpha = peak * 0.38f * a)
                val brush = if (top) Brush.verticalGradient(
                    colorStops = arrayOf(0f to c0, 0.45f to c1, 1f to Color.Transparent)
                ) else Brush.verticalGradient(
                    colorStops = arrayOf(0f to Color.Transparent, 0.55f to c1, 1f to c0)
                )
                drawRect(brush)
                val sw = sweep.value
                if (sw > 0.001f && sw < 0.999f) {
                    val bell = sin(Math.PI * sw).toFloat()
                    val x = size.width * (-0.2f + 1.4f * sw)
                    val cy = if (top) 0f else size.height
                    drawRect(
                        Brush.radialGradient(
                            listOf(Color.White.copy(alpha = 0.24f * bell), Color.Transparent),
                            center = Offset(x, cy),
                            radius = size.width * 0.38f,
                        )
                    )
                }
            }
    )
}

/** Small round glass tab shown when a side dock is collapsed. */
@Composable
fun AxDockTab(
    state: AxGlassState,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(42.dp)
            .axGlassPanel(state, 21.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(21.dp))
    }
}


/** Soft glass edge for sheets and dialogs (gradient hairline that works on dark and light themes). */
@Composable
fun Modifier.axGlassBorder(shape: androidx.compose.ui.graphics.Shape): Modifier {
    val c = MaterialTheme.colorScheme.onSurface
    return this.border(
        1.dp,
        Brush.linearGradient(listOf(c.copy(alpha = 0.42f), c.copy(alpha = 0.06f), c.copy(alpha = 0.24f))),
        shape,
    )
}

/**
 * Blurs what is behind a dialog / bottom-sheet window (Android 12+, when the device allows window blur).
 * Safe no-op everywhere else. Call it once inside the dialog's content.
 */
@Composable
fun AxWindowBlur(radius: Int = 36) {
    val view = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(view, radius) {
        if (Build.VERSION.SDK_INT < 31) return@LaunchedEffect
        try {
            val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
            if (window != null) {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                val lp = window.attributes
                lp.blurBehindRadius = radius
                window.attributes = lp
            } else {
                val root = view.rootView
                val lp = root.layoutParams as? android.view.WindowManager.LayoutParams
                if (lp != null) {
                    lp.flags = lp.flags or android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                    lp.blurBehindRadius = radius
                    val wm = view.context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
                    wm.updateViewLayout(root, lp)
                }
            }
        } catch (e: Throwable) {
            // blur is only decoration
        }
    }
}
