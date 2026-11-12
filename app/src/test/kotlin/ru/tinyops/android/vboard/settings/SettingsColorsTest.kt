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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsColorsTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun theme(id: String) = KeyboardThemes.find(id)

    @Test
    fun `the default themes keep the system colors of the badges`() {
        listOf(KeyboardThemes.SYSTEM, KeyboardThemes.DYNAMIC, KeyboardThemes.LIGHT, KeyboardThemes.DARK).forEach {
            assertNull(it, badgeColors(theme(it), context))
        }
    }

    @Test
    fun `the fixed light and dark themes force the mode`() {
        assertEquals(false, settingsMode(theme(KeyboardThemes.LIGHT)))
        assertEquals(true, settingsMode(theme(KeyboardThemes.DARK)))
        assertNull(settingsMode(theme(KeyboardThemes.SYSTEM)))
        assertNull(settingsMode(theme(KeyboardThemes.DYNAMIC)))
    }

    @Test
    fun `a color theme paints only the badges with its accent`() {
        val palette = theme("color_teal").palette(context)
        val badge = checkNotNull(badgeColors(theme("color_teal"), context))
        assertEquals(Color(palette.actionKey), badge.container)
        assertEquals(Color(palette.actionIcon), badge.content)
    }

    @Test
    fun `badge colors are opaque`() {
        val badge = checkNotNull(badgeColors(theme("gradient_purple"), context))
        assertEquals(1f, badge.container.alpha, 0f)
        assertEquals(1f, badge.content.alpha, 0f)
    }

    @Test
    fun `every themed badge keeps its icon readable`() {
        KeyboardThemes.all.forEach { spec ->
            val badge = badgeColors(spec, context) ?: return@forEach
            val contrast = contrast(badge.content, badge.container)
            assertTrue("${spec.id}: $contrast", contrast >= 2f)
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
