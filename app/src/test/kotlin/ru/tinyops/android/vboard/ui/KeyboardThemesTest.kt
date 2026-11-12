package ru.tinyops.android.vboard.ui

import android.graphics.Color
import androidx.core.graphics.ColorUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KeyboardThemesTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `the catalog has the default and colors groups`() {
        val counts = KeyboardThemes.all.groupingBy { it.group }.eachCount()
        assertEquals(mapOf(ThemeGroup.DEFAULT to 4, ThemeGroup.COLORS to 8), counts)
        assertEquals(
            KeyboardThemes.all.size,
            KeyboardThemes.all
                .map { it.id }
                .toSet()
                .size,
        )
    }

    @Test
    fun `the system theme is selected until another one is picked`() {
        assertEquals(KeyboardThemes.SYSTEM, KeyboardThemes.selected(context).id)
        KeyboardThemes.select(context, "plum")
        assertEquals("plum", KeyboardThemes.selected(context).id)
        KeyboardThemes.select(context, "no_such_theme")
        assertEquals(KeyboardThemes.SYSTEM, KeyboardThemes.selected(context).id)
    }

    @Test
    fun `fixed light and dark themes ignore the system mode`() {
        val light = KeyboardThemes.find(KeyboardThemes.LIGHT).palette(context)
        val dark = KeyboardThemes.find(KeyboardThemes.DARK).palette(context)
        assertEquals(0xFFE9EAEC.toInt(), light.background)
        assertEquals(0xFF161719.toInt(), dark.background)
    }

    @Test
    fun `labels contrast with keys in every theme`() {
        for (theme in KeyboardThemes.all) {
            val palette = theme.palette(context)
            for (key in listOf(palette.key, palette.functionKey)) {
                val contrast = ColorUtils.calculateContrast(palette.label, key)
                assertTrue("${theme.id}: label contrast $contrast", contrast >= MIN_CONTRAST)
                val hint = ColorUtils.calculateContrast(palette.hint, palette.key)
                assertTrue("${theme.id}: hint contrast $hint", hint >= MIN_CONTRAST)
            }
            val action = ColorUtils.calculateContrast(palette.actionIcon, palette.actionKey)
            assertTrue("${theme.id}: action contrast $action", action >= MIN_CONTRAST)
            for (color in listOf(palette.background, palette.key, palette.functionKey, palette.preview)) {
                assertEquals("${theme.id}: surfaces are opaque", MAX_ALPHA, Color.alpha(color))
            }
        }
    }

    private companion object {
        const val MIN_CONTRAST = 4.5
        const val MAX_ALPHA = 255
    }
}
