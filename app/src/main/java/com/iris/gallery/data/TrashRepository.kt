package com.iris.gallery.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

data class TrashMoveResult(
    val trashedMedia: List<MediaImage>,
    val originalMedia: List<MediaImage>,
    val silentSuccess: Boolean = false,
)

data class TrashItem(
    val id: Long,
    val trashFileName: String,
    val originalName: String,
    val originalPath: String,
    val originalBucketName: String,
    val originalBucketId: Long,
    val dateTaken: Long,
    val dateTrashed: Long,
    val width: Int,
    val height: Int,
    val isVideo: Boolean,
    val durationMs: Long,
    val mimeType: String = "",
    val sizeBytes: Long = 0,
    val orientation: Int = 0,
    val title: String = "",
    val description: String = "",
    val dateModified: Long = 0L,
    val dateAdded: Long = 0L,
)

/** File helpers for the trash, free of Android types so they can be unit-tested on the JVM. */
internal object TrashFiles {
    /** [file] if that path is free, otherwise "name (1).ext", "name (2).ext", ... so an existing file is never overwritten. */
    fun uniqueDestination(file: File): File {
        if (!file.exists()) return file
        val parent = file.parentFile
        val base = file.nameWithoutExtension
        val ext = file.extension.let { if (it.isEmpty()) "" else ".$it" }
        var i = 1
        while (true) {
            val candidate = File(parent, "$base ($i)$ext")
            if (!candidate.exists()) return candidate
            i++
        }
    }

    /**
     * Copies [src] to [dst] and only reports success when the copy has the same length as the source.
     * Refuses to touch a [dst] that already exists, and removes only a partial copy it created itself.
     */
    fun copyVerified(src: File, dst: File): Boolean {
        if (dst.exists() || !src.isFile) return false
        return try {
            src.copyTo(dst, overwrite = false)
            if (dst.length() == src.length()) true else { dst.delete(); false }
        } catch (e: Exception) {
            runCatching { dst.delete() }
            false
        }
    }

    /** True when [dst] already holds what [src] would restore: same length and (nearly) the same modified time. */
    fun looksIdentical(src: File, dst: File, expectedModifiedMs: Long): Boolean =
        dst.isFile && dst.length() == src.length() &&
            (expectedModifiedMs <= 0L || abs(dst.lastModified() - expectedModifiedMs) < 2000L)
}

class TrashRepository(private val context: Context) {
    private val trashDir: File = File(context.filesDir, "trash").apply {
        if (!exists()) {
            mkdirs()
        }
        val nomedia = File(this, ".nomedia")
        if (!nomedia.exists()) {
            runCatching { nomedia.createNewFile() }
        }
    }

    private val indexFile = AtomicFile(File(context.filesDir, "trash_index.json"))
    private val _trashedMedia = MutableStateFlow<List<MediaImage>>(emptyList())
    val trashedMedia: StateFlow<List<MediaImage>> = _trashedMedia.asStateFlow()

    companion object {
        const val TRASH_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L // 30 days
    }

    // Loaded off the main thread: the index read and per-item file checks used to block app start.
    // Until it finishes the list is simply empty.
    private val initScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    private val indexLock = Any()

    init {
        initScope.launch { loadTrashItems() }
    }

    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    fun autoPurgeOldItems(): Int = synchronized(indexLock) {
        val items = runCatching { readIndex().toMutableList() }.getOrNull() ?: return@synchronized 0
        val now = System.currentTimeMillis()
        var purgedCount = 0
        val iterator = items.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (now - item.dateTrashed > TRASH_RETENTION_MS) {
                val file = File(trashDir, item.trashFileName)
                runCatching { file.delete() }
                iterator.remove()
                purgedCount++
            }
        }
        if (purgedCount > 0) {
            writeIndex(items)
        }
        purgedCount
    }

    fun loadTrashItems(): List<MediaImage> = synchronized(indexLock) {
        autoPurgeOldItems()
        val items = runCatching { readIndex() }.getOrNull() ?: return@synchronized _trashedMedia.value
        // newest trashed first (the index is stored oldest-first)
        val mediaList = items.sortedByDescending { it.dateTrashed }.mapNotNull { item ->
            val file = File(trashDir, item.trashFileName)
            if (file.exists()) {
                val uniqueId = if (item.id > 0) -item.id else item.id
                MediaImage(
                    id = uniqueId,
                    uri = Uri.fromFile(file),
                    name = item.originalName,
                    dateTaken = item.dateTaken,
                    width = item.width,
                    height = item.height,
                    path = file.absolutePath,
                    bucketId = -2L,
                    bucketName = "Trash",
                    isVideo = item.isVideo,
                    durationMs = item.durationMs,
                    mimeType = item.mimeType,
                    sizeBytes = if (item.sizeBytes > 0) item.sizeBytes else file.length(),
                    orientation = item.orientation,
                    title = item.title,
                    description = item.description,
                    dateModified = item.dateModified,
                    dateAdded = item.dateAdded,
                )
            } else {
                null
            }
        }
        _trashedMedia.value = mediaList
        mediaList
    }

    fun deleteFromStorage(item: MediaImage): Boolean {
        var deleted = false
        if (item.path.isNotBlank()) {
            val file = File(item.path)
            if (file.exists()) {
                val fDel = runCatching { file.delete() }.getOrDefault(false)
                val cDel = if (!fDel && file.exists()) runCatching { file.canonicalFile.delete() }.getOrDefault(false) else false
                deleted = fDel || cDel
            }
        }
        val mediaStoreUri = if (item.id > 0) {
            if (item.isVideo) ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, item.id)
            else ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, item.id)
        } else {
            item.uri
        }
        val contentDeleted = runCatching {
            context.contentResolver.delete(mediaStoreUri, null, null) > 0
        }.getOrDefault(false)
        deleted = deleted || contentDeleted

        if (item.id > 0) {
            val table = if (item.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val idDeleted = runCatching {
                context.contentResolver.delete(table, "${MediaStore.MediaColumns._ID}=?", arrayOf(item.id.toString())) > 0
            }.getOrDefault(false)
            deleted = deleted || idDeleted
            runCatching {
                context.contentResolver.delete(MediaStore.Files.getContentUri("external"), "${MediaStore.MediaColumns._ID}=?", arrayOf(item.id.toString()))
            }
            if (Build.VERSION.SDK_INT >= 29) {
                runCatching {
                    context.contentResolver.delete(MediaStore.Files.getContentUri("external_primary"), "${MediaStore.MediaColumns._ID}=?", arrayOf(item.id.toString()))
                }
            }
        }

        if (item.uri != mediaStoreUri) {
            val genericDeleted = runCatching {
                context.contentResolver.delete(item.uri, null, null) > 0
            }.getOrDefault(false)
            deleted = deleted || genericDeleted
        }

        if (item.path.isNotBlank()) {
            val table = if (item.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val pathDeleted = runCatching {
                context.contentResolver.delete(table, "${MediaStore.MediaColumns.DATA}=?", arrayOf(item.path)) > 0
            }.getOrDefault(false)
            deleted = deleted || pathDeleted
            runCatching {
                context.contentResolver.delete(MediaStore.Files.getContentUri("external"), "${MediaStore.MediaColumns.DATA}=?", arrayOf(item.path))
            }
            if (Build.VERSION.SDK_INT >= 29) {
                runCatching {
                    context.contentResolver.delete(MediaStore.Files.getContentUri("external_primary"), "${MediaStore.MediaColumns.DATA}=?", arrayOf(item.path))
                }
            }
            MediaScannerConnection.scanFile(context, arrayOf(item.path), null, null)
        }
        return deleted
    }

    suspend fun moveToTrash(mediaList: List<MediaImage>): TrashMoveResult = withContext(Dispatchers.IO) { synchronized(indexLock) {
        val currentItems = readIndex().toMutableList()
        val trashedList = mutableListOf<MediaImage>()
        val originalList = mutableListOf<MediaImage>()
        val hasManagerAccess = hasAllFilesAccess()

        for (item in mediaList.distinctBy { it.id to it.uri }) {
            if (item.path.isNotBlank()) RestoredDates.forget(context, item.path)
            val uniqueId = generateUniqueId(currentItems)
            val cleanName = sanitizeFileName(item.name.ifBlank { "media_${uniqueId}" })
            val trashFileName = "${uniqueId}_${cleanName}"
            val targetFile = File(trashDir, trashFileName)

            val srcFile = if (item.path.isNotBlank()) File(item.path) else null
            var copied = false

            if (srcFile != null && srcFile.exists()) {
                // A truncated copy followed by deleting the original would lose the photo: the copy must match in size.
                targetFile.delete()
                copied = TrashFiles.copyVerified(srcFile, targetFile)
            }

            if (!copied) {
                copied = runCatching {
                    context.contentResolver.openInputStream(item.uri)?.use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    targetFile.exists() && targetFile.length() > 0
                }.getOrDefault(false)
            }

            if (copied && targetFile.exists()) {
                val effectiveMod = when {
                    item.dateModified > 0L -> item.dateModified
                    srcFile != null && srcFile.lastModified() > 0L -> srcFile.lastModified()
                    item.dateTaken > 0L -> item.dateTaken
                    else -> 0L
                }
                if (effectiveMod > 0L) {
                    targetFile.setLastModified(effectiveMod)
                }
                val trashItem = TrashItem(
                    id = uniqueId,
                    trashFileName = trashFileName,
                    originalName = item.name,
                    originalPath = item.path,
                    originalBucketName = item.bucketName,
                    originalBucketId = item.bucketId,
                    dateTaken = item.dateTaken,
                    dateTrashed = System.currentTimeMillis(),
                    width = item.width,
                    height = item.height,
                    isVideo = item.isVideo,
                    durationMs = item.durationMs,
                    mimeType = item.mimeType,
                    sizeBytes = targetFile.length(),
                    orientation = item.orientation,
                    title = item.title,
                    description = item.description,
                    dateModified = effectiveMod,
                    dateAdded = item.dateAdded,
                )
                currentItems.add(trashItem)
                val media = MediaImage(
                    id = -uniqueId,
                    uri = Uri.fromFile(targetFile),
                    name = item.name,
                    dateTaken = item.dateTaken,
                    width = item.width,
                    height = item.height,
                    path = targetFile.absolutePath,
                    bucketId = -2L,
                    bucketName = "Trash",
                    isVideo = item.isVideo,
                    durationMs = item.durationMs,
                    mimeType = item.mimeType,
                    sizeBytes = targetFile.length(),
                    orientation = item.orientation,
                    title = item.title,
                    description = item.description,
                    dateModified = effectiveMod,
                    dateAdded = item.dateAdded,
                )
                trashedList.add(media)
                originalList.add(item)
            }
        }

        // The index must be on disk before any original is deleted: if it cannot be written the trash copies would be
        // orphans nobody can see, and deleting the originals would silently lose the files.
        if (trashedList.isNotEmpty() && !writeIndex(currentItems)) {
            trashedList.forEach { runCatching { File(it.path).delete() } }
            return@synchronized TrashMoveResult(trashedMedia = emptyList(), originalMedia = emptyList(), silentSuccess = false)
        }

        var silentSuccess = false
        if (hasManagerAccess && trashedList.isNotEmpty()) {
            val allDeleted = originalList.all { item ->
                deleteFromStorage(item)
            }
            if (allDeleted) {
                silentSuccess = true
            }
        }

        loadTrashItems()
        TrashMoveResult(trashedMedia = trashedList, originalMedia = originalList, silentSuccess = silentSuccess)
    } }

    suspend fun rollbackTrash(trashedItems: List<MediaImage>) = withContext(Dispatchers.IO) { synchronized(indexLock) {
        val currentItems = readIndex().toMutableList()
        val idsToRemove = trashedItems.map { abs(it.id) }.toSet()

        currentItems.removeAll { item ->
            if (item.id in idsToRemove) {
                val file = File(trashDir, item.trashFileName)
                runCatching { file.delete() }
                true
            } else {
                false
            }
        }
        writeIndex(currentItems)
        loadTrashItems()
    } }

    suspend fun restoreFromTrash(mediaList: List<MediaImage>): List<MediaImage> = withContext(Dispatchers.IO) { synchronized(indexLock) {
        val currentItems = readIndex().toMutableList()
        val restoredList = mutableListOf<MediaImage>()
        val idsToRemove = mediaList.map { abs(it.id) }.toSet()

        val itemsToRestore = currentItems.filter { it.id in idsToRemove }
        for (item in itemsToRestore) {
            val trashFile = File(trashDir, item.trashFileName)
            if (!trashFile.exists()) {
                currentItems.remove(item)
                continue
            }

            val restoredPath = restoreFileToStorage(item, trashFile)
            if (restoredPath != null) {
                // MediaStore stamps DATE_ADDED with "now" for the new row and the timeline's default mode keeps new files on
                // top, so remember the original value and let the loader use it: the photo returns to where it was.
                RestoredDates.record(context, restoredPath, item.dateAdded)
                runCatching { trashFile.delete() }
                currentItems.remove(item)
                restoredList.add(
                    MediaImage(
                        id = item.id,
                        uri = Uri.fromFile(trashFile),
                        name = item.originalName,
                        dateTaken = item.dateTaken,
                        width = item.width,
                        height = item.height,
                        path = restoredPath,
                        bucketId = item.originalBucketId,
                        bucketName = item.originalBucketName,
                        isVideo = item.isVideo,
                        durationMs = item.durationMs,
                        mimeType = item.mimeType,
                        sizeBytes = item.sizeBytes,
                        orientation = item.orientation,
                        title = item.title,
                        description = item.description,
                        dateModified = item.dateModified,
                        dateAdded = item.dateAdded,
                    )
                )
            }
        }

        writeIndex(currentItems)
        loadTrashItems()
        restoredList
    } }

    suspend fun deletePermanently(mediaList: List<MediaImage>) = withContext(Dispatchers.IO) { synchronized(indexLock) {
        val currentItems = readIndex().toMutableList()
        val idsToRemove = mediaList.map { abs(it.id) }.toSet()
        val pathsToRemove = mediaList.map { it.path }.toSet()
        val fileNamesToRemove = mediaList.map { File(it.path).name }.toSet()
        val hasManagerAccess = hasAllFilesAccess()

        currentItems.removeAll { item ->
            if (item.id in idsToRemove || item.originalPath in pathsToRemove || item.trashFileName in fileNamesToRemove) {
                val trashFile = File(trashDir, item.trashFileName)
                runCatching { trashFile.delete() }
                true
            } else {
                false
            }
        }
        writeIndex(currentItems)

        for (media in mediaList) {
            deleteFromStorage(media)
        }

        loadTrashItems()
    } }

    suspend fun emptyTrash() = withContext(Dispatchers.IO) { synchronized(indexLock) {
        trashDir.listFiles()?.forEach { file ->
            if (file.name != ".nomedia") {
                runCatching { file.delete() }
            }
        }
        writeIndex(emptyList())
        loadTrashItems()
    } }

    /** Returns the final path of the restored file, or null when the restore failed (the trash copy is then kept). */
    private fun restoreFileToStorage(item: TrashItem, trashFile: File): String? {
        return try {
            val effectiveModMs = when {
                item.dateModified > 0L -> item.dateModified
                item.dateTaken > 0L -> item.dateTaken
                else -> trashFile.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
            }
            val effectiveTakenMs = if (item.dateTaken > 0L) item.dateTaken else effectiveModMs
            val effectiveModSec = effectiveModMs / 1000L
            val effectiveAddedSec = if (item.dateAdded > 0L) item.dateAdded / 1000L else effectiveModSec

            if (hasAllFilesAccess()) {
                val wanted = if (item.originalPath.isNotBlank()) {
                    val file = File(item.originalPath)
                    file.parentFile?.mkdirs()
                    file
                } else {
                    val fallbackDir = Environment.getExternalStoragePublicDirectory(
                        if (item.isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                    ).resolve("Iris").apply { mkdirs() }
                    File(fallbackDir, item.originalName)
                }
                placeRestoredFile(item, trashFile, wanted, effectiveModMs, effectiveTakenMs, effectiveAddedSec, effectiveModSec)
            } else if (Build.VERSION.SDK_INT >= 29) {
                val relativePath = computeRelativePath(item.originalPath, item.isVideo)
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, item.originalName)
                    put(
                        MediaStore.MediaColumns.MIME_TYPE,
                        item.mimeType.ifBlank { if (item.isVideo) "video/mp4" else "image/jpeg" }
                    )
                    put(MediaStore.MediaColumns.DATE_ADDED, effectiveAddedSec)
                    put(MediaStore.MediaColumns.DATE_MODIFIED, effectiveModSec)
                    put(MediaStore.MediaColumns.DATE_TAKEN, effectiveTakenMs)
                    if (!item.isVideo) {
                        put(MediaStore.Images.Media.ORIENTATION, item.orientation)
                    }
                    if (item.title.isNotBlank()) put(MediaStore.MediaColumns.TITLE, item.title)
                    if (item.isVideo) {
                        put(MediaStore.Video.VideoColumns.DURATION, item.durationMs)
                    }
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val collection = if (item.isVideo) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                } else {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                }
                val newUri = context.contentResolver.insert(collection, values) ?: return null
                val written = try {
                    val output = context.contentResolver.openOutputStream(newUri)
                    if (output == null) false else {
                        output.use { out -> trashFile.inputStream().use { input -> input.copyTo(out) } }
                        true
                    }
                } catch (e: Exception) {
                    false
                }
                if (!written) {
                    // Do not leave an empty pending row behind, and keep the trash copy (the caller only deletes it on success).
                    runCatching { context.contentResolver.delete(newUri, null, null) }
                    return null
                }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                values.put(MediaStore.MediaColumns.DATE_MODIFIED, effectiveModSec)
                values.put(MediaStore.MediaColumns.DATE_TAKEN, effectiveTakenMs)
                values.put(MediaStore.MediaColumns.DATE_ADDED, effectiveAddedSec)
                context.contentResolver.update(newUri, values, null, null)
                runCatching {
                    context.contentResolver.query(newUri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }
                }.getOrNull().orEmpty().ifBlank { File(Environment.getExternalStorageDirectory(), relativePath + item.originalName).absolutePath }
            } else {
                @Suppress("DEPRECATION")
                val publicDir = if (item.originalPath.isNotBlank()) {
                    File(item.originalPath).parentFile ?: Environment.getExternalStoragePublicDirectory(
                        if (item.isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                    ).resolve("Iris")
                } else {
                    Environment.getExternalStoragePublicDirectory(
                        if (item.isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                    ).resolve("Iris")
                }.apply { mkdirs() }
                placeRestoredFile(item, trashFile, File(publicDir, item.originalName), effectiveModMs, effectiveTakenMs, effectiveAddedSec, effectiveModSec)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Copies the trashed file back without ever overwriting a different file, waits for the media scanner so the
     * item exists in MediaStore before the caller refreshes the gallery, then pins the original dates: the scanner
     * would otherwise stamp DATE_ADDED with "now", and the restored photo would jump to the top of the timeline.
     * Throws (the caller treats that as a failed restore and keeps the trash copy) when the copy is not exact.
     */
    private fun placeRestoredFile(
        item: TrashItem,
        trashFile: File,
        wanted: File,
        modMs: Long,
        takenMs: Long,
        addedSec: Long,
        modSec: Long,
    ): String {
        val dest = if (TrashFiles.looksIdentical(trashFile, wanted, modMs)) wanted else {
            val target = TrashFiles.uniqueDestination(wanted)
            check(TrashFiles.copyVerified(trashFile, target)) { "restore copy failed" }
            target
        }
        if (modMs > 0L) dest.setLastModified(modMs)
        val uri = scanBlocking(dest.absolutePath, item.mimeType.ifBlank { null })
        if (uri != null) {
            runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DATE_TAKEN, takenMs)
                    put(MediaStore.MediaColumns.DATE_ADDED, addedSec)
                    put(MediaStore.MediaColumns.DATE_MODIFIED, modSec)
                }
                context.contentResolver.update(uri, values, null, null)
            }
        }
        return dest.absolutePath
    }

    /** Runs the media scanner on [path] and waits (bounded) for it to finish; returns the MediaStore uri when known. */
    private fun scanBlocking(path: String, mimeType: String?): Uri? {
        val latch = java.util.concurrent.CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference<Uri?>(null)
        MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf(mimeType)) { _, uri ->
            result.set(uri)
            latch.countDown()
        }
        latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
        return result.get()
    }

    private fun computeRelativePath(originalPath: String, isVideo: Boolean): String {
        val defaultDir = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        if (originalPath.isNotBlank()) {
            val parent = File(originalPath).parentFile?.absolutePath.orEmpty()
            val emulatedPrefix = "/storage/emulated/0/"
            if (parent.startsWith(emulatedPrefix)) {
                val rel = parent.removePrefix(emulatedPrefix).trim('/')
                if (rel.isNotBlank()) {
                    val root = rel.substringBefore('/')
                    val allowed = if (isVideo) {
                        root.equals(Environment.DIRECTORY_DCIM, ignoreCase = true) ||
                                root.equals(Environment.DIRECTORY_MOVIES, ignoreCase = true)
                    } else {
                        root.equals(Environment.DIRECTORY_DCIM, ignoreCase = true) ||
                                root.equals(Environment.DIRECTORY_PICTURES, ignoreCase = true)
                    }
                    if (allowed) {
                        return "$rel/"
                    }
                }
            }
        }
        return "$defaultDir/Iris/"
    }

    private fun generateUniqueId(existing: List<TrashItem>): Long {
        var id = System.currentTimeMillis()
        val ids = existing.map { it.id }.toSet()
        while (id in ids || id <= 0) {
            id++
        }
        return id
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }


    private fun readIndex(): List<TrashItem> {
        val file = indexFile.baseFile
        if (!file.exists()) return emptyList()
        return try {
            val json = indexFile.openRead().bufferedReader().use { it.readText() }
            val array = JSONArray(json)
            val list = mutableListOf<TrashItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    TrashItem(
                        id = obj.optLong("id"),
                        trashFileName = obj.optString("trashFileName"),
                        originalName = obj.optString("originalName"),
                        originalPath = obj.optString("originalPath"),
                        originalBucketName = obj.optString("originalBucketName"),
                        originalBucketId = obj.optLong("originalBucketId"),
                        dateTaken = obj.optLong("dateTaken"),
                        dateTrashed = obj.optLong("dateTrashed"),
                        width = obj.optInt("width"),
                        height = obj.optInt("height"),
                        isVideo = obj.optBoolean("isVideo"),
                        durationMs = obj.optLong("durationMs"),
                        mimeType = obj.optString("mimeType"),
                        sizeBytes = obj.optLong("sizeBytes"),
                        orientation = obj.optInt("orientation"),
                        title = obj.optString("title"),
                        description = obj.optString("description"),
                        dateModified = obj.optLong("dateModified"),
                        dateAdded = obj.optLong("dateAdded"),
                    )
                )
            }
            list
        } catch (e: Exception) {
            // Never pretend the index is empty: the caller would write that back and wipe the trash.
            throw IllegalStateException("trash index unreadable", e)
        }
    }

    private fun writeIndex(items: List<TrashItem>): Boolean {
        var stream: FileOutputStream? = null
        return try {
            val array = JSONArray()
            for (item in items) {
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("trashFileName", item.trashFileName)
                    put("originalName", item.originalName)
                    put("originalPath", item.originalPath)
                    put("originalBucketName", item.originalBucketName)
                    put("originalBucketId", item.originalBucketId)
                    put("dateTaken", item.dateTaken)
                    put("dateTrashed", item.dateTrashed)
                    put("width", item.width)
                    put("height", item.height)
                    put("isVideo", item.isVideo)
                    put("durationMs", item.durationMs)
                    put("mimeType", item.mimeType)
                    put("sizeBytes", item.sizeBytes)
                    put("orientation", item.orientation)
                    put("title", item.title)
                    put("description", item.description)
                    put("dateModified", item.dateModified)
                    put("dateAdded", item.dateAdded)
                }
                array.put(obj)
            }
            stream = indexFile.startWrite()
            stream.write(array.toString(2).toByteArray(Charsets.UTF_8))
            indexFile.finishWrite(stream)
            true
        } catch (e: Exception) {
            indexFile.failWrite(stream)
            false
        }
    }
}
