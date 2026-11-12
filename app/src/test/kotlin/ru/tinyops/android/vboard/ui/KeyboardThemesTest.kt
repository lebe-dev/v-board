package ru.tinyops.android.vboard.ui

import android.content.Context
import android.graphics.Color
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        assertEquals(mapOf(ThemeGroup.DEFAULT to 2, ThemeGroup.COLORS to 8), counts)
        assertEquals(
            KeyboardThemes.all.size,
            KeyboardThemes.all
                .map { it.id }
                .toSet()
                .size,
        )
    }

    @Test
    fun `the classic theme follows the system until another one is picked`() {
        assertEquals(KeyboardThemes.CLASSIC, KeyboardThemes.selected(context).spec.id)
        assertEquals(ThemeMode.SYSTEM, KeyboardThemes.selected(context).mode)
        KeyboardThemes.select(context, "plum")
        KeyboardThemes.selectMode(context, ThemeMode.DARK)
        assertEquals("plum", KeyboardThemes.selected(context).spec.id)
        assertEquals(ThemeMode.DARK, KeyboardThemes.selected(context).mode)
        KeyboardThemes.select(context, "no_such_theme")
        assertEquals(KeyboardThemes.CLASSIC, KeyboardThemes.selected(context).spec.id)
    }

    @Test
    fun `the light and dark ids select the classic theme in that mode`() {
        val prefs = context.getSharedPreferences("keyboard", Context.MODE_PRIVATE)
        prefs.edit(commit = true) { putString("theme", "dark") }
        val classicDark = ThemeChoice(KeyboardThemes.find(KeyboardThemes.CLASSIC), ThemeMode.DARK)
        assertEquals(classicDark, KeyboardThemes.selected(context))
        prefs.edit(commit = true) { putString("theme", "light") }
        assertEquals(ThemeMode.LIGHT, KeyboardThemes.selected(context).mode)
    }

    @Test
    fun `a fixed mode ignores the system one`() {
        val classic = KeyboardThemes.find(KeyboardThemes.CLASSIC)
        assertEquals(0xFFE9EAEC.toInt(), ThemeChoice(classic, ThemeMode.LIGHT).palette(context).background)
        assertEquals(0xFF161719.toInt(), ThemeChoice(classic, ThemeMode.DARK).palette(context).background)
    }

    @Test
    fun `every theme has distinct light and dark variants`() {
        for (theme in KeyboardThemes.all) {
            val light = theme.palette(context, night = false).background
            val dark = theme.palette(context, night = true).background
            assertNotEquals(theme.id, light, dark)
            assertTrue("${theme.id}: light", ColorUtils.calculateLuminance(light) > ColorUtils.calculateLuminance(dark))
        }
    }

    @Test
    fun `labels contrast with keys in every theme`() {
        for (theme in KeyboardThemes.all) {
            for (night in listOf(false, true)) {
                val palette = theme.palette(context, night)
                val name = "${theme.id}/${if (night) "dark" else "light"}"
                for (key in listOf(palette.key, palette.functionKey)) {
                    val contrast = ColorUtils.calculateContrast(palette.label, key)
                    assertTrue("$name: label contrast $contrast", contrast >= MIN_CONTRAST)
                }
                val hint = ColorUtils.calculateContrast(palette.hint, palette.key)
                assertTrue("$name: hint contrast $hint", hint >= MIN_CONTRAST)
                val action = ColorUtils.calculateContrast(palette.actionIcon, palette.actionKey)
                assertTrue("$name: action contrast $action", action >= MIN_CONTRAST)
                for (color in listOf(palette.background, palette.key, palette.functionKey, palette.preview)) {
                    assertEquals("$name: surfaces are opaque", MAX_ALPHA, Color.alpha(color))
                }
            }
        }
    }

    private companion object {
        const val MIN_CONTRAST = 4.5
        const val MAX_ALPHA = 255
    }
}
