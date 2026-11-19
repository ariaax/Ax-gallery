package com.iris.gallery.data

/**
 * Pure bookkeeping for the on-device problem log (no Android, unit-tested).
 * One entry per distinct problem: repeats only bump a counter, so the log stays small however often something fails.
 */
class DiagnosticsStore(private val maxEntries: Int = 200) {
    data class Entry(
        val kind: String,
        val key: String,
        var detail: String,
        var count: Int,
        val firstMs: Long,
        var lastMs: Long,
        val samples: MutableList<String> = mutableListOf(),
    )

    private val entries = LinkedHashMap<String, Entry>()

    val size: Int get() = entries.size
    fun all(): List<Entry> = entries.values.toList()

    /** Adds a problem or bumps its counter. [sample] (e.g. a file name) is remembered for the first few occurrences. */
    fun record(kind: String, key: String, detail: String, sample: String? = null, nowMs: Long): Entry {
        val id = "$kind|$key"
        val e = entries[id]?.also { it.count++; it.lastMs = nowMs }
            ?: Entry(kind, key, detail.take(DETAIL_MAX), 1, nowMs, nowMs).also { entries[id] = it }
        if (!sample.isNullOrBlank() && e.samples.size < MAX_SAMPLES && sample !in e.samples) e.samples.add(sample.take(120))
        while (entries.size > maxEntries) {
            val oldest = entries.values.minByOrNull { it.lastMs } ?: break
            entries.remove("${oldest.kind}|${oldest.key}")
        }
        return e
    }

    fun clear() = entries.clear()

    /** One line per entry, tab separated, newlines inside text escaped. */
    fun encode(): String = entries.values.joinToString("\n") { e ->
        listOf(e.firstMs, e.lastMs, e.count, e.kind, e.key, e.detail, e.samples.joinToString("\u0001"))
            .joinToString("\t") { esc(it.toString()) }
    }

    fun decode(text: String) {
        entries.clear()
        for (line in text.lineSequence()) {
            val p = line.split('\t')
            if (p.size < 7) continue
            val first = unesc(p[0]).toLongOrNull() ?: continue
            val last = unesc(p[1]).toLongOrNull() ?: continue
            val count = unesc(p[2]).toIntOrNull() ?: continue
            val e = Entry(unesc(p[3]), unesc(p[4]), unesc(p[5]), count, first, last)
            unesc(p[6]).split('\u0001').filter { it.isNotBlank() }.forEach { e.samples.add(it) }
            entries["${e.kind}|${e.key}"] = e
        }
    }

    /** Human-readable text to send: header first, newest problem first. */
    fun render(header: String, format: (Long) -> String): String = buildString {
        append(header.trimEnd()).append("\n\n")
        append("Entries: ").append(entries.size).append("\n")
        for (e in entries.values.sortedByDescending { it.lastMs }) {
            append("\n[").append(e.kind).append("] x").append(e.count)
                .append("  first ").append(format(e.firstMs)).append("  last ").append(format(e.lastMs)).append('\n')
            append("  ").append(e.key).append('\n')
            if (e.detail.isNotBlank()) append("  ").append(e.detail.replace("\n", "\n  ")).append('\n')
            if (e.samples.isNotEmpty()) append("  e.g. ").append(e.samples.joinToString(" | ")).append('\n')
        }
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "")
    private fun unesc(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) { 'n' -> sb.append('\n'); 't' -> sb.append('\t'); else -> sb.append(s[i + 1]) }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    companion object {
        const val DETAIL_MAX = 1800
        const val MAX_SAMPLES = 3
    }
}
