package com.iris.gallery.data

import android.content.Context

/**
 * Original "date added" of files that were restored from the trash, keyed by their new path.
 *
 * Why: restoring creates a new MediaStore row and MediaStore stamps its DATE_ADDED with "now". The timeline's default
 * mode keeps recently added files on top, so a restored photo from last year would jump to the very top. The loader
 * reads the original value from here instead, which puts the photo back where it was in every sort mode.
 */
internal object RestoredDatesCodec {
    const val MAX_ENTRIES = 5_000
    const val TTL_MS = 90L * 86_400_000L

    data class Entry(val recordedAt: Long, val dateAdded: Long, val path: String)

    fun encode(e: Entry) = "${e.recordedAt}|${e.dateAdded}|${e.path}"

    fun decode(s: String): Entry? {
        val a = s.indexOf('|')
        if (a < 0) return null
        val b = s.indexOf('|', a + 1)
        if (b < 0) return null
        val recordedAt = s.substring(0, a).toLongOrNull() ?: return null
        val dateAdded = s.substring(a + 1, b).toLongOrNull() ?: return null
        val path = s.substring(b + 1)
        if (path.isEmpty()) return null
        return Entry(recordedAt, dateAdded, path)
    }

    /** Newest entry per path, expired ones dropped, at most [MAX_ENTRIES] kept. */
    fun prune(entries: Collection<Entry>, now: Long): List<Entry> =
        entries.filter { now - it.recordedAt <= TTL_MS }
            .groupBy { it.path }
            .map { (_, v) -> v.maxByOrNull { it.recordedAt } ?: v.first() }
            .sortedByDescending { it.recordedAt }
            .take(MAX_ENTRIES)
}

object RestoredDates {
    private const val PREFS = "ax_restored_dates"
    private const val KEY = "entries"

    private var cache: Map<String, Long>? = null

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun read(context: Context): List<RestoredDatesCodec.Entry> =
        prefs(context).getStringSet(KEY, emptySet()).orEmpty().mapNotNull(RestoredDatesCodec::decode)

    private fun write(context: Context, entries: List<RestoredDatesCodec.Entry>) {
        prefs(context).edit().putStringSet(KEY, entries.map(RestoredDatesCodec::encode).toSet()).apply()
        cache = entries.associate { it.path to it.dateAdded }
    }

    @Synchronized
    fun record(context: Context, path: String, dateAddedMs: Long) {
        recordAll(context, listOf(path to dateAddedMs))
    }

    @Synchronized
    fun recordAll(context: Context, entries: Collection<Pair<String, Long>>) {
        val now = System.currentTimeMillis()
        val additions = entries.mapIndexedNotNull { index, (path, dateAddedMs) ->
            if (path.isBlank() || dateAddedMs <= 0L) null
            else RestoredDatesCodec.Entry(now + index, dateAddedMs, path)
        }
        if (additions.isEmpty()) return
        write(context, RestoredDatesCodec.prune(read(context) + additions, now))
    }

    @Synchronized
    fun forget(context: Context, path: String) {
        val current = cache ?: read(context).associate { it.path to it.dateAdded }.also { cache = it }
        if (path !in current) return
        write(context, read(context).filterNot { it.path == path })
    }

    /** The original date added (ms) for [path], or null when the file was not restored from the trash. */
    @Synchronized
    fun dateAddedFor(context: Context, path: String): Long? {
        if (path.isEmpty()) return null
        val map = cache ?: RestoredDatesCodec.prune(read(context), System.currentTimeMillis())
            .associate { it.path to it.dateAdded }.also { cache = it }
        return map[path]
    }
}
