package ru.tinyops.android.vboard.core.dict

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WordPairsInstallerTest {
    @TempDir
    lateinit var directory: File

    private val table = ByteArray(10_000) { (it * 31 % 251).toByte() }

    private fun gzip(bytes: ByteArray): ByteArrayInputStream {
        val packed = ByteArrayOutputStream()
        GZIPOutputStream(packed).use { it.write(bytes) }
        return ByteArrayInputStream(packed.toByteArray())
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun release(
        bytes: ByteArray = table,
        size: Long = bytes.size.toLong(),
    ) = WordPairsRelease("https://example.invalid/ru.bigrams.gz", sha256(bytes), size)

    private fun files() =
        directory
            .listFiles()
            .orEmpty()
            .map { it.name }
            .sorted()

    @Test
    fun `a matching table is unpacked under a name of its own`() {
        val release = release()
        var reported = 0L

        val file = WordPairsInstaller.install(gzip(table), release, directory) { reported = it }

        assertEquals(release.fileName, file.name)
        assertContentEquals(table, file.readBytes())
        assertEquals(table.size.toLong(), reported)
        assertEquals(listOf(release.fileName), files())
    }

    @Test
    fun `a table with another hash is rejected and leaves nothing behind`() {
        val release = release(bytes = table.copyOf().also { it[0] = 1 })

        assertThrows<WordPairsMismatch> { WordPairsInstaller.install(gzip(table), release, directory) }
        assertEquals(emptyList(), files())
    }

    @Test
    fun `a shorter table is rejected`() {
        val release = release(size = table.size + 1L)

        assertThrows<WordPairsMismatch> { WordPairsInstaller.install(gzip(table), release, directory) }
        assertEquals(emptyList(), files())
    }

    @Test
    fun `a longer table is cut off at the expected size`() {
        val release = release(size = table.size - 1L)

        assertThrows<WordPairsMismatch> { WordPairsInstaller.install(gzip(table), release, directory) }
        assertEquals(emptyList(), files())
    }

    @Test
    fun `a broken archive is an error, not a table`() {
        val broken = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))

        assertThrows<IOException> { WordPairsInstaller.install(broken, release(), directory) }
        assertEquals(emptyList(), files())
    }

    @Test
    fun `tables of other versions are deleted only after a successful install`() {
        File(directory, "ru-000000000000.bigrams").writeText("old")
        val release = release()

        assertThrows<WordPairsMismatch> {
            WordPairsInstaller.install(gzip(table), release.copy(size = 1), directory)
        }
        assertEquals(listOf("ru-000000000000.bigrams"), files())

        WordPairsInstaller.install(gzip(table), release, directory)
        assertEquals(listOf(release.fileName), files())
    }

    @Test
    fun `nothing is downloaded without room for the table`() {
        var read = false
        val stream =
            object : ByteArrayInputStream(byteArrayOf()) {
                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    read = true
                    return -1
                }
            }

        assertThrows<WordPairsNoSpace> {
            WordPairsInstaller.install(stream, release(), directory, freeSpace = { table.size - 1L })
        }
        assertFalse(read)
    }

    @Test
    fun `a release is usable only with an address, a full hash and a size`() {
        assertTrue(release().configured)
        assertFalse(WordPairsRelease("", sha256(table), 1).configured)
        assertFalse(WordPairsRelease("https://x", "abc", 1).configured)
        assertFalse(WordPairsRelease("https://x", sha256(table), 0).configured)
    }
}
