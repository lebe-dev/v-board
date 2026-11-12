package ru.tinyops.android.vboard.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PromptLibraryTest {
    private val polite = SavedPrompt("a", "Вежливо", "Пиши вежливо.")
    private val short = SavedPrompt("b", "Кратко", "Сократи текст.")
    private val library = PromptLibrary(listOf(polite, short))

    @Test
    fun `selected prompts are joined in the order of the collection`() {
        assertEquals("Пиши вежливо.\n\nСократи текст.", library.compose(listOf("b", "a")))
        assertEquals("Сократи текст.", library.compose(listOf("b")))
    }

    @Test
    fun `a prompt that no longer exists adds nothing`() {
        assertEquals("Пиши вежливо.", library.compose(listOf("gone", "a")))
        assertEquals("", library.compose(listOf("gone")))
        assertEquals(listOf("Вежливо"), library.titles(listOf("gone", "a")))
    }

    @Test
    fun `saving replaces a prompt in place and adds a new one at the end`() {
        val edited = library.with(polite.copy(title = "  Мягко  ", text = " Пиши мягко. "))
        assertEquals(listOf("Мягко", "Кратко"), edited.prompts.map { it.title })
        assertEquals("Пиши мягко.", edited.find("a")?.text)

        val added = library.with(SavedPrompt("c", "Строго", "Пиши строго."))
        assertEquals(listOf("a", "b", "c"), added.prompts.map { it.id })
    }

    @Test
    fun `a title and a text of some length make a prompt valid`() {
        assertTrue(polite.valid)
        assertFalse(polite.copy(title = " ").valid)
        assertFalse(polite.copy(text = "ab").valid)
    }

    @Test
    fun `a title and a text are bounded`() {
        val long = SavedPrompt("x", "т".repeat(100), "п".repeat(10_000)).normalized()
        assertEquals(SavedPrompt.MAX_TITLE_LENGTH, long.title.length)
        assertEquals(SavedPrompt.MAX_TEXT_LENGTH, long.text.length)
    }

    @Test
    fun `a default is added only when it is missing`() {
        val edited = library.with(polite.copy(text = "Свой текст."))
        assertEquals("Свой текст.", edited.withDefault(polite).find("a")?.text)
        assertEquals(3, library.withDefault(SavedPrompt("c", "Новый", "Текст промпта.")).prompts.size)
    }

    @Test
    fun `places that use a prompt are found`() {
        val selections =
            mapOf<PromptPlace, List<String>?>(
                PromptPlace.RECOGNITION to listOf("a"),
                PromptPlace.ENHANCEMENT to listOf("b"),
                PromptPlace.DICTATION to listOf("a", "b"),
                PromptPlace.PROOFREAD to null,
            )
        assertEquals(listOf(PromptPlace.RECOGNITION, PromptPlace.DICTATION), selections.placesUsing("a"))
        assertTrue(selections.placesUsing("gone").isEmpty())
    }

    @Test
    fun `deleting a prompt keeps the rest of a selection`() {
        val removal =
            PromptRemoval.of(library, mapOf(PromptPlace.DICTATION to listOf("a", "b")), "a") { null }
        assertEquals(listOf("b"), removal.library.prompts.map { it.id })
        assertEquals(listOf("b"), removal.selections[PromptPlace.DICTATION])
    }

    @Test
    fun `a place left without a prompt goes back to its built-in one`() {
        val recognition = SavedPrompt(PromptLibrary.RECOGNITION_ID, "Расшифровка", "Расшифруй речь дословно.")
        val removal =
            PromptRemoval.of(
                library,
                mapOf(PromptPlace.RECOGNITION to listOf("a"), PromptPlace.ENHANCEMENT to listOf("b")),
                "a",
            ) { recognition.takeIf { _ -> it == PromptLibrary.RECOGNITION_ID } }
        assertEquals(listOf(PromptLibrary.RECOGNITION_ID), removal.selections[PromptPlace.RECOGNITION])
        assertEquals(listOf("b"), removal.selections[PromptPlace.ENHANCEMENT])
        assertEquals(recognition, removal.library.find(PromptLibrary.RECOGNITION_ID))
    }

    @Test
    fun `a function left without a prompt follows the shared server again`() {
        val removal = PromptRemoval.of(library, mapOf(PromptPlace.PROOFREAD to listOf("b")), "b") { null }
        assertNull(removal.selections[PromptPlace.PROOFREAD])
    }

    @Test
    fun `a built-in prompt deleted on purpose does not come back`() {
        val builtIn = SavedPrompt(PromptLibrary.ENHANCEMENT_ID, "Исправление", "Исправь ошибки.")
        val withBuiltIn = library.with(builtIn)
        val removal =
            PromptRemoval.of(
                withBuiltIn,
                mapOf(PromptPlace.ENHANCEMENT to listOf(PromptLibrary.ENHANCEMENT_ID)),
                PromptLibrary.ENHANCEMENT_ID,
            ) { builtIn }
        assertNull(removal.library.find(PromptLibrary.ENHANCEMENT_ID))
        assertEquals(emptyList(), removal.selections[PromptPlace.ENHANCEMENT])
    }

    @Test
    fun `places that did not use the prompt stay as they were`() {
        val selections =
            mapOf<PromptPlace, List<String>?>(
                PromptPlace.DICTATION to null,
                PromptPlace.PROOFREAD to listOf("b"),
            )
        assertEquals(selections, PromptRemoval.of(library, selections, "a") { null }.selections)
    }

    @Test
    fun `a prompt has no color unless one is chosen and saving keeps it`() {
        assertNull(polite.color)
        val colored = library.with(polite.copy(color = ButtonColor.BLUE))
        assertEquals(ButtonColor.BLUE, colored.find("a")?.color)
        assertEquals(ButtonColor.BLUE, colored.with(colored.find("a")!!.copy(title = "Мягко")).find("a")?.color)
        assertNull(colored.find("b")?.color)
    }

    @Test
    fun `only valid prompts are offered for a choice on the keyboard`() {
        val untitled = SavedPrompt("c", " ", "Пиши строго и по делу.")
        val tooShort = SavedPrompt("d", "Пусто", "Да")
        val offered = PromptLibrary(listOf(polite, untitled, tooShort, short)).pickable
        assertEquals(listOf("a", "b"), offered.map { it.id })
    }

    @Test
    fun `a command that asks for a prompt sends the chosen one`() {
        val command = ButtonCommand(ButtonAction.PROMPT, "s", listOf("old"), InsertMode.APPEND, pickPrompt = true)
        assertEquals(
            ButtonCommand(ButtonAction.PROMPT, "s", listOf("b"), InsertMode.APPEND),
            command.withPrompt("b"),
        )
    }

    @Test
    fun `a hold of proofreading left without a prompt has none`() {
        val removal =
            PromptRemoval.of(library, mapOf(PromptPlace.PROOFREAD_HOLD to listOf("b")), "b") { null }
        assertNull(removal.selections[PromptPlace.PROOFREAD_HOLD])
    }
}
