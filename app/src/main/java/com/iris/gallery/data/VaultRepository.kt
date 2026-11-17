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

data class VaultItem(
    val id: Long,
    val vaultFileName: String,
    val originalName: String,
    val originalPath: String,
    val originalBucketName: String,
    val originalBucketId: Long,
    val dateTaken: Long,
    val dateLocked: Long,
    val width: Int,
    val height: Int,
    val isVideo: Boolean,
    val durationMs: Long,
    val mimeType: String = "",
    val sizeBytes: Long = 0,
    val orientation: Int = 0,
    val title: String = "",
    val description: String = "",
    val originalVolumeName: String = "external_primary",
    val dateModified: Long = 0L,
    val dateAdded: Long = 0L,
    val originalMediaId: Long = 0L,
)

data class VaultMoveResult(
    val vaultedMedia: List<MediaImage>,
    val originalMedia: List<MediaImage>,
    val silentSuccess: Boolean = false,
)

private fun volumeNameFromOriginalPath(path: String): String = when {
    path.startsWith("/storage/emulated/0/") -> "external_primary"
    path.startsWith("/storage/") -> path.removePrefix("/storage/").substringBefore('/').takeIf { it.isNotBlank() } ?: "external_primary"
    else -> "external_primary"
}

class VaultRepository(private val context: Context) {
    private val vaultDir: File = File(context.filesDir, "vault").apply {
        if (!exists()) {
            mkdirs()
        }
        val nomedia = File(this, ".nomedia")
        if (!nomedia.exists()) {
            runCatching { nomedia.createNewFile() }
        }
    }

    private val indexFile = AtomicFile(File(context.filesDir, "vault_index.json"))
    private val _vaultMedia = MutableStateFlow<List<MediaImage>>(emptyList())
    val vaultMedia: StateFlow<List<MediaImage>> = _vaultMedia.asStateFlow()

    // Loaded off the main thread: the index read and per-item file checks used to block app start.
    // Until it finishes the list is simply empty.
    private val initScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    private val indexLock = Any()

    init {
        initScope.launch { VaultFiles.cleanPartials(vaultDir); loadVaultItems() }
    }

    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    fun loadVaultItems(): List<MediaImage> = synchronized(indexLock) {
        val items = runCatching { readIndex() }.getOrNull() ?: return@synchronized emptyList<MediaImage>()
        val mediaList = items.mapNotNull { item ->
            val file = File(vaultDir, item.vaultFileName)
            if (file.exists()) {
                val uniqueId = if (item.id > 0) -item.id else item.id
                MediaImage(
                    id = uniqueId,
                    uri = Uri.fromFile(file),
                    name = item.originalName,
                    dateTaken = item.dateTaken,
                    dateModified = item.dateModified,
                    dateAdded = item.dateAdded,
                    width = item.width,
                    height = item.height,
                    path = file.absolutePath,
                    bucketId = if (item.originalBucketId != 0L) item.originalBucketId else -1L,
                    bucketName = item.originalBucketName.ifBlank { "Locked" },
                    isVideo = item.isVideo,
                    durationMs = item.durationMs,
                    mimeType = item.mimeType,
                    sizeBytes = if (item.sizeBytes > 0) item.sizeBytes else file.length(),
                    orientation = item.orientation,
                    title = item.title,
                    description = item.description,
                    volumeName = item.originalVolumeName,
                )
            } else {
                null
            }
        }
        _vaultMedia.value = mediaList
        return mediaList
    }

    /**
     * Order matters for data safety: 1) verified copy into the vault, 2) index persisted, 3) only then the originals are
     * removed. If the index cannot be written nothing is deleted and the new vault copies are discarded.
     * When only some originals could be removed, the result lists just the ones still on disk, so a later rollback
     * can never delete the vault copy of a file whose original is already gone.
     */
    suspend fun moveToVault(mediaList: List<MediaImage>): VaultMoveResult = withContext(Dispatchers.IO) { synchronized(indexLock) {
        val empty = VaultMoveResult(emptyList(), emptyList())
        val currentItems = runCatching { readIndex() }.getOrNull()?.toMutableList() ?: return@synchronized empty
        val added = mutableListOf<Triple<VaultItem, MediaImage, MediaImage>>() // item, vault media, original
        val hasManagerAccess = hasAllFilesAccess()

        for (item in mediaList.distinctBy { it.id to it.uri }) {
            val uniqueId = generateUniqueId(currentItems)
            val cleanName = sanitizeFileName(item.name.ifBlank { "media_${uniqueId}" })
            val vaultFileName = "${uniqueId}_${cleanName}"
            val targetFile = File(vaultDir, vaultFileName)

            val expected = File(item.path).takeIf { item.path.isNotBlank() && it.isFile }?.length()
                ?: item.sizeBytes.takeIf { it > 0L }
                ?: runCatching { context.contentResolver.openAssetFileDescriptor(item.uri, "r")?.use { it.length } }.getOrNull()?.takeIf { it > 0L }
                ?: -1L
            val copySuccess = runCatching {
                VaultFiles.copyStreamVerified(context.contentResolver.openInputStream(item.uri), targetFile, expected)
            }.getOrDefault(false)
            if (!copySuccess) continue

            val vaultItem = VaultItem(
                id = uniqueId,
                vaultFileName = vaultFileName,
                originalName = item.name,
                originalPath = item.path,
                originalBucketName = item.bucketName,
                originalBucketId = item.bucketId,
                dateTaken = item.dateTaken,
                dateLocked = System.currentTimeMillis(),
                width = item.width,
                height = item.height,
                isVideo = item.isVideo,
                durationMs = item.durationMs,
                mimeType = item.mimeType,
                sizeBytes = targetFile.length(),
                orientation = item.orientation,
                title = item.title,
                description = item.description,
                originalVolumeName = item.volumeName.ifBlank { "external_primary" },
                dateModified = item.dateModified,
                dateAdded = item.dateAdded,
                originalMediaId = item.id,
            )
            currentItems.add(vaultItem)
            val media = MediaImage(
                id = -uniqueId,
                uri = Uri.fromFile(targetFile),
                name = item.name,
                dateTaken = item.dateTaken,
                width = item.width,
                height = item.height,
                path = targetFile.absolutePath,
                bucketId = if (item.bucketId != 0L) item.bucketId else -1L,
                bucketName = item.bucketName.ifBlank { "Locked" },
                isVideo = item.isVideo,
                durationMs = item.durationMs,
                mimeType = item.mimeType,
                sizeBytes = targetFile.length(),
                orientation = item.orientation,
                title = item.title,
                description = item.description,
                volumeName = item.volumeName,
            )
            added.add(Triple(vaultItem, media, item))
        }
        if (added.isEmpty()) return@synchronized empty

        // the index must be on disk before any original is touched
        if (!writeIndex(currentItems)) {
            added.forEach { runCatching { File(vaultDir, it.first.vaultFileName).delete() } }
            return@synchronized empty
        }
        loadVaultItems()

        var silentSuccess = false
        var vaulted = added.map { it.second }
        var originals = added.map { it.third }
        if (hasManagerAccess) {
            val stillThere = added.filterNot { deleteFromStorage(it.third) }
            if (stillThere.isEmpty()) silentSuccess = true
            else {
                vaulted = stillThere.map { it.second }
                originals = stillThere.map { it.third }
            }
        }
        VaultMoveResult(vaultedMedia = vaulted, originalMedia = originals, silentSuccess = silentSuccess)
    } }

    fun deleteFromStorage(item: MediaImage): Boolean {
        val pathFile = item.path.takeIf { it.isNotBlank() }?.let(::File)
        var fileDeleted = false
        if (pathFile?.isFile == true) {
            fileDeleted = runCatching { pathFile.delete() }.getOrDefault(false)
        }
        // Use the exact row URI returned by the volume-specific query. Never fall back to an ID-only
        // delete against the synthetic merged "external" collection: IDs can collide across volumes.
        val rowDeleted = runCatching { context.contentResolver.delete(item.uri.buildUpon().clearQuery().build(), null, null) > 0 }
            .getOrDefault(false)
        val pathGone = pathFile == null || !pathFile.exists()
        return pathGone && (fileDeleted || rowDeleted || item.path.isBlank())
    }

    suspend fun rollbackVault(vaultedItems: List<MediaImage>) = withContext(Dispatchers.IO) { synchronized(indexLock) {
        val currentItems = runCatching { readIndex() }.getOrNull()?.toMutableList() ?: return@synchronized
        val idsToRemove = vaultedItems.map { abs(it.id) }.toSet()
        val removed = currentItems.filter { item ->
            item.id in idsToRemove && item.originalPath.isNotBlank() &&
                VaultFiles.looksIdentical(File(vaultDir, item.vaultFileName), File(item.originalPath))
        }
        currentItems.removeAll(removed.toSet())
        // drop the files only after the index no longer points at them
        if (writeIndex(currentItems)) {
            removed.forEach { runCatching { File(vaultDir, it.vaultFileName).delete() } }
        }
        loadVaultItems()
    } }

    /**
     * Each item is restored on its own: a verified copy at the original place (never overwriting a different file),
     * then the index is saved, and only then are the vault copies deleted. A failed item stays in the vault untouched.
     */
    suspend fun restoreFromVault(mediaList: List<MediaImage>): List<MediaImage> = withContext(Dispatchers.IO) { synchronized(indexLock) {
        val currentItems = runCatching { readIndex() }.getOrNull()?.toMutableList() ?: return@synchronized emptyList<MediaImage>()
        val restoredList = mutableListOf<MediaImage>()
        val filesToDelete = mutableListOf<File>()
        val idsToRemove = mediaList.map { abs(it.id) }.toSet()

        for (item in currentItems.filter { it.id in idsToRemove }) {
            val vaultFile = File(vaultDir, item.vaultFileName)
            if (!vaultFile.isFile) continue // missing file: keep the entry, never claim a restore

            if (!restoreFileToStorage(item, vaultFile)) continue
            filesToDelete.add(vaultFile)
            restoredList.add(
                MediaImage(
                    id = item.originalMediaId.takeIf { it > 0L } ?: -item.id,
                    uri = Uri.fromFile(vaultFile),
                    name = item.originalName,
                    dateTaken = item.dateTaken,
                    dateModified = item.dateModified,
                    dateAdded = item.dateAdded,
                    width = item.width,
                    height = item.height,
                    path = item.originalPath,
                    bucketId = item.originalBucketId,
                    bucketName = item.originalBucketName,
                    isVideo = item.isVideo,
                    durationMs = item.durationMs,
                    mimeType = item.mimeType,
                    sizeBytes = item.sizeBytes,
                    orientation = item.orientation,
                    title = item.title,
                    description = item.description,
                    volumeName = item.originalVolumeName,
                )
            )
            currentItems.remove(item)
        }

        if (restoredList.isNotEmpty()) {
            if (writeIndex(currentItems)) {
                RestoredDates.recordAll(context, restoredList.map { it.path to it.dateAdded })
                filesToDelete.forEach { runCatching { it.delete() } }
            }
            // if the index could not be saved the vault copies stay: a later restore reuses the identical file
        }
        loadVaultItems()
        restoredList
    } }

    suspend fun deletePermanently(mediaList: List<MediaImage>) = withContext(Dispatchers.IO) { synchronized(indexLock) {
        val currentItems = runCatching { readIndex() }.getOrNull()?.toMutableList() ?: return@synchronized
        val idsToRemove = mediaList.map { abs(it.id) }.toSet()
        val removed = currentItems.filter { it.id in idsToRemove }
        currentItems.removeAll(removed)
        if (writeIndex(currentItems)) {
            removed.forEach { runCatching { File(vaultDir, it.vaultFileName).delete() } }
        }
        loadVaultItems()
    } }

    private fun restoreFileToStorage(item: VaultItem, vaultFile: File): Boolean {
        return try {
            if (hasAllFilesAccess() || Build.VERSION.SDK_INT < 29) {
                @Suppress("DEPRECATION")
                val fallbackDir = Environment.getExternalStoragePublicDirectory(
                    if (item.isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                ).resolve("Iris")
                val wanted = if (item.originalPath.isNotBlank()) File(item.originalPath) else File(fallbackDir, item.originalName)
                (wanted.parentFile ?: fallbackDir).mkdirs()
                // an identical file already there counts as restored; a different one is never overwritten
                val destFile = if (VaultFiles.looksIdentical(vaultFile, wanted)) wanted else TrashFiles.uniqueDestination(wanted)
                if (destFile != wanted || !VaultFiles.looksIdentical(vaultFile, wanted)) {
                    if (!TrashFiles.copyVerified(vaultFile, destFile)) return false
                }
                val restoredModified = item.dateModified.takeIf { it > 0L } ?: item.dateTaken
                if (restoredModified > 0L) destFile.setLastModified(restoredModified)
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(destFile.absolutePath),
                    arrayOf(item.mimeType.ifBlank { null }),
                    null
                )
                true
            } else {
                val relativePath = computeRelativePath(item.originalPath, item.isVideo)
                val nowSec = System.currentTimeMillis() / 1000
                val dateSec = item.dateModified.takeIf { it > 0L }?.div(1000L)
                    ?: item.dateTaken.takeIf { it > 0L }?.div(1000L) ?: nowSec
                val addedSec = item.dateAdded.takeIf { it > 0L }?.div(1000L) ?: nowSec
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, item.originalName)
                    put(
                        MediaStore.MediaColumns.MIME_TYPE,
                        item.mimeType.ifBlank { if (item.isVideo) "video/mp4" else "image/jpeg" }
                    )
                    put(MediaStore.MediaColumns.DATE_ADDED, addedSec)
                    put(MediaStore.MediaColumns.DATE_MODIFIED, dateSec)
                    put(MediaStore.MediaColumns.DATE_TAKEN, item.dateTaken)
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
                // Reuse an identical row left by a previous restore whose index commit failed.
                if (findExistingIdenticalMediaStoreFile(collection, volume, item.originalName, relativePath, vaultFile)) return true
                val newUri = context.contentResolver.insert(collection, values) ?: return false
                val copied = try {
                    val out = context.contentResolver.openOutputStream(newUri)
                    VaultFiles.copyFileToStream(vaultFile, out)
                } catch (e: Exception) {
                    false
                }
                val contentVerified = copied && TrashFiles.streamMatchesFile(
                    context.contentResolver.openInputStream(newUri), vaultFile
                )
                if (!contentVerified) {
                    // a half-written or corrupt row must not stay behind, and the vault copy must not be deleted
                    runCatching { context.contentResolver.delete(newUri, null, null) }
                    return false
                }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                values.put(MediaStore.MediaColumns.DATE_MODIFIED, dateSec)
                val published = runCatching { context.contentResolver.update(newUri, values, null, null) > 0 }.getOrDefault(false)
                if (!published) {
                    runCatching { context.contentResolver.delete(newUri, null, null) }
                    return false
                }
                true
            }
        } catch (e: Exception) {
            false
        }
    }


    private fun findExistingIdenticalMediaStoreFile(
        collection: Uri,
        volumeName: String,
        displayName: String,
        relativePath: String,
        expectedFile: File,
    ): Boolean = runCatching {
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(displayName, relativePath),
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
            while (cursor.moveToNext()) {
                if (sizeCol >= 0 && cursor.getLong(sizeCol) != expectedFile.length()) continue
                val rowUri = ContentUris.withAppendedId(collection, cursor.getLong(idCol))
                if (TrashFiles.streamMatchesFile(context.contentResolver.openInputStream(rowUri), expectedFile)) return@use true
            }
            false
        } ?: false
    }.getOrDefault(false)

    private fun computeRelativePath(originalPath: String, isVideo: Boolean): String {
        val defaultDir = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val normalized = originalPath.replace('\\', '/')
        val relativeParent = when {
            normalized.startsWith("/storage/emulated/0/") -> normalized.removePrefix("/storage/emulated/0/").substringBeforeLast('/', "")
            normalized.startsWith("/storage/") -> normalized.removePrefix("/storage/").substringAfter('/', "").substringBeforeLast('/', "")
            else -> ""
        }.trim('/')
        if (relativeParent.isNotBlank()) {
            val root = relativeParent.substringBefore('/')
            val allowed = if (isVideo) {
                root.equals(Environment.DIRECTORY_DCIM, true) || root.equals(Environment.DIRECTORY_MOVIES, true)
            } else {
                root.equals(Environment.DIRECTORY_DCIM, true) || root.equals(Environment.DIRECTORY_PICTURES, true)
            }
            if (allowed) return "$relativeParent/"
        }
        return "$defaultDir/Iris/"
    }

    private fun generateUniqueId(existing: List<VaultItem>): Long {
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


    private fun readIndex(): List<VaultItem> {
        return try {
            // AtomicFile.openRead also recovers the backup left by an interrupted write
            val json = try {
                indexFile.openRead().bufferedReader().use { it.readText() }
            } catch (e: java.io.FileNotFoundException) {
                return emptyList()
            }
            val array = JSONArray(json)
            val list = mutableListOf<VaultItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    VaultItem(
                        id = obj.optLong("id"),
                        vaultFileName = obj.optString("vaultFileName"),
                        originalName = obj.optString("originalName"),
                        originalPath = obj.optString("originalPath"),
                        originalBucketName = obj.optString("originalBucketName"),
                        originalBucketId = obj.optLong("originalBucketId"),
                        dateTaken = obj.optLong("dateTaken"),
                        dateLocked = obj.optLong("dateLocked"),
                        width = obj.optInt("width"),
                        height = obj.optInt("height"),
                        isVideo = obj.optBoolean("isVideo"),
                        durationMs = obj.optLong("durationMs"),
                        mimeType = obj.optString("mimeType"),
                        sizeBytes = obj.optLong("sizeBytes"),
                        orientation = obj.optInt("orientation"),
                        title = obj.optString("title"),
                        description = obj.optString("description"),
                        originalVolumeName = obj.optString("originalVolumeName").ifBlank { volumeNameFromOriginalPath(obj.optString("originalPath")) },
                        dateModified = obj.optLong("dateModified"),
                        dateAdded = obj.optLong("dateAdded"),
                        originalMediaId = obj.optLong("originalMediaId"),
                    )
                )
            }
            list
        } catch (e: Exception) {
            // an unreadable index must not look empty: callers would write it back and lose the vault
            throw IllegalStateException("vault index unreadable", e)
        }
    }

    private fun writeIndex(items: List<VaultItem>): Boolean {
        var stream: FileOutputStream? = null
        try {
            val array = JSONArray()
            for (item in items) {
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("vaultFileName", item.vaultFileName)
                    put("originalName", item.originalName)
                    put("originalPath", item.originalPath)
                    put("originalBucketName", item.originalBucketName)
                    put("originalBucketId", item.originalBucketId)
                    put("dateTaken", item.dateTaken)
                    put("dateLocked", item.dateLocked)
                    put("width", item.width)
                    put("height", item.height)
                    put("isVideo", item.isVideo)
                    put("durationMs", item.durationMs)
                    put("mimeType", item.mimeType)
                    put("sizeBytes", item.sizeBytes)
                    put("orientation", item.orientation)
                    put("title", item.title)
                    put("description", item.description)
                    put("originalVolumeName", item.originalVolumeName)
                    put("dateModified", item.dateModified)
                    put("dateAdded", item.dateAdded)
                    put("originalMediaId", item.originalMediaId)
                }
                array.put(obj)
            }
            stream = indexFile.startWrite()
            stream.write(array.toString(2).toByteArray(Charsets.UTF_8))
            indexFile.finishWrite(stream)
            return true
        } catch (e: Exception) {
            if (stream != null) indexFile.failWrite(stream)
            return false
        }
    }
}

/** File-level helpers kept free of Android classes so they can be unit tested. */
internal object VaultFiles {
    /**
     * Copies [input] to [dst] through a temp file and only publishes it when the byte count is positive, matches
     * [expectedSize] (when known) and the file on disk has that length. Never overwrites an existing [dst]; a failed
     * copy leaves no partial files behind.
     */
    fun copyStreamVerified(input: java.io.InputStream?, dst: File, expectedSize: Long): Boolean {
        if (input == null || dst.exists()) { runCatching { input?.close() }; return false }
        val part = File(dst.parentFile, dst.name + ".part")
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val written = input.use { src ->
                FileOutputStream(part).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = src.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                        total += n
                    }
                    out.flush()
                    out.fd.sync()
                    total
                }
            }
            val destinationDigest = java.security.MessageDigest.getInstance("SHA-256")
            part.inputStream().use { inputStream ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val n = inputStream.read(buffer); if (n < 0) break; destinationDigest.update(buffer, 0, n) }
            }
            val ok = written > 0 && part.length() == written && (expectedSize <= 0L || written == expectedSize) &&
                java.security.MessageDigest.isEqual(digest.digest(), destinationDigest.digest())
            if (ok && part.renameTo(dst)) true else { part.delete(); false }
        } catch (e: Exception) {
            runCatching { part.delete() }
            false
        }
    }

    /** Copies [src] into [out] and checks the byte count; closes [out]. A null stream is a failure. */
    fun copyFileToStream(src: File, out: java.io.OutputStream?): Boolean {
        if (out == null || !src.isFile) { runCatching { out?.close() }; return false }
        return try {
            val n = src.inputStream().use { inp -> out.use { o ->
                val copied = inp.copyTo(o)
                o.flush()
                if (o is FileOutputStream) o.fd.sync()
                copied
            } }
            n == src.length() && n > 0
        } catch (e: Exception) {
            false
        }
    }

    fun looksIdentical(src: File, dst: File): Boolean =
        src.isFile && dst.isFile && src.length() > 0L && src.length() == dst.length() &&
            runCatching {
                fun hash(file: File): ByteArray {
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    file.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                    }
                    return digest.digest()
                }
                java.security.MessageDigest.isEqual(hash(src), hash(dst))
            }.getOrDefault(false)

    /** Removes leftover ".part" files from an interrupted transfer. */
    fun cleanPartials(dir: File): Int =
        dir.listFiles { f -> f.name.endsWith(".part") }?.count { it.delete() } ?: 0
}
