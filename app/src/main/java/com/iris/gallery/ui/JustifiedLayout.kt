package com.iris.gallery.ui

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure layout maths for the "justified rows" photo grid (the look of Xiaomi / Google Photos):
 * every row is filled edge to edge, tiles in a row share one height and get a width proportional to
 * their own aspect ratio, so no photo is cropped much and tiles come in different sizes.
 *
 * It is built to run inside a LazyVerticalGrid with [SPAN_UNITS] lanes: a tile of `span` lanes is
 * `span * (W + gap) / SPAN_UNITS - gap` pixels wide, which keeps drag-select, item keys, prefetch and
 * the scrubber working exactly as before (every photo is still its own grid item).
 */
object JustifiedLayout {
    const val SPAN_UNITS = 60
    private const val MIN_AR = 0.8f
    private const val MAX_AR = 1.3f
    private const val MIN_SPAN = 6

    /** Layout of one group of photos (one day). Arrays are indexed by the photo's index inside the group. */
    class GroupLayout(
        val spans: IntArray,
        /** height in px of the row each photo is in */
        val heightsPx: IntArray,
        /** index (inside the group) of the first photo of each row */
        val rowStarts: IntArray,
        /** height in px of each row */
        val rowHeightsPx: IntArray,
    ) {
        val rowCount: Int get() = rowStarts.size
    }

    fun widthOfSpan(span: Int, widthPx: Int, gapPx: Int): Float =
        span * (widthPx + gapPx).toFloat() / SPAN_UNITS - gapPx

    fun layoutGroup(aspects: FloatArray, widthPx: Int, gapPx: Int, targetRowHeightPx: Int): GroupLayout {
        val n = aspects.size
        val spans = IntArray(n)
        val heights = IntArray(n)
        val rowStarts = ArrayList<Int>()
        val rowHeights = ArrayList<Int>()
        if (n == 0 || widthPx <= 0) return GroupLayout(spans, heights, IntArray(0), IntArray(0))
        val ar = FloatArray(n) { (if (aspects[it] > 0f) aspects[it] else 1f).coerceIn(MIN_AR, MAX_AR) }
        val target = targetRowHeightPx.coerceAtLeast(40)

        fun naturalHeight(from: Int, to: Int): Float { // [from, to)
            var sum = 0f
            for (k in from until to) sum += ar[k]
            return (widthPx - (to - from - 1) * gapPx) / sum
        }

        var i = 0
        while (i < n) {
            var j = i + 1
            while (j < n && naturalHeight(i, j) > target) j++
            // j is the first count whose natural height is <= target (or the end of the group)
            if (j - i > 1 && j < n + 1) {
                val withLast = naturalHeight(i, j)
                val withoutLast = naturalHeight(i, j - 1)
                if (j <= n && abs(withoutLast - target) < abs(withLast - target) && withoutLast <= target * 1.35f) j--
            }
            val count = j - i
            val natural = naturalHeight(i, j)
            val isLastRow = j >= n
            val partial = isLastRow && natural > target * 1.25f // a short last row is not stretched to full width

            val rowSpans = IntArray(count)
            val rowHeight: Int
            if (!partial) {
                var sum = 0f
                for (k in i until j) sum += ar[k]
                // largest-remainder rounding so the spans add up to exactly SPAN_UNITS
                val raw = FloatArray(count) { ar[i + it] / sum * SPAN_UNITS }
                for (k in 0 until count) rowSpans[k] = raw[k].toInt().coerceAtLeast(MIN_SPAN)
                var diff = SPAN_UNITS - rowSpans.sum()
                val order = (0 until count).sortedByDescending { raw[it] - raw[it].toInt() }
                var guard = 0
                while (diff != 0 && guard++ < 200) {
                    for (k in order) {
                        if (diff == 0) break
                        if (diff > 0) { rowSpans[k]++; diff-- }
                        else if (rowSpans[k] > MIN_SPAN) { rowSpans[k]--; diff++ }
                    }
                }
                var widthSum = 0f
                for (k in 0 until count) widthSum += widthOfSpan(rowSpans[k], widthPx, gapPx)
                rowHeight = (widthSum / sum).roundToInt().coerceAtLeast(1)
            } else {
                for (k in 0 until count) {
                    val w = ar[i + k] * target
                    rowSpans[k] = (((w + gapPx) * SPAN_UNITS) / (widthPx + gapPx)).roundToInt().coerceAtLeast(MIN_SPAN)
                }
                while (rowSpans.sum() > SPAN_UNITS) {
                    val big = rowSpans.indices.maxByOrNull { rowSpans[it] }!!
                    rowSpans[big]--
                }
                rowHeight = target
            }
            rowStarts.add(i)
            rowHeights.add(rowHeight)
            for (k in 0 until count) { spans[i + k] = rowSpans[k]; heights[i + k] = rowHeight }
            i = j
        }
        return GroupLayout(spans, heights, rowStarts.toIntArray(), rowHeights.toIntArray())
    }
}
