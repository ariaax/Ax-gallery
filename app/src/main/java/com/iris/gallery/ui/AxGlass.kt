package com.iris.gallery.ui

import android.os.Build
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.runtime.State
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.shape.CutCornerShape
import dev.shayxo.liquidglass.GlassStyle
import dev.shayxo.liquidglass.liquidGlass
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.animateContentSize
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
    /** Shader-based glass (library) on the bottom bar; Android 13+ only, set from Settings. */
    var real: Boolean by mutableStateOf(false)
    val realSupported: Boolean get() = Build.VERSION.SDK_INT >= 33
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

// Live backdrop blur is OFF: it did not show on the test phone, and it made the whole screen draw 2-6 times
// per frame (the content is replayed into every glass area), which is what caused ~20 fps. Glass panels are
// plain tinted frosted surfaces now. Set back to `Build.VERSION.SDK_INT >= 31` to try the blur again.
private val blurSupported: Boolean get() = false

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
        // Blur is off: return before touching any state, so the content is never redrawn just because the dock moved/resized.
        if (!blurSupported) return@drawWithContent

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
    realGlass: dev.shayxo.liquidglass.LiquidGlassState? = null,
    count: Int,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (index: Int, selected: Boolean) -> Unit,
) {
    val axCtx = androidx.compose.ui.platform.LocalContext.current
    val accent = MaterialTheme.colorScheme.primary
    val collapsed = AxNavCollapse.collapsed
    val barH by animateDpAsState(
        if (collapsed) 46.dp else 58.dp,
        spring(dampingRatio = 0.82f, stiffness = 420f),
        label = "dock height",
    )
    val tint = Color(0xFF0B0B0D).copy(alpha = 0.90f)
    val edge = Brush.linearGradient(
        listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = 0.22f))
    )
    // a light glint sweeps over the glass whenever the tab changes
    val glint = remember { Animatable(1f) }
    LaunchedEffect(selectedIndex) {
        glint.snapTo(0f)
        glint.animateTo(1f, tween(750, easing = FastOutSlowInEasing))
    }
    val shape = CutCornerShape(30)

    // One highlight pill that GLIDES between tabs. It is driven by a single 0..1 progress and the tabs' live bounds, and
    // everything is read only while drawing, so a tab change costs no recomposition (that was the stutter).
    val lefts = remember(count) { FloatArray(count) }
    val rights = remember(count) { FloatArray(count) }
    var rowRootX by remember { mutableStateOf(0f) }
    val boundsTick = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val move = remember { Animatable(1f) }
    val from = remember { FloatArray(2) }   // where the pill was when the tab changed
    val cur = remember { FloatArray(2) }    // where the pill was drawn last
    val dir = remember { intArrayOf(selectedIndex, 1) } // previous index, direction
    LaunchedEffect(selectedIndex) {
        if (dir[0] != selectedIndex) {
            dir[1] = if (selectedIndex >= dir[0]) 1 else -1
            dir[0] = selectedIndex
            from[0] = cur[0]; from[1] = cur[1]
            move.snapTo(0f)
            move.animateTo(1f, tween(340, easing = FastOutSlowInEasing))
        }
    }
    val spinLite = AxPerf.lite(axCtx)
    val spin = rememberInfiniteTransition(label = "dock ring")
    val spinAngle: State<Float> = spin.animateFloat(
        0f, if (spinLite) 0f else 360f,
        infiniteRepeatable(tween(2800, easing = LinearEasing)),
        label = "dock ring angle",
    )
    val pillPath = remember { androidx.compose.ui.graphics.Path() }
    val ringBrushColors = remember {
        listOf(Color(0xFF5AD1FF), Color(0xFF3B6BFF), Color(0xFFFF9A3C), Color(0xFFFFFFFF), Color(0xFF5AD1FF))
    }
    val innerFill = remember(accent) {
        accent.copy(alpha = 0.18f).compositeOver(Color.Black.copy(alpha = 0.40f).compositeOver(Color(0xFF0B0B0D)))
    }

    Box(
        modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(barH)
                .onGloballyPositioned {
                    rowRootX = it.positionInRoot().x
                    state.barOrigin = it.positionInRoot()
                    state.barSize = Size(it.size.width.toFloat(), it.size.height.toFloat())
                    state.cornerRadiusPx = it.size.height / 2f
                }
                .shadow(14.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.30f), spotColor = Color.Black.copy(alpha = 0.40f))
                .clip(shape)
                .then(
                    if (realGlass != null) Modifier.liquidGlass(realGlass, shape, GlassStyle.DarkChrome)
                    else Modifier.background(tint)
                )
                .drawBehind {
                    drawRect(
                        brush = Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.07f), Color.Transparent),
                            endY = size.height * 0.5f,
                        )
                    )
                    // gliding selection pill with its turning ring
                    boundsTick.intValue // redraw when a tab's bounds change (read in draw only)
                    val liveL = lefts.getOrElse(selectedIndex) { 0f }
                    val liveR = rights.getOrElse(selectedIndex) { 0f }
                    val t = move.value
                    var l = liveL
                    var r = liveR
                    if (t < 1f && cur[1] - cur[0] > 1f) {
                        // the edge that leads the move arrives first, the trailing one follows: the pill stretches like liquid
                        val lead = 1f - (1f - t) * (1f - t) * (1f - t)
                        val lag = t * t * (3f - 2f * t)
                        val tl = if (dir[1] > 0) lag else lead
                        val tr = if (dir[1] > 0) lead else lag
                        l = from[0] + (liveL - from[0]) * tl
                        r = from[1] + (liveR - from[1]) * tr
                    }
                    cur[0] = l; cur[1] = r
                    if (r - l > 8f) {
                        val pad = 5.dp.toPx()
                        val top = pad
                        val hh = size.height - pad * 2f
                        val ring = 2.dp.toPx()
                        pillPath.reset()
                        pillPath.addRoundRect(androidx.compose.ui.geometry.RoundRect(l, top, r, top + hh, androidx.compose.ui.geometry.CornerRadius(hh / 2f)))
                        val c = Offset((l + r) / 2f, size.height / 2f)
                        clipPath(pillPath) {
                            rotate(spinAngle.value, c) {
                                drawCircle(Brush.sweepGradient(ringBrushColors, center = c), radius = maxOf(r - l, hh), center = c)
                            }
                        }
                        drawRoundRect(
                            innerFill,
                            topLeft = Offset(l + ring, top + ring),
                            size = Size(r - l - ring * 2f, hh - ring * 2f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius((hh - ring * 2f) / 2f),
                        )
                    }
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
                .border(1.5.dp, edge, shape)
                .padding(5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (i in 0 until count) {
                val sel = i == selectedIndex
                androidx.compose.animation.AnimatedVisibility(
                    visible = sel || !collapsed,
                    enter = androidx.compose.animation.fadeIn(tween(160)) + androidx.compose.animation.expandHorizontally(),
                    exit = androidx.compose.animation.fadeOut(tween(120)) + androidx.compose.animation.shrinkHorizontally(),
                ) {
                    val itemSource = remember { MutableInteractionSource() }
                    val pressed by itemSource.collectIsPressedAsState()
                    val press by animateFloatAsState(if (pressed) 0.90f else 1f, spring(dampingRatio = 0.5f, stiffness = 520f), label = "dock press")
                    val itemShape = CutCornerShape(30)
                    Row(
                        modifier = Modifier
                            .fillMaxHeight()
                            .graphicsLayer { scaleX = press; scaleY = press }
                            .animateContentSize(spring(dampingRatio = 1f, stiffness = 520f))
                            .clip(itemShape)
                            .onGloballyPositioned {
                                val x = it.positionInRoot().x - rowRootX
                                if (lefts[i] != x || rights[i] != x + it.size.width) {
                                    lefts[i] = x; rights[i] = x + it.size.width
                                    if (i == selectedIndex) boundsTick.intValue++
                                }
                            }
                            .clickable(interactionSource = itemSource, indication = null) {
                                AxHaptics.tick(axCtx)
                                if (AxNavCollapse.collapsed) AxNavCollapse.collapsed = false else onSelect(i)
                            }
                            .padding(horizontal = if (sel) 16.dp else 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        val iconColor = Color.White.copy(alpha = if (sel) 1f else 0.78f)
                        CompositionLocalProvider(LocalContentColor provides iconColor) {
                            icon(i, sel)
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
    val shape = CutCornerShape(corner)
    // One look for every viewer panel (top buttons, filmstrip, info, action bar): translucent frosted glass in the style of
    // Telegram. The tint is light enough that the picture's own colours (the ambient gradient) glow through it; a white
    // wash + a bright top-lit rim give the frosted edge. It stays the same with or without live blur, so all panels match.
    val tint = Color(0xFF0B0D12).copy(alpha = 0.42f)
    val edge = Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = 0.42f),
            Color.White.copy(alpha = 0.06f),
            Color.White.copy(alpha = 0.26f),
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
            // frosted wash + light falling from the top
            drawRect(Color.White.copy(alpha = 0.07f))
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.14f), Color.Transparent),
                    endY = size.height * 0.60f,
                )
            )
        }
        .border(1.dp, edge, shape)
}

/** Container look for pop-up menus over a picture: dark enough to read over any photo, still glassy. */
object AxMenuGlass {
    val color: Color = Color(0xFF12151C).copy(alpha = 0.80f)
    val shape = CutCornerShape(22.dp)
    val border = BorderStroke(
        1.dp,
        Brush.linearGradient(listOf(Color.White.copy(alpha = 0.38f), Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.22f))),
    )
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

/**
 * Two colours for the viewer background, taken from the picture itself (like a music player tinting itself from the
 * album art): [first] comes from the upper part of the picture and is the lighter, richer one, [second] from the
 * lower part and is deeper. Pictures without colour (manga, screenshots, black and white) give a neutral dark pair.
 */
fun axAmbientGradient(src: android.graphics.Bitmap): Pair<Color, Color>? {
    return try {
        val base = if (src.config == android.graphics.Bitmap.Config.HARDWARE) src.copy(android.graphics.Bitmap.Config.ARGB_8888, false) ?: return null else src
        val n = 24
        val small = android.graphics.Bitmap.createScaledBitmap(base, n, n, true)
        val hsv = FloatArray(3)
        // Dominant colour of a band: a hue histogram weighted by saturation and brightness. Averaging RGB made sand + sky + a blue
        // shirt cancel out to grey; picking the strongest hue family keeps the picture's real tint (like a music player does).
        fun region(y0: Int, y1: Int): FloatArray {
            val bins = 24
            val wBin = DoubleArray(bins); val sBin = DoubleArray(bins); val vBin = DoubleArray(bins)
            val hx = DoubleArray(bins); val hy = DoubleArray(bins)
            var all = 0.0; var sumV = 0.0; var count = 0
            for (y in y0 until y1) for (x in 0 until n) {
                android.graphics.Color.colorToHSV(small.getPixel(x, y), hsv)
                sumV += hsv[2]; count++
                if (hsv[1] < 0.12f || hsv[2] < 0.15f) continue
                val w = (hsv[1] * (0.4 + hsv[2])).toDouble()
                val bi = ((hsv[0] / 360f) * bins).toInt().coerceIn(0, bins - 1)
                wBin[bi] += w; sBin[bi] += hsv[1] * w; vBin[bi] += hsv[2] * w
                val rad = Math.toRadians(hsv[0].toDouble())
                hx[bi] += Math.cos(rad) * w; hy[bi] += Math.sin(rad) * w
                all += w
            }
            val out = FloatArray(3)
            // a family = a bin plus its two neighbours
            var best = -1; var bestW = 0.0
            for (i in 0 until bins) {
                val w = wBin[i] + wBin[(i + 1) % bins] + wBin[(i + bins - 1) % bins]
                if (w > bestW) { bestW = w; best = i }
            }
            if (best < 0 || all < 0.35) {          // no real colour in this band: neutral, remember how bright it is
                out[0] = 0f; out[1] = 0f; out[2] = (sumV / count.coerceAtLeast(1)).toFloat()
                return out
            }
            var w = 0.0; var sx = 0.0; var sy = 0.0; var ss = 0.0; var sv = 0.0
            for (d in -1..1) {
                val i = (best + d + bins) % bins
                w += wBin[i]; sx += hx[i]; sy += hy[i]; ss += sBin[i]; sv += vBin[i]
            }
            var hue = Math.toDegrees(Math.atan2(sy, sx)).toFloat(); if (hue < 0) hue += 360f
            out[0] = hue; out[1] = (ss / w).toFloat(); out[2] = (sv / w).toFloat()
            return out
        }
        val top = region(0, 11)
        val bottom = region(13, n)
        if (small !== base) small.recycle()
        if (base !== src) base.recycle()
        var hueB = bottom[0]
        // two nearly identical tints make a flat background: push the lower one towards cooler / deeper
        val dh = Math.abs(((top[0] - hueB + 540f) % 360f) - 180f)
        if (dh < 28f && top[1] > 0.12f && bottom[1] > 0.12f) hueB = (hueB + 38f) % 360f
        fun tint(h: Float, s: Float, v: Float) = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))
        val topS = if (top[1] < 0.07f) 0.05f else (top[1] * 1.35f).coerceIn(0.40f, 0.80f)
        val botS = if (bottom[1] < 0.07f) 0.05f else (bottom[1] * 1.4f).coerceIn(0.50f, 0.90f)
        Pair(
            tint(top[0], topS, if (top[1] < 0.07f) 0.30f else 0.44f),
            tint(hueB, botS, if (bottom[1] < 0.07f) 0.11f else 0.17f),
        )
    } catch (e: Throwable) {
        null
    }
}


/**
 * A thin iridescent ring (cyan, blue, warm orange, white) that keeps turning around the selected dock item.
 * The angle is only read while drawing, so it costs a redraw of a tiny pill, never a recomposition.
 * Static on low-end phones.
 */
@Composable
fun Modifier.axSpinRing(): Modifier {
    val lite = AxPerf.lite(androidx.compose.ui.platform.LocalContext.current)
    val angle: State<Float>? = if (lite) null else rememberInfiniteTransition(label = "dock ring").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2800, easing = LinearEasing)),
        label = "dock ring angle",
    )
    return this.drawWithCache {
        val w = size.width
        val h = size.height
        val stroke = 2.dp.toPx()
        val outer = androidx.compose.ui.graphics.Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, w, h, androidx.compose.ui.geometry.CornerRadius(h / 2f)))
        }
        val inner = androidx.compose.ui.graphics.Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    stroke, stroke, w - stroke, h - stroke,
                    androidx.compose.ui.geometry.CornerRadius((h - 2f * stroke) / 2f),
                )
            )
        }
        val ring = androidx.compose.ui.graphics.Path.combine(
            androidx.compose.ui.graphics.PathOperation.Difference, outer, inner,
        )
        val center = Offset(w / 2f, h / 2f)
        val brush = Brush.sweepGradient(
            listOf(
                Color(0xFF5AD1FF), Color(0xFF3B6BFF), Color(0xFFFF9A3C), Color(0xFFFFFFFF), Color(0xFF5AD1FF),
            ),
            center = center,
        )
        onDrawWithContent {
            drawContent()
            clipPath(ring) {
                rotate(angle?.value ?: 0f, center) {
                    drawCircle(brush, radius = maxOf(w, h), center = center)
                }
            }
        }
    }
}
