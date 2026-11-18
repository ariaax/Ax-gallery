package com.iris.gallery.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineScrubberMathTest {
    private val rows = intArrayOf(120, 180, 260, 340)

    @Test fun mapsExactRowStarts() {
        assertEquals(0, timelineRowAtContentY(120, rows))
        assertEquals(2, timelineRowAtContentY(260, rows))
    }

    @Test fun mapsPositionsBetweenRowsToPreviousRow() {
        assertEquals(0, timelineRowAtContentY(179, rows))
        assertEquals(2, timelineRowAtContentY(339, rows))
    }

    @Test fun clampsPositionsOutsideContent() {
        assertEquals(0, timelineRowAtContentY(-50, rows))
        assertEquals(3, timelineRowAtContentY(10_000, rows))
    }

    @Test fun emptyRowsAreSafe() {
        assertEquals(0, timelineRowAtContentY(100, intArrayOf()))
    }

    @Test fun yearMarkersIncludeEveryDistinctYearAndAccountForHeaders() {
        val markers = buildTimelineYearMarkers(
            groupFirstDates = listOf(2026L, 2025L, 2024L, 2025L),
            groupSizes = listOf(3, 4, 2, 5),
            showHeaders = true,
            yearOf = { it.toInt() },
        )
        assertEquals(listOf(2026 to 0, 2025 to 4, 2024 to 9), markers)
    }

    @Test fun yearMarkersWorkWithoutHeaders() {
        val markers = buildTimelineYearMarkers(
            groupFirstDates = listOf(2025L, 2024L),
            groupSizes = listOf(2, 3),
            showHeaders = false,
            yearOf = { it.toInt() },
        )
        assertEquals(listOf(2025 to 0, 2024 to 2), markers)
    }
}
