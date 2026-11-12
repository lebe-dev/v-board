package ru.tinyops.android.vboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.tinyops.android.vboard.ui.KeyboardThemes
import ru.tinyops.android.vboard.ui.KeyboardView
import ru.tinyops.android.vboard.ui.ThemeMode
import java.io.File
import kotlin.math.abs

/** Renders the keyboard in the picked themes into `app/build/screenshots/theme-*.png`. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-night-420dpi")
class KeyboardThemeRenderTest {
    private val service = Robolectric.buildService(KeyboardService::class.java).create().get()

    /** Shows the keyboard as the framework does: the input view first, then the editor. */
    private fun startInput(): ViewGroup {
        if (findKeyboardRoot(decor()) == null) service.setInputView(service.onCreateInputView())
        service.onStartInputView(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, false)
        return requireNotNull(findKeyboardRoot(decor()))
    }

    private fun decor(): View = service.window.window!!.decorView

    /** The input view: the vertical layout holding the strip and the keys. */
    private fun findKeyboardRoot(view: View): ViewGroup? {
        if (view !is ViewGroup) return null
        if (view.childCount == 2 && view.getChildAt(1) is KeyboardView) return view
        return (0 until view.childCount).firstNotNullOfOrNull { findKeyboardRoot(view.getChildAt(it)) }
    }

    private fun render(
        root: View,
        name: String,
    ): Bitmap {
        val width =
            RuntimeEnvironment
                .getApplication()
                .resources.displayMetrics.widthPixels
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    @Test
    fun `a theme picked in the settings applies when the keyboard shows up again`() {
        val before = render(startInput(), "theme-system-dark")
        assertEquals(service.getColor(R.color.kb_background), before.getPixel(2, 2))

        KeyboardThemes.select(service, "stripe")
        val bitmap = render(startInput(), "theme-stripe")
        val palette = KeyboardThemes.find("stripe").palette(service, night = true)
        assertNear(palette.background, bitmap.getPixel(2, 0))
        assertNear(palette.background, bitmap.getPixel(2, bitmap.height - 1))
        assertNotEquals(before.getPixel(2, 0), bitmap.getPixel(2, 0))
    }

    @Test
    fun `every theme renders`() {
        for (theme in KeyboardThemes.all) {
            for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                KeyboardThemes.select(service, theme.id)
                KeyboardThemes.selectMode(service, mode)
                val bitmap = render(startInput(), "theme-${theme.id}-${mode.id}")
                assertNear(theme.palette(service, mode == ThemeMode.DARK).background, bitmap.getPixel(2, 0))
            }
        }
    }

    private fun assertNear(
        expected: Int,
        actual: Int,
    ) {
        val delta = listOf(16, 8, 0).maxOf { abs((expected shr it and 0xFF) - (actual shr it and 0xFF)) }
        assertTrue("expected #%08X, got #%08X".format(expected, actual), delta <= TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 6
    }
}
