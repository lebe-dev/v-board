package ru.tinyops.android.vboard

import android.content.Context
import ru.tinyops.android.vboard.core.dict.BigramTable
import ru.tinyops.android.vboard.core.dict.WordPairsRelease
import ru.tinyops.android.vboard.core.layout.Language
import java.io.File
import java.io.FileInputStream
import java.nio.channels.FileChannel

/**
 * The Russian word pair table of the experimental slide to type engine. A debug build carries it in the
 * assets; a release build downloads it from the GitHub release of its version into the app's storage.
 */
internal object WordPairs {
    private const val DIRECTORY = "word-pairs"

    val language: Language = Language.RUSSIAN

    val release =
        WordPairsRelease(
            url = BuildConfig.WORD_PAIRS_URL,
            sha256 = BuildConfig.WORD_PAIRS_SHA256,
            size = BuildConfig.WORD_PAIRS_SIZE,
        )

    @Volatile private var bundled: Boolean? = null

    /** Whether the table is among the assets of this build. */
    fun isBundled(context: Context): Boolean =
        bundled ?: ("${language.code}.bigrams" in context.assets.list("dict").orEmpty()).also { bundled = it }

    /** Whether this build can download the table: it has none of its own and knows which one to fetch. */
    fun isDownloadable(context: Context): Boolean = !isBundled(context) && release.configured

    fun directory(context: Context): File = File(context.noBackupFilesDir, DIRECTORY)

    /** The downloaded table of this version, or null when there is none. */
    fun installed(context: Context): File? =
        File(directory(context), release.fileName).takeIf { release.configured && it.length() == release.size }

    /**
     * Whether the user has downloaded the table of another version: after an update the app fetches its own
     * one in its place. A user who never downloaded the table gets nothing.
     */
    fun needsRefresh(
        release: WordPairsRelease = this.release,
        directory: File,
    ): Boolean {
        if (!release.configured) return false
        val current = File(directory, release.fileName)
        if (current.length() == release.size) return false
        return directory.listFiles().orEmpty().any { it.name.endsWith(".bigrams") && it != current }
    }

    fun isAvailable(
        context: Context,
        language: Language,
    ): Boolean = language == this.language && (isBundled(context) || installed(context) != null)

    /** Opens the table of [language], or returns null when there is none. Blocks: call off the main thread. */
    fun open(
        context: Context,
        language: Language,
    ): BigramTable? {
        if (language != this.language) return null
        if (isBundled(context)) return DictionaryAssets.openBigrams(context.assets, language)
        val file = installed(context) ?: return null
        val buffer =
            FileInputStream(file).use { stream ->
                stream.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
            }
        return BigramTable.read(buffer)
    }

    /** Deletes the downloaded table; a keyboard that has it open keeps reading it until it lets it go. */
    fun delete(context: Context) {
        directory(context).listFiles()?.forEach(File::delete)
    }
}
