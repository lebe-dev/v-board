package ru.tinyops.android.vboard.update

import android.content.Context
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest

/** The check against a GitHub-like API and the download of the APK through a redirect, as GitHub serves it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UpdateFlowTest {
    private val apk = ByteArray(150_000) { (it * 13 % 251).toByte() }
    private val sha256 = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private lateinit var server: HttpServer
    private var latest: String? = null
    private var list = "[]"
    private val installed = mutableListOf<ByteArray>()

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/releases") { exchange ->
            val body = list.toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.createContext("/releases/latest") { exchange ->
            val body = latest?.toByteArray()
            if (body == null) {
                exchange.sendResponseHeaders(404, -1)
            } else {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        }
        server.createContext("/download/vboard.apk") { exchange ->
            exchange.responseHeaders.add("Location", "${base()}/cdn/vboard.apk")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/cdn/vboard.apk") { exchange ->
            exchange.sendResponseHeaders(200, apk.size.toLong())
            exchange.responseBody.use { it.write(apk) }
        }
        server.start()
    }

    @After
    fun tearDown() {
        UpdateDownload.cancel()
        server.stop(0)
    }

    private fun base() = "http://127.0.0.1:${server.address.port}"

    private fun releases() = GithubReleases("${base()}/releases")

    private fun json(
        tag: String,
        prerelease: Boolean = false,
    ) = """
        {"tag_name": "$tag", "prerelease": $prerelease, "html_url": "${base()}/tag/$tag", "body": "",
         "assets": [{"name": "vboard.apk", "size": ${apk.size}, "digest": "sha256:$sha256",
                     "browser_download_url": "${base()}/download/vboard.apk"}]}
        """

    /** A stable release, the one GitHub calls the latest. */
    private fun publish(tag: String) {
        latest = json(tag)
        list = "[${json(tag)}]"
    }

    @Test
    fun `beta releases are offered only while they are on`() {
        publish("v1.0.0")
        list = "[${json("v1.1.0-beta", prerelease = true)}, ${json("v1.0.0")}]"
        val prefs = UpdatePrefs(context)
        assertFalse("off by default", prefs.betaReleases)

        assertNull(UpdateCheck.check(context, releases(), current = "1.0.0"))

        prefs.betaReleases = true
        assertEquals("1.1.0-beta", UpdateCheck.check(context, releases(), current = "1.0.0")?.version)
        assertEquals("1.1.0-beta", UpdateCheck.available(context, current = "1.0.0")?.version)

        prefs.betaReleases = false
        assertNull("a found beta goes away with the switch", UpdateCheck.available(context, current = "1.0.0"))
    }

    @Test
    fun `with beta releases on a newer stable release still wins`() {
        UpdatePrefs(context).betaReleases = true
        list = "[${json("v1.1.0-beta", prerelease = true)}, ${json("v1.1.0")}]"

        assertEquals("1.1.0", UpdateCheck.check(context, releases(), current = "1.0.0")?.version)
    }

    private fun release(hash: String = sha256) =
        AvailableRelease("1.1.0", "", "", ApkAsset("${base()}/download/vboard.apk", apk.size.toLong(), hash))

    private fun download(
        release: AvailableRelease,
        verified: Boolean = true,
    ): UpdateDownload.State {
        UpdateDownload.start(context, release, verify = { _, _ -> verified }, install = { _, file ->
            installed += file.readBytes()
        })
        val deadline = System.currentTimeMillis() + 10_000
        while (UpdateDownload.state.value is UpdateDownload.State.Running) {
            assertTrue("the download did not finish", System.currentTimeMillis() < deadline)
            Thread.sleep(10)
        }
        return UpdateDownload.state.value
    }

    @Test
    fun `a newer release is found and stored`() {
        publish("v1.1.0")

        assertEquals("1.1.0", UpdateCheck.check(context, releases(), current = "1.0.0")?.version)
        assertEquals("1.1.0", UpdateCheck.available(context, current = "1.0.0")?.version)
        assertTrue(UpdatePrefs(context).lastCheck > 0)
    }

    @Test
    fun `the same version is not offered`() {
        publish("v1.0.0")

        assertNull(UpdateCheck.check(context, releases(), current = "1.0.0"))
        assertNull(UpdateCheck.available(context, current = "1.0.0"))
    }

    @Test
    fun `a project without releases has nothing to offer`() {
        assertNull(UpdateCheck.check(context, releases(), current = "1.0.0"))
    }

    @Test
    fun `a closed banner stays closed only for its version`() {
        publish("v1.1.0")
        UpdateCheck.check(context, releases(), current = BUILD)
        UpdatePrefs(context).dismissedVersion = "1.1.0"
        assertNull(UpdateCheck.announced(context, BUILD))

        publish("v1.2.0")
        UpdateCheck.check(context, releases(), current = BUILD)
        assertEquals("1.2.0", UpdateCheck.announced(context, BUILD)?.version)
    }

    @Test
    fun `the APK is downloaded through the redirect and installed`() {
        assertEquals(UpdateDownload.State.Installing, download(release()))
        assertArrayEquals(apk, installed.single())
    }

    @Test
    fun `an APK with another hash is not installed`() {
        val state = download(release(hash = "0".repeat(64)))

        assertEquals(UpdateDownload.State.Failed(UpdateDownload.Failure.DAMAGED), state)
        assertTrue(installed.isEmpty())
    }

    @Test
    fun `an APK of another app or key is not installed`() {
        val state = download(release(), verified = false)

        assertEquals(UpdateDownload.State.Failed(UpdateDownload.Failure.FOREIGN), state)
        assertTrue(installed.isEmpty())
        assertTrue(File(context.cacheDir, "updates").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a missing APK is a network failure`() {
        val missing = release().let { it.copy(apk = it.apk!!.copy(url = "${base()}/download/missing.apk")) }

        assertEquals(UpdateDownload.State.Failed(UpdateDownload.Failure.NETWORK), download(missing))
    }

    private companion object {
        const val BUILD = "0.0.1"
    }
}
