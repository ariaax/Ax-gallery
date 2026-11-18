package com.iris.gallery.ui

/** Maps a content-space Y coordinate to the row whose start is at or before it. */
internal fun timelineRowAtContentY(contentY: Int, rowOffsets: IntArray): Int {
    if (rowOffsets.isEmpty()) return 0
    val safeY = contentY.coerceIn(0, rowOffsets.last())
    val result = rowOffsets.binarySearch(safeY)
    return if (result >= 0) result else (-result - 2).coerceIn(0, rowOffsets.lastIndex)
}
