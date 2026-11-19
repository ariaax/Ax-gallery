package com.iris.gallery.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.shadow
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/** First position of a year in the timeline, on the same 0..1 scale as the scroll handle. */
data class YearMarker(val year: Int, val itemIndex: Int, val fraction: Float)

data class PlacedYear(val marker: YearMarker, val centerPx: Float, val active: Boolean)

/** Pure geometry for the year rail (unit-tested; no Compose here). */
object YearRailMath {
    /**
     * One marker per year, at the first item of that year. [fraction] uses the scroll scale the handle uses:
     * the row's start offset divided by the scrollable distance, so a capsule sits exactly where the handle
     * must be to show that year. The very first row is always 0.
     */
    fun markers(itemYears: IntArray, itemToRow: IntArray, rowOffsets: IntArray, maxScrollPx: Float): List<YearMarker> {
        val out = ArrayList<YearMarker>()
        val seen = HashSet<Int>()
        val max = if (maxScrollPx < 1f) 1f else maxScrollPx
        for (i in itemYears.indices) {
            val y = itemYears[i]
            if (y <= 0 || !seen.add(y)) continue
            val row = if (i < itemToRow.size) itemToRow[i] else 0
            val off = if (row > 0 && row < rowOffsets.size) rowOffsets[row] else 0
            out.add(YearMarker(y, i, (off / max).coerceIn(0f, 1f)))
        }
        return out
    }

    /**
     * Chooses which markers get a capsule so none overlap: the active year is always kept, others are kept
     * newest-first while at least [minGapPx] away from every kept one. Result is sorted top to bottom.
     */
    fun place(
        markers: List<YearMarker>,
        activeYear: Int,
        insetPx: Float,
        thumbHeightPx: Float,
        trackPx: Float,
        minGapPx: Float,
    ): List<PlacedYear> {
        val maxTravel = (trackPx - insetPx * 2f - thumbHeightPx).coerceAtLeast(1f)
        fun centerOf(m: YearMarker) = insetPx + thumbHeightPx / 2f + m.fraction * maxTravel
        val chosen = ArrayList<PlacedYear>()
        markers.firstOrNull { it.year == activeYear }?.let { chosen.add(PlacedYear(it, centerOf(it), true)) }
        for (m in markers) {
            if (m.year == activeYear) continue
            val c = centerOf(m)
            if (chosen.all { abs(it.centerPx - c) >= minGapPx }) chosen.add(PlacedYear(m, c, false))
        }
        return chosen.sortedBy { it.centerPx }
    }
}

/**
 * Small glass year pills shown next to the handle while it is dragged. They rise in one after another,
 * the active year swells with a springy overshoot and changes colour smoothly. Only the pills take touches.
 */
@Composable
fun YearRailOverlay(
    markers: List<YearMarker>,
    activeYear: Int,
    alpha: Float,
    topPadding: Dp,
    bottomPadding: Dp,
    onYearClick: (YearMarker) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val pillH = 24.dp
    BoxWithConstraints(
        modifier
            .fillMaxHeight()
            .padding(top = topPadding, bottom = bottomPadding)
            .width(56.dp)
            .graphicsLayer {
                this.alpha = alpha
                translationX = (1f - alpha) * 10.dp.toPx()
            }
    ) {
        val trackPx = with(density) { maxHeight.toPx() }
        val placed = remember(markers, activeYear, trackPx) {
            with(density) { YearRailMath.place(markers, activeYear, 16.dp.toPx(), 48.dp.toPx(), trackPx, 30.dp.toPx()) }
        }
        val pillHPx = with(density) { pillH.toPx() }
        val accent = MaterialTheme.colorScheme.primary
        val onAccent = MaterialTheme.colorScheme.onPrimary
        val glass = Color(0xE61C212B)
        placed.forEachIndexed { index, p ->
            key(p.marker.year) {
                val scale by animateFloatAsState(
                    targetValue = if (p.active) 1.2f else 1f,
                    animationSpec = spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium),
                    label = "yearPillScale"
                )
                val fill by animateColorAsState(if (p.active) accent else glass, tween(180), label = "yearPillFill")
                val ink by animateColorAsState(if (p.active) onAccent else Color(0xFFE8EAF0), tween(180), label = "yearPillInk")
                val appear = remember { Animatable(0f) }
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay((index * 24L).coerceAtMost(180L))
                    appear.animateTo(1f, tween(240, easing = FastOutSlowInEasing))
                }
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset { IntOffset(0, (p.centerPx - pillHPx / 2f).roundToInt()) }
                        .graphicsLayer {
                            val a = appear.value
                            this.alpha = a
                            translationX = (1f - a) * 12.dp.toPx()
                            scaleX = scale; scaleY = scale
                            transformOrigin = TransformOrigin(1f, 0.5f)
                        }
                        .shadow(if (p.active) 6.dp else 2.dp, CircleShape, clip = false)
                        .background(fill, CircleShape)
                        .border(0.5.dp, Color.White.copy(alpha = if (p.active) 0f else 0.14f), CircleShape)
                        .pointerInput(p.marker) { detectTapGestures { onYearClick(p.marker) } }
                        .padding(horizontal = 9.dp, vertical = 3.5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = p.marker.year.toString(),
                        fontSize = 11.5.sp,
                        fontWeight = if (p.active) FontWeight.Bold else FontWeight.Medium,
                        color = ink
                    )
                }
            }
        }
    }
}
