package ru.tinyops.android.vboard.core.gesture.calibration

import ru.tinyops.android.vboard.stt.model.Key
import ru.tinyops.android.vboard.stt.model.KeyboardLayout
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

/**
 * Writes calibration gestures as a zip in the layout of the NeuroSwipe validation set, so the slide to
 * type evaluation reads it as is: `valid.ref` holds a word per line, `valid.jsonl` the matching
 * gesture per line, `meta.json` the details of the recording.
 *
 * Every keyboard size gets its own grid; the one most gestures were made on is `default`.
 */
object SwipeSampleArchive {
    const val DEFAULT_GRID = "default"

    fun write(
        samples: List<SwipeSample>,
        meta: Map<String, String>,
        output: OutputStream,
    ) {
        val grids = gridNames(samples)
        ZipOutputStream(output).use { zip ->
            zip.entry("valid.ref", samples.joinToString("") { it.word + "\n" })
            zip.entry("valid.jsonl", samples.joinToString("") { line(it, grids.getValue(it.layout)) + "\n" })
            zip.entry("meta.json", meta.entries.joinToString(",", "{", "}\n") { "${json(it.key)}:${json(it.value)}" })
        }
    }

    private fun ZipOutputStream.entry(
        name: String,
        text: String,
    ) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun gridNames(samples: List<SwipeSample>): Map<KeyboardLayout, String> {
        val counts = LinkedHashMap<KeyboardLayout, Int>()
        for (sample in samples) counts[sample.layout] = (counts[sample.layout] ?: 0) + 1
        val main = counts.maxByOrNull { it.value }?.key
        var next = 1
        return counts.keys.associateWith { layout -> if (layout == main) DEFAULT_GRID else "grid-${++next}" }
    }

    private fun line(
        sample: SwipeSample,
        grid: String,
    ): String =
        buildString {
            append("{\"word\":").append(json(sample.word))
            append(",\"curve\":{")
            append("\"x\":").append(numbers(sample.points.map { it.x.roundToInt() }))
            append(",\"y\":").append(numbers(sample.points.map { it.y.roundToInt() }))
            append(",\"t\":").append(numbers(sample.points.map { it.timeMillis.roundToInt() }))
            append(",\"grid\":").append(grid(sample.layout, grid))
            append("},\"candidates\":").append(sample.candidates.joinToString(",", "[", "]", transform = ::json))
            append(",\"engine\":").append(json(sample.engine))
            append('}')
        }

    private fun numbers(values: List<Int>): String = values.joinToString(",", "[", "]")

    /** Letters that share a key with another letter (`ё` on `е`) are not keys of their own. */
    private fun grid(
        layout: KeyboardLayout,
        name: String,
    ): String {
        val seen = HashSet<List<Float>>()
        val keys = layout.keys.filter { seen.add(listOf(it.left, it.top, it.right, it.bottom)) }
        return buildString {
            append("{\"grid_name\":").append(json(name))
            append(",\"width\":").append(layout.width.roundToInt())
            append(",\"height\":").append(layout.height.roundToInt())
            append(",\"keys\":").append(keys.joinToString(",", "[", "]", transform = ::key))
            append('}')
        }
    }

    private fun key(key: Key): String {
        val x = key.left.roundToInt()
        val y = key.top.roundToInt()
        val width = key.right.roundToInt() - x
        val height = key.bottom.roundToInt() - y
        return "{\"label\":${json(key.label)},\"hitbox\":{\"x\":$x,\"y\":$y,\"w\":$width,\"h\":$height}}"
    }

    private fun json(text: String): String =
        buildString(text.length + 2) {
            append('"')
            for (char in text) {
                when {
                    char == '"' || char == '\\' -> append('\\').append(char)
                    char < ' ' -> append("\\u%04x".format(Locale.ROOT, char.code))
                    else -> append(char)
                }
            }
            append('"')
        }
}
