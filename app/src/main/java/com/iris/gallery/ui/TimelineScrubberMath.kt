package com.iris.gallery.ui

/** Maps a content-space Y coordinate to the row whose start is at or before it. */
internal fun timelineRowAtContentY(contentY: Int, rowOffsets: IntArray): Int {
    if (rowOffsets.isEmpty()) return 0
    val safeY = contentY.coerceIn(0, rowOffsets.last())
    val result = rowOffsets.binarySearch(safeY)
    return if (result >= 0) result else (-result - 2).coerceIn(0, rowOffsets.lastIndex)
}


/** Builds one marker for each distinct year in timeline order. Item indices include
 * the optional day-header row before each group.
 */
internal fun buildTimelineYearMarkers(
    groupFirstDates: List<Long>,
    groupSizes: List<Int>,
    showHeaders: Boolean,
    yearOf: (Long) -> Int,
): List<Pair<Int, Int>> {
    val result = ArrayList<Pair<Int, Int>>()
    val seenYears = HashSet<Int>()
    var itemIndex = 0
    for (index in groupFirstDates.indices) {
        val date = groupFirstDates[index]
        if (date > 0L) {
            val year = yearOf(date)
            if (seenYears.add(year)) result += year to itemIndex
        }
        itemIndex += (if (showHeaders) 1 else 0) + (groupSizes.getOrNull(index) ?: 0).coerceAtLeast(0)
    }
    return result
}
