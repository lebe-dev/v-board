package ru.tinyops.android.vboard

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.tinyops.android.vboard.core.gesture.neural.NeuralGestureEngine
import ru.tinyops.android.vboard.core.gesture.neural.SwipeFeatures
import ru.tinyops.android.vboard.core.gesture.neural.SwipeSequenceModel
import ru.tinyops.android.vboard.core.layout.Language
import ru.tinyops.android.vboard.stt.model.Key
import ru.tinyops.android.vboard.stt.model.KeyboardLayout
import ru.tinyops.android.vboard.stt.model.TouchAction
import ru.tinyops.android.vboard.stt.model.TouchPoint

/**
 * Runs the model of this build on the NeuroSwipe validation gestures on a phone and logs how fast
 * and how accurate it is under the tag `SwipeCheck`. Run it with `just swipe-device-check`, which
 * keeps it in a package of its own, away from the keyboard installed on the phone.
 *
 * Given `contexts.tsv` (gesture number, word and previous word per line) and a build with the word
 * pair table, each gesture with a previous word is decoded with it, as `evaluate.py --contexts` does.
 */
@RunWith(AndroidJUnit4::class)
class SwipeModelDeviceCheck {
    private class TimedModel(
        private val model: SwipeSequenceModel,
    ) : SwipeSequenceModel {
        var encodeNanos = 0L
        var decodeNanos = 0L
        var decodeCalls = 0

        fun reset() {
            encodeNanos = 0
            decodeNanos = 0
            decodeCalls = 0
        }

        override fun encode(features: SwipeFeatures) {
            val started = System.nanoTime()
            model.encode(features)
            encodeNanos = System.nanoTime() - started
        }

        override fun nextLogits(
            tokens: LongArray,
            length: Int,
        ): FloatArray {
            val started = System.nanoTime()
            val logits = model.nextLogits(tokens, length)
            decodeNanos += System.nanoTime() - started
            decodeCalls++
            return logits
        }

        override fun logitsAfterEach(
            tokens: LongArray,
            length: Int,
        ): FloatArray {
            val started = System.nanoTime()
            val logits = model.logitsAfterEach(tokens, length)
            decodeNanos += System.nanoTime() - started
            decodeCalls++
            return logits
        }
    }

    @Test
    fun measure() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        assertTrue("This build has no model", SwipeModelAssets.isPresent(target.assets))
        val limit = InstrumentationRegistry.getArguments().getString("limit")?.toInt() ?: DEFAULT_LIMIT
        val lines =
            instrumentation.context.assets
                .open(GESTURES)
                .bufferedReader(Charsets.UTF_8)
                .readLines()

        val started = System.nanoTime()
        val spec = SwipeModelAssets.readSpec(target.assets)
        val model = TimedModel(SwipeModelAssets.openModel(target, spec))
        Log.i(TAG, "Model opened in ${(System.nanoTime() - started) / NANOS_PER_MS} ms")
        val engine = NeuralGestureEngine(spec, model).apply { setLayout(layout(lines)) }
        val trie = DictionaryAssets.open(target.assets, Language.RUSSIAN).languageModel.trie
        val bigrams = DictionaryAssets.openBigrams(target.assets, Language.RUSSIAN)
        val contexts = if (bigrams == null) emptyMap() else readContexts()
        Log.i(TAG, "Previous words: ${contexts.size}, word pair table: ${bigrams?.size ?: "none"}")

        val totals = ArrayList<Double>()
        val encodes = ArrayList<Double>()
        val decodes = ArrayList<Double>()
        val calls = ArrayList<Double>()
        var measured = 0
        var top1 = 0
        var mrr = 0.0
        for (line in lines) {
            if (measured == limit + WARM_UP) break
            val fields = line.split('\t')
            if (fields[0] == "key" || fields[1] != GRID) continue
            model.reset()
            val begin = System.nanoTime()
            val context = contexts[measured]?.let { bigrams?.context(it) }
            val words = engine.decode(points(fields), trie, context)
            val total = (System.nanoTime() - begin) / NANOS_PER_MS.toDouble()
            measured++
            if (measured <= WARM_UP) continue
            totals += total
            encodes += model.encodeNanos / NANOS_PER_MS.toDouble()
            decodes += model.decodeNanos / NANOS_PER_MS.toDouble()
            calls += model.decodeCalls.toDouble()
            val candidates = words.map(::folded).distinct()
            val place = candidates.indexOf(folded(fields[0]))
            if (place == 0) top1++
            if (place in MRR_WEIGHTS.indices) mrr += MRR_WEIGHTS[place]
        }
        val count = totals.size
        assertTrue("No gestures were measured", count > 0)
        Log.i(TAG, "Gestures $count, top1 %.4f, swipe_mrr %.4f".format(top1.toDouble() / count, mrr / count))
        Log.i(TAG, "Whole decode, ms: ${summary(totals)}")
        Log.i(TAG, "Encoder, ms: ${summary(encodes)}")
        Log.i(TAG, "Decoder, ms: ${summary(decodes)}")
        Log.i(TAG, "Decoder calls per gesture: ${summary(calls)}")
    }

    /** Gesture number on the grid, from 0 with the warm-up, to its previous word; empty without the file. */
    private fun readContexts(): Map<Int, String> {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        if (assets.list("")?.contains(CONTEXTS) != true) return emptyMap()
        return assets.open(CONTEXTS).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.map { it.split('\t') }.associate { it[0].toInt() to it[2] }
        }
    }

    private fun summary(values: List<Double>): String {
        val sorted = values.sorted()
        return "mean %.1f, p50 %.1f, p95 %.1f, max %.1f".format(
            values.average(),
            sorted[sorted.size / 2],
            sorted[(sorted.size * P95).toInt()],
            sorted.last(),
        )
    }

    private fun layout(lines: List<String>): KeyboardLayout {
        val keys =
            lines
                .asSequence()
                .map { it.split('\t') }
                .filter { it[0] == "key" && it[1] == GRID }
                .mapIndexed { index, key ->
                    val (x, y) = key[3].toFloat() to key[4].toFloat()
                    Key(index + 1, key[2], x, y, x + key[5].toFloat(), y + key[6].toFloat())
                }.toList()
        return KeyboardLayout(GRID_WIDTH, GRID_HEIGHT, DPI, DPI, keys = keys)
    }

    private fun points(fields: List<String>): List<TouchPoint> {
        val xs = fields[2].split(',')
        val ys = fields[3].split(',')
        val ts = fields[4].split(',')
        return xs.indices.map { TouchPoint(TouchAction.MOVE, 0, xs[it].toFloat(), ys[it].toFloat(), ts[it].toDouble()) }
    }

    private fun folded(word: String) = word.replace('ё', 'е')

    private companion object {
        const val TAG = "SwipeCheck"
        const val GESTURES = "valid.txt"
        const val CONTEXTS = "contexts.tsv"
        const val GRID = "default"
        const val DEFAULT_LIMIT = 1000
        const val WARM_UP = 20
        const val P95 = 0.95
        const val NANOS_PER_MS = 1_000_000L
        const val GRID_WIDTH = 1080f
        const val GRID_HEIGHT = 667f
        const val DPI = 420f
        val MRR_WEIGHTS = listOf(1.0, 0.1, 0.09, 0.08)
    }
}
