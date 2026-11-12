package ru.tinyops.android.vboard.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.tinyops.android.vboard.core.voice.EnhancementOutcome

class EnhancementQueueTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val inserted = ArrayList<String>()
    private val failures = ArrayList<EnhancementOutcome>()
    private val answers = HashMap<String, CompletableDeferred<TextEnhancer.Result>>()
    private val requests = ArrayList<String>()
    private val pieces = ArrayList<Triple<String, String, Boolean>>()

    private val queue =
        EnhancementQueue(
            scope = scope,
            enhance = { text ->
                requests += text
                answers.getOrPut(text) { CompletableDeferred() }.await()
            },
            onFailure = { failures += it },
            insert = { inserted += it },
        )

    /** Improves only the phrases that contain the word "long". */
    private val selective =
        EnhancementQueue(
            scope = scope,
            shouldEnhance = { "long" in it },
            enhance = { text ->
                requests += text
                answers.getOrPut(text) { CompletableDeferred() }.await()
            },
            onFailure = { failures += it },
            insert = { inserted += it },
            onInserted = { recognized, text, improved -> pieces += Triple(recognized, text, improved) },
        )

    @After
    fun tearDown() = scope.cancel()

    private fun answer(
        text: String,
        output: String = text.uppercase(),
        outcome: EnhancementOutcome = EnhancementOutcome.SUCCESS,
    ) {
        answers.getOrPut(text) { CompletableDeferred() }.complete(TextEnhancer.Result(output, outcome))
    }

    @Test
    fun `phrases are improved one at a time and inserted in dictation order`() {
        queue.submit("one")
        queue.submit("two")
        assertEquals("the second phrase waits for the first", listOf("one"), requests)

        answer("two")
        assertEquals(emptyList<String>(), inserted)

        answer("one")
        assertEquals(listOf("ONE", "TWO"), inserted)
    }

    @Test
    fun `idle only when every phrase is inserted`() {
        assertTrue(queue.idle)
        queue.submit("one")
        assertFalse(queue.idle)
        answer("one")
        assertTrue(queue.idle)
    }

    @Test
    fun `failure inserts the recognized text and is reported once per dictation`() {
        queue.submit("one")
        queue.submit("two")
        answer("one", output = "one", outcome = EnhancementOutcome.FAILED)
        answer("two", output = "two", outcome = EnhancementOutcome.AUTH_FAILED)

        assertEquals(listOf("one", "two"), inserted)
        assertEquals(listOf(EnhancementOutcome.FAILED), failures)
    }

    @Test
    fun `finish waits for the phrases still in the queue`() =
        runBlocking {
            queue.submit("one")
            var finished = false
            val waiting =
                scope.launch {
                    queue.finish()
                    finished = true
                }
            assertFalse(finished)

            answer("one")
            waiting.join()
            assertTrue(finished)
            assertEquals(listOf("ONE"), inserted)
        }

    @Test
    fun `cancel drops everything not inserted yet`() {
        queue.submit("one")
        queue.submit("two")
        queue.cancel()
        answer("one")
        answer("two")

        assertEquals(emptyList<String>(), inserted)
        assertEquals(listOf("one"), requests)
    }

    @Test
    fun `a short phrase is inserted at once without a request`() {
        selective.submit("short")

        assertEquals(listOf("short"), inserted)
        assertEquals(emptyList<String>(), requests)
        assertTrue("nothing to wait for at the end of the dictation", selective.idle)
    }

    @Test
    fun `a short phrase does not overtake a long one`() {
        selective.submit("long one")
        selective.submit("short")
        selective.submit("long two")
        assertEquals(emptyList<String>(), inserted)
        assertFalse(selective.idle)

        answer("long one")
        assertEquals("the short phrase follows its turn", listOf("LONG ONE", "short"), inserted)

        answer("long two")
        assertEquals(listOf("LONG ONE", "short", "LONG TWO"), inserted)
        assertEquals(listOf("long one", "long two"), requests)
        assertTrue(selective.idle)
    }

    @Test
    fun `every inserted piece is reported with what was recognized and whether the model improved it`() {
        selective.submit("short")
        selective.submit("long one")
        selective.submit("long two")
        answer("long one")
        answer("long two", output = "long two", outcome = EnhancementOutcome.FAILED)

        assertEquals(
            listOf(
                Triple("short", "short", false),
                Triple("long one", "LONG ONE", true),
                Triple("long two", "long two", false),
            ),
            pieces,
        )
    }
}
