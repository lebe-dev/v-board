package ru.tinyops.android.vboard.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.tinyops.android.vboard.core.voice.DictationResults
import ru.tinyops.android.vboard.core.voice.EnhancementOutcome
import ru.tinyops.android.vboard.core.voice.PromptResult
import ru.tinyops.android.vboard.core.voice.PromptTag
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PromptResultStoreTest {
    private val context = RuntimeEnvironment.getApplication()
    private var enabled = true
    private var now = 1_000L
    private var changes = 0
    private val polish = PromptTag("p1", "Polish")

    @Before
    fun cleanUp() {
        File(context.filesDir, "prompt_results").deleteRecursively()
    }

    private fun newStore() =
        PromptResultStore(context, { enabled }, executor = { it.run() }, notify = { it.run() }, clock = { now })
            .also { it.onChanged = { changes++ } }

    @Test
    fun `a dictation keeps the improved text on top of the raw one, and both survive a restart`() {
        newStore().recordDictation(DictationResults.Texts("привет как дела", "Привет, как дела?"), listOf(polish))

        val entries = newStore().history.entries
        assertEquals(listOf("Привет, как дела?", "привет как дела"), entries.map { it.text })
        assertEquals(listOf(PromptResult.Kind.ANSWER, PromptResult.Kind.TRANSCRIPT), entries.map { it.kind })
        assertEquals(listOf(polish), entries.first().prompts)
        assertTrue("ids differ within one millisecond", entries[0].id != entries[1].id)
    }

    @Test
    fun `only successful answers are kept`() {
        val store = newStore()
        store.recordAnswer(TextEnhancer.Result("same", EnhancementOutcome.UNCHANGED), listOf(polish))
        store.recordAnswer(TextEnhancer.Result("orig", EnhancementOutcome.FAILED), listOf(polish))
        store.recordAnswer(TextEnhancer.Result("Fixed.", EnhancementOutcome.SUCCESS), listOf(polish))
        assertEquals(listOf("Fixed."), store.history.entries.map { it.text })
        assertEquals(1, changes)
    }

    @Test
    fun `nothing is kept while the buffer is off`() {
        enabled = false
        val store = newStore()
        store.recordAnswer(TextEnhancer.Result("Fixed.", EnhancementOutcome.SUCCESS), listOf(polish))
        store.recordDictation(DictationResults.Texts("a", "A."), listOf(polish))
        assertEquals(emptyList<PromptResult>(), store.history.entries)
    }

    @Test
    fun `remove and clear reach the disk`() {
        val store = newStore()
        store.recordAnswer(TextEnhancer.Result("one", EnhancementOutcome.SUCCESS), listOf(polish))
        now++
        store.recordAnswer(TextEnhancer.Result("two", EnhancementOutcome.SUCCESS), listOf(polish))
        store.remove(
            store.history.entries
                .first()
                .id,
        )
        assertEquals(listOf("one"), newStore().history.entries.map { it.text })
        store.clear()
        assertEquals(emptyList<PromptResult>(), newStore().history.entries)
    }

    @Test
    fun `a new order reaches the disk`() {
        val store = newStore()
        store.recordAnswer(TextEnhancer.Result("one", EnhancementOutcome.SUCCESS), listOf(polish))
        now++
        store.recordAnswer(TextEnhancer.Result("two", EnhancementOutcome.SUCCESS), listOf(polish))
        store.reorder(
            store.history.entries
                .map { it.id }
                .reversed(),
        )
        assertEquals(listOf("one", "two"), newStore().history.entries.map { it.text })
    }

    @Test
    fun `writes leave no temporary file next to the index`() {
        newStore().recordAnswer(TextEnhancer.Result("Fixed.", EnhancementOutcome.SUCCESS), listOf(polish))
        assertEquals(listOf("history.json"), File(context.filesDir, "prompt_results").list().orEmpty().toList())
    }

    @Test
    fun `a closed store finishes its writes and ignores what comes after`() {
        val executor = Executors.newSingleThreadExecutor()
        val store = PromptResultStore(context, { true }, executor = executor, notify = { it.run() }, clock = { now })
        store.recordAnswer(TextEnhancer.Result("Fixed.", EnhancementOutcome.SUCCESS), listOf(polish))
        store.close()
        store.recordAnswer(TextEnhancer.Result("Later.", EnhancementOutcome.SUCCESS), listOf(polish))
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(listOf("Fixed."), newStore().history.entries.map { it.text })
    }
}
