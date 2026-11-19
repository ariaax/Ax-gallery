package com.iris.gallery.data

import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Pure (Android-free) date rules for the timeline: ONE place that decides which timestamp a photo or video
 * is shown with, so headers, ordering and the year rail can never disagree.
 *
 * Priority (first valid wins):
 *  1. MediaStore DATE_TAKEN, when it is more than the scan/copy time (i.e. a real capture date);
 *  2. a date read from the file itself (EXIF for photos, container creation time for videos);
 *  3. MediaStore DATE_TAKEN even when it equals modified/added time (it may well be genuine);
 *  4. DATE_MODIFIED, then DATE_ADDED.
 *
 * File NAMES are deliberately never used: names like IMG_20210724 or random numeric ids on downloaded
 * files do not say when something was taken and would silently move files into wrong years.
 * Nothing here writes a timestamp anywhere; it only chooses which existing value to display.
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
     * The date the timeline uses for one item.
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
        nowMs: Long = System.currentTimeMillis(),
    ): Long {
        val taken = normalizeMillis(rawTaken, nowMs)
        val stampedByScanner = taken > 0L && looksScannerStamped(taken, addedMs, modifiedMs)

        if (taken > 0L && !stampedByScanner) return taken
        if (fileMetadataMs != null && fileMetadataMs > 0L) return fileMetadataMs
        if (taken > 0L) return taken
        val modified = normalizeMillis(modifiedMs, nowMs)
        if (modified > 0L) return modified
        return normalizeMillis(addedMs, nowMs)
    }
}
