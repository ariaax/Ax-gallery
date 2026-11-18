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
}
