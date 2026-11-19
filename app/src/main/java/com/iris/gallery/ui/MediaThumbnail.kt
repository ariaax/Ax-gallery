package com.iris.gallery.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import com.iris.gallery.ui.AxRound
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.iris.gallery.data.MediaImage
import com.iris.gallery.data.isGif
import com.iris.gallery.data.isMotionPhoto
import com.iris.gallery.data.isPanorama
import com.iris.gallery.data.isRaw
import com.iris.gallery.data.isSvg
import com.iris.gallery.data.livpJpegCacheFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Thumbnails used to be decoded on the unbounded IO pool: a fast fling started dozens of decodes at once, they fought
 * each other (and the UI thread) for CPU/IO, and decodes for tiles that had already scrolled away still ran to the end.
 * With a small fixed width, tiles that left the screen are still queued, so their work is cancelled before it starts.
 */
private fun backgroundPool(name: String, threads: Int) = java.util.concurrent.Executors.newFixedThreadPool(threads) { r ->
    // Decoding runs at *background* thread priority: on a weak phone the UI / render threads then win the CPU
    // while a fast fling is decoding dozens of tiles, which is what keeps the scroll smooth.
    Thread({
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        r.run()
    }, name).apply { isDaemon = true }
}.asCoroutineDispatcher()

private val thumbDispatcher = backgroundPool("ax-thumb", 2)

/**
 * Video frames take far longer to extract than photo thumbnails. On the shared lane a few videos in a row filled all
 * the threads and the photos next to them waited behind them (a stall right when a scroll goes from photos into videos),
 * so videos get their own small lane, for visible tiles and for look-ahead.
 */
private val videoThumbDispatcher = backgroundPool("ax-thumb-video", 2)
private val videoPrefetchDispatcher = backgroundPool("ax-prefetch-video", 1)
private val videoPreviewDispatcher = backgroundPool("ax-preview-video", 1)

/** Badge detection (HDR/depth) is low priority: a single background lane so it never competes with thumbnails. */
private val detectDispatcher = backgroundPool("ax-detect", 1)

/** Look-ahead decoding of tiles that are about to scroll in: one lane, so it never delays the visible tiles. */
private val prefetchDispatcher = backgroundPool("ax-prefetch", 1)

/** Decodes a thumbnail into the memory cache without showing it (no-op when it is already cached). */
suspend fun prefetchThumbnail(context: Context, image: MediaImage, targetSizePx: Int) {
    val key = "${image.stableKey}:$targetSizePx"
    if (ThumbnailCache.get(key) != null) return
    withContext(if (image.isVideo) videoPrefetchDispatcher else prefetchDispatcher) {
        if (ThumbnailCache.get(key) == null) loadThumbnail(context, image, targetSizePx)
    }
}

object ThumbnailCache {
    private val maxKilobytes = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt() // 12.5% heap
    private val cache = object : LruCache<String, Bitmap>(maxKilobytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount / 1024
    }

    // modified time each id was cached at, so an edit made by another app does not keep showing the old picture
    // Bounded metadata cache: previously this ConcurrentHashMap grew for every file ever visited.
    private val stamps = object : LruCache<String, Long>(4096) {}

    fun markFresh(key: String, stamp: Long) { if (stamp > 0L) stamps.put(key, stamp) }

    /** Removes cached copies when the source's modification stamp changes. */
    fun invalidateIfStale(key: String, stamp: Long): Boolean {
        val seen = stamps.get(key) ?: return false
        if (stamp <= 0L || seen == stamp) return false
        remove(key)
        stamps.remove(key)
        return true
    }

    fun get(key: String): Bitmap? = cache.get(key)
    fun put(key: String, bitmap: Bitmap) { cache.put(key, bitmap) }

    fun findForImage(stableKey: String): Bitmap? {
        for (bucket in listOf(320, 180, 512, 768, 256)) {
            cache.get("$stableKey:$bucket")?.let { return it }
        }
        return null
    }

    /** Reuse a larger cached bucket for a smaller tile. */
    fun findAtLeast(stableKey: String, minPx: Int): Bitmap? {
        for (bucket in intArrayOf(180, 256, 320, 512, 768)) {
            if (bucket < minPx) continue
            cache.get("$stableKey:$bucket")?.let { return it }
        }
        return null
    }

    fun remove(stableKey: String) {
        cache.snapshot().keys.filter { it.startsWith("$stableKey:") }.forEach(cache::remove)
        stamps.remove(stableKey)
        hdrCache.remove(stableKey)
        depthCache.remove(stableKey)
    }

    /** Legacy id-only invalidation removes every matching volume key rather than leaving stale images behind. */

    fun clear() {
        cache.evictAll()
        hdrCache.evictAll()
        depthCache.evictAll()
        stamps.evictAll()
    }

    private val hdrCache = LruCache<String, Boolean>(2000)

    fun isHdr(stableKey: String): Boolean? = hdrCache.get(stableKey)
    fun setHdr(stableKey: String, isHdr: Boolean) { hdrCache.put(stableKey, isHdr) }

    private val depthCache = LruCache<String, Boolean>(2000)

    fun isDepth(stableKey: String): Boolean? = depthCache.get(stableKey)
    fun setDepth(stableKey: String, isDepth: Boolean) { depthCache.put(stableKey, isDepth) }
}

fun loadThumbnailSync(context: Context, image: MediaImage, targetSizePx: Int = 320): Bitmap? =
    loadThumbnail(context, image, targetSizePx)

fun getThumbnailTargetSizePx(columns: Int): Int = when {
    columns >= 6 -> 180
    columns == 5 -> 256
    columns in 3..4 -> 384
    columns == 2 -> 512
    else -> 768
}

fun getThumbnailTargetSizePx(cellSizeDp: Float, density: Float = 2.5f): Int {
    val pixelSize = (cellSizeDp * density).toInt()
    return when {
        pixelSize <= 220 -> 180  // 5-6 columns: super lightweight, fast scroll
        pixelSize <= 380 -> 320  // 3-4 columns: standard crisp grid thumbnail
        pixelSize <= 600 -> 512  // 2 columns: high definition
        else -> 768              // 1 column / tablets: full sharp resolution
    }
}

/**
 * Persistent thumbnail cache on disk (the part a cold start was missing: the memory cache is empty after every
 * app start, so every tile had to be decoded from the full-size file again). Small WebP files, keyed by
 * id + size + modification stamp, so an edited photo gets a fresh thumbnail. Trimmed to ~256 MB, oldest first.
 */
private object ThumbDiskCache {
    private const val MAX_BYTES = 256L * 1024 * 1024
    @Volatile private var dir: java.io.File? = null
    private val writes = java.util.concurrent.atomic.AtomicInteger()

    private fun dir(context: Context): java.io.File =
        dir ?: java.io.File(context.cacheDir, "ax_thumbs").also { it.mkdirs(); dir = it }

    private fun file(context: Context, image: MediaImage, size: Int): java.io.File {
        val safeKey = image.stableKey.replace(Regex("[^A-Za-z0-9._-]"), "_").take(180)
        return java.io.File(dir(context), "${safeKey}_${size}_${image.dateModified}_${image.sizeBytes}${if (image.isVideo) "_p2" else ""}.w")
    }

    fun read(context: Context, image: MediaImage, size: Int): Bitmap? = runCatching {
        val f = file(context, image, size)
        if (!f.exists() || f.length() == 0L) return null
        android.graphics.BitmapFactory.decodeFile(f.absolutePath)?.also { f.setLastModified(System.currentTimeMillis()) }
    }.getOrNull()

    fun write(context: Context, image: MediaImage, size: Int, bitmap: Bitmap) {
        runCatching {
            val f = file(context, image, size)
            val tmp = java.io.File(f.parentFile, f.name + ".tmp")
            java.io.FileOutputStream(tmp).use { out ->
                @Suppress("DEPRECATION")
                val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                bitmap.compress(fmt, 82, out)
            }
            if (!tmp.renameTo(f)) tmp.delete()
            if (writes.incrementAndGet() % 300 == 0) trim(context)
        }
    }

    private fun trim(context: Context) {
        val files = dir(context).listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_BYTES * 8 / 10) break
            total -= f.length()
            f.delete()
        }
    }
}

private fun loadThumbnail(context: Context, image: MediaImage, targetSizePx: Int = 320): Bitmap? {
    val key = "${image.stableKey}:$targetSizePx"
    // a file edited elsewhere keeps its id: drop the memory copies when its modified time moved
    ThumbnailCache.invalidateIfStale(image.stableKey, image.dateModified)
    ThumbnailCache.get(key)?.let { return it }
    if (image.id > 0 && image.dateModified > 0L) {
        ThumbDiskCache.read(context, image, targetSizePx)?.let { ThumbnailCache.put(key, it); ThumbnailCache.markFresh(image.stableKey, image.dateModified); return it }
    }
    val bmp = loadThumbnailRaw(context, image, targetSizePx)
    if (bmp != null) ThumbnailCache.markFresh(image.stableKey, image.dateModified)
    if (bmp != null && image.id > 0 && image.dateModified > 0L) ThumbDiskCache.write(context, image, targetSizePx, bmp)
    return bmp
}

/**
 * AVIF fallback (Android 13 has no platform AVIF thumbnail). The file is memory-mapped instead of being read into a
 * heap array and copied again, and files whose full decode would not fit comfortably are skipped. The library can only
 * decode at full size, so the full bitmap is shrunk and released immediately.
 */
private const val AVIF_MAX_DECODE_BYTES = 48L * 1024 * 1024 // 12 megapixels of ARGB_8888
private const val AVIF_MAX_INPUT_BYTES = 24L * 1024 * 1024

private fun decodeAvifThumbnail(context: Context, image: MediaImage, targetSizePx: Int): Bitmap? {
    var pfd: android.os.ParcelFileDescriptor? = null
    var full: Bitmap? = null
    return try {
        var buffer: java.nio.ByteBuffer? = null
        var length = 0
        val file = java.io.File(image.path)
        if (image.path.isNotBlank() && file.isFile) {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val fileLength = raf.length()
                if (fileLength <= 0L || fileLength > AVIF_MAX_INPUT_BYTES || fileLength > Int.MAX_VALUE) return null
                length = fileLength.toInt()
                buffer = raf.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, fileLength)
            }
        } else {
            pfd = context.contentResolver.openFileDescriptor(image.uri, "r") ?: return null
            java.io.FileInputStream(pfd.fileDescriptor).use { input ->
                val fileLength = input.channel.size()
                if (fileLength <= 0L || fileLength > AVIF_MAX_INPUT_BYTES || fileLength > Int.MAX_VALUE) return null
                length = fileLength.toInt()
                buffer = input.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, fileLength)
            }
        }
        val encoded = buffer ?: return null
        if (length <= 0 || !org.aomedia.avif.android.AvifDecoder.isAvifImage(encoded)) return null
        val info = org.aomedia.avif.android.AvifDecoder.Info()
        if (!org.aomedia.avif.android.AvifDecoder.getInfo(encoded, length, info)) return null
        if (info.width <= 0 || info.height <= 0 || info.width.toLong() * info.height * 4L > AVIF_MAX_DECODE_BYTES) return null
        val bmp = Bitmap.createBitmap(info.width, info.height, Bitmap.Config.ARGB_8888)
        full = bmp
        if (!org.aomedia.avif.android.AvifDecoder.decode(encoded, length, bmp)) return null
        val scale = minOf(1f, targetSizePx.toFloat() / maxOf(info.width, info.height))
        if (scale >= 1f) {
            full = null // handed to the caller, must not be recycled below
            bmp
        } else {
            Bitmap.createScaledBitmap(
                bmp,
                (info.width * scale).toInt().coerceAtLeast(1),
                (info.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        }
    } catch (_: Throwable) {
        null
    } finally {
        runCatching { pfd?.close() }
        full?.let { if (!it.isRecycled) it.recycle() } // the full-size bitmap is never kept past this call
    }
}

/** Why the last thumbnail attempt of a file failed (for the problem report only); small and bounded. */
private val thumbFailures = java.util.concurrent.ConcurrentHashMap<String, String>()
private fun noteThumbFailure(image: MediaImage, e: Throwable) {
    if (thumbFailures.size > 200) thumbFailures.clear()
    thumbFailures[image.stableKey] = "${e.javaClass.simpleName}: ${(e.message ?: "").take(120)}"
}

private fun loadThumbnailRaw(context: Context, image: MediaImage, targetSizePx: Int = 320): Bitmap? {
    val cacheKey = "${image.stableKey}:$targetSizePx"
    ThumbnailCache.get(cacheKey)?.let { return it }

    // For .livp (Apple Live Photo zip) files, make sure the inner JPEG has been
    // extracted to a temp cache file before attempting thumbnail creation.
    if (image.path.endsWith(".livp", ignoreCase = true)) {
        val cached = image.livpJpegCacheFile(context)
        if (cached != null && (!cached.exists() || cached.length() == 0L)) {
            // Trigger motion extraction which also writes the JPEG cache file.
            runCatching { com.iris.gallery.decoder.MotionPhotoExtractor.detectMotionPhoto(context, image) }
        }
    }

    val bitmap = try {
        val size = Size(targetSizePx, targetSizePx)
        // For .livp files, use the extracted cache JPEG if available.
        val effectivePath = if (image.path.endsWith(".livp", ignoreCase = true)) {
            val cached = image.livpJpegCacheFile(context)
            if (cached != null && cached.exists() && cached.length() > 0) cached.absolutePath else image.path
        } else image.path
        val isFile = image.uri.scheme == "file" || image.path.startsWith(context.filesDir.absolutePath)
        if (isFile) {
            val file = java.io.File(effectivePath)
            if (Build.VERSION.SDK_INT >= 29) {
                if (image.isVideo) {
                    android.media.ThumbnailUtils.createVideoThumbnail(file, size, null)
                } else {
                    android.media.ThumbnailUtils.createImageThumbnail(file, size, null)
                }
            } else {
                if (image.isVideo) {
                    @Suppress("DEPRECATION")
                    android.media.ThumbnailUtils.createVideoThumbnail(image.path, MediaStore.Images.Thumbnails.MINI_KIND)
                } else {
                    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(image.path, opts)
                    val sample = maxOf(opts.outWidth / targetSizePx, opts.outHeight / targetSizePx, 1)
                    val opts2 = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                    android.graphics.BitmapFactory.decodeFile(image.path, opts2)
                }
            }
        } else if (Build.VERSION.SDK_INT >= 29) {
            context.contentResolver.loadThumbnail(image.uri, size, null)
        } else {
            @Suppress("DEPRECATION")
            if (image.isVideo) {
                MediaStore.Video.Thumbnails.getThumbnail(
                    context.contentResolver, image.id, MediaStore.Video.Thumbnails.MINI_KIND, null,
                )
            } else {
                MediaStore.Images.Thumbnails.getThumbnail(
                    context.contentResolver, image.id, MediaStore.Images.Thumbnails.MINI_KIND, null,
                )
            }
        }
    } catch (e: Exception) {
        noteThumbFailure(image, e)
        null
    }
    val firstPass = bitmap ?: run {
        if (image.mimeType.contains("avif", ignoreCase = true) || image.name.endsWith(".avif", ignoreCase = true)) {
            decodeAvifThumbnail(context, image, targetSizePx)
        } else if (image.isSvg) {
            runCatching {
                val req = coil3.request.ImageRequest.Builder(context)
                    .data(image.uri)
                    .size(targetSizePx, targetSizePx)
                    .build()
                val res = kotlinx.coroutines.runBlocking {
                    coil3.SingletonImageLoader.get(context).execute(req)
                }
                (res.image as? coil3.BitmapImage)?.bitmap
            }.getOrNull()
        } else if (!image.isVideo) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = if (image.path.isNotBlank() && java.io.File(image.path).exists()) {
                        ImageDecoder.createSource(java.io.File(image.path))
                    } else {
                        ImageDecoder.createSource(context.contentResolver, image.uri)
                    }
                    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val maxDim = maxOf(info.size.width, info.size.height)
                        var sample = 1
                        while (maxDim / (sample * 2) >= targetSizePx) {
                            sample *= 2
                        }
                        if (sample > 1) {
                            decoder.setTargetSampleSize(sample)
                        }
                    }
                } else {
                    val openStream: () -> java.io.InputStream? = {
                        if (image.path.isNotBlank() && java.io.File(image.path).exists()) {
                            java.io.FileInputStream(image.path)
                        } else {
                            context.contentResolver.openInputStream(image.uri)
                        }
                    }
                    openStream()?.use { s ->
                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeStream(s, null, opts)
                        val sample = maxOf(opts.outWidth / targetSizePx, opts.outHeight / targetSizePx, 1)
                        val opts2 = BitmapFactory.Options().apply { inSampleSize = sample }
                        openStream()?.use { s2 ->
                            BitmapFactory.decodeStream(s2, null, opts2)
                        }
                    }
                }
            }.getOrNull()
        } else {
            runCatching {
                android.media.MediaMetadataRetriever().use { mmr ->
                    if (image.path.isNotBlank() && java.io.File(image.path).exists()) {
                        mmr.setDataSource(image.path)
                    } else {
                        mmr.setDataSource(context, image.uri)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        mmr.getScaledFrameAtTime(
                            -1L,
                            android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            targetSizePx,
                            targetSizePx
                        )
                    } else {
                        val width = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                        val height = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                        if (width <= 0 || height <= 0 || width.toLong() * height.toLong() > 12_000_000L) null
                        else mmr.getFrameAtTime(-1L)
                    }
                }
            }.onFailure { noteThumbFailure(image, it) }.getOrNull()
        }
    }
    // The system's video thumbnail is often a black first frame (or missing): use a real frame instead.
    val finalBitmap = if (image.isVideo && (firstPass == null || VideoFrames.isDark(firstPass))) {
        VideoFrames.poster(context, image, targetSizePx) ?: firstPass
    } else firstPass
    finalBitmap?.prepareToDraw()
    finalBitmap?.let { ThumbnailCache.put(cacheKey, it) }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !image.isVideo && !image.isRaw && !image.isGif) {
        if (finalBitmap != null && finalBitmap.hasGainmap()) {
            ThumbnailCache.setHdr(image.stableKey, true)
        }
    }
    return finalBitmap
}

private val HDR_MARKERS = listOf(
    "http://ns.adobe.com/hdr-gain-map/1.0/", "hdrgm:", "Item:Semantic=\"GainMap\"", "21496-1",
).map { it.toByteArray(Charsets.ISO_8859_1) }
private val GAINMAP_MARKER = "GainMap".toByteArray(Charsets.ISO_8859_1)
private val CONTAINER_MARKER = "Container:Directory".toByteArray(Charsets.ISO_8859_1)
private val DEPTH_MARKERS = listOf("GDepth:", "GCamera:SpecialTypeID=\"PORTRAIT_TYPE\"").map { it.toByteArray(Charsets.ISO_8859_1) }

/** Plain byte search: no String is built from the file header any more (that was ~256 KB of garbage per photo). */
private fun ByteArray.containsBytes(len: Int, pat: ByteArray): Boolean {
    if (pat.isEmpty() || len < pat.size) return false
    val first = pat[0]
    var i = 0
    val last = len - pat.size
    while (i <= last) {
        if (this[i] == first) {
            var j = 1
            while (j < pat.size && this[i + j] == pat[j]) j++
            if (j == pat.size) return true
        }
        i++
    }
    return false
}

private val headerBuffer = ThreadLocal<ByteArray>()

/** Reads the first 64 KB (XMP metadata lives at the very start of the file) into a reused per-thread buffer. */
private inline fun <T> withHeader(context: Context, image: MediaImage, block: (ByteArray, Int) -> T): T? {
    val buffer = headerBuffer.get() ?: ByteArray(65536).also { headerBuffer.set(it) }
    val stream = if (image.path.isNotBlank() && java.io.File(image.path).canRead()) {
        runCatching { java.io.FileInputStream(image.path) }.getOrNull()
    } else null
    val finalStream = stream ?: context.contentResolver.openInputStream(image.uri)
    return finalStream?.use { input ->
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read <= 0) break
            total += read
        }
        if (total > 0) block(buffer, total) else null
    }
}

private fun checkIsUltraHdrFast(context: Context, image: MediaImage): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
    if (image.isVideo || image.isRaw || image.isGif) return false
    return runCatching {
        withHeader(context, image) { buf, len ->
            HDR_MARKERS.any { buf.containsBytes(len, it) } ||
                (buf.containsBytes(len, CONTAINER_MARKER) && buf.containsBytes(len, GAINMAP_MARKER))
        } ?: false
    }.getOrDefault(false)
}

private fun checkIsDepthPhotoFast(context: Context, image: MediaImage): Boolean {
    if (image.isVideo || image.isRaw || image.isGif || image.isSvg) return false
    return runCatching {
        withHeader(context, image) { buf, len ->
            DEPTH_MARKERS.any { buf.containsBytes(len, it) }
        } ?: false
    }.getOrDefault(false)
}

@Composable
fun MediaThumbnail(
    image: MediaImage,
    modifier: Modifier = Modifier,
    targetSizePx: Int = 320,
    showVideoDuration: Boolean = true,
    showFormatBadge: Boolean = true,
    isFavorite: Boolean = false,
    onInvalidMedia: ((MediaImage) -> Unit)? = null,
) {
    val context = LocalContext.current
    val cacheKey = remember(image.stableKey, targetSizePx) { "${image.stableKey}:$targetSizePx" }
    val cached = remember(cacheKey, image.dateModified) {
        ThumbnailCache.invalidateIfStale(image.stableKey, image.dateModified)
        ThumbnailCache.get(cacheKey) ?: ThumbnailCache.findAtLeast(image.stableKey, targetSizePx)
    }
    var bitmap by remember(cacheKey, image.dateModified) { mutableStateOf(cached) }
    val cachedHdr = remember(image.stableKey) { ThumbnailCache.isHdr(image.stableKey) ?: false }
    var isHdr by remember(image.stableKey) { mutableStateOf(cachedHdr) }
    val cachedDepth = remember(image.stableKey) { ThumbnailCache.isDepth(image.stableKey) ?: false }
    var isDepth by remember(image.stableKey) { mutableStateOf(cachedDepth) }

    if (bitmap == null) {
        LaunchedEffect(cacheKey, image.uri, image.dateModified) {
            val loaded = withContext(if (image.isVideo) videoThumbDispatcher else thumbDispatcher) { loadThumbnail(context, image, targetSizePx) }
            if (loaded != null) {
                bitmap = loaded
            } else {
                val isGhost = withContext(Dispatchers.IO) {
                    val filePath = image.path
                    if (filePath.isNotBlank()) {
                        val file = java.io.File(filePath)
                        if (!file.exists()) {
                            if (image.id > 0) {
                                val canOpen = runCatching {
                                    context.contentResolver.openAssetFileDescriptor(image.uri, "r")?.use { true } ?: false
                                }.getOrDefault(false)
                                !canOpen
                            } else {
                                true
                            }
                        } else {
                            false
                        }
                    } else if (image.id > 0) {
                        runCatching {
                            context.contentResolver.openAssetFileDescriptor(image.uri, "r")?.use { false } ?: true
                        }.getOrDefault(false)
                    } else {
                        false
                    }
                }
                if (isGhost) {
                    onInvalidMedia?.invoke(image)
                } else {
                    // The file exists but nothing produced a thumbnail. Hardware video decoders can be briefly busy
                    // (viewer, other tiles), so try once more before giving up and recording it.
                    val dispatcher = if (image.isVideo) videoThumbDispatcher else thumbDispatcher
                    val retry = if (image.sizeBytes > 0L) {
                        kotlinx.coroutines.delay(700)
                        withContext(dispatcher) { loadThumbnail(context, image, targetSizePx) }
                    } else null
                    if (retry != null) {
                        bitmap = retry
                    } else {
                        val why = thumbFailures.remove(image.stableKey)
                        com.iris.gallery.data.DiagnosticsLog.unsupported(
                            image,
                            "thumbnail could not be decoded" + (why?.let { "\n$it" } ?: "")
                        )
                    }
                }
            }
        }
    }

    // HDR / depth badges: looked up once per photo, on a single low-priority lane, and only after the thumbnail is
    // already on screen. Skipped completely when badges are switched off.
    val needHdr = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !image.isVideo && !image.isRaw && !image.isGif
    val needDepth = !image.isVideo && !image.isRaw && !image.isGif && !image.isSvg && !image.isMotionPhoto
    val knownHdr = if (needHdr) ThumbnailCache.isHdr(image.stableKey) else null
    val knownDepth = if (needDepth) ThumbnailCache.isDepth(image.stableKey) else null
    if (knownHdr != null && isHdr != knownHdr) isHdr = knownHdr
    if (knownDepth != null && isDepth != knownDepth) isDepth = knownDepth
    if (showFormatBadge && bitmap != null && ((needHdr && knownHdr == null) || (needDepth && knownDepth == null))) {
        LaunchedEffect(image.stableKey) {
            if (needHdr && ThumbnailCache.isHdr(image.stableKey) == null) {
                val detected = withContext(detectDispatcher) { checkIsUltraHdrFast(context, image) }
                ThumbnailCache.setHdr(image.stableKey, detected)
                isHdr = detected
            }
            if (needDepth && ThumbnailCache.isDepth(image.stableKey) == null) {
                val detected = withContext(detectDispatcher) { checkIsDepthPhotoFast(context, image) }
                ThumbnailCache.setDepth(image.stableKey, detected)
                isDepth = detected
            }
        }
    }

    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        bitmap?.let {
            val shown = remember(it) { it.asImageBitmap() }
            Image(
                bitmap = shown,
                contentDescription = image.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (image.isVideo && bitmap != null) VideoPreviewLayer(image, targetSizePx)
        if (isFavorite) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(5.dp)
                    .background(Color.Black.copy(alpha = 0.55f), AxRound(30))
                    .padding(3.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Favorite,
                    contentDescription = null,
                    tint = Color(0xFFFF3B30),
                    modifier = Modifier.size(11.dp)
                )
            }
        }
        if (image.isVideo && showVideoDuration) {
            Row(
                modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp)
                    .background(Color.Black.copy(alpha = .55f), AxRound(7.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.PlayArrow, null, tint = Color.White.copy(alpha = .9f),
                    modifier = Modifier.size(10.dp).padding(end = 1.dp)
                )
                Text(
                    formatDuration(image.durationMs), color = Color.White.copy(alpha = .92f), fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        } else if (!image.isVideo && showFormatBadge) {
            val badge = when {
                image.isRaw -> "RAW"
                image.isGif -> "GIF"
                image.isSvg -> "SVG"
                image.isPanorama -> "PANO"
                image.isMotionPhoto -> "MOTION"
                isDepth -> "DEPTH"
                isHdr || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && bitmap?.hasGainmap() == true) -> "HDR"
                else -> null
            }
            if (badge != null) {
                Box(
                    modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                        .background(Color.Black.copy(alpha = .68f), AxRound(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = badge,
                        color = Color.White.copy(alpha = .92f),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

/**
 * Multi-shot preview: once the grid has been still for a moment, a visible video quietly cycles through four small
 * frames taken along its length (cross-faded), then back to its poster. Frames are cut on one background-priority lane,
 * cached in a ~6 MB memory cache, never made while scrolling, and at most six tiles animate at the same time.
 */
@Composable
private fun VideoPreviewLayer(image: MediaImage, targetSizePx: Int) {
    val context = LocalContext.current
    val key = remember(image.stableKey, image.dateModified) { "${image.stableKey}:${image.dateModified}" }
    var frames by remember(key) { mutableStateOf(VideoPreviewCache.get(key)) }
    var index by remember(key) { androidx.compose.runtime.mutableIntStateOf(-1) }
    LaunchedEffect(key) {
        kotlinx.coroutines.delay(1100)
        while (com.iris.gallery.data.ScrollActivity.recentlyScrolling()) kotlinx.coroutines.delay(400)
        if (frames == null && !VideoPreviewCache.hasNone(key)) {
            val made = withContext(videoPreviewDispatcher) {
                if (com.iris.gallery.data.ScrollActivity.recentlyScrolling()) null
                else VideoFrames.previewFrames(context, image, targetSizePx.coerceIn(160, 320))
            }
            if (made != null) { VideoPreviewCache.put(key, made); frames = made.ifEmpty { null } }
        }
        val list = frames ?: return@LaunchedEffect
        if (!VideoPreviewCache.tryStartAnimating()) return@LaunchedEffect
        try {
            while (true) {
                kotlinx.coroutines.delay(1000)
                if (com.iris.gallery.data.ScrollActivity.recentlyScrolling()) continue
                index = if (index + 1 >= list.size) -1 else index + 1
            }
        } finally {
            VideoPreviewCache.stopAnimating()
        }
    }
    val list = frames
    if (list != null) {
        androidx.compose.animation.Crossfade(
            targetState = index,
            animationSpec = androidx.compose.animation.core.tween(380),
            label = "videoPreview"
        ) { n ->
            val bmp = list.getOrNull(n)
            if (bmp != null) {
                val shown = remember(bmp) { bmp.asImageBitmap() }
                Image(bitmap = shown, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0) / 1_000
    val hours = totalSeconds / 3_600
    val minutes = totalSeconds % 3_600 / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}