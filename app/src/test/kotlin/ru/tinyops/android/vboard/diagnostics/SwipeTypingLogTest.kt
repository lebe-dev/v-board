package ru.tinyops.android.vboard.diagnostics

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import ru.tinyops.android.vboard.EditorProfile
import ru.tinyops.android.vboard.KeyboardPrefs
import ru.tinyops.android.vboard.core.gesture.GestureDecodeResult
import ru.tinyops.android.vboard.core.gesture.logging.SwipeLogEntry
import ru.tinyops.android.vboard.core.gesture.logging.SwipeOutcome
import ru.tinyops.android.vboard.core.gesture.neural.ScoredCandidate
import ru.tinyops.android.vboard.core.layout.Language
import ru.tinyops.android.vboard.stt.model.Key
import ru.tinyops.android.vboard.stt.model.KeyboardLayout
import ru.tinyops.android.vboard.stt.model.TouchAction
import ru.tinyops.android.vboard.stt.model.TouchPoint
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
class SwipeTypingLogTest {
    private lateinit var context: Context

    private val layout =
        KeyboardLayout(
            width = 1080f,
            height = 600f,
            xdpi = 420f,
            ydpi = 420f,
            keys =
                listOf(
                    Key(1, "й", 0f, 0f, 100f, 150f),
                    Key(2, "е", 400f, 0f, 500f, 150f),
                ),
        )

    private val points =
        listOf(
            TouchPoint(TouchAction.DOWN, 0, 50f, 75f, 0.0),
            TouchPoint(TouchAction.UP, 0, 450f, 75f, 120.0),
        )

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        SwipeTypingLog.init(context)
        SwipeTypingLog.setEnabled(false)
        SwipeTypingLog.clear()
        SwipeTypingLog.setLayout(Language.RUSSIAN, layout)
    }

    @Test
    fun `disabled by default and turns off automatically once the deadline expires`() {
        val prefs = KeyboardPrefs(context)
        assertFalse(prefs.isSwipeLogActive(1_000L))

        SwipeTypingLog.setEnabled(true, nowMillis = 1_000L)
        assertTrue(SwipeTypingLog.isRecording(nowMillis = 1_000L + 60_000L))
        assertFalse(SwipeTypingLog.isRecording(nowMillis = 1_000L + SwipeTypingLog.AUTO_OFF_MILLIS))
        assertEquals(0L, prefs.swipeLogUntilMillis)
    }

    @Test
    fun `editor profile disables swipe logging on password and incognito fields`() {
        val normal = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
        val password =
            EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        val incognito =
            EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT
                imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            }

        assertTrue(EditorProfile.from(normal).swipeLogAllowed)
        assertFalse(EditorProfile.from(password).swipeLogAllowed)
        assertFalse(EditorProfile.from(incognito).swipeLogAllowed)
    }

    @Test
    fun `records swipe, tracks picked suggestion, exports valid zip and clears cleanly`() {
        SwipeTypingLog.setEnabled(true, nowMillis = 10_000L)
        val result =
            GestureDecodeResult(
                words = listOf("похож", "поход"),
                details =
                    listOf(
                        ScoredCandidate("похож", 1.1, 1.4, -0.3, 0.0, 0.2, 1.3),
                        ScoredCandidate("поход", 1.2, 1.5, -0.3, 0.0, 0.0, 1.2),
                    ),
                engine = "experimental",
            )
        SwipeTypingLog.recordSwipe(Language.RUSSIAN, points, "наш", result, "похож", nowMillis = 10_500L)
        SwipeTypingLog.onSuggestionPicked("поход")

        val recorded = SwipeTypingLog.entries()
        assertEquals(1, recorded.size)
        assertEquals(SwipeOutcome.PICKED_SUGGESTION, recorded.first().outcome)
        assertEquals("поход", recorded.first().finalWord)

        val bytes = ByteArrayOutputStream().also { SwipeTypingLog.writeArchive(context, it) }
        val files = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                files[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals("поход\n", files["valid.ref"])
        assertTrue(files["valid.jsonl"].orEmpty().contains("\"previous_word\":\"наш\""))
        assertTrue(files["meta.json"].orEmpty().contains("\"type\":\"free_typing_log\""))

        SwipeTypingLog.clear()
        assertTrue(SwipeTypingLog.entries().isEmpty())
    }

    @Test
    fun `store round-trips entries and deletes file on clear`() {
        val file = File(context.noBackupFilesDir, "test-swipe-log.jsonl")
        val store = SwipeLogStore(file)
        val entry =
            SwipeLogEntry(
                id = 7L,
                timestampMillis = 123_456L,
                points = points,
                layout = layout,
                previousWord = "привет",
                engine = "experimental",
                candidates = listOf("мир"),
                candidateScores = listOf(ScoredCandidate("мир", 1.0, 1.2, -0.2, -0.1, 0.05, 0.95)),
                inserted = "мир",
                outcome = SwipeOutcome.RETYPED,
                finalWord = "миру",
            )
        store.write(listOf(entry))
        val loaded = store.read()
        assertEquals(1, loaded.size)
        assertEquals(entry.id, loaded.first().id)
        assertEquals(entry.previousWord, loaded.first().previousWord)
        assertEquals(entry.outcome, loaded.first().outcome)
        assertEquals(entry.finalWord, loaded.first().finalWord)
        assertEquals(entry.candidateScores, loaded.first().candidateScores)

        store.clear()
        assertFalse(file.exists())
        assertTrue(store.read().isEmpty())
    }
}
