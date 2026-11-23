package ru.tinyops.android.vboard.core.gesture.logging

import ru.tinyops.android.vboard.core.gesture.neural.ScoredCandidate
import ru.tinyops.android.vboard.stt.model.Key
import ru.tinyops.android.vboard.stt.model.KeyboardLayout
import ru.tinyops.android.vboard.stt.model.TouchAction
import ru.tinyops.android.vboard.stt.model.TouchPoint
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SwipeLogTest {
    private val layout =
        KeyboardLayout(
            width = 100f,
            height = 70f,
            xdpi = 420f,
            ydpi = 420f,
            keys =
                listOf(
                    Key(1, "й", 0f, 10f, 50f, 60f),
                    Key(2, "е", 50f, 10f, 100f, 60f),
                    Key(3, "ё", 50f, 10f, 100f, 60f),
                ),
        )

    private fun entry(
        id: Long,
        inserted: String,
        outcome: SwipeOutcome = SwipeOutcome.KEPT,
        finalWord: String = inserted,
    ) = SwipeLogEntry(
        id = id,
        timestampMillis = 1_700_000_000_000L + id,
        points =
            listOf(
                TouchPoint(TouchAction.DOWN, 0, 10f, 20f, 0.0),
                TouchPoint(TouchAction.UP, 0, 70f, 30f, 50.0),
            ),
        layout = layout,
        previousWord = "доброе",
        engine = "experimental",
        candidates = listOf(inserted, "другое"),
        candidateScores =
            listOf(
                ScoredCandidate(
                    word = inserted,
                    searchScore = 1.2,
                    modelScore = 1.5,
                    frequencyTerm = -0.4,
                    bigramTerm = -0.2,
                    geometryTerm = 0.05,
                    totalScore = 0.95,
                ),
            ),
        inserted = inserted,
        outcome = outcome,
        finalWord = finalWord,
    )

    @Test
    fun `ring buffer drops the oldest entries once full`() {
        val buffer = SwipeLogBuffer(capacity = 3)
        repeat(5) { index -> buffer.add(entry(index.toLong(), "word$index")) }

        assertEquals(3, buffer.size)
        assertEquals(listOf(2L, 3L, 4L), buffer.snapshot().map { it.id })
    }

    @Test
    fun `tracker records picked suggestion, deleted and retyped outcomes`() {
        val buffer = SwipeLogBuffer(capacity = 10)
        val tracker = SwipeLogTracker { id, outcome, finalWord -> buffer.updateOutcome(id, outcome, finalWord) }

        buffer.add(entry(1, "похож"))
        tracker.onSwipeCommitted(1, "похож")
        tracker.onSuggestionPicked("поход")
        tracker.onWordSettled()

        buffer.add(entry(2, "можно"))
        tracker.onSwipeCommitted(2, "можно")
        tracker.onSwipeWordDeleted()
        tracker.onTypedWordCommitted("может")

        buffer.add(entry(3, "привет"))
        tracker.onSwipeCommitted(3, "привет")
        tracker.onSwipeWordDeleted()
        tracker.reset()

        val all = buffer.snapshot()
        assertEquals(SwipeOutcome.PICKED_SUGGESTION, all[0].outcome)
        assertEquals("поход", all[0].finalWord)
        assertEquals("поход", all[0].referenceWord())

        assertEquals(SwipeOutcome.RETYPED, all[1].outcome)
        assertEquals("может", all[1].finalWord)
        assertEquals("может", all[1].referenceWord())

        assertEquals(SwipeOutcome.DELETED, all[2].outcome)
        assertEquals("", all[2].finalWord)
        assertEquals("привет", all[2].referenceWord())
    }

    @Test
    fun `tracker records swipe replacement after deleting a swiped word`() {
        val buffer = SwipeLogBuffer(capacity = 10)
        val tracker = SwipeLogTracker { id, outcome, finalWord -> buffer.updateOutcome(id, outcome, finalWord) }

        buffer.add(entry(1, "день"))
        tracker.onSwipeCommitted(1, "день")
        tracker.onSwipeWordDeleted()

        buffer.add(entry(2, "дела"))
        tracker.onSwipeCommitted(2, "дела")
        tracker.onWordSettled()

        val all = buffer.snapshot()
        assertEquals(SwipeOutcome.RETYPED, all[0].outcome)
        assertEquals("дела", all[0].finalWord)
        assertEquals(SwipeOutcome.KEPT, all[1].outcome)
    }

    @Test
    fun `archive writes NeuroSwipe compatible zip with score details and outcomes`() {
        val items =
            listOf(
                entry(1, "Похож", SwipeOutcome.PICKED_SUGGESTION, "Поход"),
                entry(2, "привет", SwipeOutcome.KEPT, "привет"),
            )
        val out = ByteArrayOutputStream().also { SwipeLogArchive.write(items, mapOf("app" to "1.2.0-beta.5"), it) }
        val files = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                val zipEntry = zip.nextEntry ?: break
                files[zipEntry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals(setOf("valid.ref", "valid.jsonl", "meta.json"), files.keys)
        assertEquals("поход\nпривет\n", files.getValue("valid.ref"))
        val firstLine = files.getValue("valid.jsonl").lines().first()
        assertTrue(firstLine.contains("\"outcome\":\"picked_suggestion\""), firstLine)
        assertTrue(firstLine.contains("\"final_word\":\"Поход\""), firstLine)
        assertTrue(firstLine.contains("\"previous_word\":\"доброе\""), firstLine)
        assertTrue(firstLine.contains("\"candidate_scores\":[{\"word\":\"Похож\""), firstLine)
        assertTrue(firstLine.contains("\"geometry\":0.0500"), firstLine)
        assertTrue(firstLine.contains("\"grid_name\":\"default\""), firstLine)
    }
}
