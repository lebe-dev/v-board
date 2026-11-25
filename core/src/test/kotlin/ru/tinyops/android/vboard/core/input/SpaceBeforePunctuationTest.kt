package ru.tinyops.android.vboard.core.input

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class PunctuationTestEditor : TextEditor {
    val text = StringBuilder()
    var cursor = 0
    private var composingStart = -1
    private var composingEnd = -1

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

class SpaceBeforePunctuationTest {
    private val dictionary = listOf("hello", "world")
    private val editor = PunctuationTestEditor()
    private lateinit var logic: InputLogic

    @BeforeTest
    fun setUp() {
        logic =
            InputLogic(
                completions = { prefix, _ -> dictionary.filter { it.startsWith(prefix) } },
                onSuggestions = {},
            )
    }

    private fun type(text: String) = text.forEach { logic.typeCharacter(editor, it.toString()) }

    @Test
    fun `accidental space before question mark or comma is removed`() {
        type("Саша мыла раму")
        logic.typeSpace(editor, 1_000)
        logic.typeCharacter(editor, "?")
        assertEquals("Саша мыла раму?", editor.toString())

        logic.reset()
        editor.text.setLength(0)
        editor.cursor = 0

        type("Саша")
        logic.typeSpace(editor, 2_000)
        logic.typeCharacter(editor, ",")
        logic.typeSpace(editor, 3_000)
        type("как дела")
        assertEquals("Саша, как дела", editor.toString())
    }

    @Test
    fun `space after picking a suggestion is removed when punctuation follows`() {
        type("hel")
        logic.pickSuggestion(editor, "hello")
        assertEquals("hello ", editor.toString())

        logic.typeCharacter(editor, ",")
        assertEquals("hello,", editor.toString())
    }

    @Test
    fun `multiple spaces before punctuation are removed after a word`() {
        type("Саша")
        logic.typeSpace(editor, 1_000)
        logic.typeSpace(editor, 2_000)
        logic.typeCharacter(editor, ",")
        assertEquals("Саша,", editor.toString())
    }

    @Test
    fun `space before punctuation stays when disabled or when suggestions are off`() {
        logic.trimSpaceBeforePunctuation = false
        type("Саша")
        logic.typeSpace(editor, 1_000)
        logic.typeCharacter(editor, "?")
        assertEquals("Саша ?", editor.toString())

        logic.trimSpaceBeforePunctuation = true
        logic.suggestionsAllowed = false
        logic.typeSpace(editor, 2_000)
        type("раму")
        logic.typeSpace(editor, 3_000)
        logic.typeCharacter(editor, "?")
        assertEquals("Саша ? раму ?", editor.toString())
    }
}
