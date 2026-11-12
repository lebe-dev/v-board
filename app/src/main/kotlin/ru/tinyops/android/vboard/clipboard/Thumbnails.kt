package ru.tinyops.android.vboard.clipboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri

/** Small previews of clipboard images. Decoding reads the file: call it off the main thread. */
object Thumbnails {
    /** The image at [uri] reduced so that its longer side is about [maxSide] pixels, or null when it cannot be read. */
    fun load(
        context: Context,
        uri: Uri,
        maxSide: Int,
    ): Bitmap? =
        runCatching {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val longest = maxOf(info.size.width, info.size.height)
                decoder.setTargetSampleSize((longest / maxSide).coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull()
}
