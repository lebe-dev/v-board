package ru.tinyops.android.vboard.core.gesture.calibration

import ru.tinyops.android.vboard.stt.model.Key
import ru.tinyops.android.vboard.stt.model.KeyboardLayout
import ru.tinyops.android.vboard.stt.model.TouchAction
import ru.tinyops.android.vboard.stt.model.TouchPoint
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwipeSampleArchiveTest {
    private fun layout(width: Float): KeyboardLayout {
        val keys =
            listOf(
                Key(1, "й", 0f, 10f, 50f, 60f),
                Key(2, "е", 50f, 10f, 100f, 60f),
                Key(3, "ё", 50f, 10f, 100f, 60f),
            )
        return KeyboardLayout(width = width, height = 70f, xdpi = 400f, ydpi = 400f, keys = keys)
    }

    private fun sample(
        word: String,
        layout: KeyboardLayout,
        candidates: List<String> = listOf(word),
    ) = SwipeSample(
        word = word,
        points =
            listOf(
                TouchPoint(TouchAction.DOWN, 0, 10.4f, 20.6f, 0.0),
                TouchPoint(TouchAction.UP, 0, 70f, 30f, 48.7),
            ),
        layout = layout,
        candidates = candidates,
        engine = "experimental",
    )

    private fun entries(samples: List<SwipeSample>): Map<String, String> {
        val bytes = ByteArrayOutputStream().also { SwipeSampleArchive.write(samples, mapOf("app" to "1.0"), it) }
        val result = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return result
    }

    @Test
    fun `the archive holds the words, the gestures and the details of the recording`() {
        val files = entries(listOf(sample("ещё", layout(100f)), sample("день", layout(100f))))
        assertEquals(setOf("valid.ref", "valid.jsonl", "meta.json"), files.keys)
        assertEquals("ещё\nдень\n", files.getValue("valid.ref"))
        assertEquals(2, files.getValue("valid.jsonl").lines().count { it.isNotEmpty() })
        assertTrue(files.getValue("meta.json").contains("\"app\":\"1.0\""))
    }

    @Test
    fun `a gesture line keeps whole pixels and milliseconds and the grid of its keys`() {
        val line = entries(listOf(sample("день", layout(100f)))).getValue("valid.jsonl").lines().first()
        assertTrue(line.contains("\"x\":[10,70]"), line)
        assertTrue(line.contains("\"y\":[21,30]"), line)
        assertTrue(line.contains("\"t\":[0,49]"), line)
        assertTrue(line.contains("\"grid_name\":\"default\""), line)
        assertTrue(line.contains("{\"label\":\"й\",\"hitbox\":{\"x\":0,\"y\":10,\"w\":50,\"h\":50}}"), line)
        assertTrue(line.contains("\"candidates\":[\"день\"]"), line)
    }

    @Test
    fun `a letter sharing the key of another letter is left out of the grid`() {
        val line = entries(listOf(sample("день", layout(100f)))).getValue("valid.jsonl").lines().first()
        assertFalse(line.contains("\"label\":\"ё\""), line)
    }

    @Test
    fun `the keyboard most gestures were made on is the default grid`() {
        val small = layout(100f)
        val wide = layout(200f)
        val lines =
            entries(listOf(sample("а", small), sample("б", wide), sample("в", wide)))
                .getValue("valid.jsonl")
                .lines()
        assertTrue(lines[0].contains("\"grid_name\":\"grid-2\""), lines[0])
        assertTrue(lines[1].contains("\"grid_name\":\"default\""), lines[1])
        assertTrue(lines[2].contains("\"grid_name\":\"default\""), lines[2])
    }

    @Test
    fun `quotes and backslashes in words are escaped`() {
        val line = entries(listOf(sample("a\"b\\c", layout(100f)))).getValue("valid.jsonl").lines().first()
        assertTrue(line.contains("\"word\":\"a\\\"b\\\\c\""), line)
    }

    @Test
    fun `the score counts the first candidate and any shown candidate, ignoring case and ё`() {
        val keyboard = layout(100f)
        val score =
            CalibrationScore.of(
                listOf(
                    sample("ещё", keyboard, listOf("Еще", "уже")),
                    sample("день", keyboard, listOf("дело", "день")),
                    sample("всё", keyboard, listOf("все")),
                    sample("мама", keyboard, listOf("папа")),
                ),
            )
        assertEquals(CalibrationScore(total = 4, first = 2, shown = 3), score)
    }
}
