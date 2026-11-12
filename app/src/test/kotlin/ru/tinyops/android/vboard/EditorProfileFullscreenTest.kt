package ru.tinyops.android.vboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EditorProfileFullscreenTest {
    private fun allowed(
        inputType: Int,
        imeOptions: Int = 0,
    ): Boolean =
        EditorProfile
            .from(
                EditorInfo().apply {
                    this.inputType = inputType
                    this.imeOptions = imeOptions
                },
            ).fullscreenAllowed

    @Test
    fun `text fields allow the fullscreen editor`() {
        assertTrue(allowed(InputType.TYPE_CLASS_TEXT))
        assertTrue(allowed(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
    }

    @Test
    fun `passwords and numbers do not`() {
        assertFalse(allowed(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertFalse(allowed(InputType.TYPE_CLASS_NUMBER))
        assertFalse(allowed(InputType.TYPE_NULL))
    }

    @Test
    fun `the flags of a field do not stop the mode the user asks for`() {
        assertTrue(allowed(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_EXTRACT_UI))
        assertTrue(allowed(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_FULLSCREEN))
    }
}
