package ru.tinyops.android.vboard.voice

import android.view.inputmethod.EditorInfo
import android.widget.EditText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.tinyops.android.vboard.core.voice.EnhancementOutcome

/** The whole cycle against a real editor: what is read, replaced and brought back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProofreadFieldTest {
    private val edit = EditText(RuntimeEnvironment.getApplication())
    private val requests = ArrayList<String>()
    private val answer = CompletableDeferred<TextEnhancer.Result>()

    private fun proofreader(): Proofreader {
        val connection = edit.onCreateInputConnection(EditorInfo())
        return Proofreader(
            scope = CoroutineScope(Dispatchers.Unconfined),
            editor = ProofreadField { connection },
            enhance = {
                requests += it
                answer.await()
            },
            onState = {},
            onMessage = {},
        )
    }

    private fun succeed(text: String) = answer.complete(TextEnhancer.Result(text, EnhancementOutcome.SUCCESS))

    @Test
    fun `the whole field is replaced and brought back, whitespace stays`() {
        edit.setText("  привет мир ")
        edit.setSelection(4)
        val proofreader = proofreader()

        proofreader.onTap()
        assertEquals(listOf("привет мир"), requests)
        succeed("Привет, мир!")
        assertEquals("  Привет, мир! ", edit.text.toString())
        assertEquals(ProofreadState.UNDO, proofreader.state)

        proofreader.onTap()
        assertEquals("  привет мир ", edit.text.toString())
    }

    @Test
    fun `a selection in the middle is replaced in place`() {
        edit.setText("раз два три")
        edit.setSelection(4, 7)
        val proofreader = proofreader()

        proofreader.onTap()
        assertEquals(listOf("два"), requests)
        succeed("ДВА!")
        assertEquals("раз ДВА! три", edit.text.toString())

        proofreader.onTap()
        assertEquals("раз два три", edit.text.toString())
    }

    @Test
    fun `an edit made while waiting is not overwritten`() {
        edit.setText("привет мир")
        val proofreader = proofreader()

        proofreader.onTap()
        edit.setText("другой текст")
        succeed("Привет, мир!")
        assertEquals("другой текст", edit.text.toString())
    }
}
