package ru.tinyops.android.vboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A copy leaves the phone only from a text or number field that is neither a password nor private. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EditorProfileClipboardSyncTest {
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
            ).clipboardSyncAllowed

    @Test
    fun `text and number fields allow it`() {
        assertTrue(allowed(InputType.TYPE_CLASS_TEXT))
        assertTrue(allowed(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI))
        assertTrue(allowed(InputType.TYPE_CLASS_NUMBER))
    }

    @Test
    fun `password fields do not`() {
        assertFalse(allowed(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertFalse(allowed(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertFalse(allowed(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertFalse(allowed(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
    }

    @Test
    fun `private fields, other fields and no field do not`() {
        assertFalse(allowed(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
        assertFalse(allowed(InputType.TYPE_NULL))
        assertFalse(allowed(InputType.TYPE_CLASS_PHONE))
        assertFalse(EditorProfile.from(null).clipboardSyncAllowed)
    }
}
