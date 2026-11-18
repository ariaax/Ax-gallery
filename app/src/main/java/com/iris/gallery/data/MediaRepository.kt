package com.iris.gallery.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.os.Bundle
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

// Ax Gallery: reading EXIF for every photo while listing is very slow on FUSE storage.
private const val ENABLE_EXIF_ON_LOAD = false
// Ax Gallery: per-file exists()/lastModified()/length() on every load is very slow; trust MediaStore.
private const val ENABLE_DISK_VERIFY = false

private fun volumeNameFromPath(path: String): String = when {
    path.startsWith("/storage/emulated/0/") -> "external_primary"
    path.startsWith("/storage/") -> path.removePrefix("/storage/").substringBefore('/').takeIf { it.isNotBlank() } ?: "external_primary"
    else -> "external_primary"
}

class MediaRepository(private val context: Context) {
    private val snapshot = AtomicFile(File(context.filesDir, "media_snapshot.bin"))
    private val snapshotLock = Any()
    private val libraryPreferences = LibraryPreferences(context)

    companion object {
        private const val VERIFIED_PATH_TTL_MS = 60 * 1_000L // 1 minute
        private const val TOMBSTONE_TTL_MS = 24 * 60 * 60 * 1_000L // 24 hours
    }

    private val inMemoryCache = java.util.concurrent.ConcurrentHashMap<String, MediaImage>()
    private val recentMovedOrDeletedKeys = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val recentMovedOrDeletedPaths = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val verifiedPathsCache = java.util.concurrent.ConcurrentHashMap<String, Long>()
    // Stat-ing every cached row costs seconds on a big library (it kept the spinner up for 10-20 s after install),
    // so cached rows are only re-checked once, right after something was restored or deleted.
    @Volatile private var verifyCachedRows = false

    fun loadSnapshot(): List<MediaImage> = synchronized(snapshotLock) { runCatching {
        DataInputStream(snapshot.openRead().buffered()).use { input ->
            val version = input.readInt()
            if (version != 5) return@use emptyList() // v3/v4 snapshots hold dates from the old resolver; rebuild once
            val list = List(input.readInt().coerceIn(0, 100_000)) {
                val id = input.readLong(); val isVideo = input.readBoolean()
                val savedVolume = if (version >= 4) input.readUTF() else ""
                val pathV4 = if (version >= 4) input.readUTF() else null
                val name = input.readUTF(); val dateTaken = input.readLong()
                val width = input.readInt(); val height = input.readInt()
                val savedPath = pathV4 ?: input.readUTF()
                val volumeName = savedVolume.ifBlank { volumeNameFromPath(savedPath) }
                val volume = if (android.os.Build.VERSION.SDK_INT >= 29) volumeName else "external"
                val collection = if (isVideo) MediaStore.Video.Media.getContentUri(volume) else MediaStore.Images.Media.getContentUri(volume)
                val bucketId = input.readLong(); val bucketName = input.readUTF()
                val durationMs = input.readLong(); val mimeType = input.readUTF(); val sizeBytes = input.readLong()
                val orientation = input.readInt(); val savedTitle = input.readUTF()
                val dateModified = input.readLong(); val dateAdded = input.readLong()
                val title = libraryPreferences.getCustomTitle(id) ?: savedTitle
                MediaImage(id, ContentUris.withAppendedId(collection, id), name, dateTaken,
                    width, height, savedPath, bucketId, bucketName,
                    isVideo, durationMs, mimeType, sizeBytes, orientation, title,
                    dateModified = dateModified, dateAdded = dateAdded, volumeName = volumeName)
            }
            list.forEach { item ->
                inMemoryCache[item.stableKey] = item
            }
            list
        }
    }.getOrDefault(emptyList()) }

    fun markMovedOrDeleted(items: Collection<MediaImage>) {
        val now = System.currentTimeMillis()
        items.forEach { item ->
            recentMovedOrDeletedKeys[item.stableKey] = now
            inMemoryCache.remove(item.stableKey)
            if (item.path.isNotBlank()) {
                recentMovedOrDeletedPaths[item.path] = now
                verifiedPathsCache.remove(item.path)
            }
        }
    }

    fun isMarkedMovedOrDeleted(stableKey: String, path: String): Boolean {
        val now = System.currentTimeMillis()
        val keyTime = recentMovedOrDeletedKeys[stableKey]
        if (keyTime != null && now - keyTime < TOMBSTONE_TTL_MS) return true
        val pathTime = if (path.isNotBlank()) recentMovedOrDeletedPaths[path] else null
        return pathTime != null && now - pathTime < TOMBSTONE_TTL_MS
    }

    fun clearRecentMovedOrDeleted(keys: Set<String>, paths: Set<String> = emptySet()) {
        keys.forEach { recentMovedOrDeletedKeys.remove(it) }
        paths.forEach { recentMovedOrDeletedPaths.remove(it) }
    }

    fun clearRecentMovedOrDeletedPaths(paths: Set<String>) {
        paths.forEach { recentMovedOrDeletedPaths.remove(it) }
    }

    fun clearVerifiedPathsCache() {
        verifiedPathsCache.clear()
        verifyCachedRows = true
    }

    fun purgeGhostMedia(image: MediaImage) {
        val now = System.currentTimeMillis()
        recentMovedOrDeletedKeys[image.stableKey] = now
        inMemoryCache.remove(image.stableKey)
        if (image.path.isNotBlank()) {
            recentMovedOrDeletedPaths[image.path] = now
            verifiedPathsCache.remove(image.path)
            android.media.MediaScannerConnection.scanFile(context, arrayOf(image.path), null, null)
        }
        runCatching { context.contentResolver.delete(image.uri.buildUpon().clearQuery().build(), null, null) }
    }

    suspend fun rescanStorage(): Int = withContext(Dispatchers.IO) {
        verifiedPathsCache.clear()
        inMemoryCache.clear()

        val storageRoots = mutableListOf<File>()
        runCatching {
            Environment.getExternalStorageDirectory()?.takeIf { it.exists() }?.let { storageRoots.add(it) }
        }
        runCatching {
            androidx.core.content.ContextCompat.getExternalFilesDirs(context, null).forEach { dir ->
                if (dir != null) {
                    val path = dir.absolutePath
                    val rootPath = path.substringBefore("/Android/data")
                    if (rootPath.isNotBlank() && rootPath != path) {
                        val rootFile = File(rootPath)
                        if (rootFile.exists() && rootFile !in storageRoots) {
                            storageRoots.add(rootFile)
                        }
                    }
                }
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val sm = context.getSystemService(Context.STORAGE_SERVICE) as? android.os.storage.StorageManager
                sm?.storageVolumes?.forEach { vol ->
                    val dir = vol.directory
                    if (dir != null && dir.exists() && dir !in storageRoots) {
                        storageRoots.add(dir)
                    }
                }
            }
        }

        val candidateFolders = mutableListOf<File>()
        val standardFolders = listOf("DCIM", "Pictures", "Movies", "Download", "Documents")
        for (root in storageRoots) {
            for (folder in standardFolders) {
                val f = File(root, folder)
                if (f.exists() && f.isDirectory && f !in candidateFolders) {
                    candidateFolders.add(f)
                }
            }
            root.listFiles()?.forEach { sub ->
                if (sub.isDirectory &&
                    !sub.name.startsWith(".") &&
                    !sub.name.equals("Android", ignoreCase = true) &&
                    !sub.name.equals("lost.dir", ignoreCase = true) &&
                    sub !in candidateFolders
                ) {
                    candidateFolders.add(sub)
                }
            }
        }

        val mediaExtensions = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif", "dng", "svg",
            "cr2", "nef", "arw", "rw2", "orf", "pef", "raf",
            "mp4", "mkv", "mov", "webm", "3gp", "avi", "flv", "ts", "m4v", "wmv"
        )

        val knownMap = HashMap<String, Pair<Long, Long>>()
        val projection = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE
        )
        val selection = "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?)"
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )

        val collectionsToQuery = if (android.os.Build.VERSION.SDK_INT >= 29) {
            val names = runCatching { MediaStore.getExternalVolumeNames(context) }.getOrNull()?.filter { it.isNotBlank() }
            if (!names.isNullOrEmpty()) {
                names.map { MediaStore.Files.getContentUri(it) }
            } else {
                listOf(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL))
            }
        } else {
            listOf(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL))
        }

        for (collection in collectionsToQuery) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            runCatching {
                context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                    val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    val modCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                    val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    if (dataCol >= 0 && modCol >= 0 && sizeCol >= 0) {
                        var scannedRows = 0
                        while (cursor.moveToNext()) {
                            if (scannedRows++ % 128 == 0) kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            val path = cursor.getString(dataCol)
                            if (!path.isNullOrBlank()) {
                                val mod = cursor.getLong(modCol)
                                val sz = cursor.getLong(sizeCol)
                                knownMap[path] = Pair(mod, sz)
                            }
                        }
                    }
                }
            }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        }

        val pathsToScan = mutableListOf<String>()
        val ghostPathsToPurge = mutableListOf<String>()

        for (folder in candidateFolders) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            try {
                var visited = 0
                folder.walkTopDown()
                    .maxDepth(12)
                    .onEnter { dir ->
                        !dir.name.startsWith(".") &&
                        !dir.name.equals("Android", ignoreCase = true) &&
                        !File(dir, ".nomedia").exists()
                    }
                    .forEach { file ->
                        if (visited++ % 256 == 0) kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        if (file.isFile && !file.name.startsWith(".")) {
                            val ext = file.extension.lowercase(java.util.Locale.ROOT)
                            if (ext in mediaExtensions) {
                                val path = file.absolutePath
                                val known = knownMap[path]
                                if (known == null) pathsToScan.add(path)
                                else {
                                    val (msModSec, msSize) = known
                                    val diskModSec = file.lastModified() / 1000L
                                    val diskSize = file.length()
                                    if (Math.abs(diskModSec - msModSec) > 1L || (diskSize > 0L && diskSize != msSize)) pathsToScan.add(path)
                                }
                            }
                        }
                    }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A denied or disappearing directory should not abort scanning other volumes.
            }
        }

        var checkedKnown = 0
        for ((knownPath, _) in knownMap) {
            if (checkedKnown++ % 256 == 0) kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (!File(knownPath).exists()) {
                ghostPathsToPurge.add(knownPath)
            }
        }

        val allPaths = pathsToScan + ghostPathsToPurge
        if (allPaths.isEmpty()) {
            return@withContext 0
        }

        var scannedCount = 0
        for (chunk in allPaths.chunked(200)) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val latch = kotlinx.coroutines.CompletableDeferred<Unit>()
            val counter = java.util.concurrent.atomic.AtomicInteger(0)
            android.media.MediaScannerConnection.scanFile(
                context,
                chunk.toTypedArray(),
                null
            ) { _, _ ->
                if (counter.incrementAndGet() >= chunk.size) {
                    latch.complete(Unit)
                }
            }
            kotlinx.coroutines.withTimeoutOrNull(8_000) { latch.await() }
            scannedCount += counter.get()
        }

        verifiedPathsCache.clear()
        inMemoryCache.clear()

        scannedCount
    }

    suspend fun loadImages(trashed: Boolean = false): List<MediaImage> = withContext(Dispatchers.IO) {
        ExifDateStore.ensureLoaded(context)
        TimelineSort.refreshNow()
        val now = System.currentTimeMillis()
        recentMovedOrDeletedKeys.entries.removeIf { now - it.value > TOMBSTONE_TTL_MS }
        recentMovedOrDeletedPaths.entries.removeIf { now - it.value > TOMBSTONE_TTL_MS }

        val collectionsToQuery = if (android.os.Build.VERSION.SDK_INT >= 29) {
            val names = runCatching { MediaStore.getExternalVolumeNames(context) }.getOrNull()?.filter { it.isNotBlank() }
            if (!names.isNullOrEmpty()) {
                names.map { MediaStore.Files.getContentUri(it) }
            } else {
                listOf(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL))
            }
        } else {
            listOf(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL))
        }

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Video.VideoColumns.DURATION,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.Images.Media.ORIENTATION,
            MediaStore.MediaColumns.TITLE,
            MediaStore.MediaColumns.DATA,
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                MediaStore.Images.Media.RELATIVE_PATH
            } else {
                MediaStore.Images.Media.DATA
            },
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                MediaStore.MediaColumns.VOLUME_NAME
            } else {
                MediaStore.Images.Media.DATA
            },
        )

        val result = buildList {
            val mediaSelection = buildString {
                append("(${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?)")
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    if (!trashed) {
                        append(" AND ${MediaStore.MediaColumns.IS_TRASHED} = 0 AND ${MediaStore.MediaColumns.IS_PENDING} = 0")
                    }
                } else if (android.os.Build.VERSION.SDK_INT >= 29) {
                    append(" AND ${MediaStore.MediaColumns.IS_PENDING} = 0")
                }
            }
            val selectionArgs = buildList {
                add(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString())
                add(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString())
            }.toTypedArray()
            val order = "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media.DATE_ADDED} DESC"

            for (collection in collectionsToQuery) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val cursorResult = if (android.os.Build.VERSION.SDK_INT >= 30) {
                    context.contentResolver.query(collection, projection, Bundle().apply {
                        putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, mediaSelection)
                        putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
                        putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, order)
                        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED,
                            if (trashed) MediaStore.MATCH_ONLY else MediaStore.MATCH_EXCLUDE)
                    }, null)
                } else {
                    context.contentResolver.query(collection, projection, mediaSelection, selectionArgs, order)
                }
                cursorResult?.use { cursor ->
                    val id = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val name = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val taken = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                    val added = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                    val modified = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    val width = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                    val height = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                    val bucketId = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
                    val bucketName = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                    val mediaType = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                    val duration = cursor.getColumnIndexOrThrow(MediaStore.Video.VideoColumns.DURATION)
                    val mimeType = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                    val size = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val orientation = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.ORIENTATION)
                    val title = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.TITLE)
                    val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    val relPathCol = if (android.os.Build.VERSION.SDK_INT >= 29) cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH) else -1
                    val volCol = if (android.os.Build.VERSION.SDK_INT >= 29) cursor.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME) else -1

                    var scannedRows = 0
                    while (cursor.moveToNext()) {
                        if (scannedRows++ % 128 == 0) kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val mediaId = cursor.getLong(id)
                        val isVid = cursor.getInt(mediaType) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                        val displayName = cursor.getString(name).orEmpty()
                        val rawData = if (dataCol >= 0) cursor.getString(dataCol).orEmpty() else ""
                        val relPath = if (relPathCol >= 0) cursor.getString(relPathCol).orEmpty() else ""
                        val volName = if (volCol >= 0) cursor.getString(volCol).orEmpty() else ""
                        val identityKey = MediaIdentity.key(volName, rawData, isVid, mediaId)

                        val filePath = when {
                            rawData.isNotBlank() -> rawData
                            relPath.isNotBlank() && volName.isNotBlank() && volName != "external_primary" -> "/storage/$volName/$relPath$displayName"
                            relPath.isNotBlank() -> "/storage/emulated/0/$relPath$displayName"
                            else -> ""
                        }
                        if (!trashed && isMarkedMovedOrDeleted(identityKey, filePath)) continue

                        val volumeForUri = if (volName.isNotBlank()) volName else "external"
                        val baseMediaUri = if (isVid) {
                            ContentUris.withAppendedId(MediaStore.Video.Media.getContentUri(volumeForUri), mediaId)
                        } else {
                            ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(volumeForUri), mediaId)
                        }
                        val mediaUri = if (trashed && android.os.Build.VERSION.SDK_INT >= 30) {
                            baseMediaUri.buildUpon().appendQueryParameter("include_trashed", "1").build()
                        } else {
                            baseMediaUri
                        }

                        val cursorDateTaken = cursor.getLong(taken)
                        val cursorDateModified = cursor.getLong(modified) * 1_000L
                        // a restored-from-trash file keeps the date it was originally added (see RestoredDates)
                        val cursorDateAdded = RestoredDates.dateAddedFor(context, filePath) ?: (cursor.getLong(added) * 1_000L)
                        val itemWidth = cursor.getInt(width)
                        val itemHeight = cursor.getInt(height)
                        val itemDuration = cursor.getLong(duration)
                        val itemSize = cursor.getLong(size)
                        val itemOrientation = cursor.getInt(orientation)
                        val itemTitle = libraryPreferences.getCustomTitle(mediaId) ?: cursor.getString(title).orEmpty()

                        // Ghost rows (file already gone, MediaStore row not cleaned yet) must be dropped BEFORE the
                        // cache shortcut below, otherwise a cached ghost is shown again on every reload.
                        if (verifyCachedRows && !trashed && filePath.startsWith("/storage/emulated/")) {
                            val seenAt = verifiedPathsCache[filePath]
                            if (seenAt == null || now - seenAt > 300_000L) {
                                if (!File(filePath).exists()) {
                                    inMemoryCache.remove(identityKey)
                                    continue
                                }
                                verifiedPathsCache[filePath] = now
                            }
                        }

                        val existing = inMemoryCache[identityKey]
                        if (!trashed && existing != null &&
                            existing.name == displayName &&
                            existing.path == filePath &&
                            existing.dateModified == cursorDateModified &&
                            existing.sizeBytes == itemSize &&
                            existing.orientation == itemOrientation &&
                            existing.width == itemWidth &&
                            existing.height == itemHeight &&
                            existing.title == itemTitle &&
                            existing.isVideo == isVid
                        ) {
                            add(existing)
                            continue
                        }

                        var diskModifiedMs = 0L
                        var diskSizeBytes = -1L
                        if (ENABLE_DISK_VERIFY && !trashed && filePath.isNotBlank()) {
                            val file = File(filePath)
                            var exists = file.exists()
                            if (exists) {
                                diskModifiedMs = file.lastModified()
                                diskSizeBytes = file.length()
                            } else if (volName.isNotBlank() && volName != "external_primary") {
                                exists = runCatching {
                                    context.contentResolver.openAssetFileDescriptor(mediaUri, "r")?.use { true } ?: false
                                }.getOrDefault(false)
                            }
                            if (!exists) {
                                inMemoryCache.remove(identityKey)
                                recentMovedOrDeletedPaths[filePath] = now
                                android.media.MediaScannerConnection.scanFile(context, arrayOf(filePath), null, null)
                                runCatching { context.contentResolver.delete(mediaUri, null, null) }
                                if (android.os.Build.VERSION.SDK_INT >= 29 && volName.isNotBlank() && volName != "external") {
                                    runCatching {
                                        context.contentResolver.delete(
                                            MediaStore.Files.getContentUri(volName),
                                            "${MediaStore.MediaColumns._ID}=?",
                                            arrayOf(mediaId.toString())
                                        )
                                    }
                                }
                                continue
                            }
                        }

                        // Non-destructive ghost filter: hide rows whose file is gone from primary storage
                        // (never deletes anything from MediaStore).
                        if (!trashed && filePath.startsWith("/storage/emulated/") && !File(filePath).exists()) {
                            continue
                        }

                        var effectiveModified = cursorDateModified
                        var effectiveSize = itemSize
                        if (diskModifiedMs > 0L) {
                            val diskModSec = diskModifiedMs / 1000L
                            val cursorModSec = cursor.getLong(modified)
                            if (Math.abs(diskModSec - cursorModSec) > 1L || (diskSizeBytes >= 0L && diskSizeBytes != itemSize)) {
                                effectiveModified = diskModifiedMs
                                if (diskSizeBytes >= 0L) effectiveSize = diskSizeBytes
                            }
                        }

                        // File-level date: remembered background read (photos AND videos); never touches the file here.
                        var fileMetadataMs: Long? = if (trashed) null else ExifDateStore.get(identityKey)
                        if (fileMetadataMs == null && ENABLE_EXIF_ON_LOAD && !isVid) {
                            fileMetadataMs = extractExifDateTaken(context, mediaUri, filePath)?.takeIf { it > 0L }
                        }
                        val takenTime = MediaDates.resolve(
                            rawTaken = cursorDateTaken,
                            addedMs = cursorDateAdded,
                            modifiedMs = effectiveModified,
                            fileMetadataMs = fileMetadataMs,
                            fileName = displayName,
                            nowMs = now,
                        )

                        val itemBucketId = if (trashed) -2L else cursor.getLong(bucketId)
                        val itemBucketName = if (trashed) "Trash" else cursor.getString(bucketName).orEmpty().ifBlank { "Other" }

                        val newImage = MediaImage(
                            id = mediaId,
                            uri = mediaUri,
                            name = displayName,
                            dateTaken = takenTime,
                            width = itemWidth,
                            height = itemHeight,
                            path = filePath,
                            bucketId = itemBucketId,
                            bucketName = itemBucketName,
                            isVideo = isVid,
                            durationMs = itemDuration,
                            mimeType = cursor.getString(mimeType).orEmpty(),
                            sizeBytes = effectiveSize,
                            orientation = itemOrientation,
                            title = itemTitle,
                            dateModified = effectiveModified,
                            dateAdded = cursorDateAdded,
                            volumeName = volName.ifBlank { volumeNameFromPath(filePath) },
                        )
                        if (!trashed) {
                            inMemoryCache[newImage.stableKey] = newImage
                        }
                        add(newImage)
                    }
                }
            }
        }
        val sorted = result.distinctBy { it.stableKey }.sortedWith(
            compareByDescending<MediaImage> { it.sortDate }
                .thenByDescending { it.dateModified }
                .thenByDescending { it.dateAdded }
                .thenByDescending { it.id }
        )
        if (!trashed) {
            verifyCachedRows = false
            val validKeys = sorted.mapTo(HashSet(sorted.size)) { it.stableKey }
            inMemoryCache.keys.retainAll(validKeys)
            saveSnapshot(sorted)
        }
        sorted
    }

    private fun looksSyntheticDate(img: MediaImage): Boolean {
        val t = img.dateTaken / 1000L
        return t <= 0L ||
            (img.dateAdded > 0L && t == img.dateAdded / 1000L) ||
            (img.dateModified > 0L && t == img.dateModified / 1000L)
    }

    /** Files that could not be opened this session; retried next launch, not on every refresh. */
    private val unreadableThisSession = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Reads the real capture date (EXIF for photos, container creation time for videos) of items whose
     * MediaStore date looks fake (equal to the added/modified time), one file at a time in the background,
     * and remembers it. A file that could not be opened is NOT remembered as "has no date": that mistake
     * (common on SD cards) used to freeze the wrong date forever. Returns how many items got a new date.
     */
    suspend fun enrichExifDates(images: List<MediaImage>): Int = withContext(Dispatchers.IO) {
        ExifDateStore.ensureLoaded(context)
        if (!ExifDateStore.enabled) return@withContext 0
        val candidates = images.filter {
            it.id > 0L && (it.path.isNotBlank() || it.uri.scheme == "content") &&
                !ExifDateStore.isChecked(it.stableKey) && it.stableKey !in unreadableThisSession &&
                looksSyntheticDate(it)
        }
        if (candidates.isEmpty()) return@withContext 0
        val updates = HashMap<String, Long>()
        for ((index, img) in candidates.withIndex()) {
            val probe = runCatching {
                if (img.isVideo) probeVideoDate(context, img.uri, img.path) else probePhotoDate(context, img.uri, img.path)
            }.getOrDefault(DateProbe.Unreadable)
            when (probe) {
                is DateProbe.Found -> {
                    ExifDateStore.put(img.stableKey, probe.ms)
                    if (Math.abs(probe.ms - img.dateTaken) > 1_000L) updates[img.stableKey] = probe.ms
                }
                DateProbe.Absent -> ExifDateStore.put(img.stableKey, 0L)
                DateProbe.Unreadable -> unreadableThisSession.add(img.stableKey)
            }
            if (index % 100 == 99) ExifDateStore.save()
            kotlinx.coroutines.yield()
        }
        ExifDateStore.save()
        if (updates.isNotEmpty()) applyExifDates(updates)
        updates.size
    }

    fun applyExifDates(updates: Map<String, Long>) {
        for ((stableKey, millis) in updates) {
            inMemoryCache.computeIfPresent(stableKey) { _, old -> old.copy(dateTaken = millis) }
        }
    }

    suspend fun renameMedia(item: MediaImage, newName: String): MediaImage? = withContext(Dispatchers.IO) {
        val trimmedName = newName.trim()
        if (trimmedName.isBlank()) return@withContext null

        val currentExt = item.name.substringAfterLast('.', "")
        val finalName = if (trimmedName.contains('.') || currentExt.isEmpty()) trimmedName else "$trimmedName.$currentExt"
        if (finalName == item.name) return@withContext item

        val srcFile = if (item.path.isNotBlank()) File(item.path) else null
        var renamedPath = item.path

        val canonicalUri = if (item.uri.authority == "media") {
            item.uri.buildUpon().clearQuery().build()
        } else {
            val volume = if (Build.VERSION.SDK_INT >= 29) item.volumeName.ifBlank { volumeNameFromPath(item.path) } else "external"
            val collection = if (item.isVideo) {
                if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(volume) else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.getContentUri(volume) else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            ContentUris.withAppendedId(collection, item.id)
        }

        var updatedInMediaStore = false
        if (item.id > 0) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
            }
            updatedInMediaStore = runCatching {
                context.contentResolver.update(canonicalUri, values, null, null) > 0
            }.getOrDefault(false)
        }

        if (updatedInMediaStore) {
            val (actualName, actualPath) = runCatching {
                context.contentResolver.query(
                    canonicalUri,
                    arrayOf(
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.DATA,
                        if (android.os.Build.VERSION.SDK_INT >= 29) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA
                    ),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                        val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                        val relCol = if (android.os.Build.VERSION.SDK_INT >= 29) cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) else -1
                        val resolvedName = if (nameCol >= 0) cursor.getString(nameCol) else null
                        val rawData = if (dataCol >= 0) cursor.getString(dataCol) else null
                        val relPath = if (relCol >= 0) cursor.getString(relCol) else null
                        val resolvedPath = when {
                            !rawData.isNullOrBlank() -> rawData
                            !relPath.isNullOrBlank() && !resolvedName.isNullOrBlank() -> {
                                val root = if (item.volumeName == "external_primary") "/storage/emulated/0/" else "/storage/${item.volumeName}/"
                                root + relPath + resolvedName
                            }
                            else -> null
                        }
                        Pair(resolvedName, resolvedPath)
                    } else Pair(null, null)
                }
            }.getOrNull() ?: Pair(null, null)

            val resolvedFinalName = actualName ?: finalName
            renamedPath = actualPath ?: if (srcFile != null && srcFile.parentFile != null) {
                File(srcFile.parentFile, resolvedFinalName).absolutePath
            } else {
                item.path
            }

            verifiedPathsCache.remove(item.path)
            verifiedPathsCache[renamedPath] = System.currentTimeMillis()
            android.media.MediaScannerConnection.scanFile(context, arrayOf(renamedPath), null, null)

            val updatedItem = item.copy(name = resolvedFinalName, path = renamedPath)
            inMemoryCache[updatedItem.stableKey] = updatedItem
            return@withContext updatedItem
        }

        // Direct filesystem rename fallback (for Android <= 28 or full storage access)
        if (srcFile != null && srcFile.exists() && srcFile.parentFile != null) {
            val parent = srcFile.parentFile!!
            var destFile = File(parent, finalName)
            if (destFile.exists() && destFile.absolutePath != srcFile.absolutePath) {
                val base = finalName.substringBeforeLast('.')
                val ext = if (finalName.contains('.')) ".${finalName.substringAfterLast('.')}" else ""
                var index = 1
                while (destFile.exists()) {
                    destFile = File(parent, "$base ($index)$ext")
                    index++
                }
            }
            val lastModified = srcFile.lastModified()
            val renamed = runCatching { srcFile.renameTo(destFile) }.getOrDefault(false)
            if (renamed) {
                if (lastModified > 0) destFile.setLastModified(lastModified)
                renamedPath = destFile.absolutePath
                val resolvedFinalName = destFile.name
                verifiedPathsCache.remove(item.path)
                verifiedPathsCache[renamedPath] = System.currentTimeMillis()

                if (item.id > 0) {
                    val values = android.content.ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, resolvedFinalName)
                        if (android.os.Build.VERSION.SDK_INT <= 28) {
                            put(MediaStore.MediaColumns.DATA, destFile.absolutePath)
                        }
                    }
                    runCatching { context.contentResolver.update(canonicalUri, values, null, null) }
                }
                android.media.MediaScannerConnection.scanFile(
                    context,
                    arrayOf(destFile.absolutePath, srcFile.absolutePath),
                    null,
                    null
                )
                val updatedItem = item.copy(name = resolvedFinalName, path = renamedPath)
                inMemoryCache[updatedItem.stableKey] = updatedItem
                return@withContext updatedItem
            }
        }

        null
    }

    private fun saveSnapshot(media: List<MediaImage>) = synchronized(snapshotLock) {
        var stream: java.io.FileOutputStream? = null
        runCatching {
            stream = snapshot.startWrite()
            val output = DataOutputStream(stream!!.buffered())
            output.writeInt(5); output.writeInt(media.size)
            media.forEach { item ->
                output.writeLong(item.id); output.writeBoolean(item.isVideo)
                output.writeUTF(item.volumeName.ifBlank { volumeNameFromPath(item.path) }.take(256))
                output.writeUTF(item.path.take(16_000)); output.writeUTF(item.name.take(8_000))
                output.writeLong(item.dateTaken); output.writeInt(item.width); output.writeInt(item.height)
                output.writeLong(item.bucketId); output.writeUTF(item.bucketName.take(8_000))
                output.writeLong(item.durationMs); output.writeUTF(item.mimeType.take(1_000)); output.writeLong(item.sizeBytes)
                output.writeInt(item.orientation); output.writeUTF(item.title.take(8_000))
                output.writeLong(item.dateModified); output.writeLong(item.dateAdded)
            }
            output.flush()
            snapshot.finishWrite(stream)
        }.onFailure { stream?.let { snapshot.failWrite(it) } }
    }
}
