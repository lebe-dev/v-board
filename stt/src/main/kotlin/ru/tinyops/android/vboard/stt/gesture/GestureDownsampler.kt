package ru.tinyops.android.vboard.stt.gesture

import ru.tinyops.android.vboard.stt.model.KeyboardLayout
import ru.tinyops.android.vboard.stt.model.TouchPoint

/**
 * Thins a raw stream of touch points before decoding.
 *
 * Dense, jittering points degrade a beam search, so a point is only kept when
 * it is either far enough from the last kept point (`minDistanceSq`) or enough
 * time has passed (`maxTimeGapMillis`). The first and last raw points are always
 * retained so that a gesture keeps its true start and end.
 *
 * This reproduces the reference android implementation's downsampling rule
 * (threshold derived from 10% of the key height, 100 ms time gap).
 */
class GestureDownsampler(
    private val minDistanceSq: Double,
    private val maxTimeGapMillis: Double,
) {
    fun downsample(points: List<TouchPoint>): List<TouchPoint> {
        val kept = ArrayList<TouchPoint>(points.size)
        downsampleInto(points, kept)
        return kept
    }

    /** Replaces the contents of [into] with the kept points, so a caller can reuse one list. */
    fun downsampleInto(
        points: List<TouchPoint>,
        into: MutableList<TouchPoint>,
    ) {
        into.clear()
        if (points.size <= 2) {
            into.addAll(points)
            return
        }

        var lastKept = points.first()
        into.add(lastKept)

        for (i in 1 until points.size) {
            val point = points[i]
            val distSq = point.distanceSqTo(lastKept)
            val dt = point.timeDeltaTo(lastKept)
            val timeTriggered = maxTimeGapMillis > 0.0 && dt >= maxTimeGapMillis
            if (distSq >= minDistanceSq || timeTriggered) {
                into.add(point)
                lastKept = point
            }
        }

        val lastRaw = points.last()
        if (into.last() !== lastRaw) into.add(lastRaw)
    }

    companion object {
        const val DEFAULT_MIN_DISTANCE_FRACTION: Double = 0.1
        const val DEFAULT_MAX_TIME_GAP_MILLIS: Double = 100.0

        /** Builds a downsampler whose distance threshold is a fraction of the key height. */
        fun forLayout(
            layout: KeyboardLayout,
            minDistanceFraction: Double = DEFAULT_MIN_DISTANCE_FRACTION,
            maxTimeGapMillis: Double = DEFAULT_MAX_TIME_GAP_MILLIS,
        ): GestureDownsampler {
            val minDistance = layout.averageKeyHeight * minDistanceFraction
            return GestureDownsampler(minDistance * minDistance, maxTimeGapMillis)
        }
    }
}
