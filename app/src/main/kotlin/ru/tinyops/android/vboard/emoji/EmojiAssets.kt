package ru.tinyops.android.vboard.emoji

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import ru.tinyops.android.vboard.core.emoji.Emoji
import ru.tinyops.android.vboard.core.emoji.EmojiCatalog

/**
 * The emoji catalog and pictures bundled with the app. The pictures are fetched at build time
 * (`just emoji`); without them [picture] gives null and the picker draws the system glyphs.
 */
class EmojiAssets(
    private val assets: AssetManager,
) {
    val catalog: EmojiCatalog by lazy {
        val lines =
            runCatching { assets.open(CATALOG).bufferedReader(Charsets.UTF_8).readLines() }
                .getOrDefault(emptyList())
        EmojiCatalog.parse(lines.asSequence())
    }

    private val pictures = LruCache<String, Bitmap>(CACHE_SIZE)
    private val missing = HashSet<String>()

    /** The 64 px picture of [emoji], decoded once and kept while it is in use. */
    fun picture(emoji: Emoji): Bitmap? = picture(emoji.image)

    fun picture(image: String): Bitmap? {
        pictures.get(image)?.let { return it }
        if (image in missing) return null
        val bitmap =
            runCatching { assets.open("$PICTURES/$image.webp").use(BitmapFactory::decodeStream) }.getOrNull()
        if (bitmap == null) missing += image else pictures.put(image, bitmap)
        return bitmap
    }

    private companion object {
        const val CATALOG = "emoji/emoji.tsv"
        const val PICTURES = "emoji/img"

        // About 400 pictures of 16 KB: enough for several screens of the grid.
        const val CACHE_SIZE = 400
    }
}
