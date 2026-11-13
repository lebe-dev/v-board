package ru.tinyops.android.vboard.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** The answer of `GET /repos/{owner}/{repo}/releases/latest`, trimmed to the fields the app reads. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GithubReleasesTest {
    private fun release(
        tag: String = "v1.2.0",
        prerelease: Boolean = false,
        assets: String = APK,
    ) = """
        {"tag_name": "$tag", "draft": false, "prerelease": $prerelease,
         "html_url": "https://github.com/lebe-dev/v-board/releases/tag/$tag",
         "body": "- Fixes\r\n", "assets": [$assets]}
        """

    @Test
    fun `the release and its APK are read`() {
        val release = GithubReleases.parse(release())!!

        assertEquals("1.2.0", release.version)
        assertEquals("- Fixes", release.notes)
        assertEquals("https://github.com/lebe-dev/v-board/releases/tag/v1.2.0", release.pageUrl)
        assertEquals(ApkAsset("https://example.org/vboard-1.2.0.apk", 42, "ab".repeat(32)), release.apk)
    }

    @Test
    fun `the APK is found among the other assets`() {
        val release = GithubReleases.parse(release(assets = "$TABLE, $APK"))!!

        assertEquals("https://example.org/vboard-1.2.0.apk", release.apk?.url)
    }

    @Test
    fun `a release without an APK has none to install`() {
        assertNull(GithubReleases.parse(release(assets = TABLE))!!.apk)
    }

    @Test
    fun `an asset without a digest is still offered`() {
        val apk = """{"name": "a.apk", "size": 1, "browser_download_url": "https://example.org/a.apk"}"""

        assertEquals("", GithubReleases.parse(release(assets = apk))!!.apk?.sha256)
    }

    @Test
    fun `pre-releases and tags that are not versions are not offered`() {
        assertNull(GithubReleases.parse(release(prerelease = true)))
        assertNull(GithubReleases.parse(release(tag = "v1.3.0-beta")))
        assertNull(GithubReleases.parse(release(tag = "nightly")))
    }

    @Test(expected = IOException::class)
    fun `a broken answer is a failure`() {
        GithubReleases.parse("<html>")
    }

    @Test
    fun `a release is offered only over an older version`() {
        val release = GithubReleases.parse(release())!!

        assertTrue(release.isNewerThan("1.0.0-beta"))
        assertTrue(release.isNewerThan("1.1.9"))
        assertFalse(release.isNewerThan("1.2.0"))
        assertFalse(release.isNewerThan("1.3.0"))
    }

    @Test
    fun `a stored release reads back the same`() {
        val release = GithubReleases.parse(release())!!

        assertEquals(release, AvailableRelease.decode(release.encode()))
        assertEquals(release.copy(apk = null), AvailableRelease.decode(release.copy(apk = null).encode()))
    }

    private companion object {
        const val APK =
            """{"name": "vboard-1.2.0.apk", "size": 42, "digest": "sha256:ABABABABABABABABABABABABABABABABABABABABABABABABABABABABABABABAB",
                "browser_download_url": "https://example.org/vboard-1.2.0.apk"}"""
        const val TABLE =
            """{"name": "ru.bigrams.gz", "size": 7, "browser_download_url": "https://example.org/ru.bigrams.gz"}"""
    }
}
