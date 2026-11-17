package com.iris.gallery.data

/** Stable MediaStore identity; numeric IDs are scoped to a volume and media table, not globally unique. */
internal object MediaIdentity {
    fun key(volumeName: String, path: String, isVideo: Boolean, id: Long): String {
        val volume = volumeName.ifBlank {
            when {
                path.startsWith("/storage/emulated/0/") -> "external_primary"
                path.startsWith("/storage/") -> path.removePrefix("/storage/").substringBefore('/').ifBlank { "external_primary" }
                else -> "external_primary"
            }
        }
        return "$volume:${if (isVideo) "video" else "image"}:$id"
    }
}
