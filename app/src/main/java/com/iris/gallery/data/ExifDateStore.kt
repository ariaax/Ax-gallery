package com.iris.gallery.data

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers photo "date taken" values read from EXIF in the background, so the gallery can open fast
 * and still sort/group photos by their real date. Stored as stable volume/type/id keys;
 * ambiguous legacy ID-only records are ignored and recomputed safely.
 */
object ExifDateStore {
    @Volatile var enabled: Boolean = true
    private val map = ConcurrentHashMap<String, Long>()
    @Volatile private var loaded = false
    private var file: File? = null
    private var atomicFile: android.util.AtomicFile? = null

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return
        val f = File(context.filesDir, "exif_dates.txt")
        file = f
        val atomic = android.util.AtomicFile(f)
        atomicFile = atomic
        runCatching {
            atomic.openRead().bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val i = line.indexOf(',')
                    if (i > 0) {
                        val keyText = line.substring(0, i)
                        val isLegacyId = keyText.toLongOrNull() != null
                        val ms = line.substring(i + 1).toLongOrNull()
                        if (!isLegacyId && keyText.isNotBlank() && ms != null) map[keyText] = ms
                    }
                }
            }
        }
        loaded = true
    }

    /** A real EXIF date, or null when none is known. Ambiguous legacy ID-only values are not applied across volumes. */
    fun get(stableKey: String): Long? = map[stableKey]?.takeIf { it > 0L }

    fun isChecked(stableKey: String): Boolean = map.containsKey(stableKey)

    fun put(stableKey: String, millis: Long) { map[stableKey] = millis }

    @Synchronized
    fun save() {
        val atomic = atomicFile ?: file?.let { android.util.AtomicFile(it).also { created -> atomicFile = created } } ?: return
        var stream: java.io.FileOutputStream? = null
        try {
            val output = atomic.startWrite()
            stream = output
            val writer = output.bufferedWriter(Charsets.UTF_8)
            for ((key, value) in map) {
                writer.write("$key,$value")
                writer.newLine()
            }
            writer.flush()
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (_: Exception) {
            stream?.let { atomic.failWrite(it) }
        }
    }
}