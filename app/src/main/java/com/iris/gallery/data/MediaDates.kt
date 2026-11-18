package com.iris.gallery.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Pure (Android-free) date rules for the timeline.
 *
 * Why this exists: MediaStore only has a trustworthy DATE_TAKEN for files whose EXIF/container
 * date it could read. For many files (old videos, files copied to an SD card, photos without EXIF)
 * DATE_TAKEN is missing, or the scanner stamped it with the scan/copy time. The old code then fell
 * back straight to DATE_MODIFIED, which for a bulk-copied folder is the copy day, so thousands of
 * old files collapsed onto one date (for this library: 5 Dec 2024).
 *
 * Priority used here (the first valid one wins):
 *  1. MediaStore DATE_TAKEN, when it is not just the scan time / modified time;
 *  2. a date read from the file itself (EXIF for photos, container creation time for videos);
 *  3. a date written in the file name (IMG_20200315_101500.jpg, VID-2020-03-15, epoch millis...);
 *  4. MediaStore DATE_TAKEN even if it equals modified time (it may be genuine);
 *  5. DATE_MODIFIED, then DATE_ADDED.
 * Nothing here ever writes a timestamp anywhere: it only chooses which existing value to display.
 */
object MediaDates {
    private const val DAY_MS = 86_400_000L

    /** Values below this are seconds, not milliseconds (1e11 ms = March 1973). */
    private const val SECONDS_LIMIT = 100_000_000_000L
    /** Values above this are microseconds (1e14 ms would be the year 5138). */
    private const val MICROS_LIMIT = 100_000_000_000_000L
    private const val NANOS_LIMIT = 100_000_000_000_000_000L

    /**
     * Turns a raw timestamp of unknown unit into epoch milliseconds.
     * Returns 0 when the value is empty, absurd (before 1970-01-02) or more than two days in the future.
     */
    fun normalizeMillis(raw: Long, nowMs: Long = System.currentTimeMillis()): Long {
        if (raw <= 0L) return 0L
        val ms = when {
            raw < SECONDS_LIMIT -> raw * 1_000L
            raw >= NANOS_LIMIT -> raw / 1_000_000L
            raw >= MICROS_LIMIT -> raw / 1_000L
            else -> raw
        }
        if (ms <= DAY_MS) return 0L
        if (ms > nowMs + 2 * DAY_MS) return 0L
        return ms
    }

    private val dateInName = Regex(
        """(?<!\d)((?:19[89]\d)|(?:20\d\d))[-_.]?(0[1-9]|1[0-2])[-_.]?(0[1-9]|[12]\d|3[01])(?:[-_ T.]?([01]\d|2[0-3])[-_.:]?([0-5]\d)[-_.:]?([0-5]\d))?(?!\d)"""
    )
    private val epochMsInName = Regex("""(?<!\d)(1\d{12})(?!\d)""")
    private val epochSecInName = Regex("""^(?:[A-Za-z_\-]*)(1\d{9})$""")

    /**
     * Reads the capture date a camera/app wrote into the file name. [zone] is the zone the name's
     * clock time is interpreted in (names carry local time). Date-only names resolve to local noon,
     * so a time-zone shift can never move them onto another day.
     */
    fun parseFromFileName(
        name: String,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? {
        if (name.isBlank()) return null
        val stem = name.substringBeforeLast('.', name)

        for (m in dateInName.findAll(stem)) {
            val year = m.groupValues[1].toInt()
            val month = m.groupValues[2].toInt()
            val day = m.groupValues[3].toInt()
            val date = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: continue
            val hasTime = m.groupValues[4].isNotEmpty()
            val dt = if (hasTime) {
                LocalDateTime.of(date, java.time.LocalTime.of(m.groupValues[4].toInt(), m.groupValues[5].toInt(), m.groupValues[6].toInt()))
            } else {
                date.atTime(12, 0)
            }
            val ms = dt.atZone(zone).toInstant().toEpochMilli()
            val valid = normalizeMillis(ms, nowMs)
            if (valid > 0L) return valid
        }

        epochMsInName.find(stem)?.let { m ->
            val valid = normalizeMillis(m.groupValues[1].toLong(), nowMs)
            if (valid > 0L) return valid
        }
        epochSecInName.find(stem)?.let { m ->
            val valid = normalizeMillis(m.groupValues[1].toLong(), nowMs)
            if (valid > 0L) return valid
        }
        return null
    }

    private val containerDate = Regex(
        """^(\d{4})-?(\d{2})-?(\d{2})[T ](\d{2}):?(\d{2}):?(\d{2})(?:[.,](\d+))?\s*(Z|[+-]\d{2}:?\d{2})?$"""
    )

    /**
     * Parses MediaMetadataRetriever.METADATA_KEY_DATE / MP4 creation_time strings such as
     * "20200315T101500.000Z" or "2020-03-15T10:15:00.000000Z". A missing zone means UTC (that is what
     * the retriever documents). Placeholder dates such as the 1904 MP4 epoch are rejected.
     */
    fun parseContainerDate(text: String?, nowMs: Long = System.currentTimeMillis()): Long? {
        val s = text?.trim().orEmpty()
        val m = containerDate.matchEntire(s) ?: return null
        val g = m.groupValues
        val zone: ZoneOffset = when (val z = g[8]) {
            "", "Z" -> ZoneOffset.UTC
            else -> runCatching { ZoneOffset.of(if (z.length == 5) z.substring(0, 3) + ":" + z.substring(3) else z) }.getOrNull() ?: ZoneOffset.UTC
        }
        val ms = runCatching {
            LocalDateTime.of(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
                .toInstant(zone).toEpochMilli() + (g[7].take(3).padEnd(3, '0').toLongOrNull() ?: 0L)
        }.getOrNull() ?: return null
        val valid = normalizeMillis(ms, nowMs)
        return valid.takeIf { it > 0L }
    }

    private fun sameSecond(a: Long, b: Long) = a > 0L && b > 0L && a / 1_000L == b / 1_000L

    /** True when [takenMs] is only the time the file was scanned or copied rather than a capture date. */
    fun looksScannerStamped(takenMs: Long, addedMs: Long, modifiedMs: Long): Boolean =
        sameSecond(takenMs, addedMs) || sameSecond(takenMs, modifiedMs)

    /**
     * The date the timeline should use for one item.
     *
     * @param rawTaken MediaStore DATE_TAKEN as stored (unit unknown; 0/negative = missing)
     * @param addedMs / [modifiedMs] MediaStore DATE_ADDED / DATE_MODIFIED already in milliseconds
     * @param fileMetadataMs a date read from the file itself (EXIF / video container), or null
     */
    fun resolve(
        rawTaken: Long,
        addedMs: Long,
        modifiedMs: Long,
        fileMetadataMs: Long?,
        fileName: String,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long {
        val metadata = fileMetadataMs?.let { normalizeMillis(it, nowMs) }?.takeIf { it > 0L }
        val taken = normalizeMillis(rawTaken, nowMs)
        val stampedByScanner = taken > 0L && looksScannerStamped(taken, addedMs, modifiedMs)

        // 1. Valid file-level metadata is the strongest source: EXIF DateTimeOriginal or
        // the video's container creation time. MediaStore's cached DATE_TAKEN can be stale.
        if (metadata != null) return metadata
        // 2. Trust a MediaStore DATE_TAKEN only when it is not merely a scan/copy timestamp.
        if (taken > 0L && !stampedByScanner) return taken
        // 3. Camera/app filenames can preserve a date when metadata was stripped.
        parseFromFileName(fileName, nowMs, zone)?.let { return it }
        // 4. A DATE_TAKEN matching scan/modified time may still be genuine; retain it as fallback.
        if (taken > 0L) return taken
        // 5. Last-resort filesystem/MediaStore timestamps.
        val modified = normalizeMillis(modifiedMs, nowMs)
        if (modified > 0L) return modified
        return normalizeMillis(addedMs, nowMs)
    }
}
