package com.iris.gallery.data

/**
 * Adds freshly restored items to the list the gallery already shows, without waiting for MediaStore.
 * An item whose path or key is already present (MediaStore got there first) is skipped, so the grid never shows
 * a photo twice. Existing items keep their place and come first, so the real MediaStore row wins over our stand-in.
 */
internal fun <T> mergeRestored(existing: List<T>, restored: List<T>, path: (T) -> String, key: (T) -> String): List<T> {
    if (restored.isEmpty()) return existing
    val seenPaths = HashSet<String>(existing.size + restored.size)
    val seenKeys = HashSet<String>(existing.size + restored.size)
    existing.forEach { item -> path(item).takeIf { it.isNotBlank() }?.let(seenPaths::add); seenKeys.add(key(item)) }
    val fresh = restored.filter { item ->
        val p = path(item)
        (p.isBlank() || seenPaths.add(p)) && seenKeys.add(key(item))
    }
    return if (fresh.isEmpty()) existing else existing + fresh
}
