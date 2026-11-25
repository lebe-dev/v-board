package ru.tinyops.android.vboard.core.input

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class CorrectionFakeEditor : TextEditor {
    val text = StringBuilder()
    var cursor = 0
    var composingStart = -1
    var composingEnd = -1

    val composing: String
        get() = if (composingStart < 0) "" else text.substring(composingStart, composingEnd)

    override fun textBeforeCursor(length: Int): CharSequence = text.substring(maxOf(0, cursor - length), cursor)

    override fun textAfterCursor(length: Int): CharSequence =
        text.substring(cursor, minOf(text.length, cursor + length))

    override fun replaceAroundCursor(
        before: Int,
        after: Int,
        text: CharSequence,
    ) {
        this.text.replace(cursor - before, cursor + after, text.toString())
        cursor = cursor - before + text.length
        composingStart = -1
        composingEnd = -1
    }

    override fun commitText(text: CharSequence) {
        replaceComposing(text)
        composingStart = -1
        composingEnd = -1
    }

    override fun setComposingText(text: CharSequence) {
        val start = replaceComposing(text)
        if (text.isEmpty()) {
            composingStart = -1
            composingEnd = -1
        } else {
            composingStart = start
            composingEnd = start + text.length
        }
    }

    override fun finishComposingText() {
        composingStart = -1
        composingEnd = -1
    }

    override fun sendBackspace() {
        if (cursor == 0) return
        text.deleteCharAt(cursor - 1)
        cursor--
    }

    override fun sendEnter() = commitText("\n")

    override fun beginBatchEdit() = Unit

    override fun endBatchEdit() = Unit

    private fun replaceComposing(replacement: CharSequence): Int {
        val start = if (composingStart >= 0) composingStart else cursor
        val end = if (composingStart >= 0) composingEnd else cursor
        text.replace(start, end, replacement.toString())
        cursor = start + replacement.length
        return start
    }

    override fun toString(): String = text.toString()
}

class SingleInputCorrectionTest {
    private val dictionary = listOf("hello", "help", "world")
    private var suggestions: List<String> = emptyList()
    private val editor = CorrectionFakeEditor()
    private lateinit var logic: InputLogic

    @BeforeTest
    fun setUp() {
        logic =
            InputLogic(
                completions = { prefix, _ -> dictionary.filter { it.startsWith(prefix) } },
                onSuggestions = { suggestions = it },
                corrections = { word -> if (word == "helo") listOf("hello", "help") else emptyList() },
            )
    }

    private fun placeCursor(
        content: String,
        at: Int,
    ) {
        editor.text.setLength(0)
        editor.text.append(content)
        editor.cursor = at
        logic.onSelectionChanged(editor, at, at, -1)
    }

    @Test
    fun `swiping a word while a single correction is shown swaps the swipe instead of the earlier word`() {
        placeCursor("helo ", 5)
        assertEquals(listOf("hello", "help"), suggestions)

        logic.startGesture(editor)
        assertTrue(suggestions.isEmpty())
        logic.commitGesture(editor, listOf("world", "word"))
        assertEquals(listOf("world", "word"), suggestions)

        logic.pickSuggestion(editor, "word")
        assertEquals("helo word", editor.toString())
        assertEquals("word", editor.composing)
    }

    @Test
    fun `punctuation, enter and deleting a word clear stale corrections`() {
        placeCursor("helo ", 5)
        assertEquals(listOf("hello", "help"), suggestions)
        logic.typeCharacter(editor, ",")
        assertTrue(suggestions.isEmpty())
        logic.pickSuggestion(editor, "hello")
        assertEquals("helo,", editor.toString())

        placeCursor("helo ", 5)
        assertEquals(listOf("hello", "help"), suggestions)
        logic.typeEnter(editor)
        assertTrue(suggestions.isEmpty())
        logic.pickSuggestion(editor, "hello")
        assertEquals("helo \n", editor.toString())

        placeCursor("helo world", 10)
        logic.onSelectionChanged(editor, 10, 10, -1)
        assertEquals(listOf("hello", "help"), suggestions)
        logic.deleteBy(editor, DeleteHoldMode.WORDS)
        assertTrue(suggestions.isEmpty())
        logic.pickSuggestion(editor, "hello")
        assertEquals("helo ", editor.toString())
    }

    @Test
    fun `single-input correction caches word lookups and skips duplicate scans`() {
        var correctionCalls = 0
        val countingLogic =
            InputLogic(
                completions = { prefix, _ -> dictionary.filter { it.startsWith(prefix) } },
                onSuggestions = { suggestions = it },
                corrections = { word ->
                    correctionCalls++
                    if (word == "helo") listOf("hello", "help") else emptyList()
                },
            )
        editor.text.append("helo world")
        editor.cursor = editor.text.length
        countingLogic.typeSpace(editor, 1_000)
        val afterSpace = correctionCalls
        // Duplicate selection update with identical text must not re-scan.
        countingLogic.onSelectionChanged(editor, editor.cursor, editor.cursor, -1)
        assertEquals(afterSpace, correctionCalls)

        // New edit only evaluates the new word, reusing cached results for "helo" and "world".
        editor.text.append("hello ")
        editor.cursor = editor.text.length
        countingLogic.onSelectionChanged(editor, editor.cursor, editor.cursor, -1)
        assertEquals(afterSpace + 1, correctionCalls)
    }
}
