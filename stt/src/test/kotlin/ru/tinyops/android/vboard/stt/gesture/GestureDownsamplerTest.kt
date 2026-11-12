package ru.tinyops.android.vboard.stt.gesture

import ru.tinyops.android.vboard.stt.model.TouchAction
import ru.tinyops.android.vboard.stt.model.TouchPoint
import ru.tinyops.android.vboard.stt.qwertyLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GestureDownsamplerTest {
    private fun point(
        x: Float,
        y: Float,
        t: Double,
        action: TouchAction = TouchAction.MOVE,
    ) = TouchPoint(action, 0, x, y, t)

    @Test
    fun `empty and single point gestures are returned as-is`() {
        val downsampler = GestureDownsampler(100.0, 100.0)
        assertEquals(emptyList(), downsampler.downsample(emptyList()))

        val single = listOf(point(0f, 0f, 0.0, TouchAction.DOWN))
        assertEquals(single, downsampler.downsample(single))
    }

    @Test
    fun `drops points that are close in space and time`() {
        val downsampler = GestureDownsampler(minDistanceSq = 100.0, maxTimeGapMillis = 100.0)
        val input =
            listOf(
                point(0f, 0f, 0.0),
                point(5f, 0f, 10.0), // too close, too soon -> dropped
                point(50f, 0f, 20.0), // far enough -> kept
                point(51f, 0f, 30.0), // too close -> dropped
                point(52f, 0f, 200.0), // time gap elapsed -> kept
                point(500f, 0f, 210.0),
            )
        val kept = downsampler.downsample(input)
        assertEquals(listOf(input[0], input[2], input[4], input[5]), kept)
    }

    @Test
    fun `always keeps the final raw point`() {
        val downsampler = GestureDownsampler(minDistanceSq = 10_000.0, maxTimeGapMillis = 10_000.0)
        val input =
            listOf(
                point(0f, 0f, 0.0, TouchAction.DOWN),
                point(1f, 0f, 5.0),
                point(2f, 1f, 9.0, TouchAction.UP),
            )
        val kept = downsampler.downsample(input)
        assertEquals(2, kept.size)
        assertSame(input.first(), kept.first())
        assertSame(input.last(), kept.last())
    }

    @Test
    fun `zero time gap means only distance matters`() {
        val downsampler = GestureDownsampler(minDistanceSq = 100.0, maxTimeGapMillis = 0.0)
        val input =
            listOf(
                point(0f, 0f, 0.0),
                point(5f, 0f, 100.0), // no distance, no gap -> dropped
                point(50f, 0f, 100.0), // distance -> kept
            )
        val kept = downsampler.downsample(input)
        assertEquals(listOf(input[0], input[2]), kept)
    }

    @Test
    fun `layout derived threshold scales with key height`() {
        val layout = qwertyLayout()
        val downsampler = GestureDownsampler.forLayout(layout)
        // Key height is 100, so the min distance is 0.1 * 100 = 10.
        val input =
            listOf(
                point(0f, 0f, 0.0),
                point(9f, 0f, 5.0), // < 10 -> dropped
                point(11f, 0f, 6.0), // >= 10 from origin -> kept
            )
        val kept = downsampler.downsample(input)
        assertEquals(2, kept.size)
        assertTrue(kept.contains(input[2]))
    }
}
