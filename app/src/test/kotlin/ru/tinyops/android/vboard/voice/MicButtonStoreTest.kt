package ru.tinyops.android.vboard.voice

import android.content.Context
import androidx.core.content.edit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.tinyops.android.vboard.core.voice.MicButton
import ru.tinyops.android.vboard.core.voice.MicEnding
import ru.tinyops.android.vboard.core.voice.MicMode
import ru.tinyops.android.vboard.core.voice.MicProcessing

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MicButtonStoreTest {
    private val context = RuntimeEnvironment.getApplication()
    private val raw = context.getSharedPreferences("mic-button-test", Context.MODE_PRIVATE)
    private val store = MicButtonStore(raw)

    @Test
    fun `nothing stored gives the default button`() {
        assertEquals(MicButton.DEFAULT, store.button)
    }

    @Test
    fun `the button survives storage with every field`() {
        val button =
            MicButton(
                tap = MicMode(MicButton.TAP_ID, MicProcessing.NONE, ending = MicEnding.REPEAT_TAP),
                hold =
                    listOf(
                        MicMode("a", MicProcessing.PROMPTS, listOf("p1", "p2"), MicEnding.AFTER_PAUSE),
                        MicMode("b", MicProcessing.ENHANCE),
                    ),
            )
        store.button = button
        assertEquals(button, MicButtonStore(raw).button)
    }

    @Test
    fun `an empty hold menu stays empty`() {
        store.button = MicButton(hold = emptyList())
        assertEquals(emptyList<MicMode>(), MicButtonStore(raw).button.hold)
    }

    @Test
    fun `a broken record gives the default button`() {
        raw.edit { putString(MicButtonStore.KEY, "{not json") }
        assertEquals(MicButton.DEFAULT, store.button)
    }

    @Test
    fun `unknown values fall back to the defaults and modes without an id are skipped`() {
        raw.edit {
            putString(
                MicButtonStore.KEY,
                """{"tap":{"id":"tap","processing":"future"},"hold":[{"processing":"none"},{"id":"x","ending":"?"}]}""",
            )
        }
        val button = store.button
        assertEquals(MicProcessing.DEFAULT, button.tap.processing)
        assertEquals(listOf(MicMode("x")), button.hold)
    }

    @Test
    fun `a new hold mode gets an id of its own`() {
        val first = store.newHoldMode()
        val second = store.newHoldMode()
        assertNotEquals(first.id, second.id)
        assertNotEquals(MicButton.TAP_ID, first.id)
    }
}
