package ru.tinyops.android.vboard.core.gesture.neural

import ru.tinyops.android.vboard.core.gesture.GestureLayoutFactory
import ru.tinyops.android.vboard.core.layout.Language
import ru.tinyops.android.vboard.core.phoneGeometry
import ru.tinyops.android.vboard.core.swipe
import ru.tinyops.android.vboard.stt.model.Key
import ru.tinyops.android.vboard.stt.model.KeyboardLayout
import ru.tinyops.android.vboard.stt.model.TouchAction
import ru.tinyops.android.vboard.stt.model.TouchPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwipeFeatureExtractorTest {
    private fun point(
        x: Float,
        y: Float,
        t: Double,
    ) = TouchPoint(TouchAction.MOVE, 0, x, y, t)

    /** A layout whose anchors equal those of the training grid, so coordinates pass through unchanged. */
    private val identityLayout =
        KeyboardLayout(
            width = 1080f,
            height = 667f,
            xdpi = 420f,
            ydpi = 420f,
            keys =
                listOf(
                    Key(1, "й", 0f, 15f, 99f, 169f),
                    Key(2, "х", 982f, 15f, 1080f, 169f),
                    Key(3, "я", 119f, 323f, 213f, 477f),
                    Key(4, "ё", 0f, 15f, 99f, 169f),
                ),
        )

    private val extractor = SwipeFeatureExtractor(TEST_SPEC)

    private fun traj(
        features: SwipeFeatures,
        point: Int,
        feature: Int,
    ) = features.trajectory[point * SwipeFeatures.TRAJECTORY_SIZE + feature]

    @Test
    fun `coordinates and their derivatives are computed as in training`() {
        val points =
            listOf(point(100f, 100f, 0.0), point(120f, 100f, 16.0), point(160f, 130f, 32.0), point(170f, 160f, 48.0))
        val features = assertNotNull(extractor.extract(points, identityLayout))
        assertEquals(4, features.length)
        val eps = 1e-3f

        // Second point: x, y, then the central differences of position and of velocity.
        assertEquals(120f / 1080f, traj(features, 1, 0), eps)
        assertEquals(100f / 667f, traj(features, 1, 1), eps)
        val vx1 = (160f - 100f) / 32f
        val vy1 = (130f - 100f) / 32f
        assertEquals((vx1 - 0.1f) / 2f, traj(features, 1, 2), eps)
        assertEquals((vy1 + 0.1f) / 2f, traj(features, 1, 3), eps)
        val vx2 = (170f - 120f) / 32f
        // The velocity of the first point is zero by definition.
        assertEquals((vx2 - 0f) / 32f / 0.5f, traj(features, 1, 4), eps)

        // The edges have zero velocity and zero acceleration before normalization.
        assertEquals((0f - 0.1f) / 2f, traj(features, 0, 2), eps)
        assertEquals(0f, traj(features, 0, 4), eps)
        assertEquals((0f - 0.1f) / 2f, traj(features, 3, 2), eps)
    }

    @Test
    fun `the nearest key is looked up among the letters of the training grid`() {
        val points = listOf(point(40f, 80f, 0.0), point(1000f, 80f, 16.0), point(160f, 400f, 32.0))
        val features = assertNotNull(extractor.extract(points, identityLayout))
        val labels = TEST_SPEC.keyLabels
        assertEquals(labels.indexOf('й').toLong(), features.keys[0], "ё shares the place of й but the grid has no ё")
        assertEquals(labels.indexOf('х').toLong(), features.keys[1])
        assertEquals(labels.indexOf('я').toLong(), features.keys[2])
    }

    @Test
    fun `a point equally far from two keys goes to the lower token, as in training`() {
        // Halfway between `й` and `х` and high enough for `я` to be farther than both.
        val middle = listOf(point(540.25f, 15f, 0.0), point(540.25f, 15f, 16.0))
        val reversed = identityLayout.copy(keys = identityLayout.keys.reversed())
        val features = assertNotNull(extractor.extract(middle, reversed))
        assertEquals(TEST_SPEC.keyLabels.indexOf('й').toLong(), features.keys[0])
    }

    @Test
    fun `a screen faster than the training data is thinned out`() {
        val points = List(100) { point(it * 5f, 100f, it * 8.0) }
        val features = assertNotNull(extractor.extract(points, identityLayout))
        assertTrue(features.length in 49..51, "length ${features.length}")
    }

    @Test
    fun `the last point always stays`() {
        val points = List(101) { point(it * 5f, 100f, it * 8.0) }
        val features = assertNotNull(extractor.extract(points, identityLayout))
        val lastX = traj(features, features.length - 1, 0)
        assertEquals(500f / 1080f, lastX, 1e-3f)
    }

    @Test
    fun `points sharing a timestamp collapse so that derivatives stay finite`() {
        val times = listOf(0.0, 0.0, 16.0, 16.0, 32.0)
        val points = times.mapIndexed { index, time -> point(index + 1f, index + 1f, time) }
        val features = assertNotNull(extractor.extract(points, identityLayout))
        assertTrue(features.trajectory.all { it.isFinite() })
        assertEquals(3, features.length)
    }

    @Test
    fun `a very long gesture is cut down to what the model accepts`() {
        val points = List(1000) { point((it % 1000).toFloat(), 100f, it * 20.0) }
        val features = assertNotNull(extractor.extract(points, identityLayout))
        assertTrue(features.length <= SwipeFeatureExtractor.MAX_POINTS)
        assertEquals(SwipeFeatures.TRAJECTORY_SIZE * features.length, features.trajectory.size)
    }

    @Test
    fun `a gesture of one point or a layout without the anchor keys gives nothing`() {
        assertNull(extractor.extract(listOf(point(1f, 1f, 0.0)), identityLayout))
        val noAnchors = identityLayout.copy(keys = emptyList())
        assertNull(extractor.extract(listOf(point(1f, 1f, 0.0), point(2f, 2f, 16.0)), noAnchors))
    }

    @Test
    fun `the real russian layout is mapped onto the training grid`() {
        val geometry = phoneGeometry(Language.RUSSIAN)
        val layout = GestureLayoutFactory.create(geometry, xdpi = 420f, ydpi = 420f)
        val features = assertNotNull(extractor.extract(swipe(geometry, "йхя"), layout))
        // The gesture starts on the center of `й`, which is the left anchor of the grid.
        assertEquals(49.5f / 1080f, traj(features, 0, 0), 1e-3f)
        assertEquals(92f / 667f, traj(features, 0, 1), 1e-3f)
        assertEquals(TEST_SPEC.keyLabels.indexOf('й').toLong(), features.keys[0])
        assertEquals(TEST_SPEC.keyLabels.indexOf('я').toLong(), features.keys[features.length - 1])
    }
}
