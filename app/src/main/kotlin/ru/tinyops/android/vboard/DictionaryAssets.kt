package ru.tinyops.android.vboard

import android.content.res.AssetManager
import ru.tinyops.android.vboard.core.dict.BigramTable
import ru.tinyops.android.vboard.core.dict.Dictionary
import ru.tinyops.android.vboard.core.layout.Language
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Opens the dictionary images packed as uncompressed assets. Blocks: call off the main thread. */
internal object DictionaryAssets {
    fun open(
        assets: AssetManager,
        language: Language,
    ): Dictionary {
        val path = "dict/${language.code}.dict"
        return Dictionary.read(
            mapped(assets, path, preload = true) ?: ByteBuffer.wrap(assets.open(path).use { it.readBytes() }),
        )
    }

    /**
     * Opens the word pair table of [language] packed in a debug build, or returns null when the build
     * has none. Tens of megabytes, so its pages are not brought in: a lookup reads only a few of them.
     */
    fun openBigrams(
        assets: AssetManager,
        language: Language,
    ): BigramTable? {
        val buffer = mapped(assets, "dict/${language.code}.bigrams", preload = false) ?: return null
        return BigramTable.read(buffer)
    }

    /**
     * Maps the asset straight from the APK, or returns null when the asset has no file of its own.
     * The word text of a dictionary is read on the main thread at every key press, so with [preload]
     * its pages are brought into memory here, on the caller's thread.
     */
    private fun mapped(
        assets: AssetManager,
        path: String,
        preload: Boolean,
    ): ByteBuffer? {
        val buffer =
            try {
                assets.openFd(path).use { descriptor ->
                    descriptor.createInputStream().use { stream ->
                        stream.channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.length)
                    }
                }
            } catch (_: FileNotFoundException) {
                return null
            }
        return if (preload) buffer.load() else buffer
    }
}
