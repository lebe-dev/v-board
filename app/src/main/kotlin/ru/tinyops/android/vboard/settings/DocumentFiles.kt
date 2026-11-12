package ru.tinyops.android.vboard.settings

import android.content.Context
import android.net.Uri
import java.io.IOException

/** Files the user picks in the system file picker. Blocks on the disk: call off the main thread. */
internal object DocumentFiles {
    /**
     * Writes [text] as UTF-8 over what the file at [uri] held. The mode truncates the file: with a plain "w" some
     * providers keep the end of a longer file that was picked to be overwritten.
     */
    fun writeText(
        context: Context,
        uri: Uri,
        text: String,
    ) {
        val stream = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("No stream for $uri")
        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }
}
