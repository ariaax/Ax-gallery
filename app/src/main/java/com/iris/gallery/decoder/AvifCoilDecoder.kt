package com.iris.gallery.decoder

import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import org.aomedia.avif.android.AvifDecoder
import java.nio.ByteBuffer

class AvifCoilDecoder(
    private val source: ImageSource,
    private val options: Options
) : Decoder {

    override suspend fun decode(): DecodeResult? {
        // Never let a single source allocate an unbounded heap array + direct buffer. AVIF decoding
        // currently requires a full-resolution bitmap, so very large files are delegated to another decoder.
        val buffered = source.source()
        if (!buffered.request(MAX_INPUT_BYTES + 1L) && buffered.buffer.size == 0L) return null
        if (buffered.buffer.size > MAX_INPUT_BYTES) return null
        val bytes = buffered.readByteArray()
        if (bytes.isEmpty() || bytes.size.toLong() > MAX_INPUT_BYTES) return null

        val byteBuffer = ByteBuffer.allocateDirect(bytes.size).apply {
            put(bytes)
            flip()
        }

        if (!AvifDecoder.isAvifImage(byteBuffer)) {
            return null
        }

        val info = AvifDecoder.Info()
        if (!AvifDecoder.getInfo(byteBuffer, bytes.size, info)) return null
        if (info.width <= 0 || info.height <= 0 ||
            info.width.toLong() * info.height.toLong() > MAX_DECODE_PIXELS) return null

        val bitmap = Bitmap.createBitmap(info.width, info.height, Bitmap.Config.ARGB_8888)
        if (!AvifDecoder.decode(byteBuffer, bytes.size, bitmap)) {
            bitmap.recycle()
            return null
        }

        return DecodeResult(
            image = bitmap.asImage(),
            isSampled = false,
        )
    }

    private companion object {
        const val MAX_INPUT_BYTES = 16L * 1024L * 1024L
        const val MAX_DECODE_PIXELS = 8_000_000L
    }

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader
        ): Decoder? {
            val mimeType = result.mimeType
            if (mimeType != null && mimeType.equals("image/avif", ignoreCase = true)) {
                return AvifCoilDecoder(result.source, options)
            }
            val source = result.source.source()
            if (source.request(12)) {
                val header = source.buffer.peek().readByteString(12)
                if (header.size >= 12 && header.substring(4, 8).utf8() == "ftyp") {
                    val brand = header.substring(8, 12).utf8()
                    if (brand == "avif" || brand == "avis" || brand == "mif1") {
                        return AvifCoilDecoder(result.source, options)
                    }
                }
            }
            return null
        }
    }
}
