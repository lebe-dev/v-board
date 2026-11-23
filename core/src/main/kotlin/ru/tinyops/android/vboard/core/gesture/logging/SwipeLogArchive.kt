package ru.tinyops.android.vboard.core.gesture.logging

import ru.tinyops.android.vboard.core.gesture.neural.ScoredCandidate
import ru.tinyops.android.vboard.stt.model.Key
import ru.tinyops.android.vboard.stt.model.KeyboardLayout
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

/**
 * Writes free-typing swipe log entries as a zip archive compatible with `prepare_valid.py` and
 * `evaluate.py`: `valid.ref` holds the target word per line, `valid.jsonl` holds the gesture curve,
 * grid, candidates, score breakdown and user outcome, and `meta.json` holds recording metadata.
 */
object SwipeLogArchive {
    const val DEFAULT_GRID = "default"

    fun write(
        entries: List<SwipeLogEntry>,
        meta: Map<String, String>,
        output: OutputStream,
    ) {
        val grids = gridNames(entries)
        ZipOutputStream(output).use { zip ->
            zip.entry("valid.ref", entries.joinToString("") { it.referenceWord() + "\n" })
            zip.entry("valid.jsonl", entries.joinToString("") { line(it, grids.getValue(it.layout)) + "\n" })
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

    private fun gridNames(entries: List<SwipeLogEntry>): Map<KeyboardLayout, String> {
        val counts = LinkedHashMap<KeyboardLayout, Int>()
        for (entry in entries) counts[entry.layout] = (counts[entry.layout] ?: 0) + 1
        val main = counts.maxByOrNull { it.value }?.key
        var next = 1
        return counts.keys.associateWith { layout -> if (layout == main) DEFAULT_GRID else "grid-${++next}" }
    }

    private fun line(
        entry: SwipeLogEntry,
        grid: String,
    ): String =
        buildString {
            append("{\"word\":").append(json(entry.referenceWord()))
            append(",\"curve\":{")
            append("\"x\":").append(numbers(entry.points.map { it.x.roundToInt() }))
            append(",\"y\":").append(numbers(entry.points.map { it.y.roundToInt() }))
            append(",\"t\":").append(numbers(entry.points.map { it.timeMillis.roundToInt() }))
            append(",\"grid\":").append(grid(entry.layout, grid))
            append("},\"candidates\":").append(entry.candidates.joinToString(",", "[", "]", transform = ::json))
            if (entry.candidateScores.isNotEmpty()) {
                append(",\"candidate_scores\":")
                append(entry.candidateScores.joinToString(",", "[", "]", transform = ::score))
            }
            append(",\"engine\":").append(json(entry.engine))
            if (entry.previousWord != null) {
                append(",\"previous_word\":").append(json(entry.previousWord))
            }
            append(",\"inserted\":").append(json(entry.inserted))
            append(",\"outcome\":").append(json(entry.outcome.code))
            append(",\"final_word\":").append(json(entry.finalWord))
            append(",\"timestamp\":").append(entry.timestampMillis)
            append('}')
        }

    private fun score(candidate: ScoredCandidate): String =
        buildString {
            append("{\"word\":").append(json(candidate.word))
            append(",\"search\":").append(decimal(candidate.searchScore))
            append(",\"model\":").append(decimal(candidate.modelScore))
            append(",\"frequency\":").append(decimal(candidate.frequencyTerm))
            append(",\"bigram\":").append(decimal(candidate.bigramTerm))
            append(",\"geometry\":").append(decimal(candidate.geometryTerm))
            append(",\"total\":").append(decimal(candidate.totalScore))
            append('}')
        }

    private fun decimal(value: Double): String =
        if (value.isFinite()) String.format(Locale.ROOT, "%.4f", value) else "0.0000"

    private fun numbers(values: List<Int>): String = values.joinToString(",", "[", "]")

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
