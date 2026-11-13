package ru.tinyops.android.vboard.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppVersionTest {
    private fun v(text: String) = requireNotNull(AppVersion.parse(text)) { "not a version: $text" }

    @Test
    fun `a tag with the v prefix is the same version as the name`() {
        assertEquals(v("1.2.3"), v("v1.2.3"))
    }

    @Test
    fun `numbers compare as numbers, not as text`() {
        assertTrue(v("1.10.0") > v("1.9.0"))
        assertTrue(v("2.0.0") > v("1.99.99"))
        assertTrue(v("1.0.1") > v("1.0.0"))
    }

    @Test
    fun `a release is newer than its pre-release`() {
        assertTrue(v("1.0.0") > v("1.0.0-beta"))
        assertTrue(v("1.0.1-beta") > v("1.0.0"))
    }

    @Test
    fun `pre-releases compare by their parts`() {
        assertTrue(v("1.0.0-beta.2") > v("1.0.0-beta"))
        assertTrue(v("1.0.0-beta.10") > v("1.0.0-beta.9"))
        assertTrue(v("1.0.0-beta") > v("1.0.0-alpha"))
        assertTrue(v("1.0.0-rc.1") > v("1.0.0-beta.5"))
    }

    @Test
    fun `build metadata does not change the version`() {
        assertEquals(0, v("1.0.0+abc").compareTo(v("1.0.0")))
    }

    @Test
    fun `missing minor and patch numbers are zeros`() {
        assertEquals(0, v("1.2").compareTo(v("1.2.0")))
    }

    @Test
    fun `anything else is not a version`() {
        assertNull(AppVersion.parse(""))
        assertNull(AppVersion.parse("latest"))
        assertNull(AppVersion.parse("v1.x.0"))
        assertNull(AppVersion.parse("1.0.0-"))
    }

    @Test
    fun `the text is kept without the prefix`() {
        assertEquals("1.0.0-beta", v("v1.0.0-beta").toString())
    }
}
