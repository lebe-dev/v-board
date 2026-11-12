package ru.tinyops.android.vboard.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.input.CursorWindow
import ru.tinyops.android.vboard.core.voice.EnhancementOutcome

class ProofreaderTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val messages = ArrayList<Int>()
    private val states = ArrayList<ProofreadState>()
    private val requests = ArrayList<String>()
    private val heldRequests = ArrayList<String>()
    private var holdPrompt = true
    private var answer = CompletableDeferred<TextEnhancer.Result>()

    private var text = "привет мир"
    private var selection = 0 to 0

    private val editor =
        object : Proofreader.Editor {
            override fun window(length: Int) = CursorWindow(text, selection.first, selection.second, 0)

            override fun replace(
                start: Int,
                end: Int,
                replacement: String,
            ) {
                text = text.replaceRange(start, end, replacement)
            }
        }

    private val proofreader =
        Proofreader(
            scope = scope,
            editor = editor,
            enhance = {
                requests += it
                answer.await()
            },
            enhanceHeld =
                if (holdPrompt) {
                    {
                        heldRequests += it
                        answer.await()
                    }
                } else {
                    null
                },
            onState = { states += it },
            onMessage = { messages += it },
        )

    @After
    fun tearDown() = scope.cancel()

    private fun succeed(result: String) = answer.complete(TextEnhancer.Result(result, EnhancementOutcome.SUCCESS))

    @Test
    fun `a tap sends the text and the answer replaces it`() {
        proofreader.onTap()
        assertEquals(listOf("привет мир"), requests)
        assertEquals(ProofreadState.WORKING, proofreader.state)

        succeed("Привет, мир!")
        assertEquals("Привет, мир!", text)
        assertEquals(ProofreadState.UNDO, proofreader.state)
    }

    @Test
    fun `the preview of an undo shows the original text and leaves the field alone`() {
        assertNull(proofreader.undoPreview())
        proofreader.onTap()
        assertNull(proofreader.undoPreview())
        succeed("Привет, мир!")
        assertEquals("привет мир", proofreader.undoPreview())
        assertEquals("Привет, мир!", text)
        assertEquals(ProofreadState.UNDO, proofreader.state)
    }

    @Test
    fun `a preview of text that changed gives up the undo`() {
        proofreader.onTap()
        succeed("Привет, мир!")
        text = "другое"
        assertNull(proofreader.undoPreview())
        assertEquals(ProofreadState.IDLE, proofreader.state)
        assertEquals(listOf(R.string.proofread_changed), messages)
    }

    @Test
    fun `a hold sends the text with the hold prompt and the answer replaces it`() {
        proofreader.onHold()
        assertEquals(emptyList<String>(), requests)
        assertEquals(listOf("привет мир"), heldRequests)
        assertEquals(ProofreadState.WORKING, proofreader.state)

        succeed("Привет, мир!")
        assertEquals("Привет, мир!", text)
        assertEquals(ProofreadState.UNDO, proofreader.state)
    }

    @Test
    fun `a tap after a hold brings the original text back`() {
        proofreader.onHold()
        succeed("Привет, мир!")
        proofreader.onTap()
        assertEquals("привет мир", text)
        assertEquals(ProofreadState.IDLE, proofreader.state)
    }

    @Test
    fun `a hold while waiting cancels the request`() {
        proofreader.onHold()
        proofreader.onHold()
        succeed("Привет, мир!")
        assertEquals("привет мир", text)
        assertEquals(ProofreadState.IDLE, proofreader.state)
    }

    @Test
    fun `a hold without a hold prompt does nothing`() {
        val plain =
            Proofreader(
                scope = scope,
                editor = editor,
                enhance = {
                    requests += it
                    answer.await()
                },
                onState = { states += it },
                onMessage = { messages += it },
            )
        plain.onHold()
        assertEquals(emptyList<String>(), requests)
        assertEquals(ProofreadState.IDLE, plain.state)
    }

    @Test
    fun `only the selection is replaced`() {
        selection = 7 to 10
        proofreader.onTap()
        succeed("Мир")
        assertEquals("привет Мир", text)
    }

    @Test
    fun `the next tap brings the original text back`() {
        proofreader.onTap()
        succeed("Привет, мир!")
        proofreader.onTap()
        assertEquals("привет мир", text)
        assertEquals(ProofreadState.IDLE, proofreader.state)
    }

    @Test
    fun `a tap while waiting cancels the request`() {
        proofreader.onTap()
        proofreader.onTap()
        succeed("Привет, мир!")
        assertEquals("привет мир", text)
        assertEquals(ProofreadState.IDLE, proofreader.state)
    }

    @Test
    fun `typing cancels the request`() {
        proofreader.onTap()
        proofreader.reset()
        succeed("Привет, мир!")
        assertEquals("привет мир", text)
        assertEquals(ProofreadState.IDLE, proofreader.state)
    }

    @Test
    fun `typing ends the chance to undo`() {
        proofreader.onTap()
        succeed("Привет, мир!")
        proofreader.reset()
        assertEquals(ProofreadState.IDLE, proofreader.state)
        assertEquals("Привет, мир!", text)
    }

    @Test
    fun `text changed elsewhere while waiting is not overwritten`() {
        proofreader.onTap()
        text = "другой текст"
        succeed("Привет, мир!")
        assertEquals("другой текст", text)
        assertEquals(listOf(R.string.proofread_changed), messages)
        assertEquals(ProofreadState.IDLE, proofreader.state)
    }

    @Test
    fun `undo does not overwrite text changed elsewhere`() {
        proofreader.onTap()
        succeed("Привет, мир!")
        text = "другой текст"
        proofreader.onTap()
        assertEquals("другой текст", text)
        assertEquals(listOf(R.string.proofread_changed), messages)
    }

    @Test
    fun `a failure keeps the text and tells about it`() {
        proofreader.onTap()
        answer.complete(TextEnhancer.Result("привет мир", EnhancementOutcome.FAILED))
        assertEquals("привет мир", text)
        assertEquals(listOf(R.string.proofread_failed), messages)
        assertEquals(ProofreadState.IDLE, proofreader.state)
    }

    @Test
    fun `a refused access has its own message`() {
        proofreader.onTap()
        answer.complete(TextEnhancer.Result("привет мир", EnhancementOutcome.AUTH_FAILED))
        assertEquals(listOf(R.string.proofread_auth_failed), messages)
    }

    @Test
    fun `an unchanged answer offers nothing to undo`() {
        proofreader.onTap()
        answer.complete(TextEnhancer.Result("привет мир", EnhancementOutcome.UNCHANGED))
        assertEquals(listOf(R.string.proofread_no_changes), messages)
        assertEquals(ProofreadState.IDLE, proofreader.state)
    }

    @Test
    fun `blank text sends nothing`() {
        text = "   "
        proofreader.onTap()
        assertTrue(requests.isEmpty())
        assertEquals(ProofreadState.IDLE, proofreader.state)
    }

    @Test
    fun `text that does not fit is refused before sending`() {
        text = "a".repeat(Proofreader.MAX_CHARS_AROUND_CURSOR)
        proofreader.onTap()
        assertTrue(requests.isEmpty())
        assertEquals(listOf(R.string.proofread_too_long), messages)
    }

    @Test
    fun `the state is reported once per change`() {
        proofreader.onTap()
        succeed("Привет, мир!")
        proofreader.reset()
        proofreader.reset()
        assertEquals(listOf(ProofreadState.WORKING, ProofreadState.UNDO, ProofreadState.IDLE), states)
    }
}
