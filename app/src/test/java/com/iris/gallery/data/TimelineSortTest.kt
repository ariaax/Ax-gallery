package com.iris.gallery.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineSortTest {
    @Test fun dateAddedModeUsesAddedTimestampEvenWhenCaptureDateIsMuchOlder() {
        val added2026 = 1_783_584_000_000L
        val taken2001 = 999_000_000_000L
        assertEquals(
            added2026,
            resolveTimelineSortDate(taken2001, added2026, DateSortMode.DATE_ADDED, 1_800_000_000_000L, 7),
        )
    }

    @Test fun dateAddedModeFallsBackOnlyWhenAddedTimestampIsMissing() {
        val taken2021 = 1_640_995_200_000L
        assertEquals(
            taken2021,
            resolveTimelineSortDate(taken2021, 0L, DateSortMode.DATE_ADDED, 1_800_000_000_000L, 7),
        )
    }

    @Test fun dateTakenModeIgnoresAddedTimestamp() {
        val taken2001 = 999_000_000_000L
        val added2026 = 1_783_584_000_000L
        assertEquals(
            taken2001,
            resolveTimelineSortDate(taken2001, added2026, DateSortMode.DATE_TAKEN, 1_800_000_000_000L, 7),
        )
    }
}
