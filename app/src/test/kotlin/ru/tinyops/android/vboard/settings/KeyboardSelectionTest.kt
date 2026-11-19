package ru.tinyops.android.vboard.settings

import android.provider.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KeyboardSelectionTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun select(id: String?) {
        Settings.Secure.putString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, id)
    }

    @Test
    fun `the keyboard of this app is recognized in short and full form`() {
        select("${context.packageName}/.KeyboardService")
        assertTrue(KeyboardSelection.isOwnSelected(context))
        select("${context.packageName}/ru.tinyops.android.vboard.KeyboardService")
        assertTrue(KeyboardSelection.isOwnSelected(context))
    }

    @Test
    fun `another keyboard or none is not ours`() {
        select("com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME")
        assertFalse(KeyboardSelection.isOwnSelected(context))
        select(null)
        assertFalse(KeyboardSelection.isOwnSelected(context))
    }
}
