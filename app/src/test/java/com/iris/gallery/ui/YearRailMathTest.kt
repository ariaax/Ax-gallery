package com.iris.gallery.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class YearRailMathTest {
    // 7 years, 10 items each; one row per item, 100px rows, 50px top padding
    private val years = IntArray(70) { 2026 - it / 10 }
    private val itemToRow = IntArray(70) { it }
    private val rows = IntArray(70) { 50 + it * 100 }
    private val maxScroll = 6500f

    @Test fun oneMarkerPerYearNewestFirstWithTruthfulFractions() {
        val m = YearRailMath.markers(years, itemToRow, rows, maxScroll)
        assertEquals((2026 downTo 2020).toList(), m.map { it.year })
        assertEquals(0f, m[0].fraction, 0f)
        assertEquals(0, m[0].itemIndex)
        assertEquals(10, m[1].itemIndex)
        assertEquals(1050f / 6500f, m[1].fraction, 1e-6f)
        assertTrue(m.zipWithNext().all { (a, b) -> a.fraction < b.fraction })
    }

    @Test fun interleavedYearsKeepOnlyTheFirstOccurrence() {
        val y = intArrayOf(2024, 2023, 2024, 2023, 2022)
        val m = YearRailMath.markers(y, IntArray(5) { it }, IntArray(5) { it * 100 }, 400f)
        assertEquals(listOf(2024, 2023, 2022), m.map { it.year })
    }

    @Test fun invalidYearsAndTinyScrollRangesAreSafe() {
        val m = YearRailMath.markers(intArrayOf(0, 2025), intArrayOf(0, 1), intArrayOf(0, 90), 0f)
        assertEquals(listOf(2025), m.map { it.year })
        assertEquals(1f, m[0].fraction, 0f)
    }

    @Test fun placedCapsulesNeverOverlapAndKeepTheActiveYear() {
        val m = YearRailMath.markers(years, itemToRow, rows, maxScroll)
        for (active in 2020..2026) {
            val p = YearRailMath.place(m, active, 16f, 48f, 700f, 38f)
            assertTrue(p.any { it.marker.year == active && it.active })
            assertTrue(p.zipWithNext().all { (a, b) -> abs(b.centerPx - a.centerPx) >= 38f })
            assertEquals(p.sortedBy { it.centerPx }, p)
        }
    }

    @Test fun plentyOfRoomShowsEveryYear() {
        val m = YearRailMath.markers(years, itemToRow, rows, maxScroll)
        assertEquals(7, YearRailMath.place(m, 2023, 16f, 48f, 2000f, 38f).size)
    }

    @Test fun capsuleSitsWhereTheHandleWouldBe() {
        val m = YearRailMath.markers(years, itemToRow, rows, maxScroll)
        val p = YearRailMath.place(m, 2026, 16f, 48f, 1000f, 10f)
        // fraction 0 -> handle centre = inset + thumb/2 ; last year ends below it
        assertEquals(40f, p.first().centerPx, 0.001f)
        assertTrue(p.last().centerPx <= 1000f - 16f - 24f)
    }
}
