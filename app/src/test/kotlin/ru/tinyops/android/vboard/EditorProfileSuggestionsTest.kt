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
class EditorProfileSuggestionsTest {
    private fun allowed(
        inputType: Int,
        always: Boolean,
    ): Boolean =
        EditorProfile
            .from(EditorInfo().apply { this.inputType = inputType }, suggestAlways = always)
            .suggestionsAllowed

    private val noSuggestions = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

    @Test
    fun `a field that asks for no suggestions gets none by default`() {
        assertFalse(allowed(noSuggestions, always = false))
        assertTrue(allowed(InputType.TYPE_CLASS_TEXT, always = false))
    }

    @Test
    fun `suggesting always overrides the request of a field`() {
        assertTrue(allowed(noSuggestions, always = true))
    }

    @Test
    fun `passwords and numbers get none either way`() {
        assertFalse(allowed(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, always = true))
        assertFalse(allowed(InputType.TYPE_CLASS_NUMBER, always = true))
    }
}
