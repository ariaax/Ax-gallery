package com.iris.gallery.data

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers photo "date taken" values read from EXIF in the background, so the gallery can open fast
 * and still sort/group photos by their real date. Stored in a small text file ("id,millis" per line;
 * 0 means "checked, no EXIF date").
 */
object ExifDateStore {
    @Volatile var enabled: Boolean = true
    private val map = ConcurrentHashMap<Long, Long>()
    @Volatile private var loaded = false
    private var file: File? = null

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return
        val f = File(context.filesDir, "exif_dates.txt")
        file = f
        runCatching {
            if (f.exists()) {
                f.forEachLine { line ->
                    val i = line.indexOf(',')
                    if (i > 0) {
                        val id = line.substring(0, i).toLongOrNull()
                        val ms = line.substring(i + 1).toLongOrNull()
                        if (id != null && ms != null) map[id] = ms
                    }
                }
            }
        }
        loaded = true
    }

    /** A real EXIF date, or null when none is known. */
    fun get(id: Long): Long? = map[id]?.takeIf { it > 0L }

    fun isChecked(id: Long): Boolean = map.containsKey(id)

    fun put(id: Long, millis: Long) {
        map[id] = millis
    }

    @Synchronized
    fun save() {
        val f = file ?: return
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.bufferedWriter().use { w ->
                for ((k, v) in map) {
                    w.write("$k,$v")
                    w.newLine()
                }
            }
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }
    }
}
