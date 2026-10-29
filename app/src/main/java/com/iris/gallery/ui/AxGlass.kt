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
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.clipPath
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlin.math.roundToInt

/** Direct vibrator haptics: works even when the phone's "touch feedback" setting is off. */
object AxHaptics {
    private fun vibrator(context: android.content.Context): android.os.Vibrator? = try {
        if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        }
    } catch (e: Throwable) { null }

    private fun fire(context: android.content.Context, heavy: Boolean) {
        try {
            val v = vibrator(context) ?: return
            if (!v.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= 29) {
                v.vibrate(android.os.VibrationEffect.createPredefined(
                    if (heavy) android.os.VibrationEffect.EFFECT_HEAVY_CLICK else android.os.VibrationEffect.EFFECT_TICK))
            } else {
                v.vibrate(android.os.VibrationEffect.createOneShot(if (heavy) 30L else 14L, 120))
            }
        } catch (e: Throwable) { }
    }

    fun tick(context: android.content.Context) = fire(context, false)
    fun heavy(context: android.content.Context) = fire(context, true)
}

/** Global switch for the Liquid Glass look (a settings toggle can drive this later). */
/** Weak-phone detection: low-RAM / old Android -> no endless animations, no sensor parallax. Computed once. */
object AxPerf {
    private var cached: Boolean? = null
    fun lite(ctx: android.content.Context): Boolean {
        cached?.let { return it }
        val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val v = Build.VERSION.SDK_INT < 31 || am?.isLowRamDevice == true || (am?.memoryClass ?: 256) <= 128
        cached = v
        return v
    }
}

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

private const val NAV_REFRACT_AGSL = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float depth;
half4 main(float2 p) {
    float2 c = size * 0.5;
    float2 q = abs(p - c) - (c - float2(radius));
    float outside = length(max(q, float2(0.0)));
    float sd = outside + min(max(q.x, q.y), 0.0) - radius;
    float inside = -sd;
    if (inside >= depth) { return content.eval(p); }
    float t = 1.0 - clamp(inside / depth, 0.0, 1.0);
    float2 s = sign(p - c);
    float2 g;
    if (q.x > 0.0 || q.y > 0.0) { g = normalize(max(q, float2(0.0))) * s; }
    else if (q.x > q.y) { g = float2(s.x, 0.0); }
    else { g = float2(0.0, s.y); }
    float2 sp = p - g * (t * t * depth * 0.9);
    sp = clamp(sp, float2(1.0), size - float2(1.0));
    half4 col = content.eval(sp);
    col.rgb += half3(t * t * 0.10) * col.a;
    return col;
}
"""

private var navShader: android.graphics.RuntimeShader? = null
private var navCacheKey = ""
private var navCacheEffect: androidx.compose.ui.graphics.RenderEffect? = null

/** Light blur + real edge refraction (Android 13+); older phones keep the plain blur. Cached: rebuilt only when the bar's size changes. */
private fun navGlassEffect(w: Float, h: Float, radius: Float): androidx.compose.ui.graphics.RenderEffect {
    // The AGSL refraction chain did not render on some phones (MIUI/Adreno): the bar then showed the sharp photo.
    // A plain blur always renders, so it is used everywhere; the rim/glint give the glass look.
    if (true) return NavBlurPlain
    val key = "${w.toInt()}x${h.toInt()}r${radius.toInt()}"
    val cached = navCacheEffect
    if (cached != null && key == navCacheKey) return cached
    return try {
        val sh = navShader ?: android.graphics.RuntimeShader(NAV_REFRACT_AGSL).also { navShader = it }
        sh.setFloatUniform("size", w, h)
        sh.setFloatUniform("radius", radius)
        sh.setFloatUniform("depth", minOf(h * 0.55f, 46f))
        val blur = android.graphics.RenderEffect.createBlurEffect(24f, 24f, android.graphics.Shader.TileMode.CLAMP)
        val refr = android.graphics.RenderEffect.createRuntimeShaderEffect(sh, "content")
        val eff = android.graphics.RenderEffect.createChainEffect(refr, blur).asComposeRenderEffect()
        navCacheKey = key
        navCacheEffect = eff
        eff
    } catch (e: Throwable) {
        NavBlurEffect
    }
}

private val NavBlurPlain = BlurEffect(26f, 26f, TileMode.Clamp)
private val NavBlurSmall = BlurEffect(3f, 3f, TileMode.Clamp)

private val PanelBlurEffect = BlurEffect(18f, 18f, TileMode.Clamp)

/** Put this on the big content area that should show up blurred behind the glass bar. */
fun Modifier.axGlassSource(state: AxGlassState): Modifier = this
    .onGloballyPositioned { state.sourceOrigin = it.positionInRoot() }
    .drawWithContent {
        // The content is drawn normally (no capture layer in between). For every glass area we play the same
        // content once more into a small layer, shifted so that exactly the part under the glass lands in it,
        // and blur that layer. Drawing a layer inside another layer did not render on some phones, this does.
        drawContent()

        val barSize = state.barSize
        if (AxGlass.enabled && blurSupported && barSize.width > 1f && barSize.height > 1f) {
            val local = state.barOrigin - state.sourceOrigin
            val blur = state.blurLayer
            blur.renderEffect = navGlassEffect(barSize.width, barSize.height, state.cornerRadiusPx)
            blur.clip = true
            blur.setRoundRectOutline(Offset.Zero, barSize, state.cornerRadiusPx)
            blur.record(this, layoutDirection, IntSize(barSize.width.toInt(), barSize.height.toInt())) {
                translate(-local.x, -local.y) { this@drawWithContent.drawContent() }
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
                        translate(-local.x, -local.y) { this@drawWithContent.drawContent() }
                    }
                    translate(local.x, local.y) { drawLayer(blur) }
                }
            }
        }
    }

/** Scroll direction -> the bar shrinks into a small pill while you scroll down, and grows back when you scroll up. */
object AxNavCollapse {
    var collapsed by mutableStateOf(false)
    val connection = object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
        private var acc = 0f
        override fun onPreScroll(
            available: Offset,
            source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
        ): Offset {
            val dy = available.y
            if (dy == 0f) return Offset.Zero
            if ((dy > 0f && acc < 0f) || (dy < 0f && acc > 0f)) acc = 0f
            acc += dy
            if (acc < -48f) { if (!collapsed) collapsed = true; acc = 0f }
            else if (acc > 48f) { if (collapsed) collapsed = false; acc = 0f }
            return Offset.Zero
        }
    }
}

/**
 * Full-width floating glass bar (iOS 26 style): light translucent glass with a thin bright rim, an elastic
 * "lens" that stretches while pressed/dragged and can be dragged across the tabs, and it shrinks into a
 * small pill (only the selected tab) while scrolling down.
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
    val density = LocalDensity.current
    val axCtx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val accent = MaterialTheme.colorScheme.primary
    val c by animateFloatAsState(
        if (AxNavCollapse.collapsed) 1f else 0f,
        spring(dampingRatio = 0.82f, stiffness = 420f),
        label = "nav collapse",
    )
    val tint = Color.White.copy(alpha = if (blurSupported) 0.14f else 0.22f).compositeOver(Color.Black.copy(alpha = if (blurSupported) 0.22f else 0.80f))
    val edge = Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = 0.95f),
            Color.White.copy(alpha = 0.10f),
            Color.White.copy(alpha = 0.80f),
        )
    )
    val lensAnim = remember { Animatable(selectedIndex.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    var dragIndex by remember { mutableFloatStateOf(selectedIndex.toFloat()) }
    var pressedAny by remember { mutableStateOf(false) }
    // light glint that sweeps across the glass whenever the tab changes
    val glint = remember { Animatable(1f) }
    LaunchedEffect(selectedIndex) {
        glint.snapTo(0f)
        glint.animateTo(1f, tween(750, easing = androidx.compose.animation.core.FastOutSlowInEasing))
    }
    LaunchedEffect(selectedIndex) {
        if (!dragging) lensAnim.animateTo(selectedIndex.toFloat(), spring(dampingRatio = 0.62f, stiffness = 380f))
    }
    val lensScale by animateFloatAsState(
        if (dragging || pressedAny) 1.16f else 1f,
        spring(dampingRatio = 0.5f, stiffness = 520f),
        label = "lens stretch",
    )

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        val fullW = minOf(maxWidth, 12.dp + 80.dp * count)
        val itemFull = (fullW - 12.dp) / count
        val itemW = itemFull + (58.dp - itemFull) * c
        val barW = itemW * (1f + (count - 1) * (1f - c)) + 12.dp
        val barH = 66.dp + (50.dp - 66.dp) * c
        val shape = RoundedCornerShape(50)
        val itemWPx = with(density) { itemW.toPx() }
        val itemWPxState = rememberUpdatedState(itemWPx)
        val padPx = with(density) { 6.dp.toPx() }

        Box(
            modifier = Modifier
                .width(barW)
                .height(barH)
                .onGloballyPositioned {
                    state.barOrigin = it.positionInRoot()
                    state.barSize = Size(it.size.width.toFloat(), it.size.height.toFloat())
                    state.cornerRadiusPx = it.size.height / 2f
                }
                .shadow(14.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.30f), spotColor = Color.Black.copy(alpha = 0.40f))
                .clip(shape)
                .background(tint)
                .drawBehind {
                    drawRect(
                        brush = Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = if (dark) 0.10f else 0.28f), Color.Transparent),
                            endY = size.height * 0.5f,
                        )
                    )
                }
                .drawBehind {
                    val g = glint.value
                    if (g < 1f) {
                        val h = size.height
                        val x = -h + g * (size.width + h * 2f)
                        val a = kotlin.math.sin(Math.PI.toFloat() * g) * 0.30f
                        drawRect(
                            Brush.linearGradient(
                                listOf(Color.Transparent, Color.White.copy(alpha = a), Color.Transparent),
                                start = Offset(x - h * 0.6f, 0f),
                                end = Offset(x + h * 0.6f, h),
                            )
                        )
                    }
                }
                .drawBehind {
                    // inner soft rim: gives the thick-glass look of the reference
                    val inset = 3.dp.toPx()
                    drawRoundRect(
                        Color.White.copy(alpha = 0.20f),
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - inset * 2, size.height - inset * 2),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius((size.height - inset * 2) / 2f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()),
                    )
                }
                .border(1.5.dp, edge, shape)
                .pointerInput(count) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            if (!AxNavCollapse.collapsed) {
                                dragging = true
                                dragIndex = lensAnim.value
                            }
                        },
                        onDragEnd = {
                            if (dragging) {
                                dragging = false
                                val target = dragIndex.roundToInt().coerceIn(0, count - 1)
                                AxHaptics.tick(axCtx)
                                scope.launch {
                                    lensAnim.snapTo(dragIndex)
                                    lensAnim.animateTo(target.toFloat(), spring(dampingRatio = 0.62f, stiffness = 380f))
                                }
                                onSelect(target)
                            }
                        },
                        onDragCancel = {
                            dragging = false
                            scope.launch { lensAnim.animateTo(selectedIndex.toFloat(), spring(dampingRatio = 0.62f, stiffness = 380f)) }
                        },
                        onHorizontalDrag = { change, dx ->
                            if (dragging) {
                                change.consume()
                                dragIndex = (dragIndex + dx / itemWPxState.value).coerceIn(0f, count - 1f)
                            }
                        },
                    )
                },
        ) {
            // elastic glass lens on the selected tab
            Box(
                Modifier
                    .offset {
                        val idx = if (dragging) dragIndex else lensAnim.value
                        val rowShift = -(itemWPx * selectedIndex) * c
                        IntOffset((padPx + idx * itemWPx + rowShift).roundToInt(), 0)
                    }
                    .width(itemW)
                    .fillMaxHeight()
                    .padding(vertical = 6.dp)
                    .graphicsLayer {
                        scaleX = lensScale
                        scaleY = 1f + (lensScale - 1f) * 0.55f
                    }
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.40f))
                    .background(accent.copy(alpha = 0.16f))
                    .border(
                        1.dp,
                        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.08f))),
                        RoundedCornerShape(50),
                    )
            )
            Row(
                Modifier
                    .offset { IntOffset((padPx - itemWPx * selectedIndex * c).roundToInt(), 0) }
                    .requiredWidth(itemW * count)
                    .fillMaxHeight()
            ) {
                for (i in 0 until count) {
                    val itemSource = remember { MutableInteractionSource() }
                    val pressed by itemSource.collectIsPressedAsState()
                    LaunchedEffect(pressed) { pressedAny = pressed }
                    val itemAlpha = if (i == selectedIndex) 1f else (1f - c).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .width(itemW)
                            .fillMaxHeight()
                            .graphicsLayer { alpha = itemAlpha }
                            .clickable(interactionSource = itemSource, indication = null) {
                                AxHaptics.tick(axCtx)
                                if (AxNavCollapse.collapsed) AxNavCollapse.collapsed = false
                                else onSelect(i)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        val iconColor = Color.White.copy(alpha = if (i == selectedIndex) 1f else 0.82f)
                        CompositionLocalProvider(LocalContentColor provides iconColor) {
                            icon(i, i == selectedIndex)
                        }
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
    // One look for every viewer panel (top buttons, filmstrip, info, action bar): icy light glass.
    // It no longer turns black when blur is off (videos), so everything stays uniform.
    val tint = Color(0xFFCFE0FF).copy(alpha = 0.20f).compositeOver(Color.Black.copy(alpha = 0.30f))
    val edge = Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = 0.75f),
            Color.White.copy(alpha = 0.10f),
            Color.White.copy(alpha = 0.45f),
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
                    listOf(Color.White.copy(alpha = 0.24f), Color.Transparent),
                    endY = size.height * 0.55f,
                )
            )
        }
        .border(1.2.dp, edge, shape)
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


/** (soft dark background, bright accent or null for grey photos) taken from a photo. */
fun axPhotoColors(src: android.graphics.Bitmap): Pair<Color, Color?> {
    val fallback = Pair(Color(0xFF080808), null as Color?)
    return try {
        val base = if (src.config == android.graphics.Bitmap.Config.HARDWARE) src.copy(android.graphics.Bitmap.Config.ARGB_8888, false) ?: return fallback else src
        val small = android.graphics.Bitmap.createScaledBitmap(base, 24, 24, true)
        var r = 0.0; var g = 0.0; var b = 0.0; var wSum = 0.0
        val hsv = FloatArray(3)
        for (y in 0 until 24) for (x in 0 until 24) {
            val p = small.getPixel(x, y)
            android.graphics.Color.colorToHSV(p, hsv)
            val w = 0.15 + hsv[1] * hsv[2] // favour colourful, bright pixels over grey/black ones
            r += android.graphics.Color.red(p) * w
            g += android.graphics.Color.green(p) * w
            b += android.graphics.Color.blue(p) * w
            wSum += w
        }
        if (small !== base) small.recycle()
        if (base !== src) base.recycle()
        if (wSum <= 0.0) return fallback
        val avg = android.graphics.Color.rgb((r / wSum).toInt().coerceIn(0, 255), (g / wSum).toInt().coerceIn(0, 255), (b / wSum).toInt().coerceIn(0, 255))
        android.graphics.Color.colorToHSV(avg, hsv)
        val hue = hsv[0]; val sat = hsv[1]
        val bg = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, minOf(sat, 0.55f), 0.22f)))
        val accent = if (sat < 0.15f) null
        else Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat.coerceIn(0.55f, 0.9f), 0.97f)))
        Pair(bg, accent)
    } catch (e: Throwable) {
        fallback
    }
}

fun axDominantDark(src: android.graphics.Bitmap): Color = axPhotoColors(src).first
