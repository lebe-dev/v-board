package ru.tinyops.android.vboard.core.update

import kotlin.test.Test
import kotlin.test.assertEquals

class ReleaseNotesTest {
    @Test
    fun `markup is dropped and the bullets are kept`() {
        val notes =
            """
            - **Breaking:** export the settings before upgrading
            - New `vboard-settings` format, see [the FAQ](docs/FAQ.md)
            """.trimIndent()
        assertEquals(
            "• Breaking: export the settings before upgrading\n• New vboard-settings format, see the FAQ",
            ReleaseNotes.plain(notes),
        )
    }

    @Test
    fun `a wrapped bullet is joined into one line`() {
        val notes = "- The keyboard now remembers\n  the folded sections\n- Fixes"
        assertEquals("• The keyboard now remembers the folded sections\n• Fixes", ReleaseNotes.plain(notes))
    }

    @Test
    fun `the changelog link at the end is left out`() {
        val notes = "- Fixes\r\n\r\n**Full Changelog**: https://github.com/o/r/compare/v1...v2"
        assertEquals("• Fixes", ReleaseNotes.plain(notes))
    }
}
