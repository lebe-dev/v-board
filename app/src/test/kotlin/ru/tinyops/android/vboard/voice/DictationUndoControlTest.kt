package ru.tinyops.android.vboard.voice

import android.view.inputmethod.EditorInfo
import android.widget.EditText
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.tinyops.android.vboard.R

/** The undo of a dictation against a real editor: what it offers, removes and refuses. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DictationUndoControlTest {
    private val edit = EditText(RuntimeEnvironment.getApplication())
    private val connection = edit.onCreateInputConnection(EditorInfo())
    private val offers = ArrayList<Boolean>()
    private val messages = ArrayList<Int>()
    private val control = DictationUndoControl({ connection }, { offers += it }, { messages += it })

    private fun dictate(vararg pieces: String) {
        control.onVoiceActive(true)
        for (piece in pieces) {
            connection.commitText(piece, 1)
            control.add(piece)
        }
        control.onVoiceActive(false)
    }

    @Test
    fun `the undo removes the whole dictation and nothing before it`() {
        edit.setText("Было.")
        edit.setSelection(edit.length())
        dictate(" Привет", " как дела")
        assertEquals(true, offers.last())
        control.onTap()
        assertEquals("Было.", edit.text.toString())
        assertEquals(false, offers.last())
        assertEquals(emptyList<Int>(), messages)
    }

    @Test
    fun `text typed after the dictation gives the undo up`() {
        dictate("Привет")
        connection.commitText("!", 1)
        control.onTap()
        assertEquals("Привет!", edit.text.toString())
        assertEquals(listOf(R.string.voice_undo_changed), messages)
        assertEquals(false, offers.last())
    }

    @Test
    fun `a cursor moved away from the dictated text takes the offer back`() {
        dictate("Привет")
        edit.setSelection(2)
        control.onSelectionChanged()
        assertEquals(false, offers.last())
    }

    @Test
    fun `typing during the dictation does not stop the record`() {
        control.onVoiceActive(true)
        control.reset()
        connection.commitText("текст", 1)
        control.add("текст")
        control.onVoiceActive(false)
        assertEquals(true, offers.last())
    }

    @Test
    fun `typing after the dictation ends the offer`() {
        dictate("Привет")
        control.reset()
        assertEquals(false, offers.last())
    }

    @Test
    fun `a dictation without text offers nothing`() {
        dictate()
        assertEquals(false, offers.last())
    }
}
