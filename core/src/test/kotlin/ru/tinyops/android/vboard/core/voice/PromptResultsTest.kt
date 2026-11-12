package ru.tinyops.android.vboard.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PromptResultsTest {
    private val polish = PromptTag("p1", "Polish")
    private val formal = PromptTag("p2", "Formal")

    private fun answer(
        id: Long,
        text: String,
        vararg prompts: PromptTag,
    ) = PromptResult(id, text, PromptResult.Kind.ANSWER, prompts.toList())

    private fun transcript(
        id: Long,
        text: String,
    ) = PromptResult(id, text, PromptResult.Kind.TRANSCRIPT)

    @Test
    fun `the newest results go first, a group keeps its order`() {
        val history =
            PromptResultHistory()
                .add(listOf(answer(1, "a", polish)))
                .add(listOf(answer(3, "improved", polish), transcript(2, "raw")))
        assertEquals(listOf("improved", "raw", "a"), history.entries.map { it.text })
    }

    @Test
    fun `the same text of the same kind is moved up instead of repeated`() {
        val history =
            PromptResultHistory()
                .add(listOf(answer(1, "a", polish)))
                .add(listOf(answer(2, "b", polish)))
                .add(listOf(answer(3, "a", formal)))
        assertEquals(listOf("a", "b"), history.entries.map { it.text })
        assertEquals(listOf(formal), history.entries.first().prompts)
    }

    @Test
    fun `a transcript and an answer with the same text are both kept`() {
        val history = PromptResultHistory().add(listOf(answer(2, "same", polish), transcript(1, "same")))
        assertEquals(2, history.entries.size)
    }

    @Test
    fun `the oldest results leave a full history`() {
        val history =
            PromptResultHistory(capacity = 2)
                .add(listOf(answer(1, "a", polish)))
                .add(listOf(answer(2, "b", polish)))
                .add(listOf(answer(3, "c", polish)))
        assertEquals(listOf("c", "b"), history.entries.map { it.text })
    }

    @Test
    fun `a new order puts the results where the user dragged them`() {
        val history =
            PromptResultHistory(listOf(answer(1, "a", polish), answer(2, "b", polish), answer(3, "c", polish)))
                .reorder(listOf(3, 1, 2))
        assertEquals(listOf("c", "a", "b"), history.entries.map { it.text })
    }

    @Test
    fun `a new order of a filtered part leaves the other results in their places`() {
        val history =
            PromptResultHistory(
                listOf(answer(1, "a", polish), transcript(2, "raw"), answer(3, "c", polish), transcript(4, "raw2")),
            ).reorder(listOf(3, 1))
        assertEquals(listOf("c", "raw", "a", "raw2"), history.entries.map { it.text })
    }

    @Test
    fun `an order with unknown ids changes nothing it does not know`() {
        val start = PromptResultHistory(listOf(answer(1, "a", polish), answer(2, "b", polish)))
        assertEquals(listOf("b", "a"), start.reorder(listOf(2, 9, 1)).entries.map { it.text })
        assertEquals(start, start.reorder(listOf(9)))
    }

    @Test
    fun `blank texts are not kept`() {
        val history = PromptResultHistory().add(listOf(answer(1, "  ", polish)))
        assertEquals(emptyList(), history.entries)
    }

    @Test
    fun `remove and clear`() {
        val history = PromptResultHistory().add(listOf(answer(2, "b", polish), transcript(1, "a")))
        assertEquals(listOf("a"), history.remove(2).entries.map { it.text })
        assertEquals(emptyList(), history.clear().entries)
    }

    @Test
    fun `filters list every prompt once, the most recently used first, transcripts after all`() {
        val history =
            PromptResultHistory()
                .add(listOf(answer(1, "a", polish)))
                .add(listOf(answer(3, "b", formal, polish), transcript(2, "raw")))
        assertEquals(
            listOf(
                PromptFilter.All,
                PromptFilter.Transcripts,
                PromptFilter.Prompt("p2", "Formal"),
                PromptFilter.Prompt("p1", "Polish"),
            ),
            history.filters(),
        )
    }

    @Test
    fun `a filter by prompt shows only its answers, transcripts show only raw texts`() {
        val history =
            PromptResultHistory()
                .add(listOf(answer(1, "a", polish)))
                .add(listOf(answer(3, "b", formal, polish), transcript(2, "raw")))
        assertEquals(listOf("b", "raw", "a"), history.matching(PromptFilter.All).map { it.text })
        assertEquals(listOf("raw"), history.matching(PromptFilter.Transcripts).map { it.text })
        assertEquals(listOf("b", "a"), history.matching(PromptFilter.Prompt("p1", "Polish")).map { it.text })
        assertEquals(listOf("b"), history.matching(PromptFilter.Prompt("p2", "Formal")).map { it.text })
    }

    @Test
    fun `a filter that matches nothing any more falls back to all`() {
        val history = PromptResultHistory().add(listOf(answer(1, "a", polish)))
        assertEquals(PromptFilter.All, history.available(PromptFilter.Prompt("gone", "Gone")))
        assertEquals(PromptFilter.All, history.available(PromptFilter.Transcripts))
        assertEquals(PromptFilter.Prompt("p1", "Polish"), history.available(PromptFilter.Prompt("p1", "Old title")))
    }

    @Test
    fun `a dictation gives its raw and improved text when the model changed something`() {
        val dictation = DictationResults()
        dictation.add("привет как дела", "Привет, как дела?", improved = true)
        dictation.add("ок", "ок", improved = false)
        assertEquals(DictationResults.Texts("привет как дела ок", "Привет, как дела? ок"), dictation.texts())
    }

    @Test
    fun `a dictation the model did not change gives nothing`() {
        val dictation = DictationResults()
        assertNull(dictation.texts())
        dictation.add("ок", "ок", improved = false)
        assertNull(dictation.texts())
        dictation.add("да", "да", improved = true)
        assertNull(dictation.texts())
    }

    @Test
    fun `tags name the chosen prompts that still exist`() {
        val library = PromptLibrary(listOf(SavedPrompt("p1", "Polish", "text"), SavedPrompt("p2", "Formal", "text")))
        assertEquals(listOf(PromptTag("p2", "Formal")), library.tags(listOf("p2", "gone")))
    }
}
