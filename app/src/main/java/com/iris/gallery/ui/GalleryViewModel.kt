package com.iris.gallery.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iris.gallery.data.MediaImage
import com.iris.gallery.data.sortDate
import com.iris.gallery.data.MediaRepository
import com.iris.gallery.data.LibraryPreferences
import com.iris.gallery.data.LibraryPreferencesState
import com.iris.gallery.data.AlbumSort
import com.iris.gallery.data.VaultRepository
import com.iris.gallery.data.TrashRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import com.iris.gallery.data.SettingsPreferences
import kotlinx.coroutines.flow.map
import com.iris.gallery.data.DuplicateDetector
import com.iris.gallery.data.DuplicateGroup
import com.iris.gallery.data.ExifEditRequest

import com.iris.gallery.data.AlbumRepository
import com.iris.gallery.data.AlbumAction
import com.iris.gallery.data.AlbumOperationResult
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.delay
import java.io.File

data class GalleryUiState(
    val loading: Boolean = false,
    val images: List<MediaImage> = emptyList(),
    val trashed: List<MediaImage> = emptyList(),
    val error: String? = null,
)

class GalleryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MediaRepository(application)
    private val libraryPreferences = LibraryPreferences(application)
    private val vaultRepository = VaultRepository(application)
    private val trashRepository = TrashRepository(application)
    private val albumRepository = AlbumRepository(application)
    private val settingsPreferences = SettingsPreferences.get(application)
    private val preferences = application.getSharedPreferences("gallery", 0)
    private val _uiState = MutableStateFlow(GalleryUiState(trashed = trashRepository.trashedMedia.value))
    val uiState: StateFlow<GalleryUiState> = _uiState.asStateFlow()
    private val _favorites = MutableStateFlow(
        preferences.getStringSet("favorites", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet(),
    )
    val favorites: StateFlow<Set<Long>> = _favorites.asStateFlow()
    val libraryState: StateFlow<LibraryPreferencesState> = libraryPreferences.state
    val vaultMedia: StateFlow<List<MediaImage>> = vaultRepository.vaultMedia
    val trashedMedia: StateFlow<List<MediaImage>> = trashRepository.trashedMedia
    private val duplicateDetector = DuplicateDetector(application)
    private var duplicateJob: Job? = null
    private val _duplicateState = MutableStateFlow(DuplicateScanState())
    val duplicateState: StateFlow<DuplicateScanState> = _duplicateState.asStateFlow()

    init {
        // Snapshot disk I/O is deliberately off Main; publish it only if a live refresh has not won the race.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val snapshot = repository.loadSnapshot()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (_uiState.value.images.isEmpty() && snapshot.isNotEmpty()) {
                    _uiState.update { it.copy(images = snapshot) }
                }
            }
        }
        viewModelScope.launch {
            var lastSystemTrash: List<MediaImage> = emptyList()
            trashRepository.trashedMedia.collect { trashedList ->
                // Show the in-app change at once; the full MediaStore query for the platform trash can take a while on a
                // big library and used to hold the Trash screen back until it finished.
                _uiState.value = _uiState.value.copy(trashed = (trashedList + lastSystemTrash).distinctBy { it.stableKey })
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    lastSystemTrash = runCatching { repository.loadImages(trashed = true) }.getOrDefault(lastSystemTrash)
                    _uiState.value = _uiState.value.copy(trashed = (trashedList + lastSystemTrash).distinctBy { it.stableKey })
                }
            }
        }
        viewModelScope.launch {
            settingsPreferences.state.map { it.useSystemTrash }.collect {
                refresh(showLoading = false)
            }
        }
    }

    fun scanDuplicates() {
        if (duplicateJob?.isActive == true) return
        duplicateJob = viewModelScope.launch {
            _duplicateState.value = DuplicateScanState(scanning = true)
            runCatching {
                duplicateDetector.scan(_uiState.value.images) { done, total ->
                    _duplicateState.value = _duplicateState.value.copy(done = done, total = total)
                }
            }.onSuccess { groups ->
                _duplicateState.value = DuplicateScanState(groups = groups, hasScanned = true)
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) {
                    _duplicateState.value = DuplicateScanState()
                } else _duplicateState.value = DuplicateScanState(hasScanned = true, error = error.message ?: "Scan failed")
            }
        }
    }

    fun cancelDuplicateScan() { duplicateJob?.cancel() }

    fun setLocked(ids: Collection<Long>, locked: Boolean) = libraryPreferences.setLocked(ids, locked)
    fun hasAllFilesAccess(): Boolean = vaultRepository.hasAllFilesAccess()
    suspend fun moveToVault(mediaList: List<MediaImage>): com.iris.gallery.data.VaultMoveResult = vaultRepository.moveToVault(mediaList)
    suspend fun rollbackVaultMove(vaultedMedia: List<MediaImage>) = vaultRepository.rollbackVault(vaultedMedia)
    suspend fun restoreFromVault(mediaList: List<MediaImage>): List<MediaImage> {
        val result = vaultRepository.restoreFromVault(mediaList)
        repository.clearRecentMovedOrDeleted(
            result.mapTo(HashSet(result.size)) { it.stableKey },
            result.mapNotNullTo(HashSet(result.size)) { it.path.takeIf(String::isNotBlank) },
        )
        refresh(forceClearCache = true)
        return result
    }
    suspend fun deletePermanentlyFromVault(mediaList: List<MediaImage>) = vaultRepository.deletePermanently(mediaList)

    suspend fun moveToTrash(mediaList: List<MediaImage>): com.iris.gallery.data.TrashMoveResult = trashRepository.moveToTrash(mediaList)
    suspend fun rollbackTrashMove(trashedMedia: List<MediaImage>) = trashRepository.rollbackTrash(trashedMedia)

    suspend fun restoreFromTrash(mediaList: List<MediaImage>): List<MediaImage> {
        val result = trashRepository.restoreFromTrash(mediaList)
        val restoredPaths = result.map { it.path }.filter { it.isNotBlank() }.toSet()
        repository.clearRecentMovedOrDeleted(result.mapTo(HashSet(result.size)) { it.stableKey }, restoredPaths)
        if (result.isNotEmpty()) {
            // Show the restored photos right away (their files are verified by the repository and exist on disk),
            // in their original timeline position, instead of waiting for a full reload. The light refresh that follows
            // replaces these stand-ins with the real MediaStore rows; nothing is cleared or re-verified library-wide.
            val shown = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                result.filter { it.path.isNotBlank() && java.io.File(it.path).isFile }
                    .map { it.copy(uri = android.net.Uri.fromFile(java.io.File(it.path))) }
            }
            _uiState.update { state ->
                val merged = com.iris.gallery.data.mergeRestored(state.images, shown, { it.path }, { it.stableKey })
                if (merged === state.images) state else state.copy(
                    images = merged.sortedWith(
                        compareByDescending<MediaImage> { it.sortDate }
                            .thenByDescending { it.dateModified }
                            .thenByDescending { it.dateAdded }
                            .thenByDescending { it.id }
                    )
                )
            }
        }
        refresh(showLoading = false, light = true)
        return result
    }

    suspend fun deletePermanently(mediaList: List<MediaImage>) {
        trashRepository.deletePermanently(mediaList)
        refresh(forceClearCache = true)
    }

    suspend fun emptyTrash() {
        trashRepository.emptyTrash()
        refresh()
    }

    suspend fun moveMediaToAlbum(mediaList: List<MediaImage>, targetDir: File, targetAlbumName: String): AlbumOperationResult {
        val result = albumRepository.moveMedia(mediaList, targetDir, targetAlbumName)
        if (result.successCount > 0) {
            val movedOldKeys = result.successfulSourceMedia.mapTo(HashSet()) { it.stableKey }
            val movedOldPaths = result.successfulSourceMedia.map { it.path }.filter { it.isNotBlank() }.toSet()
            result.successfulSourceMedia.forEach { item ->
                ThumbnailCache.remove(item.stableKey)
            }
            // Keep in-place moves visible, but tombstone only source identities that were recreated elsewhere.
            val newKeys = result.movedMedia.mapTo(HashSet()) { it.stableKey }
            val trulyDeletedOldItems = result.successfulSourceMedia.filterNot { it.stableKey in newKeys }
            repository.markMovedOrDeleted(trulyDeletedOldItems)
            val currentImages = _uiState.value.images
            val remainingImages = currentImages.filterNot { it.stableKey in movedOldKeys || (it.path.isNotBlank() && it.path in movedOldPaths) }
            val updatedImages = (remainingImages + result.movedMedia).sortedWith(
                compareByDescending<MediaImage> { it.sortDate }
                    .thenByDescending { it.dateModified }
                    .thenByDescending { it.dateAdded }
                    .thenByDescending { it.id }
            )
            _uiState.value = _uiState.value.copy(images = updatedImages)
        }
        refresh(showLoading = false)
        return result
    }

    suspend fun copyMediaToAlbum(mediaList: List<MediaImage>, targetDir: File, targetAlbumName: String): AlbumOperationResult {
        val result = albumRepository.copyMedia(mediaList, targetDir, targetAlbumName)
        if (result.successCount > 0) {
            val currentImages = _uiState.value.images
            val updatedImages = (currentImages + result.movedMedia).distinctBy { it.path }.sortedWith(
                compareByDescending<MediaImage> { it.sortDate }
                    .thenByDescending { it.dateModified }
                    .thenByDescending { it.dateAdded }
                    .thenByDescending { it.id }
            )
            _uiState.value = _uiState.value.copy(images = updatedImages)
        }
        refresh(showLoading = false)
        return result
    }

    fun getAlbumDirectory(album: MediaAlbum): File = albumRepository.getAlbumDirectory(album)
    fun createNewAlbumDirectory(albumName: String): File = albumRepository.createNewAlbumDirectory(albumName)

    fun togglePinnedAlbum(id: Long) = libraryPreferences.togglePinnedAlbum(id)
    fun setAlbumCover(albumId: Long, mediaId: Long) = libraryPreferences.setAlbumCover(albumId, mediaId)
    fun setAlbumSort(sort: AlbumSort) = libraryPreferences.setAlbumSort(sort)
    fun setAlbumOrder(order: List<Long>) = libraryPreferences.setAlbumOrder(order)
    fun setAlbumMediaSort(sort: com.iris.gallery.data.MediaSort) = libraryPreferences.setAlbumMediaSort(sort)
    fun setAlbumMediaSortOverride(albumId: Long, sort: com.iris.gallery.data.MediaSort?) = libraryPreferences.setAlbumMediaSortOverride(albumId, sort)
    fun addExcludedFolder(path: String) = libraryPreferences.addExcludedFolder(path)
    fun removeExcludedFolder(path: String) = libraryPreferences.removeExcludedFolder(path)
    fun setExcludedFolders(folders: Set<String>) = libraryPreferences.setExcludedFolders(folders)

    fun toggleFavorite(id: Long) {
        val updated = _favorites.value.toMutableSet().apply {
            if (!add(id)) remove(id)
        }
        _favorites.value = updated
        preferences.edit().putStringSet("favorites", updated.mapTo(mutableSetOf()) { it.toString() }).apply()
    }

    private var contentObserver: ContentObserver? = null
    private var liveReloadJob: Job? = null
    @Volatile private var exifWriting = false
    @Volatile private var exifWritingUntil = 0L

    fun registerObserver() {
        if (contentObserver != null) return
        runCatching {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    super.onChange(selfChange, uri)
                    if (exifWriting || System.currentTimeMillis() < exifWritingUntil) {
                        com.iris.gallery.data.LatencyLog.add("1 ignored: our own EXIF write (" + (uri?.lastPathSegment ?: "?") + ")")
                        return
                    }
                    com.iris.gallery.data.LatencyLog.add("1 MediaStore notified (" + (uri?.lastPathSegment ?: "?") + ")")
                    liveReloadJob?.cancel()
                    liveReloadJob = viewModelScope.launch {
                        delay(120)
                        // keep a running reload alive (cancelling it on every notification starves new files);
                        // a second, light pass picks up whatever changed meanwhile
                        while (refreshJob?.isActive == true) delay(60)
                        refresh(showLoading = false, light = true)
                    }
                }
            }
            val resolver = getApplication<Application>().contentResolver
            resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
            resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
            contentObserver = observer
        }
    }

    override fun onCleared() {
        super.onCleared()
        contentObserver?.let {
            runCatching {
                getApplication<Application>().contentResolver.unregisterContentObserver(it)
            }
        }
        contentObserver = null
    }

    fun renameMedia(item: MediaImage, newName: String, onResult: (MediaImage?) -> Unit = {}) {
        viewModelScope.launch {
            val updated = repository.renameMedia(item, newName)
            if (updated != null) {
                _uiState.update { state ->
                    state.copy(
                        images = state.images.map { if (it.stableKey == item.stableKey) updated else it }
                    )
                }
                ThumbnailCache.remove(item.stableKey)
            }
            onResult(updated)
        }
    }

    fun updateMediaMetadata(media: MediaImage, request: ExifEditRequest): MediaImage {
        val newTitle = if (request.stripAllExif) "" else request.title
        val newDescription = if (request.stripAllExif) "" else (request.imageDescription ?: media.description)
        libraryPreferences.setCustomTitle(media.id, if (request.stripAllExif) null else request.title.ifBlank { null })
        val updated = media.copy(
            title = newTitle,
            orientation = request.orientation,
            dateTaken = request.dateTakenMillis,
            description = newDescription,
        )
        if (media.orientation != request.orientation) {
            ThumbnailCache.remove(media.stableKey)
        }
        _uiState.update { state ->
            state.copy(
                images = state.images.map { if (it.stableKey == media.stableKey) updated else it },
                trashed = state.trashed.map { if (it.stableKey == media.stableKey) updated else it },
            )
        }
        return updated
    }

    fun markMediaDeleted(items: Collection<MediaImage>) {
        if (items.isEmpty()) return
        val keySet = items.mapTo(HashSet(items.size)) { it.stableKey }
        val pathSet = items.mapNotNullTo(HashSet(items.size)) { it.path.takeIf(String::isNotBlank) }
        repository.markMovedOrDeleted(items)
        items.forEach { ThumbnailCache.remove(it.stableKey) }
        _uiState.update { state ->
            state.copy(images = state.images.filterNot { it.stableKey in keySet || (it.path.isNotBlank() && it.path in pathSet) })
        }
        if (_duplicateState.value.groups.isNotEmpty()) {
            val updatedGroups = _duplicateState.value.groups.mapNotNull { group ->
                val remainingItems = group.items.filterNot { it.stableKey in keySet || (it.path.isNotBlank() && it.path in pathSet) }
                if (remainingItems.size > 1) group.copy(items = remainingItems) else null
            }
            _duplicateState.update { it.copy(groups = updatedGroups) }
        }
    }

    fun rescanMedia(onComplete: (Int) -> Unit = {}) {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            val count = repository.rescanStorage()
            refresh(showLoading = false)
            onComplete(count)
        }
    }

    fun restoreSystemTrash(items: Collection<MediaImage>) {
        repository.clearRecentMovedOrDeleted(
            items.mapTo(HashSet(items.size)) { it.stableKey },
            items.mapNotNullTo(HashSet(items.size)) { it.path.takeIf(String::isNotBlank) },
        )
        refresh(showLoading = false)
    }

    fun purgeGhostMedia(image: MediaImage) {
        repository.purgeGhostMedia(image)
        com.iris.gallery.ui.ThumbnailCache.remove(image.stableKey)
    }

    private var refreshJob: Job? = null
    private var exifJob: Job? = null
    @Volatile private var pendingExifImages: List<MediaImage>? = null

    /** Reads real photo dates in the background (once per photo); refreshes the list if any date changed. */
    private fun startExifEnrichment(list: List<MediaImage>) {
        if (!com.iris.gallery.data.ExifDateStore.enabled || list.isEmpty()) return
        if (exifJob?.isActive == true) {
            // Keep the newest list and run it after the current batch; do not permanently skip new files.
            pendingExifImages = list
            return
        }
        exifJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var next: List<MediaImage>? = list
            while (true) {
                val batch = next ?: break
                pendingExifImages = null
                exifWriting = true
                val changed = try {
                    runCatching { repository.enrichExifDates(batch) }.getOrDefault(0)
                } finally {
                    exifWriting = false
                    exifWritingUntil = System.currentTimeMillis() + 1500L
                }
                if (changed > 0) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        refresh(showLoading = false, light = true)
                    }
                }
                next = pendingExifImages
            }
        }
    }

    private var firstContentLogged = false

    fun refresh(showLoading: Boolean = true, forceClearCache: Boolean = false, light: Boolean = false) {
        val current = refreshJob
        if (current?.isActive == true) {
            current.cancel()
        }
        if (forceClearCache) {
            repository.clearVerifiedPathsCache()
        }
        refreshJob = viewModelScope.launch {
            try {
                kotlinx.coroutines.withTimeout(20_000) {
                    // A light reload (a new photo/video appeared) only needs the media list; trash and vault are unchanged.
                    val trashList = if (light) _uiState.value.trashed else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        vaultRepository.loadVaultItems()
                        val internalTrash = trashRepository.loadTrashItems()
                        val systemTrash = if (android.os.Build.VERSION.SDK_INT >= 30 && settingsPreferences.state.value.useSystemTrash) {
                            try {
                                repository.loadImages(trashed = true)
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                emptyList()
                            }
                        } else emptyList()
                        (internalTrash + systemTrash).distinctBy { it.stableKey }
                    }
                    if (showLoading) {
                        _uiState.update { it.copy(loading = true, trashed = trashList, error = null) }
                    }
                    try {
                        val loadStart = android.os.SystemClock.elapsedRealtime()
                        val loaded = repository.loadImages()
                        com.iris.gallery.data.LatencyLog.add(
                            "load: " + loaded.size + " items in " + (android.os.SystemClock.elapsedRealtime() - loadStart) + " ms" + (if (light) " (light)" else "")
                        )
                        val loadedPaths = loaded.mapTo(HashSet(loaded.size)) { it.path }
                        val loadedKeys = loaded.mapTo(HashSet(loaded.size)) { it.stableKey }
                        val currentPending = _uiState.value.images.filter { item ->
                            item.path.isNotBlank() &&
                            item.path !in loadedPaths &&
                            item.stableKey !in loadedKeys &&
                            !repository.isMarkedMovedOrDeleted(item.stableKey, item.path) &&
                            java.io.File(item.path).exists()
                        }
                        val combined = if (currentPending.isNotEmpty()) {
                            (loaded + currentPending).distinctBy { it.stableKey }.sortedWith(
                                compareByDescending<MediaImage> { it.sortDate }
                                    .thenByDescending { it.dateModified }
                                    .thenByDescending { it.dateAdded }
                                    .thenByDescending { it.id }
                            )
                        } else {
                            loaded
                        }
                        _uiState.update { it.copy(images = combined, loading = false, trashed = trashList, error = null) }
                        if (!firstContentLogged && combined.isNotEmpty()) {
                            firstContentLogged = true
                            val sinceStart = android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()
                            com.iris.gallery.data.LatencyLog.add("first content: " + sinceStart + " ms after process start")
                        }
                        run {
                            val newest = combined.maxOfOrNull { it.dateAdded } ?: 0L
                            com.iris.gallery.data.LatencyLog.add("2 on screen: " + combined.size + " items, newest MediaStore dateAdded=" + com.iris.gallery.data.LatencyLog.time(newest * 1000L))
                        }
                        startExifEnrichment(combined)
                        if (_duplicateState.value.hasScanned) _duplicateState.value = DuplicateScanState()
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        android.util.Log.e("GalleryViewModel", "refresh loadImages failed", e)
                        if (_uiState.value.images.isEmpty()) {
                            _uiState.update { state -> state.copy(loading = false, error = e.message ?: "Could not load photos") }
                        } else {
                            _uiState.update { it.copy(loading = false) }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e("GalleryViewModel", "refresh overall failed", e)
                _uiState.update { it.copy(loading = false) }
            } finally {
                _uiState.update { it.copy(loading = false) }
            }
        }
    }
}

data class DuplicateScanState(
    val scanning: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val groups: List<DuplicateGroup> = emptyList(),
    val hasScanned: Boolean = false,
    val error: String? = null,
)
