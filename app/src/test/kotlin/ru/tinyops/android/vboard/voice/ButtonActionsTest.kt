package ru.tinyops.android.vboard.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.input.CursorWindow
import ru.tinyops.android.vboard.core.voice.ButtonAction
import ru.tinyops.android.vboard.core.voice.ButtonCommand
import ru.tinyops.android.vboard.core.voice.EnhancementOutcome
import ru.tinyops.android.vboard.core.voice.InsertMode

class ButtonActionsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val messages = ArrayList<Int>()
    private val requests = ArrayList<String>()
    private val copied = ArrayList<String>()
    private val commands = ArrayList<ButtonCommand>()
    private var choice: ((String) -> Unit)? = null
    private var answer = CompletableDeferred<TextEnhancer.Result>()

    private var text = "раз два"
    private var selection = 0 to 0
    private var offset = 0
    private var clipboard: String? = null
    private var replaced = 0

    private val editor =
        object : ButtonActions.Editor {
            override fun window(length: Int) = CursorWindow(text, selection.first, selection.second, offset)

            override fun replace(
                start: Int,
                end: Int,
                replacement: String,
            ) {
                replaced++
                text = text.replaceRange(start, end, replacement)
            }

            override fun commit(replacement: String) {
                text = text.replaceRange(selection.first, selection.second, replacement)
            }

            override fun clear() {
                text = ""
            }
        }

    private val actions =
        ButtonActions(
            scope = scope,
            editor = editor,
            copy = { copied += it },
            paste = { clipboard },
            enhance = { command, sent ->
                commands += command
                requests += sent
                answer.await()
            },
            choose = { choice = it },
            onState = {},
            onMessage = { messages += it },
        )

    private fun clear() = actions.trigger("clear", ButtonCommand(ButtonAction.CLEAR))

    private fun paste() = actions.trigger("paste", ButtonCommand(ButtonAction.PASTE))

    private fun copy() = actions.trigger("copy", ButtonCommand(ButtonAction.COPY))

    private fun prompt(mode: InsertMode) = actions.trigger("prompt", ButtonCommand(ButtonAction.PROMPT, insert = mode))

    private fun pick() =
        actions.trigger("pick", ButtonCommand(ButtonAction.PROMPT, "s", insert = InsertMode.APPEND, pickPrompt = true))

    private fun succeed(result: String) = answer.complete(TextEnhancer.Result(result, EnhancementOutcome.SUCCESS))

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `clearing removes the text and the same button brings it back`() {
        clear()
        assertEquals("", text)
        assertEquals(ProofreadState.UNDO, actions.stateOf("clear"))
        clear()
        assertEquals("раз два", text)
        assertEquals(ProofreadState.IDLE, actions.stateOf("clear"))
    }

    @Test
    fun `pasting puts the clipboard in place of the selection and the same button takes it back`() {
        clipboard = "три"
        selection = 4 to 7
        paste()
        assertEquals("раз три", text)
        assertEquals(ProofreadState.UNDO, actions.stateOf("paste"))
        paste()
        assertEquals("раз два", text)
        assertEquals(ProofreadState.IDLE, actions.stateOf("paste"))
    }

    @Test
    fun `pasting without a selection goes to the cursor`() {
        clipboard = "X"
        selection = 3 to 3
        paste()
        assertEquals("раzX два".replace("z", "з"), text)
    }

    @Test
    fun `pasting in a field that hides where its text is goes to the selection without an undo`() {
        clipboard = "три"
        selection = 4 to 7
        offset = -1
        paste()
        assertEquals("раз три", text)
        assertEquals(0, replaced)
        assertEquals(ProofreadState.IDLE, actions.stateOf("paste"))
    }

    @Test
    fun `an empty clipboard pastes nothing and says so`() {
        paste()
        assertEquals("раз два", text)
        assertEquals(listOf(R.string.custom_button_clipboard_empty), messages)
    }

    @Test
    fun `the preview of an undo shows the text as it will be and leaves the field alone`() {
        selection = 0 to 3
        prompt(InsertMode.REPLACE_ALL)
        succeed("ответ")
        assertEquals("раз два", actions.undoPreview("prompt"))
        assertEquals("ответ", text)
        assertEquals(ProofreadState.UNDO, actions.stateOf("prompt"))
        prompt(InsertMode.REPLACE_ALL)
        assertEquals("раз два", text)
    }

    @Test
    fun `the preview of undoing a clear shows the cleared text`() {
        clear()
        assertEquals("раз два", actions.undoPreview("clear"))
        assertEquals("", text)
        assertEquals(ProofreadState.UNDO, actions.stateOf("clear"))
    }

    @Test
    fun `the preview of an appended answer shows the text without it`() {
        selection = 7 to 7
        prompt(InsertMode.APPEND)
        succeed("три")
        val answered = text
        assertEquals("раз два", actions.undoPreview("prompt"))
        assertEquals(answered, text)
    }

    @Test
    fun `there is no preview without a change to undo or for another button`() {
        assertNull(actions.undoPreview("clear"))
        clear()
        assertNull(actions.undoPreview("copy"))
        assertEquals(ProofreadState.UNDO, actions.stateOf("clear"))
    }

    @Test
    fun `a preview of text that changed gives up the undo`() {
        clear()
        text = "новое"
        assertNull(actions.undoPreview("clear"))
        assertEquals("новое", text)
        assertEquals(ProofreadState.IDLE, actions.stateOf("clear"))
        assertEquals(listOf(R.string.proofread_changed), messages)
    }

    @Test
    fun `typed text is not overwritten by undoing a clear`() {
        clear()
        text = "новое"
        clear()
        assertEquals("новое", text)
        assertEquals(listOf(R.string.proofread_changed), messages)
    }

    @Test
    fun `clearing an empty field does nothing`() {
        text = ""
        clear()
        assertEquals(ProofreadState.IDLE, actions.stateOf("clear"))
    }

    @Test
    fun `copying takes the selection, or the whole text without one`() {
        copy()
        selection = 4 to 7
        copy()
        assertEquals(listOf("раз два", "два"), copied)
        assertEquals(ProofreadState.IDLE, actions.stateOf("copy"))
    }

    @Test
    fun `an answer replaces the whole text and the button undoes it`() {
        selection = 0 to 3
        prompt(InsertMode.REPLACE_ALL)
        assertEquals(listOf("раз два"), requests)
        assertEquals(ProofreadState.WORKING, actions.stateOf("prompt"))
        succeed("ответ")
        assertEquals("ответ", text)
        assertEquals(ProofreadState.UNDO, actions.stateOf("prompt"))
        prompt(InsertMode.REPLACE_ALL)
        assertEquals("раз два", text)
    }

    @Test
    fun `an answer replaces only the selection`() {
        selection = 4 to 7
        prompt(InsertMode.REPLACE_SELECTION)
        assertEquals(listOf("два"), requests)
        succeed("ответ")
        assertEquals("раз ответ", text)
    }

    @Test
    fun `an appended answer is undone together with its space`() {
        prompt(InsertMode.APPEND)
        succeed("ответ")
        assertEquals("раз два ответ", text)
        prompt(InsertMode.APPEND)
        assertEquals("раз два", text)
    }

    @Test
    fun `an answer goes to the cursor`() {
        selection = 3 to 3
        prompt(InsertMode.AT_CURSOR)
        succeed("ответ")
        assertEquals("раз ответ два", text)
    }

    @Test
    fun `a text changed during the request stays`() {
        prompt(InsertMode.REPLACE_ALL)
        text = "другой текст"
        succeed("ответ")
        assertEquals("другой текст", text)
        assertEquals(listOf(R.string.proofread_changed), messages)
        assertEquals(ProofreadState.IDLE, actions.stateOf("prompt"))
    }

    @Test
    fun `a failed request reports and leaves the text alone`() {
        prompt(InsertMode.REPLACE_ALL)
        answer.complete(TextEnhancer.Result("раз два", EnhancementOutcome.FAILED))
        assertEquals("раз два", text)
        assertEquals(listOf(R.string.proofread_failed), messages)
    }

    @Test
    fun `other buttons wait for a running request and its own tap cancels it`() {
        prompt(InsertMode.REPLACE_ALL)
        clear()
        assertEquals("раз два", text)
        assertTrue(actions.busy)
        assertEquals(listOf(R.string.custom_button_busy), messages)
        prompt(InsertMode.REPLACE_ALL)
        assertFalse(actions.busy)
        succeed("ответ")
        assertEquals("раз два", text)
    }

    @Test
    fun `another button ends the chance to undo`() {
        clear()
        text = "новое"
        copy()
        assertEquals(ProofreadState.IDLE, actions.stateOf("clear"))
        assertEquals(listOf("новое"), copied)
    }

    @Test
    fun `too long a text is not sent`() {
        text = "a".repeat(Proofreader.MAX_CHARS_AROUND_CURSOR + 1)
        prompt(InsertMode.APPEND)
        assertEquals(emptyList<String>(), requests)
        assertEquals(listOf(R.string.proofread_too_long), messages)
    }

    @Test
    fun `a gesture without a command does nothing, unless the button has something to undo`() {
        actions.trigger("clear", null)
        assertEquals("раз два", text)
        clear()
        actions.trigger("clear", null)
        assertEquals("раз два", text)
    }

    @Test
    fun `a button that asks for a prompt sends the chosen one and undoes its answer`() {
        pick()
        assertEquals(emptyList<String>(), requests)
        assertEquals(ProofreadState.IDLE, actions.stateOf("pick"))
        requireNotNull(choice)("p")
        assertEquals(listOf(ButtonCommand(ButtonAction.PROMPT, "s", listOf("p"), InsertMode.APPEND)), commands)
        assertEquals(ProofreadState.WORKING, actions.stateOf("pick"))
        succeed("ответ")
        assertEquals("раз два ответ", text)
        pick()
        assertEquals("раз два", text)
    }

    @Test
    fun `the list of prompts is not offered when there is nothing to send`() {
        text = ""
        pick()
        assertNull(choice)
        text = "a".repeat(Proofreader.MAX_CHARS_AROUND_CURSOR + 1)
        pick()
        assertNull(choice)
        assertEquals(listOf(R.string.custom_button_nothing_to_send, R.string.proofread_too_long), messages)
    }

    @Test
    fun `a prompt with nothing to send says so`() {
        text = "   "
        prompt(InsertMode.REPLACE_ALL)
        assertEquals(emptyList<String>(), requests)
        assertEquals(listOf(R.string.custom_button_nothing_to_send), messages)
    }

    @Test
    fun `a gesture without a command says nothing about a running request`() {
        prompt(InsertMode.REPLACE_ALL)
        actions.trigger("clear", null)
        assertEquals(emptyList<Int>(), messages)
    }

    @Test
    fun `a prompt chosen while another request runs is not sent`() {
        pick()
        prompt(InsertMode.REPLACE_ALL)
        requireNotNull(choice)("p")
        assertEquals(listOf("раз два"), requests)
        assertEquals(ProofreadState.IDLE, actions.stateOf("pick"))
    }
}
