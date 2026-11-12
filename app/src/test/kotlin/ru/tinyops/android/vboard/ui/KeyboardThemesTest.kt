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
    fun `the catalog has the default, colors and dark gradient groups`() {
        val counts = KeyboardThemes.all.groupingBy { it.group }.eachCount()
        assertEquals(mapOf(ThemeGroup.DEFAULT to 4, ThemeGroup.COLORS to 9, ThemeGroup.DARK_GRADIENT to 9), counts)
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
        KeyboardThemes.select(context, "gradient_blue")
        assertEquals("gradient_blue", KeyboardThemes.selected(context).id)
        KeyboardThemes.select(context, "no_such_theme")
        assertEquals(KeyboardThemes.SYSTEM, KeyboardThemes.selected(context).id)
    }

    @Test
    fun `fixed light and dark themes ignore the system mode`() {
        val light = KeyboardThemes.find(KeyboardThemes.LIGHT).palette(context)
        val dark = KeyboardThemes.find(KeyboardThemes.DARK).palette(context)
        assertEquals(0xFFE8EAED.toInt(), light.backgroundTop)
        assertEquals(0xFF1B1B1B.toInt(), dark.backgroundTop)
    }

    @Test
    fun `labels contrast with keys in every theme`() {
        for (theme in KeyboardThemes.all) {
            val palette = theme.palette(context)
            for (background in listOf(palette.backgroundTop, palette.backgroundBottom)) {
                val key = ColorUtils.compositeColors(palette.key, background)
                val label = ColorUtils.compositeColors(palette.label, key)
                val contrast = ColorUtils.calculateContrast(label, key)
                assertTrue("${theme.id}: contrast $contrast", contrast >= MIN_CONTRAST)
            }
            assertEquals("${theme.id}: preview is opaque", MAX_ALPHA, Color.alpha(palette.preview))
        }
    }

    private companion object {
        const val MIN_CONTRAST = 3.0
        const val MAX_ALPHA = 255
    }
}
