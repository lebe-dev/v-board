package ru.tinyops.android.vboard.voice

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.tinyops.android.vboard.core.voice.ButtonAction
import ru.tinyops.android.vboard.core.voice.ButtonCommand
import ru.tinyops.android.vboard.core.voice.ButtonVisibility
import ru.tinyops.android.vboard.core.voice.CustomButton
import ru.tinyops.android.vboard.core.voice.InsertMode
import ru.tinyops.android.vboard.ui.ButtonGroupPopup
import ru.tinyops.android.vboard.ui.KeyboardTheme
import ru.tinyops.android.vboard.ui.StripButton

/** A quick replies button on a real editor: the list it offers, and the undo after a reply went in. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QuickRepliesControlTest {
    private val context = RuntimeEnvironment.getApplication()
    private val prefs = VoicePrefs(context)
    private val edit = EditText(context)
    private val connection = edit.onCreateInputConnection(EditorInfo())
    private var strip: List<StripButton> = emptyList()

    private val control =
        CustomButtonsControl(
            context = context,
            connection = { connection },
            onButtons = { buttons, _ -> strip = buttons },
            onPanelButtons = {},
            onChoosePrompt = { _, _ -> },
            onMessage = {},
        )

    private val reply = prefs.quickReplies.replies.first()

    private fun button() = strip.single { it.id == "replies" }

    private fun start() {
        val tap = ButtonCommand(ButtonAction.QUICK_REPLIES, insert = InsertMode.APPEND, replyIds = listOf(reply.id))
        prefs.customButtons.save(CustomButton("replies", tap = tap, visibility = ButtonVisibility.ALWAYS))
        control.onStartInput(textField = true, selectionEnd = edit.selectionEnd)
    }

    @After
    fun tearDown() = control.destroy()

    @Test
    fun `the button offers its replies`() {
        start()
        assertEquals(listOf(reply.text), button().members.map { it.description })
        assertTrue(button().phrases)
    }

    @Test
    fun `the list shows only the phrases, without a title or icons`() {
        start()
        val content = ButtonGroupPopup(context, KeyboardTheme(context)) { _, _ -> }.content(button())
        assertEquals(listOf(reply.text), content.texts())
        assertTrue(content.all().none { it is ImageView })
    }

    @Test
    fun `after a reply the button undoes it instead of offering the list again`() {
        edit.setText("Привет")
        edit.setSelection(edit.length())
        start()
        control.onGesture(button().members.single().id, held = false)
        assertEquals("Привет ${reply.text}", edit.text.toString())
        assertEquals(ProofreadState.UNDO, button().state)
        assertTrue(button().members.isEmpty())
        assertTrue(button().holds)
        assertEquals("Привет", control.undoPreview("replies"))
        control.onGesture("replies", held = false)
        assertEquals("Привет", edit.text.toString())
        assertEquals(ProofreadState.IDLE, button().state)
        assertEquals(1, button().members.size)
    }
}

private fun View.all(): List<View> =
    listOf(this) + if (this is ViewGroup) (0 until childCount).flatMap { getChildAt(it).all() } else emptyList()

private fun View.texts(): List<String> = all().filterIsInstance<TextView>().map { it.text.toString() }
