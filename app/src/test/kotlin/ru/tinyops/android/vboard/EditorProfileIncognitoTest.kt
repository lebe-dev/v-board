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
class EditorProfileIncognitoTest {
    private fun incognito(imeOptions: Int): Boolean =
        EditorProfile
            .from(
                EditorInfo().apply {
                    inputType = InputType.TYPE_CLASS_TEXT
                    this.imeOptions = imeOptions
                },
            ).incognito

    @Test
    fun `a field that asks for no personalized learning is incognito`() {
        assertTrue(incognito(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
        assertTrue(incognito(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_GO))
    }

    @Test
    fun `other fields are not`() {
        assertFalse(incognito(0))
        assertFalse(incognito(EditorInfo.IME_FLAG_NO_EXTRACT_UI))
        assertFalse(EditorProfile.from(null).incognito)
    }
}
