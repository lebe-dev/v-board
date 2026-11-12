package ru.tinyops.android.vboard.core.gesture.neural

import org.junit.jupiter.api.Assumptions.assumeTrue
import ru.tinyops.android.vboard.stt.model.Key
import ru.tinyops.android.vboard.stt.model.KeyboardLayout
import ru.tinyops.android.vboard.stt.model.TouchAction
import ru.tinyops.android.vboard.stt.model.TouchPoint
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Compares the features with those the training code computed for the same gestures. The reference
 * comes with a trained model; until one is installed there is nothing to compare with.
 */
class SwipeTrainingParityTest {
    private fun resource(name: String) = javaClass.getResourceAsStream("/swipe/$name")

    @Test
    fun `features match the training code`() {
        val golden = resource("golden.txt")
        val specStream = resource("ru.properties")
        assumeTrue(golden != null && specStream != null, "No trained model is installed")
        val spec = specStream!!.use(SwipeModelSpec::parse)
        val lines = golden!!.bufferedReader(Charsets.UTF_8).readLines()

        val keys =
            lines.filter { it.startsWith("key ") }.mapIndexed { index, line ->
                val fields = line.split(' ')
                val (x, y) = fields[2].toFloat() to fields[3].toFloat()
                Key(index + 1, fields[1], x, y, x + fields[4].toFloat(), y + fields[5].toFloat())
            }
        val layout = KeyboardLayout(1080f, 667f, 420f, 420f, keys = keys)
        val extractor = SwipeFeatureExtractor(spec)
        val gestures = lines.filter { it.startsWith("gesture ") }
        assumeTrue(gestures.isNotEmpty(), "The reference has no gestures")
        for (gesture in gestures) check(extractor, layout, gesture)
    }

    private fun check(
        extractor: SwipeFeatureExtractor,
        layout: KeyboardLayout,
        line: String,
    ) {
        val fields = line.split('|')
        val word = fields[0].removePrefix("gesture ")
        val xs = fields[1].numbers()
        val ys = fields[2].numbers()
        val ts = fields[3].numbers()
        val points = xs.indices.map { TouchPoint(TouchAction.MOVE, 0, xs[it], ys[it], ts[it].toDouble()) }
        val features = extractor.features(points, layout)!!
        assertEquals(fields[4].numbers().map { it.toLong() }, features.keys.toList(), "keys of $word")
        val expected = fields[5].numbers()
        assertEquals(expected.size, features.trajectory.size, "size of $word")
        for (index in expected.indices) {
            assertEquals(expected[index], features.trajectory[index], TOLERANCE, "feature $index of $word")
        }
    }

    private fun String.numbers(): List<Float> = trim().split(' ').map(String::toFloat)

    private companion object {
        const val TOLERANCE = 1e-3f
    }
}
