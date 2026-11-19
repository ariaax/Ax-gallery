package com.iris.gallery.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDatesTest {
    private val zone: ZoneId = ZoneId.of("Asia/Tehran")
    private val now = Instant.parse("2026-11-19T06:00:00Z").toEpochMilli()

    private fun ms(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0, s: Int = 0, z: ZoneId = zone) =
        LocalDateTime.of(y, m, d, h, min, s).atZone(z).toInstant().toEpochMilli()

    private fun year(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).year
    private fun day(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    // the copy/scan day a whole SD card was stamped with
    private val copyDay = ms(2024, 12, 5, 14, 30)

    private fun resolve(taken: Long, added: Long = copyDay, modified: Long = copyDay, meta: Long? = null) =
        MediaDates.resolve(taken, added, modified, meta, now)

    // ---- seconds / milliseconds / junk ------------------------------------------------------

    @Test fun secondsAreConvertedToMillis() {
        val sec = ms(2020, 3, 15, 10, 15) / 1000L
        assertEquals(sec * 1000L, MediaDates.normalizeMillis(sec, now))
    }

    @Test fun millisStayMillis() {
        val v = ms(2023, 7, 1)
        assertEquals(v, MediaDates.normalizeMillis(v, now))
    }

    @Test fun microsAndNanosAreScaledDown() {
        val v = ms(2021, 5, 9, 8)
        assertEquals(v, MediaDates.normalizeMillis(v * 1_000L, now))
        assertEquals(v, MediaDates.normalizeMillis(v * 1_000_000L, now))
    }

    @Test fun junkValuesAreRejected() {
        assertEquals(0L, MediaDates.normalizeMillis(0, now))
        assertEquals(0L, MediaDates.normalizeMillis(-5, now))
        assertEquals(0L, MediaDates.normalizeMillis(ms(2031, 1, 1), now)) // far future
    }

    // ---- video container date ---------------------------------------------------------------

    @Test fun retrieverDateFormat() {
        assertEquals(ms(2020, 3, 15, 10, 15, 0, ZoneOffset.UTC) + 250, MediaDates.parseContainerDate("20200315T101500.250Z", now))
        assertEquals(ms(2022, 8, 1, 6, 0, 0, ZoneOffset.UTC), MediaDates.parseContainerDate("2022-08-01T06:00:00.000000Z", now))
        assertEquals(ms(2022, 8, 1, 6, 0, 0, ZoneOffset.UTC), MediaDates.parseContainerDate("2022-08-01T08:30:00+02:30", now))
    }

    @Test fun placeholderContainerDatesAreRejected() {
        assertNull(MediaDates.parseContainerDate("19040101T000000.000Z", now)) // MP4 epoch
        assertNull(MediaDates.parseContainerDate("19700101T000000.000Z", now))
        assertNull(MediaDates.parseContainerDate("garbage", now))
        assertNull(MediaDates.parseContainerDate(null, now))
    }

    // ---- resolve() --------------------------------------------------------------------------

    @Test fun genuineMediaStoreDateIsNeverReplaced() {
        val real = ms(2020, 6, 1, 12)
        assertEquals(real, resolve(real, meta = ms(2019, 1, 1)))
    }

    @Test fun scannerStampedDateTakenYieldsToFileMetadata() {
        // SD card: DATE_TAKEN == DATE_ADDED == DATE_MODIFIED == copy day; the file's own EXIF knows better
        val exif = ms(2020, 3, 15, 10, 15)
        assertEquals(exif, resolve(copyDay, meta = exif))
    }

    @Test fun videoWithNoDateTakenUsesContainerDate() {
        val container = ms(2021, 8, 20, 17, 0)
        assertEquals(container, resolve(0L, meta = container))
    }

    @Test fun secondsInDateTakenColumnAreHandled() {
        val real = ms(2020, 6, 1, 12)
        assertEquals(real / 1000L * 1000L, resolve(real / 1000L))
    }

    @Test fun withoutAnyBetterInformationTheStampedDateIsKeptNotInvented() {
        // no EXIF, nothing else: the honest answer is the date the system has, never a guess from the name
        assertEquals(copyDay, resolve(copyDay))
    }

    @Test fun noDateTakenFallsBackToModifiedThenAdded() {
        val modified = ms(2018, 1, 1)
        assertEquals(modified, resolve(0L, added = copyDay, modified = modified))
        assertEquals(copyDay, resolve(0L, added = copyDay, modified = 0L))
    }

    @Test fun dateTakenEqualToModifiedIsKeptWhenNothingBetterExists() {
        val camera = ms(2022, 2, 2, 2)
        assertEquals(camera, resolve(camera, added = copyDay, modified = camera))
    }

    @Test fun invalidFutureDateTakenFallsBack() {
        assertEquals(copyDay, resolve(ms(2031, 1, 1), added = copyDay, modified = copyDay))
    }

    // ---- regression: years must stay truthful ----------------------------------------------

    @Test fun aRecentDownloadIsNeverMovedToAnOldYear() {
        // Downloaded/saved in Nov 2026: DATE_TAKEN is the save time. Whatever the file is called
        // (IMG_20210724..., a 10-digit id, a 13-digit id) its year must stay 2026.
        val saved = ms(2026, 11, 14, 9, 30)
        assertEquals(2026, year(MediaDates.resolve(saved, saved, saved, null, now)))
    }

    @Test fun everyYearFrom2020To2026SurvivesResolution() {
        val inputs = (2020..2026).flatMap { y ->
            listOf(
                resolve(ms(y, 6, 1, 12)),                       // EXIF known to MediaStore
                resolve(0L, meta = ms(y, 8, 3, 15)),            // video, container date
                resolve(copyDay, meta = ms(y, 9, 4, 9)),        // SD photo, EXIF read later
            )
        }
        assertEquals((2020..2026).toSet(), inputs.map { year(it) }.toSet())
    }

    @Test fun sortingAndYearNavigationUseTheSameResolvedDates() {
        val resolved = (2020..2026).map { resolve(ms(it, 5, 5)) }.shuffled(java.util.Random(7))
        val sortedDesc = resolved.sortedDescending()
        val railYears = sortedDesc.map { year(it) }.distinct()
        assertEquals((2026 downTo 2020).toList(), railYears) // every year present, once, newest first
    }

    @Test fun dayGroupingKeepsDifferentYearsApart() {
        val a = ms(2024, 12, 5, 10)
        val b = ms(2020, 12, 5, 10)
        assertEquals(2, listOf(a, b).groupBy { day(it) }.size)
        assertTrue(a > b)
    }
}
