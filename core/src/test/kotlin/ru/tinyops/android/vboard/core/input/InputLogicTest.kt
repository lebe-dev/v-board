package ru.tinyops.android.vboard.core.input

import ru.tinyops.android.vboard.core.dict.TapSuggestions
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Mimics an EditText behind an InputConnection: one cursor and an optional composing region. */
private class FakeEditor : TextEditor {
    val text = StringBuilder()
    var cursor = 0
    var composingStart = -1
    var composingEnd = -1
    var enterPresses = 0

    val composing: String
        get() = if (composingStart < 0) "" else text.substring(composingStart, composingEnd)

    override fun textBeforeCursor(length: Int): CharSequence = text.substring(maxOf(0, cursor - length), cursor)

    override fun textAfterCursor(length: Int): CharSequence =
        text.substring(cursor, minOf(text.length, cursor + length))

    override fun replaceAroundCursor(
        before: Int,
        after: Int,
        replacement: CharSequence,
    ) {
        text.replace(cursor - before, cursor + after, replacement.toString())
        cursor = cursor - before + replacement.length
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

    override fun sendEnter() {
        enterPresses++
        commitText("\n")
    }

    override fun beginBatchEdit() = Unit

    override fun endBatchEdit() = Unit

    /** Replaces the composing region, or inserts at the cursor; returns the start offset. */
    private fun replaceComposing(replacement: CharSequence): Int {
        val start = if (composingStart >= 0) composingStart else cursor
        val end = if (composingStart >= 0) composingEnd else cursor
        text.replace(start, end, replacement.toString())
        cursor = start + replacement.length
        return start
    }

    override fun toString(): String = text.toString()
}

class InputLogicTest {
    private val dictionary = listOf("hello", "help", "hell", "world", "word")
    private val followers = mapOf("good" to listOf("morning", "luck"), "morning" to listOf("everyone"))
    private var suggestions: List<String> = emptyList()
    private var completedAfter: String? = null
    private val editor = FakeEditor()
    private lateinit var logic: InputLogic

    @BeforeTest
    fun setUp() {
        logic =
            InputLogic(
                completions = { prefix, before ->
                    completedAfter = before
                    dictionary.filter { it.startsWith(prefix) }
                },
                onSuggestions = { suggestions = it },
                corrections = { word -> if (word == "helo") listOf("hello", "help") else emptyList() },
                predictions = { before -> followers[before.trimEnd().substringAfterLast(' ').lowercase()].orEmpty() },
            )
    }

    @Test
    fun `a space after a word offers the words that usually follow it`() {
        type("good")
        logic.typeSpace(editor, 0)
        assertEquals(listOf("morning", "luck"), suggestions)
    }

    @Test
    fun `picking a next word inserts it with a space and offers the words after it`() {
        type("good")
        logic.typeSpace(editor, 0)
        logic.pickSuggestion(editor, "morning")
        assertEquals("good morning ", editor.toString())
        assertEquals(listOf("everyone"), suggestions)
        logic.pickSuggestion(editor, "everyone")
        assertEquals("good morning everyone ", editor.toString())
        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun `a space right after a picked next word does not end the sentence`() {
        type("good")
        logic.typeSpace(editor, 1_000)
        logic.pickSuggestion(editor, "luck")
        logic.typeSpace(editor, 1_100)
        assertEquals("good luck  ", editor.toString())
    }

    @Test
    fun `picking a completion offers the words after it`() {
        type("goo")
        logic.pickSuggestion(editor, "good")
        assertEquals("good ", editor.toString())
        assertEquals(listOf("morning", "luck"), suggestions)
    }

    @Test
    fun `typing a letter replaces the next words with completions`() {
        type("good")
        logic.typeSpace(editor, 0)
        type("wo")
        assertEquals(listOf("world", "wo", "word"), suggestions)
        assertEquals("good ", completedAfter)
    }

    @Test
    fun `punctuation, enter and a cursor move take the next words away`() {
        type("good")
        logic.typeSpace(editor, 0)
        logic.typeCharacter(editor, ",")
        assertTrue(suggestions.isEmpty())

        type("good")
        logic.typeSpace(editor, 0)
        logic.typeEnter(editor)
        assertTrue(suggestions.isEmpty())

        type("good")
        logic.typeSpace(editor, 0)
        logic.startCursorMove(editor)
        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun `backspace takes the next words away`() {
        type("good")
        logic.typeSpace(editor, 0)
        logic.backspace(editor)
        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun `a cursor placed after a word and a space offers the next words`() {
        placeCursor("good ", 5)
        assertEquals(listOf("morning", "luck"), suggestions)
        logic.onSelectionChanged(editor, 0, 5, -1)
        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun `no next words for a cursor right before a word or after a word without a space`() {
        placeCursor("good luck", 5)
        assertTrue(suggestions.isEmpty())
        placeCursor("good luck", 4)
        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun `next words follow caps lock`() {
        type("good")
        logic.typeSpace(editor, 0)
        logic.shift.onLongPress()
        logic.onSelectionChanged(editor, 5, 5, -1)
        assertEquals(listOf("MORNING", "LUCK"), suggestions)
    }

    @Test
    fun `no next words where suggestions are off`() {
        logic.suggestionsAllowed = false
        type("good")
        logic.typeSpace(editor, 0)
        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun `next words turned off leave the strip empty after a space but keep completions`() {
        logic.nextWordsAllowed = false
        type("good")
        logic.typeSpace(editor, 0)
        assertTrue(suggestions.isEmpty())
        placeCursor("good ", 5)
        assertTrue(suggestions.isEmpty())
        type("wo")
        assertEquals(listOf("world", "wo", "word"), suggestions)
    }

    private fun placeCursor(
        text: String,
        at: Int,
    ) {
        editor.text.setLength(0)
        editor.text.append(text)
        editor.cursor = at
        logic.onSelectionChanged(editor, at, at, -1)
    }

    @Test
    fun `deleting a word removes the committed word before the cursor`() {
        placeCursor("say hello now", 10)
        logic.deleteBy(editor, DeleteHoldMode.WORDS)
        assertEquals("say now", editor.toString())
        assertEquals(4, editor.cursor)
    }

    @Test
    fun `deleting a word drops the word being typed as a whole`() {
        type("say hel")
        logic.deleteBy(editor, DeleteHoldMode.WORDS)
        assertEquals("say ", editor.toString())
        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun `deleting all clears the field and the word being typed`() {
        placeCursor("say hello now", 4)
        type("wo")
        logic.deleteBy(editor, DeleteHoldMode.ALL)
        assertEquals("", editor.toString())
        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun `a cursor inside a misspelled word offers corrections`() {
        placeCursor("say helo now", 6)
        assertEquals(listOf("hello", "help"), suggestions)
    }

    @Test
    fun `a cursor at the end of a misspelled word offers corrections`() {
        placeCursor("say helo now", 8)
        assertEquals(listOf("hello", "help"), suggestions)
    }

    @Test
    fun `a cursor after a space or on a known word offers nothing`() {
        placeCursor("say helo now", 9)
        assertTrue(suggestions.isEmpty())
        placeCursor("say helo now", 11)
        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun `moving off the word takes the corrections away`() {
        placeCursor("say helo now", 6)
        placeCursor("say helo now", 1)
        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun `picking a correction replaces the whole word and keeps the cursor behind it`() {
        placeCursor("say helo now", 6)
        logic.pickSuggestion(editor, "hello")
        assertEquals("say hello now", editor.toString())
        assertEquals(9, editor.cursor)
    }

    @Test
    fun `a correction keeps the capital letter of the word`() {
        placeCursor("Helo", 4)
        assertEquals(listOf("Hello", "Help"), suggestions)
        logic.pickSuggestion(editor, "Hello")
        assertEquals("Hello", editor.toString())
    }

    @Test
    fun `typing after corrections were shown keeps the completions`() {
        placeCursor("helo", 4)
        type("w")
        logic.onSelectionChanged(editor, 5, 5, 5)
        assertEquals(listOf("world", "w", "word"), suggestions)
    }

    @Test
    fun `no corrections while the word is being composed or suggestions are off`() {
        type("helo")
        logic.onSelectionChanged(editor, 4, 4, 4)
        assertEquals(listOf("helo"), suggestions)
        logic.reset()
        logic.suggestionsAllowed = false
        placeCursor("say helo now", 6)
        assertTrue(suggestions.isEmpty())
    }

    private fun type(text: String) = text.forEach { logic.typeCharacter(editor, it.toString()) }

    @Test
    fun `typed letters compose and suggest completions`() {
        type("hel")
        assertEquals("hel", editor.composing)
        assertEquals(listOf("hello", "hel", "help"), suggestions)
    }

    @Test
    fun `space commits the composing word`() {
        type("hi")
        logic.typeSpace(editor, 0)
        assertEquals("hi ", editor.toString())
        assertEquals("", editor.composing)
        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun `picking a completion commits it with a space`() {
        type("wo")
        logic.pickSuggestion(editor, "world")
        assertEquals("world ", editor.toString())
    }

    @Test
    fun `completions follow the typed case`() {
        logic.shift.onTap(0)
        type("he")
        assertEquals("He", editor.composing)
        assertEquals("Hello", suggestions.first())
    }

    @Test
    fun `consecutive gestures are separated by spaces`() {
        logic.commitGesture(editor, listOf("hello", "help"))
        logic.startGesture(editor)
        logic.commitGesture(editor, listOf("world"))
        assertEquals("hello world", editor.toString())
        assertEquals("world", editor.composing)
    }

    @Test
    fun `a gesture offers its alternatives and can be swapped`() {
        logic.commitGesture(editor, listOf("hello", "help", "hell"))
        assertEquals(listOf("hello", "help", "hell"), suggestions)
        logic.pickSuggestion(editor, "help")
        assertEquals("help", editor.toString())
        assertEquals("help", editor.composing)
    }

    @Test
    fun `punctuation after a gesture attaches without a space`() {
        logic.commitGesture(editor, listOf("hello"))
        logic.typeCharacter(editor, ",")
        assertEquals("hello,", editor.toString())
    }

    @Test
    fun `a letter after a gesture starts a new word`() {
        logic.commitGesture(editor, listOf("hello"))
        type("a")
        assertEquals("hello a", editor.toString())
    }

    @Test
    fun `space after a gesture is not doubled`() {
        logic.commitGesture(editor, listOf("hello"))
        logic.typeSpace(editor, 0)
        logic.commitGesture(editor, listOf("world"))
        assertEquals("hello world", editor.toString())
    }

    @Test
    fun `backspace deletes a swiped word as a whole`() {
        type("say")
        logic.typeSpace(editor, 0)
        logic.commitGesture(editor, listOf("hello"))
        logic.backspace(editor)
        assertEquals("say ", editor.toString())
        logic.backspace(editor)
        assertEquals("say", editor.toString())
    }

    @Test
    fun `backspace edits the composing word letter by letter`() {
        type("help")
        logic.backspace(editor)
        assertEquals("hel", editor.composing)
        assertEquals("hel", editor.toString())
    }

    @Test
    fun `a gesture after a typed word starts a new word`() {
        type("hi")
        logic.startGesture(editor)
        logic.commitGesture(editor, listOf("world"))
        assertEquals("hi world", editor.toString())
    }

    @Test
    fun `a gesture is capitalized by one-shot shift`() {
        logic.shift.onTap(0)
        logic.commitGesture(editor, listOf("привет"))
        assertEquals("Привет", editor.toString())
        assertEquals(ShiftState.OFF, logic.shift.state)
    }

    @Test
    fun `quick double space becomes a period`() {
        type("end")
        logic.typeSpace(editor, 1_000)
        logic.typeSpace(editor, 1_200)
        assertEquals("end. ", editor.toString())
    }

    @Test
    fun `slow double space stays two spaces`() {
        type("end")
        logic.typeSpace(editor, 1_000)
        logic.typeSpace(editor, 3_000)
        assertEquals("end  ", editor.toString())
    }

    @Test
    fun `enter commits the composing word and performs the action`() {
        type("go")
        logic.typeEnter(editor)
        assertEquals("go\n", editor.toString())
        assertEquals(1, editor.enterPresses)
    }

    @Test
    fun `moving the cursor away commits the composing word`() {
        type("hel")
        logic.onSelectionChanged(editor, selectionStart = 0, selectionEnd = 0, candidatesEnd = 3)
        assertEquals("", editor.composing)
        type("x")
        assertEquals("helx", editor.toString())
    }

    @Test
    fun `stale selection updates without a composing region are ignored`() {
        type("hel")
        logic.onSelectionChanged(editor, selectionStart = 0, selectionEnd = 0, candidatesEnd = -1)
        assertEquals("hel", editor.composing)
    }

    @Test
    fun `without suggestions text is committed directly`() {
        logic.suggestionsAllowed = false
        type("pw")
        logic.commitGesture(editor, listOf("hello"))
        assertEquals("pw hello", editor.toString())
        assertEquals("", editor.composing)
        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun `moving the cursor commits the word and forgets the pending space and period`() {
        logic.commitGesture(editor, listOf("hello"))
        logic.startCursorMove(editor)
        assertEquals("", editor.composing)
        type("a")
        assertEquals("helloa", editor.toString())

        logic.reset()
        logic.typeSpace(editor, 1_000)
        logic.startCursorMove(editor)
        logic.typeSpace(editor, 1_100)
        assertTrue(editor.toString().endsWith("  "))
    }

    @Test
    fun `completions appear for a word typed before the dictionary loaded`() {
        var loaded = false
        logic =
            InputLogic(
                completions = { prefix, _ -> if (loaded) dictionary.filter { it.startsWith(prefix) } else emptyList() },
                onSuggestions = { suggestions = it },
            )
        type("wor")
        assertEquals(listOf("wor"), suggestions)

        loaded = true
        logic.refreshCompletions()

        assertEquals(listOf("world", "wor", "word"), suggestions)
    }

    @Test
    fun `a swiped word keeps its alternatives when the dictionary loads`() {
        logic.commitGesture(editor, listOf("hello", "hell"))
        logic.refreshCompletions()
        assertEquals(listOf("hello", "hell"), suggestions)
    }

    @Test
    fun `the live preview of a gesture follows shift`() {
        logic.shift.onTap(0)
        logic.showGesturePreview(listOf("hello", "help", "hell", "world"))
        assertEquals(listOf("Hello", "Help", "Hell"), suggestions)
    }

    @Test
    fun `the cursor at the end of the composing word keeps it composing`() {
        type("hel")
        logic.onSelectionChanged(editor, selectionStart = 3, selectionEnd = 3, candidatesEnd = 3)
        assertEquals("hel", editor.composing)
    }

    @Test
    fun `selecting the composing word commits it`() {
        type("hel")
        logic.onSelectionChanged(editor, selectionStart = 0, selectionEnd = 3, candidatesEnd = 3)
        assertEquals("", editor.composing)
    }

    @Test
    fun `backspace with nothing composed deletes the character before the cursor`() {
        type("ab")
        logic.typeSpace(editor, 1_000)
        logic.backspace(editor)
        logic.backspace(editor)
        assertEquals("a", editor.toString())
    }

    @Test
    fun `a gesture without words changes nothing`() {
        type("ab")
        logic.commitGesture(editor, emptyList())
        assertEquals("ab", editor.composing)
    }

    @Test
    fun `a letter after a gesture in a field without suggestions is separated by a space`() {
        logic.suggestionsAllowed = false
        logic.commitGesture(editor, listOf("hello"))
        type("x")
        assertEquals("hello x", editor.toString())
    }

    @Test
    fun `locale drives letter case`() {
        logic.locale = Locale.forLanguageTag("ru")
        logic.shift.onLongPress()
        type("ё")
        assertEquals("Ё", editor.toString())
    }

    @Test
    fun `autocorrect applies on space and backspace reverts to the original word`() {
        var highlighted = false
        val acLogic =
            InputLogic(
                completions = { _, _ -> emptyList() },
                onSuggestions = { suggestions = it },
                tapSuggestions = { typed, _, _ ->
                    if (typed == "helo") {
                        TapSuggestions(listOf("hello", "help"), autocorrect = "hello")
                    } else {
                        TapSuggestions(emptyList())
                    }
                },
                onAutocorrectHighlight = { highlighted = it },
            )
        for (ch in "helo") acLogic.typeCharacter(editor, ch.toString())
        assertEquals(listOf("hello", "helo", "help"), suggestions)
        assertTrue(highlighted)

        acLogic.typeSpace(editor, 1_000)
        assertEquals("hello ", editor.toString())
        assertFalse(highlighted)

        acLogic.backspace(editor)
        assertEquals("helo", editor.toString())

        // A space after undoing keeps the user's literal word.
        acLogic.typeSpace(editor, 2_000)
        assertEquals("helo ", editor.toString())
    }

    @Test
    fun `autocorrect applies on punctuation and respects capitalization`() {
        val acLogic =
            InputLogic(
                completions = { _, _ -> emptyList() },
                onSuggestions = { suggestions = it },
                tapSuggestions = { typed, _, _ ->
                    if (typed == "helo") {
                        TapSuggestions(listOf("hello"), autocorrect = "hello")
                    } else {
                        TapSuggestions(emptyList())
                    }
                },
            )
        acLogic.shift.onTap(0)
        for (ch in "helo") acLogic.typeCharacter(editor, ch.toString())
        assertEquals(listOf("Hello", "Helo"), suggestions)

        acLogic.typeCharacter(editor, ",")
        assertEquals("Hello,", editor.toString())

        acLogic.backspace(editor)
        assertEquals("Helo", editor.toString())
    }

    @Test
    fun `autocorrect disabled still shows corrections in strip without auto-replacing`() {
        var highlighted = false
        val acLogic =
            InputLogic(
                completions = { _, _ -> emptyList() },
                onSuggestions = { suggestions = it },
                tapSuggestions = { typed, _, _ ->
                    if (typed == "helo") {
                        TapSuggestions(listOf("hello", "help"), autocorrect = "hello")
                    } else {
                        TapSuggestions(emptyList())
                    }
                },
                onAutocorrectHighlight = { highlighted = it },
            )
        acLogic.autocorrectEnabled = false
        for (ch in "helo") acLogic.typeCharacter(editor, ch.toString())
        assertEquals(listOf("hello", "helo", "help"), suggestions)
        assertFalse(highlighted)

        acLogic.typeSpace(editor, 1_000)
        assertEquals("helo ", editor.toString())
    }

    @Test
    fun `tapping the literal word in the strip keeps it without autocorrecting`() {
        val acLogic =
            InputLogic(
                completions = { _, _ -> emptyList() },
                onSuggestions = { suggestions = it },
                tapSuggestions = { typed, _, _ ->
                    if (typed == "helo") {
                        TapSuggestions(listOf("hello"), autocorrect = "hello")
                    } else {
                        TapSuggestions(emptyList())
                    }
                },
            )
        for (ch in "helo") acLogic.typeCharacter(editor, ch.toString())
        acLogic.pickSuggestion(editor, "helo")
        assertEquals("helo ", editor.toString())
    }
}
