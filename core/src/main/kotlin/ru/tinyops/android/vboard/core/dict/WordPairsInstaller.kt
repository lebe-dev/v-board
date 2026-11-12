package ru.tinyops.android.vboard.core.dict

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** Where a release build downloads the Russian word pair table from, and what the unpacked table must be. */
data class WordPairsRelease(
    val url: String,
    val sha256: String,
    val size: Long,
) {
    /** A build without the table in its assets and without these values cannot offer it. */
    val configured: Boolean get() = url.isNotEmpty() && sha256.length == SHA256_HEX && size > 0

    /** Named after the hash, so a table of another app version is never taken for this one. */
    val fileName: String get() = "ru-${sha256.take(NAME_HASH)}.bigrams"

    private companion object {
        const val SHA256_HEX = 64
        const val NAME_HASH = 12
    }
}

/** The downloaded table is not the one this build expects. */
class WordPairsMismatch(
    message: String,
) : IOException(message)

/** The phone has no room for the unpacked table. */
class WordPairsNoSpace(
    val needed: Long,
) : IOException("not enough space for the word pair table: $needed bytes needed")

/** Unpacks a downloaded table and puts it in place only when it is exactly the expected one. */
object WordPairsInstaller {
    private const val BUFFER_BYTES = 64 * 1024

    /**
     * Unpacks the gzip [packed] into [directory] as [WordPairsRelease.fileName] and returns the file.
     * Nothing larger than the expected size is written, a table with another size or hash is
     * rejected, and the file appears only whole. Tables of other versions are deleted after a
     * successful install. [onProgress] gets the unpacked bytes so far and may throw to stop.
     */
    fun install(
        packed: InputStream,
        release: WordPairsRelease,
        directory: File,
        freeSpace: (File) -> Long = File::getUsableSpace,
        onProgress: (Long) -> Unit = {},
    ): File {
        directory.mkdirs()
        if (freeSpace(directory) < release.size) throw WordPairsNoSpace(release.size)
        val target = File(directory, release.fileName)
        val partial = File(directory, "${release.fileName}.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val written = unpack(packed, partial, release.size, digest, onProgress)
            verify(release, written, digest.digest().joinToString("") { "%02x".format(it) })
            if (!partial.renameTo(target)) throw IOException("could not move the word pair table into place")
        } finally {
            partial.delete()
        }
        directory
            .listFiles { file -> file.name.endsWith(".bigrams") && file.name != target.name }
            ?.forEach(File::delete)
        return target
    }

    private fun verify(
        release: WordPairsRelease,
        written: Long,
        hash: String,
    ) {
        if (written != release.size) {
            throw WordPairsMismatch("word pair table has $written bytes, expected ${release.size}")
        }
        if (hash != release.sha256) {
            throw WordPairsMismatch("word pair table hash $hash differs from the expected one")
        }
    }

    /** Returns the unpacked size, or the limit plus one when the stream goes past it. */
    private fun unpack(
        packed: InputStream,
        partial: File,
        limit: Long,
        digest: MessageDigest,
        onProgress: (Long) -> Unit,
    ): Long =
        GZIPInputStream(packed, BUFFER_BYTES).use { input ->
            partial.outputStream().use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                var written = 0L
                var read = input.read(buffer)
                while (read >= 0 && written + read <= limit) {
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    written += read
                    onProgress(written)
                    read = input.read(buffer)
                }
                output.fd.sync()
                if (read >= 0) limit + 1 else written
            }
        }
}
