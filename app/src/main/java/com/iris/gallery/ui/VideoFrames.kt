package com.iris.gallery.ui

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import com.iris.gallery.data.MediaImage

/**
 * Video frames for the grid: a poster that is never a black fade-in frame, plus a few small frames for the
 * multi-shot preview. One MediaMetadataRetriever session per video; nothing here touches the UI thread.
 */
object VideoFrames {
    private fun open(context: Context, image: MediaImage): MediaMetadataRetriever? {
        val mmr = MediaMetadataRetriever()
        return try {
            if (image.path.isNotBlank() && java.io.File(image.path).exists()) mmr.setDataSource(image.path)
            else mmr.setDataSource(context, image.uri)
            mmr
        } catch (_: Exception) { runCatching { mmr.release() }; null }
    }

    /** True for a (nearly) black frame: mean brightness of a 6x6 shrink below ~5 %. */
    fun isDark(b: Bitmap): Boolean = runCatching {
        val s = Bitmap.createScaledBitmap(b, 6, 6, true)
        var sum = 0L
        for (y in 0 until 6) for (x in 0 until 6) {
            val p = s.getPixel(x, y)
            sum += ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
        if (s !== b) s.recycle()
        sum / 36 < 13
    }.getOrDefault(false)

    private fun frameAt(mmr: MediaMetadataRetriever, timeUs: Long, w: Int, h: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            mmr.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h)
        } else {
            mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { Bitmap.createScaledBitmap(it, w, h, true) }
        }
    } catch (_: Exception) { null }

    /** Output size that keeps the video's aspect ratio (the scaled-frame API does not) with the long side = [size]. */
    private fun fitSize(mmr: MediaMetadataRetriever, size: Int): Pair<Int, Int> {
        fun meta(k: Int) = mmr.extractMetadata(k)?.toIntOrNull() ?: 0
        var w = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        var h = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        val rot = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
        if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
        if (w <= 0 || h <= 0) return size to size
        val k = size.toFloat() / maxOf(w, h)
        return maxOf(1, (w * k).toInt()) to maxOf(1, (h * k).toInt())
    }

    private fun durationMs(mmr: MediaMetadataRetriever, image: MediaImage): Long =
        mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 } ?: image.durationMs

    /** First clearly visible frame among 10 %, 30 % and 60 % of the video (the first frame is often black). */
    fun poster(context: Context, image: MediaImage, size: Int): Bitmap? {
        val mmr = open(context, image) ?: return null
        return try {
            val (w, h) = fitSize(mmr, size)
            val d = durationMs(mmr, image)
            val fractions = if (d > 1500) floatArrayOf(0.1f, 0.3f, 0.6f) else floatArrayOf(0f)
            var fallback: Bitmap? = null
            for (f in fractions) {
                val b = frameAt(mmr, (d * f * 1000f).toLong(), w, h) ?: continue
                if (!isDark(b)) return b
                if (fallback == null) fallback = b
            }
            fallback
        } finally { runCatching { mmr.release() } }
    }
}
