package ru.tinyops.android.vboard.core.gesture

import org.junit.jupiter.api.Assumptions.assumeTrue
import ru.tinyops.android.vboard.core.bundledDictionary
import ru.tinyops.android.vboard.core.layout.Language
import ru.tinyops.android.vboard.stt.model.Key
import ru.tinyops.android.vboard.stt.model.KeyboardLayout
import ru.tinyops.android.vboard.stt.model.TouchAction
import ru.tinyops.android.vboard.stt.model.TouchPoint
import java.io.File
import kotlin.test.Test

/**
 * Measures the default decoder on the NeuroSwipe validation set, to compare with a trained model
 * under the same conditions. Runs only when `SWIPE_VALID_TEXT` points to the output of
 * `scripts/swipe_model/prepare_valid.py`: `just swipe-baseline`.
 */
class DefaultEngineBaselineTest {
    private class Score {
        var total = 0
        var top1 = 0
        var mrr = 0.0

        fun add(
            candidates: List<String>,
            target: String,
        ) {
            total++
            val place = candidates.indexOf(target)
            if (place == 0) top1++
            if (place in MRR_WEIGHTS.indices) mrr += MRR_WEIGHTS[place]
        }

        override fun toString() =
            "gestures=$total top1=%.4f swipe_mrr=%.4f".format(top1.toDouble() / total, mrr / total)
    }

    @Test
    fun `measure the default decoder`() {
        val path = System.getenv("SWIPE_VALID_TEXT")
        assumeTrue(path != null, "SWIPE_VALID_TEXT is not set")
        val lines = File(path!!).readLines()
        val layouts = layouts(lines)
        val model = bundledDictionary(Language.RUSSIAN).languageModel
        val engines =
            layouts.mapValues { (_, layout) ->
                GestureEngine().apply {
                    setLayout(layout)
                    setLanguageModel(model)
                }
            }
        val scores = HashMap<String, Score>()
        for (line in lines) {
            if (line.startsWith("key\t")) continue
            val fields = line.split('\t')
            val xs = fields[2].split(',')
            val ys = fields[3].split(',')
            val ts = fields[4].split(',')
            val points =
                xs.indices.map { i ->
                    val action =
                        when (i) {
                            0 -> TouchAction.DOWN
                            xs.lastIndex -> TouchAction.UP
                            else -> TouchAction.MOVE
                        }
                    TouchPoint(action, 0, xs[i].toFloat(), ys[i].toFloat(), ts[i].toDouble())
                }
            val words = engines.getValue(fields[1]).decode(points, "", complete = true)
            val candidates = words.map(::folded).distinct().take(MRR_WEIGHTS.size)
            scores.getOrPut(fields[1]) { Score() }.add(candidates, folded(fields[0]))
        }
        for ((grid, score) in scores) println("BASELINE default decoder, grid $grid: $score")
    }

    private fun layouts(lines: List<String>): Map<String, KeyboardLayout> =
        lines
            .filter { it.startsWith("key\t") }
            .map { it.split('\t') }
            .groupBy { it[1] }
            .mapValues { (_, keys) ->
                KeyboardLayout(
                    width = 1080f,
                    height = 667f,
                    xdpi = 420f,
                    ydpi = 420f,
                    keys =
                        keys.mapIndexed { index, key ->
                            val (x, y) = key[3].toFloat() to key[4].toFloat()
                            Key(index + 1, key[2], x, y, x + key[5].toFloat(), y + key[6].toFloat())
                        },
                )
            }

    private fun folded(word: String) = word.replace('ё', 'е')

    private companion object {
        val MRR_WEIGHTS = listOf(1.0, 0.1, 0.09, 0.08)
    }
}
