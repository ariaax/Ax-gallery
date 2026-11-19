package com.iris.gallery.data

import android.content.Context
import android.graphics.BitmapFactory
import android.util.AtomicFile
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Damaged picture files (empty, or a header no decoder can read) are not shown, like Samsung / Xiaomi galleries.
 * Nothing is deleted: the file stays where it is and only this remembered list hides it. An entry stops hiding the
 * file as soon as its size or modified time changes, so a repaired or replaced file comes back by itself.
 */
object BrokenMedia {
    private data class Mark(val size: Long, val modified: Long, val name: String)

    private val marks = ConcurrentHashMap<String, Mark>()
    private var appContext: Context? = null
    @Volatile private var loaded = false
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "ax-broken").also { it.isDaemon = true; it.priority = Thread.MIN_PRIORITY } }

    /** Set by the view model: removes a newly hidden file from the list on screen right away. */
    @Volatile var onHidden: ((MediaImage) -> Unit)? = null

    fun init(context: Context) { appContext = context.applicationContext }

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val ctx = appContext ?: return
        runCatching {
            val f = File(ctx.filesDir, FILE)
            if (!f.exists()) return
            AtomicFile(f).readFully().toString(Charsets.UTF_8).lineSequence().forEach { line ->
                val p = line.split('\t')
                if (p.size >= 4) {
                    val size = p[1].toLongOrNull(); val mod = p[2].toLongOrNull()
                    if (size != null && mod != null) marks[p[0]] = Mark(size, mod, p[3])
                }
            }
        }
    }

    fun hides(image: MediaImage): Boolean {
        ensureLoaded()
        val m = marks[image.stableKey] ?: return false
        return m.size == image.sizeBytes && m.modified == image.dateModified
    }

    fun count(): Int { ensureLoaded(); return marks.size }

    fun clear() {
        ensureLoaded()
        marks.clear()
        persist()
    }

    /**
     * Decides whether [image] is a damaged picture. Conservative on purpose: only common still-image formats whose
     * file exists and is empty or has an unreadable header; videos only when the file is empty.
     */
    fun looksBroken(image: MediaImage): Boolean {
        val path = image.path
        if (path.isBlank()) return false
        val file = File(path)
        if (!file.exists()) return false
        if (file.length() <= 0L) return true
        if (image.isVideo) return false
        val mime = image.mimeType.lowercase()
        if (mime != "image/jpeg" && mime != "image/png" && mime != "image/webp" && mime != "image/bmp") return false
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        return runCatching { BitmapFactory.decodeFile(path, opts); opts.outWidth <= 0 || opts.outHeight <= 0 }.getOrDefault(false)
    }

    /** Remember and hide a damaged file (called after thumbnail decoding failed twice). */
    fun hide(image: MediaImage) {
        ensureLoaded()
        if (marks.size > 5000) return
        marks[image.stableKey] = Mark(image.sizeBytes, image.dateModified, image.name.take(120))
        persist()
        DiagnosticsLog.report("HIDDEN_DAMAGED", "damaged picture hidden from the gallery", "empty or unreadable file; the file itself was not touched", "${image.name} (${image.sizeBytes} B)")
        onHidden?.invoke(image)
    }

    private fun persist() {
        val ctx = appContext ?: return
        val text = marks.entries.joinToString("\n") { (k, m) -> "$k\t${m.size}\t${m.modified}\t${m.name.replace('\t', ' ').replace('\n', ' ')}" }
        io.execute {
            runCatching {
                val atomic = AtomicFile(File(ctx.filesDir, FILE))
                val out = atomic.startWrite()
                try { out.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(out) } catch (e: Exception) { atomic.failWrite(out); throw e }
            }
        }
    }

    private const val FILE = "broken_media.txt"
}
