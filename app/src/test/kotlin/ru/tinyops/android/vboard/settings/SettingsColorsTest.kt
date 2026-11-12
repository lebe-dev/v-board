package ru.tinyops.android.vboard.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.tinyops.android.vboard.ui.KeyboardThemes
import ru.tinyops.android.vboard.ui.ThemeMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsColorsTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun theme(id: String) = KeyboardThemes.find(id)

    @Test
    fun `the default themes keep the system colors of the badges`() {
        listOf(KeyboardThemes.CLASSIC, KeyboardThemes.DYNAMIC).forEach {
            assertNull(it, badgeColors(theme(it), context, dark = false))
        }
    }

    @Test
    fun `a fixed mode forces the settings mode`() {
        assertEquals(false, settingsMode(ThemeMode.LIGHT))
        assertEquals(true, settingsMode(ThemeMode.DARK))
        assertNull(settingsMode(ThemeMode.SYSTEM))
    }

    @Test
    fun `a color theme paints only the badges with the accent of the current variant`() {
        for (dark in listOf(false, true)) {
            val palette = theme("coral").palette(context, dark)
            val badge = checkNotNull(badgeColors(theme("coral"), context, dark))
            assertEquals(Color(palette.actionKey), badge.container)
            assertEquals(Color(palette.actionIcon), badge.content)
        }
    }

    @Test
    fun `badge colors are opaque`() {
        val badge = checkNotNull(badgeColors(theme("plum"), context, dark = true))
        assertEquals(1f, badge.container.alpha, 0f)
        assertEquals(1f, badge.content.alpha, 0f)
    }

    @Test
    fun `every themed badge keeps its icon readable`() {
        for (spec in KeyboardThemes.all) {
            for (dark in listOf(false, true)) {
                val badge = badgeColors(spec, context, dark) ?: continue
                val contrast = contrast(badge.content, badge.container)
                assertTrue("${spec.id}: $contrast", contrast >= 2f)
            }
        }
    }

    private fun contrast(
        a: Color,
        b: Color,
    ): Float {
        val hi = maxOf(a.luminance(), b.luminance())
        val lo = minOf(a.luminance(), b.luminance())
        return (hi + 0.05f) / (lo + 0.05f)
    }
}
