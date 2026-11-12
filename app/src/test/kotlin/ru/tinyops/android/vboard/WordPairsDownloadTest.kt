package ru.tinyops.android.vboard

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.tinyops.android.vboard.core.dict.WordPairsRelease
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/** The download of the word pair table over HTTP, as GitHub serves release assets: through a redirect. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WordPairsDownloadTest {
    private val table = ByteArray(200_000) { (it * 7 % 253).toByte() }
    private val packed =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(table) } }.toByteArray()
    private val sha256 = MessageDigest.getInstance("SHA-256").digest(table).joinToString("") { "%02x".format(it) }

    private lateinit var server: HttpServer
    private lateinit var directory: File

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/download/ru.bigrams.gz") { exchange ->
            exchange.responseHeaders.add("Location", "${base()}/cdn/ru.bigrams.gz")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/cdn/ru.bigrams.gz") { exchange ->
            exchange.sendResponseHeaders(200, packed.size.toLong())
            exchange.responseBody.use { it.write(packed) }
        }
        server.start()
        directory = File(RuntimeEnvironment.getApplication().noBackupFilesDir, "word-pairs-test")
        directory.deleteRecursively()
    }

    @After
    fun tearDown() {
        WordPairsDownload.cancel()
        server.stop(0)
        directory.deleteRecursively()
    }

    private fun base() = "http://127.0.0.1:${server.address.port}"

    private fun release(
        path: String = "/download/ru.bigrams.gz",
        hash: String = sha256,
    ) = WordPairsRelease("${base()}$path", hash, table.size.toLong())

    private fun download(release: WordPairsRelease): WordPairsDownload.State {
        WordPairsDownload.start(RuntimeEnvironment.getApplication(), release, directory)
        val deadline = System.currentTimeMillis() + 10_000
        while (WordPairsDownload.state.value is WordPairsDownload.State.Running) {
            assertTrue("the download did not finish", System.currentTimeMillis() < deadline)
            Thread.sleep(10)
        }
        return WordPairsDownload.state.value
    }

    @Test
    fun `the table is downloaded through the redirect and unpacked`() {
        val release = release()

        assertEquals(WordPairsDownload.State.Idle, download(release))
        assertArrayEquals(table, File(directory, release.fileName).readBytes())
    }

    @Test
    fun `a missing asset is a failure and leaves nothing behind`() {
        assertEquals(WordPairsDownload.State.Failed(noSpace = false), download(release(path = "/download/missing.gz")))
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a table with another hash is a failure`() {
        val release = release(hash = "0".repeat(64))

        assertEquals(WordPairsDownload.State.Failed(noSpace = false), download(release))
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }
}
