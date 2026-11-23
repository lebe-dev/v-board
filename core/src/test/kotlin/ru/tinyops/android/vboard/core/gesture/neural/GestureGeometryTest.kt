package ru.tinyops.android.vboard.core.gesture.neural

import ru.tinyops.android.vboard.stt.model.Key
import ru.tinyops.android.vboard.stt.model.KeyboardLayout
import ru.tinyops.android.vboard.stt.model.TouchAction
import ru.tinyops.android.vboard.stt.model.TouchPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GestureGeometryTest {
    /** A simple grid with uniform pitch (pitchX, pitchY) for exact polyline checks. */
    private fun grid(
        pitchX: Float = 100f,
        pitchY: Float = 150f,
    ): KeyboardLayout {
        fun key(
            id: Int,
            label: String,
            col: Int,
            row: Int,
        ): Key {
            val cx = (col + 0.5f) * pitchX
            val cy = (row + 0.5f) * pitchY
            return Key(id, label, cx - pitchX / 2f, cy - pitchY / 2f, cx + pitchX / 2f, cy + pitchY / 2f)
        }
        val keys =
            listOf(
                key(1, "й", 0, 0),
                key(2, "ц", 1, 0),
                key(3, "е", 4, 0),
                key(4, "х", 10, 0),
                key(5, "а", 2, 1),
                key(6, "д", 7, 1),
                key(7, "ж", 8, 1),
                key(8, "о", 6, 0),
                key(9, "п", 5, 1),
                key(10, "я", 0, 2),
                key(11, "ь", 6, 2),
            )
        return KeyboardLayout(11 * pitchX, 3 * pitchY, 420f, 420f, keys = keys)
    }

    private fun points(
        layout: KeyboardLayout,
        vararg letters: Char,
    ): List<TouchPoint> =
        letters.mapIndexed { index, ch ->
            val key = requireNotNull(layout.keyForCharacter(ch))
            TouchPoint(TouchAction.MOVE, 0, key.centerX, key.centerY, index * 20.0)
        }

    @Test
    fun `zero weights disable scoring`() {
        val layout = grid()
        val geometry =
            assertNotNull(
                GestureGeometry.forLayout(
                    layout,
                    startWeight = 0.0,
                    endWeight = 0.0,
                    shapeWeight = 0.0,
                    locationWeight = 0.0,
                ),
            )
        assertNull(geometry.scorer(points(layout, 'п', 'о', 'х', 'о', 'д')))
    }

    @Test
    fun `an exact key-center polyline has zero penalty and matches Python reference values`() {
        val layout = grid()
        val geometry = assertNotNull(GestureGeometry.forLayout(layout))
        val scorer = assertNotNull(geometry.scorer(points(layout, 'п', 'о', 'х', 'о', 'д')))

        assertEquals(0.0, scorer.penalty("поход"), absoluteTolerance = 1e-9)
        assertEquals(0.2766837482210198, scorer.penalty("похож"), absoluteTolerance = 1e-9)
    }

    @Test
    fun `penalties are invariant to screen scale because distances use key pitches`() {
        val small = grid(pitchX = 80f, pitchY = 120f)
        val large = grid(pitchX = 160f, pitchY = 270f)
        val smallScorer =
            assertNotNull(GestureGeometry.forLayout(small)?.scorer(points(small, 'п', 'о', 'х', 'о', 'д')))
        val largeScorer =
            assertNotNull(GestureGeometry.forLayout(large)?.scorer(points(large, 'п', 'о', 'х', 'о', 'д')))

        assertEquals(smallScorer.penalty("похож"), largeScorer.penalty("похож"), absoluteTolerance = 1e-9)
        assertEquals(smallScorer.penalty("поход"), largeScorer.penalty("поход"), absoluteTolerance = 1e-9)
    }

    @Test
    fun `folded letters and hard sign map to their base keys and hyphens are skipped`() {
        val layout = grid()
        val geometry = assertNotNull(GestureGeometry.forLayout(layout))
        val scorer = assertNotNull(geometry.scorer(points(layout, 'е', 'ь')))

        assertEquals(0.0, scorer.penalty("ё-ъ"), absoluteTolerance = 1e-9)
        assertEquals(scorer.penalty("еь"), scorer.penalty("ёъ"), absoluteTolerance = 1e-9)
    }

    @Test
    fun `start and end penalties break a model tie in favor of the matching path`() {
        val layout = grid()
        val geometry =
            assertNotNull(
                GestureGeometry.forLayout(
                    layout,
                    startWeight = 0.0,
                    endWeight = 0.2,
                    endPoints = 2,
                    shapeWeight = 1.0,
                    shapeSamples = 32,
                ),
            )
        val scorer = assertNotNull(geometry.scorer(points(layout, 'п', 'о', 'х', 'о', 'д')))
        val trie = trieOf("поход" to 1.0, "похож" to 1.0)
        val model =
            ScriptedModel(
                TEST_SPEC,
                logits =
                    mapOf(
                        "" to mapOf('п' to 5f),
                        "п" to mapOf('о' to 5f),
                        "по" to mapOf('х' to 5f),
                        "пох" to mapOf('о' to 5f),
                        "похо" to mapOf('ж' to 5f, 'д' to 5f),
                    ),
                eos = mapOf("похож" to 5f, "поход" to 5f),
            )
        val search = NeuralWordSearch(TEST_SPEC, beamSize = 4, maxSteps = 35)
        assertEquals(listOf("поход", "похож"), search.search(model, trie.root, limit = 4))
        val gestureToZhe = assertNotNull(geometry.scorer(points(layout, 'п', 'о', 'х', 'о', 'ж')))
        assertEquals(listOf("похож", "поход"), search.search(model, trie.root, limit = 4, geometry = gestureToZhe))

        val detailed = search.searchDetailed(model, trie.root, limit = 4, geometry = scorer)
        assertEquals("поход", detailed.first().word)
        assertEquals(0.0, detailed.first().geometryTerm, absoluteTolerance = 1e-9)
        assertTrue(detailed[1].geometryTerm > 0.0)
    }

    @Test
    fun `a layout without Russian anchor keys yields no geometry`() {
        val latin =
            KeyboardLayout(
                1000f,
                300f,
                420f,
                420f,
                keys = listOf(Key(1, "q", 0f, 0f, 100f, 100f)),
            )
        assertNull(GestureGeometry.forLayout(latin))
    }
}
