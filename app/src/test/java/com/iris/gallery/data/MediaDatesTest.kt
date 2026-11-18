package com.iris.gallery.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaDatesTest {
    private val zone: ZoneId = ZoneId.of("Asia/Tehran")
    private val now = Instant.parse("2026-11-18T06:00:00Z").toEpochMilli()

    private fun ms(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0, s: Int = 0, z: ZoneId = zone) =
        java.time.LocalDateTime.of(y, m, d, h, min, s).atZone(z).toInstant().toEpochMilli()

    private fun day(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    // the copy/scan day that the real library collapsed onto
    private val dec5 = ms(2024, 12, 5, 14, 30)

    // ---- seconds / milliseconds -------------------------------------------------------------

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

    // ---- file names -------------------------------------------------------------------------

    @Test fun cameraNamesFrom2020To2026() {
        val cases: List<Pair<String, Long>> = listOf(
            "IMG_20200315_101500.jpg" to ms(2020, 3, 15, 10, 15),
            "VID_20210722_235959.mp4" to ms(2021, 7, 22, 23, 59, 59),
            "PXL_20220101_000102345.jpg" to ms(2022, 1, 1, 12, 0), // 9-digit time block is not HHMMSS: date only
            "20230430_081530.jpg" to ms(2023, 4, 30, 8, 15, 30),
            "Screenshot_2024-02-29-18-05-10.png" to ms(2024, 2, 29, 18, 5, 10),
            "2025-12-31 23.59.58.jpg" to ms(2025, 12, 31, 23, 59, 58),
            "IMG-20260102-WA0007.jpg" to ms(2026, 1, 2, 12, 0),
        )
        for ((name, expected) in cases) {
            val got = MediaDates.parseFromFileName(name, now, zone)
            assertEquals(name, expected, got)
        }
    }

    @Test fun epochMillisAndSecondsInNames() {
        val v = ms(2022, 6, 1, 9)
        assertEquals(v, MediaDates.parseFromFileName("$v.jpg", now, zone))
        assertEquals(v / 1000 * 1000, MediaDates.parseFromFileName("IMG_${v / 1000}.jpg", now, zone))
    }

    @Test fun namesWithoutDatesGiveNothing() {
        assertNull(MediaDates.parseFromFileName("holiday.jpg", now, zone))
        assertNull(MediaDates.parseFromFileName("IMG_1234.jpg", now, zone))
        assertNull(MediaDates.parseFromFileName("IMG_20201340_101500.jpg", now, zone)) // month 13 / day 40
        assertNull(MediaDates.parseFromFileName("file_20300101_000000.jpg", now, zone)) // future
    }

    @Test fun impossibleCalendarDateIsRejected() {
        assertNull(MediaDates.parseFromFileName("IMG_20210230_101500.jpg", now, zone)) // 30 Feb
    }

    // ---- video container date ---------------------------------------------------------------

    @Test fun retrieverDateFormat() {
        val expected = ms(2020, 3, 15, 10, 15, 0, ZoneOffset.UTC) + 250
        assertEquals(expected, MediaDates.parseContainerDate("20200315T101500.250Z", now))
        assertEquals(ms(2022, 8, 1, 6, 0, 0, ZoneOffset.UTC), MediaDates.parseContainerDate("2022-08-01T06:00:00.000000Z", now))
        assertEquals(ms(2022, 8, 1, 6, 0, 0, ZoneOffset.UTC), MediaDates.parseContainerDate("2022-08-01T08:30:00+02:30", now))
    }

    @Test fun placeholderContainerDatesAreRejected() {
        assertNull(MediaDates.parseContainerDate("19040101T000000.000Z", now)) // MP4 epoch
        assertNull(MediaDates.parseContainerDate("19700101T000000.000Z", now))
        assertNull(MediaDates.parseContainerDate("garbage", now))
        assertNull(MediaDates.parseContainerDate(null, now))
    }

    // ---- resolve(): the actual bug ----------------------------------------------------------

    @Test fun sdCardPhotoStampedWithCopyDateUsesFileName() {
        // DATE_TAKEN == DATE_ADDED == DATE_MODIFIED == the day the card was filled (5 Dec 2024)
        val t = MediaDates.resolve(rawTaken = dec5, addedMs = dec5, modifiedMs = dec5,
            fileMetadataMs = null, fileName = "IMG_20200315_101500.jpg", nowMs = now, zone = zone)
        assertEquals(ms(2020, 3, 15, 10, 15), t)
    }

    @Test fun sdCardVideoWithNoDateTakenUsesContainerDate() {
        val container = ms(2021, 8, 20, 17, 0)
        val t = MediaDates.resolve(0, dec5, dec5, container, "MOV_0042.mp4", now, zone)
        assertEquals(container, t)
    }

    @Test fun fileMetadataBeatsFileName() {
        val exif = ms(2019, 12, 31, 23, 0)
        val t = MediaDates.resolve(dec5, dec5, dec5, exif, "IMG_20200315_101500.jpg", now, zone)
        assertEquals(exif, t)
    }

    @Test fun fileLevelCaptureMetadataOverridesStaleMediaStoreDateTaken() {
        val exif = ms(2020, 6, 1, 12)
        val staleMediaStoreDate = ms(2021, 6, 1, 12)
        val t = MediaDates.resolve(
            rawTaken = staleMediaStoreDate,
            addedMs = ms(2026, 7, 9),
            modifiedMs = ms(2026, 5, 14),
            fileMetadataMs = exif,
            fileName = "1000058967.jpeg",
            nowMs = now,
            zone = zone,
        )
        assertEquals(exif, t)
    }

    @Test fun secondsInDateTakenColumnAreHandled() {
        val real = ms(2020, 6, 1, 12)
        val t = MediaDates.resolve(real / 1000L, dec5, dec5, null, "x.jpg", now, zone)
        assertEquals(real / 1000L * 1000L, t)
    }

    @Test fun noInformationAtAllFallsBackToModifiedThenAdded() {
        val modified = ms(2018, 1, 1)
        assertEquals(modified, MediaDates.resolve(0, dec5, modified, null, "blob.bin.jpg", now, zone))
        assertEquals(dec5, MediaDates.resolve(0, dec5, 0, null, "blob.jpg", now, zone))
    }

    @Test fun dateTakenEqualToModifiedIsKeptWhenNothingBetterExists() {
        val camera = ms(2022, 2, 2, 2)
        assertEquals(camera, MediaDates.resolve(camera, dec5, camera, null, "DSC0001.jpg", now, zone))
    }

    // ---- 2020..2026 library: nothing stuck on one day, oldest reachable ----------------------

    @Test fun libraryFrom2020To2026SortsAcrossAllYears() {
        data class Item(val name: String, val taken: Long, val metadata: Long? = null)
        val items = buildList {
            for (y in 2020..2026) {
                // photo with EXIF (MediaStore knows the date)
                add(Item("IMG_${y}0601_120000.jpg", ms(y, 6, 1, 12)))
                // photo without EXIF on the SD card: DATE_TAKEN stamped with the copy day
                add(Item("IMG_${y}0702_090000.jpg", dec5))
                // video without any date in name: only the container knows
                add(Item("MOV_$y.mp4", 0L, metadata = ms(y, 8, 3, 15)))
                // plain file without any metadata or name date
                if (y == 2020) add(Item("scan.jpg", 0L))
            }
        }
        val resolved = items.map { it to MediaDates.resolve(it.taken, dec5, dec5, it.metadata, it.name, now, zone) }

        // exactly the one file with no information at all lands on the copy day
        val onCopyDay = resolved.filter { day(it.second) == day(dec5) }.map { it.first.name }
        assertEquals(listOf("scan.jpg"), onCopyDay)

        val years = resolved.map { day(it.second).year }.toSet()
        assertEquals((2020..2026).toSet(), years)

        val sorted = resolved.sortedByDescending { it.second }
        assertEquals(2026, day(sorted.first().second).year)
        assertEquals(2020, day(sorted.last().second).year) // the oldest item is reachable at the end
        assertEquals(resolved.size, sorted.map { it.first.name }.toSet().size) // nothing skipped
        assertNotEquals(sorted.first().second, sorted.last().second)
    }

    @Test fun dayGroupingKeepsDifferentYearsApart() {
        // same day-of-year in different years must be different groups even if the label hides the year
        val a = ms(2024, 12, 5, 10)
        val b = ms(2020, 12, 5, 10)
        val groups = listOf(a, b).groupBy { day(it) }
        assertEquals(2, groups.size)
    }
}
