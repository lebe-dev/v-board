package ru.tinyops.android.vboard.diagnostics

import org.json.JSONArray
import org.json.JSONObject
import ru.tinyops.android.vboard.core.gesture.logging.SwipeLogEntry
import ru.tinyops.android.vboard.core.gesture.logging.SwipeOutcome
import ru.tinyops.android.vboard.core.gesture.neural.ScoredCandidate
import ru.tinyops.android.vboard.stt.model.Key
import ru.tinyops.android.vboard.stt.model.KeyboardLayout
import ru.tinyops.android.vboard.stt.model.TouchAction
import ru.tinyops.android.vboard.stt.model.TouchPoint
import java.io.File

/**
 * Persists the free-typing swipe ring buffer as line-delimited JSON in private non-backed-up storage.
 * Malformed lines are skipped so a partial write never drops the rest of the log.
 */
internal class SwipeLogStore(
    private val file: File,
) {
    fun read(): List<SwipeLogEntry> {
        if (!file.isFile) return emptyList()
        return runCatching {
            file.readLines(Charsets.UTF_8).mapNotNull { line ->
                if (line.isBlank()) null else runCatching { decodeEntry(JSONObject(line)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    fun write(entries: List<SwipeLogEntry>) {
        if (entries.isEmpty()) {
            clear()
            return
        }
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.bufferedWriter(Charsets.UTF_8).use { writer ->
                for (entry in entries) {
                    writer.write(encodeEntry(entry).toString())
                    writer.newLine()
                }
            }
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        }
    }

    fun clear() {
        runCatching { file.delete() }
    }

    private fun encodeEntry(entry: SwipeLogEntry): JSONObject =
        JSONObject().apply {
            put("id", entry.id)
            put("ts", entry.timestampMillis)
            put("points", encodePoints(entry.points))
            put("layout", encodeLayout(entry.layout))
            if (entry.previousWord != null) put("prev", entry.previousWord)
            put("engine", entry.engine)
            put("candidates", JSONArray(entry.candidates))
            put("scores", encodeScores(entry.candidateScores))
            put("inserted", entry.inserted)
            put("outcome", entry.outcome.code)
            put("final", entry.finalWord)
        }

    private fun encodePoints(points: List<TouchPoint>): JSONArray =
        JSONArray().apply {
            for (point in points) {
                put(
                    JSONArray().apply {
                        put(point.x.toDouble())
                        put(point.y.toDouble())
                        put(point.timeMillis)
                    },
                )
            }
        }

    private fun encodeLayout(layout: KeyboardLayout): JSONObject =
        JSONObject().apply {
            put("w", layout.width.toDouble())
            put("h", layout.height.toDouble())
            put("xdpi", layout.xdpi.toDouble())
            put("ydpi", layout.ydpi.toDouble())
            put(
                "keys",
                JSONArray().apply {
                    for (key in layout.keys) {
                        put(
                            JSONObject().apply {
                                put("code", key.code)
                                put("l", key.label)
                                put("x0", key.left.toDouble())
                                put("y0", key.top.toDouble())
                                put("x1", key.right.toDouble())
                                put("y1", key.bottom.toDouble())
                            },
                        )
                    }
                },
            )
        }

    private fun encodeScores(scores: List<ScoredCandidate>): JSONArray =
        JSONArray().apply {
            for (item in scores) {
                put(
                    JSONObject().apply {
                        put("w", item.word)
                        put("s", item.searchScore)
                        put("m", item.modelScore)
                        put("f", item.frequencyTerm)
                        put("b", item.bigramTerm)
                        put("g", item.geometryTerm)
                        put("t", item.totalScore)
                    },
                )
            }
        }

    private fun decodeEntry(json: JSONObject): SwipeLogEntry =
        SwipeLogEntry(
            id = json.getLong("id"),
            timestampMillis = json.getLong("ts"),
            points = decodePoints(json.getJSONArray("points")),
            layout = decodeLayout(json.getJSONObject("layout")),
            previousWord = if (json.has("prev") && !json.isNull("prev")) json.getString("prev") else null,
            engine = json.getString("engine"),
            candidates = decodeStrings(json.getJSONArray("candidates")),
            candidateScores = decodeScores(json.optJSONArray("scores")),
            inserted = json.getString("inserted"),
            outcome = SwipeOutcome.fromCode(json.optString("outcome", SwipeOutcome.KEPT.code)),
            finalWord = json.optString("final", json.getString("inserted")),
        )

    private fun decodePoints(array: JSONArray): List<TouchPoint> =
        List(array.length()) { index ->
            val item = array.getJSONArray(index)
            val action =
                when (index) {
                    0 -> TouchAction.DOWN
                    array.length() - 1 -> TouchAction.UP
                    else -> TouchAction.MOVE
                }
            TouchPoint(
                action = action,
                pointerId = 0,
                x = item.getDouble(0).toFloat(),
                y = item.getDouble(1).toFloat(),
                timeMillis = item.getDouble(2),
            )
        }

    private fun decodeLayout(json: JSONObject): KeyboardLayout {
        val keysJson = json.getJSONArray("keys")
        val keys =
            List(keysJson.length()) { index ->
                val item = keysJson.getJSONObject(index)
                Key(
                    code = item.getInt("code"),
                    label = item.getString("l"),
                    left = item.getDouble("x0").toFloat(),
                    top = item.getDouble("y0").toFloat(),
                    right = item.getDouble("x1").toFloat(),
                    bottom = item.getDouble("y1").toFloat(),
                )
            }
        return KeyboardLayout(
            width = json.getDouble("w").toFloat(),
            height = json.getDouble("h").toFloat(),
            xdpi = json.getDouble("xdpi").toFloat(),
            ydpi = json.getDouble("ydpi").toFloat(),
            keys = keys,
        )
    }

    private fun decodeStrings(array: JSONArray): List<String> = List(array.length()) { array.getString(it) }

    private fun decodeScores(array: JSONArray?): List<ScoredCandidate> {
        if (array == null) return emptyList()
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            ScoredCandidate(
                word = item.getString("w"),
                searchScore = item.getDouble("s"),
                modelScore = item.getDouble("m"),
                frequencyTerm = item.getDouble("f"),
                bigramTerm = item.getDouble("b"),
                geometryTerm = item.getDouble("g"),
                totalScore = item.getDouble("t"),
            )
        }
    }
}
