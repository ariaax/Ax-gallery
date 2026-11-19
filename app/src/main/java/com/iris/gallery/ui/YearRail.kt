package com.iris.gallery.ui

import androidx.compose.animation.core.Spring
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
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
 * Floating year capsules shown next to the scroll handle while scrolling. Fades and slides in/out with [alpha];
 * the active year is enlarged with a springy "bubble". Only the capsules themselves take touches.
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
    val capsuleH = 32.dp
    BoxWithConstraints(
        modifier
            .fillMaxHeight()
            .padding(top = topPadding, bottom = bottomPadding)
            .width(76.dp)
            .graphicsLayer {
                this.alpha = alpha
                translationX = (1f - alpha) * 14.dp.toPx()
            }
    ) {
        val trackPx = with(density) { maxHeight.toPx() }
        val placed = remember(markers, activeYear, trackPx) {
            with(density) { YearRailMath.place(markers, activeYear, 16.dp.toPx(), 48.dp.toPx(), trackPx, 38.dp.toPx()) }
        }
        val capsuleHPx = with(density) { capsuleH.toPx() }
        val accent = MaterialTheme.colorScheme.primary
        val onAccent = MaterialTheme.colorScheme.onPrimary
        placed.forEach { p ->
            key(p.marker.year) {
                val scale by animateFloatAsState(
                    targetValue = if (p.active) 1.18f else 1f,
                    animationSpec = spring(dampingRatio = 0.48f, stiffness = Spring.StiffnessMediumLow),
                    label = "yearCapsuleScale"
                )
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset { IntOffset(0, (p.centerPx - capsuleHPx / 2f).roundToInt()) }
                        .graphicsLayer {
                            scaleX = scale; scaleY = scale
                            transformOrigin = TransformOrigin(1f, 0.5f)
                        }
                        .shadow(if (p.active) 8.dp else 3.dp, CircleShape, clip = false)
                        .background(if (p.active) accent else Color.White.copy(alpha = 0.96f), CircleShape)
                        .pointerInput(p.marker) { detectTapGestures { onYearClick(p.marker) } }
                        .padding(horizontal = 13.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = p.marker.year.toString(),
                        fontSize = 13.sp,
                        fontWeight = if (p.active) FontWeight.Bold else FontWeight.Medium,
                        color = if (p.active) onAccent else Color(0xFF1B1F27)
                    )
                }
            }
        }
    }
}
