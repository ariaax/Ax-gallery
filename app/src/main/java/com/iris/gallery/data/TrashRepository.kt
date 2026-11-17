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
import java.security.MessageDigest
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
    val originalVolumeName: String = "external_primary",
    val originalMediaId: Long = 0L,
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
        if (!src.isFile || !copyStreamVerified(src.inputStream(), dst, src.length())) return false
        if (looksIdentical(src, dst, expectedModifiedMs = 0L)) return true
        runCatching { dst.delete() }
        return false
    }

    /** Compares a provider stream with a local file without buffering the full payload in memory. */
    fun streamMatchesFile(input: java.io.InputStream?, expected: File): Boolean {
        if (input == null || !expected.isFile) { runCatching { input?.close() }; return false }
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            input.use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    count += read
                }
            }
            count == expected.length() && MessageDigest.isEqual(digest.digest(), sha256(expected))
        }.getOrDefault(false)
    }

    /** Stream to a sibling .part file, verify its byte count, sync it, then atomically publish it. */
    fun copyStreamVerified(input: java.io.InputStream?, dst: File, expectedSize: Long): Boolean {
        if (input == null || dst.exists()) { runCatching { input?.close() }; return false }
        val part = File(dst.parentFile, dst.name + ".part")
        return try {
            dst.parentFile?.mkdirs()
            val sourceDigest = MessageDigest.getInstance("SHA-256")
            val written = input.use { src ->
                FileOutputStream(part).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = src.read(buffer)
                        if (count < 0) break
                        sourceDigest.update(buffer, 0, count)
                        out.write(buffer, 0, count)
                        total += count
                    }
                    out.flush()
                    out.fd.sync()
                    total
                }
            }
            val verified = written > 0L && part.length() == written &&
                (expectedSize <= 0L || written == expectedSize) &&
                MessageDigest.isEqual(sourceDigest.digest(), sha256(part))
            verified && !dst.exists() && part.renameTo(dst)
        } catch (_: Exception) {
            false
        } finally {
            runCatching { part.delete() }
        }
    }

    /** A backup may be discarded only when the original path contains the exact same bytes. */
    fun canDiscardBackup(originalPath: String, backup: File): Boolean {
        if (originalPath.isBlank()) return false
        val original = runCatching { File(originalPath) }.getOrNull() ?: return false
        return looksIdentical(backup, original, expectedModifiedMs = 0L)
    }

    fun <T> deleteIndependently(items: List<T>, delete: (T) -> Boolean): List<Pair<T, Boolean>> =
        items.map { item -> item to runCatching { delete(item) }.getOrDefault(false) }

    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest()
    }

    /** Same-length files are not assumed identical: compare their SHA-256 content digests. */
    fun looksIdentical(src: File, dst: File, expectedModifiedMs: Long): Boolean =
        src.isFile && dst.isFile && src.length() > 0L && src.length() == dst.length() &&
            (expectedModifiedMs <= 0L || abs(dst.lastModified() - expectedModifiedMs) < 2000L) &&
            runCatching { MessageDigest.isEqual(sha256(src), sha256(dst)) }.getOrDefault(false)
}

private fun volumeNameFromOriginalPath(path: String): String = when {
    path.startsWith("/storage/emulated/0/") -> "external_primary"
    path.startsWith("/storage/") -> path.removePrefix("/storage/").substringBefore('/').takeIf { it.isNotBlank() } ?: "external_primary"
    else -> "external_primary"
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
        initScope.launch {
            trashDir.listFiles { file -> file.name.endsWith(".part") }?.forEach { runCatching { it.delete() } }
            loadTrashItems()
        }
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
        val expired = items.filter { now - it.dateTrashed > TRASH_RETENTION_MS }
        if (expired.isEmpty()) return@synchronized 0
        val retained = items - expired.toSet()
        if (!writeIndex(retained)) return@synchronized 0
        expired.forEach { item -> runCatching { File(trashDir, item.trashFileName).delete() } }
        expired.size
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
                    volumeName = item.originalVolumeName,
                )
            } else {
                null
            }
        }
        _trashedMedia.value = mediaList
        mediaList
    }

    fun deleteFromStorage(item: MediaImage): Boolean {
        val pathFile = item.path.takeIf { it.isNotBlank() }?.let(::File)
        var fileDeleted = false
        if (pathFile?.isFile == true) fileDeleted = runCatching { pathFile.delete() }.getOrDefault(false)
        // The MediaImage URI is already volume-specific. Do not issue a second ID-only delete on
        // the synthetic external collection, where an SD-card row can share the same numeric ID.
        val rowDeleted = runCatching {
            context.contentResolver.delete(item.uri.buildUpon().clearQuery().build(), null, null) > 0
        }.getOrDefault(false)
        return (pathFile == null || !pathFile.exists()) && (fileDeleted || rowDeleted || item.path.isBlank())
    }

    suspend fun moveToTrash(mediaList: List<MediaImage>): TrashMoveResult = withContext(Dispatchers.IO) { synchronized(indexLock) {
        val currentItems = readIndex().toMutableList()
        val trashedList = mutableListOf<MediaImage>()
        val originalList = mutableListOf<MediaImage>()
        val hasManagerAccess = hasAllFilesAccess()

        for (item in mediaList.distinctBy { it.id to it.uri }) {
            val uniqueId = generateUniqueId(currentItems)
            val cleanName = sanitizeFileName(item.name.ifBlank { "media_${uniqueId}" })
            val trashFileName = "${uniqueId}_${cleanName}"
            val targetFile = File(trashDir, trashFileName)

            val srcFile = if (item.path.isNotBlank()) File(item.path) else null
            var copied = false

            if (srcFile != null && srcFile.exists()) {
                // A truncated copy followed by deleting the original would lose the photo: the copy must match in size.
                copied = TrashFiles.copyVerified(srcFile, targetFile)
            }

            if (!copied) {
                copied = runCatching {
                    TrashFiles.copyStreamVerified(
                        context.contentResolver.openInputStream(item.uri),
                        targetFile,
                        expectedSourceSize(item)
                    ) && TrashFiles.streamMatchesFile(context.contentResolver.openInputStream(item.uri), targetFile)
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
                    originalVolumeName = item.volumeName.ifBlank { "external_primary" },
                    originalMediaId = item.id,
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
                    volumeName = item.volumeName,
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
            // Evaluate every item independently. all() short-circuits and can leave later originals untouched.
            val deleteResults = TrashFiles.deleteIndependently(originalList, ::deleteFromStorage)
            deleteResults.filter { it.second }.forEach { (item, _) ->
                if (item.path.isNotBlank()) RestoredDates.forget(context, item.path)
            }
            silentSuccess = deleteResults.all { it.second }
        }

        loadTrashItems()
        TrashMoveResult(trashedMedia = trashedList, originalMedia = originalList, silentSuccess = silentSuccess)
    } }

    suspend fun rollbackTrash(trashedItems: List<MediaImage>) = withContext(Dispatchers.IO) { synchronized(indexLock) {
        val currentItems = runCatching { readIndex() }.getOrNull()?.toMutableList() ?: return@synchronized
        val idsToRollback = trashedItems.map { abs(it.id) }.toSet()
        val removable = currentItems.filter { item ->
            if (item.id !in idsToRollback) false
            else {
                // A backup is disposable only when the original is positively present. If path access is
                // unavailable or the original vanished during a partial system delete, preserve the backup.
                TrashFiles.canDiscardBackup(item.originalPath, File(trashDir, item.trashFileName))
            }
        }
        if (removable.isEmpty()) { loadTrashItems(); return@synchronized }
        currentItems.removeAll(removable.toSet())
        if (writeIndex(currentItems)) {
            removable.forEach { item -> runCatching { File(trashDir, item.trashFileName).delete() } }
        }
        loadTrashItems()
    } }

    suspend fun restoreFromTrash(mediaList: List<MediaImage>): List<MediaImage> = withContext(Dispatchers.IO) { synchronized(indexLock) {
        val currentItems = readIndex().toMutableList()
        val restoredList = mutableListOf<MediaImage>()
        val restoredDateEntries = mutableListOf<Pair<String, Long>>()
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
                restoredDateEntries.add(restoredPath to item.dateAdded)
                currentItems.remove(item)
                restoredList.add(
                    MediaImage(
                        id = item.originalMediaId.takeIf { it > 0L } ?: -item.id,
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
                        volumeName = item.originalVolumeName,
                    )
                )
            }
        }

        if (restoredList.isNotEmpty() && writeIndex(currentItems)) {
            RestoredDates.recordAll(context, restoredDateEntries)
            itemsToRestore.filter { it !in currentItems }.forEach { item ->
                runCatching { File(trashDir, item.trashFileName).delete() }
            }
        }
        loadTrashItems()
        restoredList
    } }

    suspend fun deletePermanently(mediaList: List<MediaImage>) = withContext(Dispatchers.IO) { synchronized(indexLock) {
        val currentItems = runCatching { readIndex() }.getOrNull()?.toMutableList() ?: return@synchronized
        val idsToRemove = mediaList.map { abs(it.id) }.toSet()
        val pathsToRemove = mediaList.map { it.path }.toSet()
        val fileNamesToRemove = mediaList.map { File(it.path).name }.toSet()
        val removed = currentItems.filter { item ->
            item.id in idsToRemove || item.originalPath in pathsToRemove || item.trashFileName in fileNamesToRemove
        }
        currentItems.removeAll(removed.toSet())
        if (writeIndex(currentItems)) {
            removed.forEach { item -> runCatching { File(trashDir, item.trashFileName).delete() } }
        }
        for (media in mediaList) deleteFromStorage(media)
        loadTrashItems()
    } }

    suspend fun emptyTrash() = withContext(Dispatchers.IO) { synchronized(indexLock) {
        if (writeIndex(emptyList())) {
            trashDir.listFiles()?.forEach { file ->
                if (file.name != ".nomedia") runCatching { file.delete() }
            }
        }
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
                val volume = item.originalVolumeName.ifBlank { "external_primary" }
                val collection = if (item.isVideo) {
                    MediaStore.Video.Media.getContentUri(volume)
                } else {
                    MediaStore.Images.Media.getContentUri(volume)
                }
                // If the prior attempt published the row but crashed before committing the index,
                // reuse that exact row instead of inserting a duplicate.
                val existingPath = findExistingIdenticalMediaStoreFile(collection, volume, item.originalName, relativePath, trashFile)
                if (existingPath != null) return existingPath
                val newUri = context.contentResolver.insert(collection, values) ?: return null
                val written = try {
                    val output = context.contentResolver.openOutputStream(newUri)
                    VaultFiles.copyFileToStream(trashFile, output)
                } catch (_: Exception) {
                    false
                }
                val contentVerified = written && TrashFiles.streamMatchesFile(
                    context.contentResolver.openInputStream(newUri), trashFile
                )
                if (!contentVerified) {
                    // Do not leave an empty/corrupt pending row behind; keep the trash copy.
                    runCatching { context.contentResolver.delete(newUri, null, null) }
                    return null
                }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                values.put(MediaStore.MediaColumns.DATE_MODIFIED, effectiveModSec)
                values.put(MediaStore.MediaColumns.DATE_TAKEN, effectiveTakenMs)
                values.put(MediaStore.MediaColumns.DATE_ADDED, effectiveAddedSec)
                val published = runCatching { context.contentResolver.update(newUri, values, null, null) > 0 }.getOrDefault(false)
                if (!published) {
                    runCatching { context.contentResolver.delete(newUri, null, null) }
                    return null
                }
                runCatching {
                    context.contentResolver.query(newUri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }
                }.getOrNull().orEmpty().ifBlank {
                    val volumeRoot = if (item.originalVolumeName == "external_primary") {
                        Environment.getExternalStorageDirectory()
                    } else File("/storage/${item.originalVolumeName}")
                    File(volumeRoot, relativePath + item.originalName).absolutePath
                }
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


    private fun findExistingIdenticalMediaStoreFile(
        collection: Uri,
        volumeName: String,
        displayName: String,
        relativePath: String,
        expectedFile: File,
    ): String? = runCatching {
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(displayName, relativePath),
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
            val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
            while (cursor.moveToNext()) {
                if (sizeCol >= 0 && cursor.getLong(sizeCol) != expectedFile.length()) continue
                val rowId = cursor.getLong(idCol)
                val rowUri = ContentUris.withAppendedId(collection, rowId)
                if (TrashFiles.streamMatchesFile(context.contentResolver.openInputStream(rowUri), expectedFile)) {
                    val dataPath = if (dataCol >= 0) cursor.getString(dataCol).orEmpty() else ""
                    return@use dataPath.ifBlank {
                        val root = if (volumeName == "external_primary") Environment.getExternalStorageDirectory() else File("/storage/$volumeName")
                        File(root, relativePath + displayName).absolutePath
                    }
                }
            }
            null
        }
    }.getOrNull()

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
            val normalized = originalPath.replace('\\', '/')
            val rel = when {
                normalized.startsWith("/storage/emulated/0/") -> normalized.removePrefix("/storage/emulated/0/").substringBeforeLast('/', "")
                normalized.startsWith("/storage/") -> normalized.removePrefix("/storage/").substringAfter('/', "").substringBeforeLast('/', "")
                else -> ""
            }.trim('/')
            if (rel.isNotBlank()) {
                val root = rel.substringBefore('/')
                val allowed = if (isVideo) {
                    root.equals(Environment.DIRECTORY_DCIM, ignoreCase = true) || root.equals(Environment.DIRECTORY_MOVIES, ignoreCase = true)
                } else {
                    root.equals(Environment.DIRECTORY_DCIM, ignoreCase = true) || root.equals(Environment.DIRECTORY_PICTURES, ignoreCase = true)
                }
                if (allowed) return "$rel/"
            }
        }
        return "$defaultDir/Iris/"
    }


    private fun expectedSourceSize(item: MediaImage): Long {
        if (item.sizeBytes > 0L) return item.sizeBytes
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(item.uri, "r")?.use { it.length }
        }.getOrNull()?.takeIf { it > 0L } ?: -1L
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
                        originalVolumeName = obj.optString("originalVolumeName").ifBlank { volumeNameFromOriginalPath(obj.optString("originalPath")) },
                        originalMediaId = obj.optLong("originalMediaId"),
                    )
                )
            }
            list
        } catch (e: java.io.FileNotFoundException) {
            emptyList()
        } catch (e: Exception) {
            // Never pretend a corrupt index is empty: callers must not overwrite it and orphan the backups.
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
                    put("originalVolumeName", item.originalVolumeName)
                    put("originalMediaId", item.originalMediaId)
                }
                array.put(obj)
            }
            stream = indexFile.startWrite()
            stream.write(array.toString(2).toByteArray(Charsets.UTF_8))
            indexFile.finishWrite(stream)
            true
        } catch (e: Exception) {
            if (stream != null) indexFile.failWrite(stream)
            false
        }
    }
}
