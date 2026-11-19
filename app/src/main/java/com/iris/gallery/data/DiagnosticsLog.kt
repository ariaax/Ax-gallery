package com.iris.gallery.data

import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Permanent, tiny problem log: crashes, photos/videos the gallery cannot show or play, and caught errors.
 * Survives closing the app (one small file in app storage), de-duplicates repeats, never leaves the phone until the
 * user taps Share in About. Writes are debounced on a background thread, so logging costs almost nothing.
 */
object DiagnosticsLog {
    private const val FILE = "diagnostics.log"
    private val store = DiagnosticsStore()
    private val lock = Any()
    private var appContext: Context? = null
    private var loaded = false
    private var dirty = false
    private val seenThisSession = HashSet<String>()
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ax-diagnostics").also { it.isDaemon = true; it.priority = Thread.MIN_PRIORITY } }

    fun init(context: Context) { appContext = context.applicationContext }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val ctx = appContext ?: return
        runCatching {
            val f = AtomicFile(File(ctx.filesDir, FILE))
            if (File(ctx.filesDir, FILE).exists()) store.decode(f.readFully().toString(Charsets.UTF_8))
        }
    }

    private fun schedulePersist() {
        if (dirty) return
        dirty = true
        worker.schedule({ persistNow() }, 1500, TimeUnit.MILLISECONDS)
    }

    private fun persistNow() {
        val ctx = appContext ?: return
        val text = synchronized(lock) { dirty = false; store.encode() }
        runCatching {
            val atomic = AtomicFile(File(ctx.filesDir, FILE))
            val out = atomic.startWrite()
            try { out.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(out) } catch (e: Exception) { atomic.failWrite(out); throw e }
        }
    }

    /** Generic entry. Repeats of the same [key] only count up. */
    fun report(kind: String, key: String, detail: String = "", sample: String? = null) {
        if (appContext == null) return
        synchronized(lock) {
            ensureLoaded()
            // the same file failing again and again while scrolling is counted once per session
            if (sample != null && !seenThisSession.add("$kind|$key|$sample") ) return
            if (seenThisSession.size > 2000) seenThisSession.clear()
            store.record(kind, key, detail, sample, System.currentTimeMillis())
        }
        schedulePersist()
    }

    /** A photo/video the gallery could not decode or play. Only type/size are logged, never the folder path. */
    fun unsupported(image: MediaImage, reason: String) {
        val ext = image.name.substringAfterLast('.', "").lowercase(Locale.ROOT).ifBlank { "?" }
        val mime = image.mimeType.ifBlank { "?" }
        val sizeMb = image.sizeBytes / 1_048_576.0
        report(
            kind = "UNSUPPORTED",
            key = "$mime .$ext — ${reason.lineSequence().firstOrNull().orEmpty().take(90)}",
            detail = reason.take(600),
            sample = "${image.name} (${"%.1f".format(Locale.US, sizeMb)} MB, ${image.width}x${image.height})",
        )
    }

    fun error(where: String, t: Throwable) {
        report("ERROR", "$where: ${t.javaClass.simpleName}", android.util.Log.getStackTraceString(t).take(1500))
    }

    /** Called from the crash handler: the process is dying, so write straight away. */
    fun crash(thread: Thread, t: Throwable) {
        if (appContext == null) return
        runCatching {
            synchronized(lock) {
                ensureLoaded()
                store.record("CRASH", "${t.javaClass.simpleName}: ${(t.message ?: "").take(80)}",
                    "Thread: ${thread.name}\n" + android.util.Log.getStackTraceString(t).take(1500), null, System.currentTimeMillis())
            }
            persistNow()
        }
    }

    fun count(): Int = synchronized(lock) { ensureLoaded(); store.size }

    fun clear() {
        synchronized(lock) { ensureLoaded(); store.clear(); seenThisSession.clear() }
        persistNow()
    }

    fun buildReport(context: Context): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val header = buildString {
            append("Ax Gallery problem report\n")
            append("App: ").append(com.iris.gallery.BuildConfig.VERSION_NAME).append(" (").append(com.iris.gallery.BuildConfig.VERSION_CODE).append(")\n")
            append("Device: ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
            append(", Android ").append(android.os.Build.VERSION.RELEASE).append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
            append("Sort mode: ").append(TimelineSort.mode).append("\n")
            append("Created: ").append(fmt.format(Date()))
        }
        return synchronized(lock) { ensureLoaded(); store.render(header) { fmt.format(Date(it)) } }
    }

    /** Opens the share sheet with the report as a small text file. */
    fun share(context: Context) {
        runCatching {
            val dir = File(context.cacheDir, "shared_media").apply { mkdirs() }
            val file = File(dir, "ax-gallery-problems.txt")
            file.writeText(buildReport(context), Charsets.UTF_8)
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Ax Gallery problem report")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Send problem report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
