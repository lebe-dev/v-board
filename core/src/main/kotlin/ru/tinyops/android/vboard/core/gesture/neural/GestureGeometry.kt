package ru.tinyops.android.vboard.core.gesture.neural

import ru.tinyops.android.vboard.stt.model.KeyboardLayout
import ru.tinyops.android.vboard.stt.model.TouchPoint
import kotlin.math.hypot

/**
 * Path geometry penalties for rescoring the best neural candidates: compares the end of the gesture
 * against the end of the key-center polyline of a word and compares the normalized whole-path shape
 * of both. Distances are measured in key pitches (horizontal and vertical separately), so the same
 * weights hold across screen sizes.
 */
class GestureGeometry private constructor(
    private val pitchX: Double,
    private val pitchY: Double,
    private val centers: Map<Char, Point>,
    private val startWeight: Double,
    private val endWeight: Double,
    private val endPoints: Int,
    private val shapeWeight: Double,
    private val locationWeight: Double,
    private val shapeSamples: Int,
) {
    private class Point(
        val x: Double,
        val y: Double,
    )

    internal class Path(
        val xs: DoubleArray,
        val ys: DoubleArray,
    ) {
        val size: Int get() = xs.size

        fun reversed(): Path =
            Path(
                DoubleArray(size) { xs[size - 1 - it] },
                DoubleArray(size) { ys[size - 1 - it] },
            )
    }

    /** Precomputed gesture representation ready to score candidate words for one swipe. */
    inner class PathScorer internal constructor(
        private val start: Path?,
        private val end: Path?,
        private val drawn: Path?,
        private val shape: Path?,
    ) {
        /** Non-negative penalty added to a candidate's rescoring cost (smaller is better). */
        fun penalty(word: String): Double {
            val wordPath = wordPath(word) ?: return 0.0
            var total = 0.0
            if (start != null) {
                total += startWeight * meanDistance(start, resample(wordPath, GEOMETRY_STEP, endPoints))
            }
            if (end != null) {
                total += endWeight * meanDistance(end, resample(wordPath.reversed(), GEOMETRY_STEP, endPoints))
            }
            if (shape != null || drawn != null) {
                val wordSamples = spread(wordPath, shapeSamples)
                if (shape != null) {
                    total += shapeWeight * meanDistance(shape, normalized(wordSamples))
                }
                if (drawn != null) {
                    total += locationWeight * meanDistance(drawn, wordSamples)
                }
            }
            return total
        }
    }

    /** Prepares [points] (view coordinates) once per gesture, or null when no penalty is active. */
    fun scorer(points: List<TouchPoint>): PathScorer? {
        if (points.isEmpty()) return null
        val useStart = startWeight != 0.0
        val useEnd = endWeight != 0.0
        val useShape = shapeWeight != 0.0
        val useLocation = locationWeight != 0.0
        val useEnds = useStart || useEnd
        val useWhole = useShape || useLocation
        if (!useEnds && !useWhole) return null

        val count = points.size
        val scaled =
            Path(
                DoubleArray(count) { points[it].x.toDouble() / pitchX },
                DoubleArray(count) { points[it].y.toDouble() / pitchY },
            )
        val start = if (useStart) resample(scaled, GEOMETRY_STEP, endPoints) else null
        val end = if (useEnd) resample(scaled.reversed(), GEOMETRY_STEP, endPoints) else null
        val spreadGesture = if (useShape || useLocation) spread(scaled, shapeSamples) else null
        val shape = if (useShape && spreadGesture != null) normalized(spreadGesture) else null
        val drawn = if (useLocation) spreadGesture else null
        return PathScorer(start, end, drawn, shape)
    }

    private fun wordPath(word: String): Path? {
        val xs = DoubleArray(word.length)
        val ys = DoubleArray(word.length)
        var size = 0
        for (raw in word) {
            val folded = FOLDED_LETTERS[raw] ?: raw
            val key = KEY_SUBSTITUTES[folded] ?: folded
            val center = centers[key] ?: continue
            if (size == 0 || xs[size - 1] != center.x || ys[size - 1] != center.y) {
                xs[size] = center.x
                ys[size] = center.y
                size++
            }
        }
        if (size == 0) return null
        return if (size == word.length) Path(xs, ys) else Path(xs.copyOf(size), ys.copyOf(size))
    }

    companion object {
        const val DEFAULT_START_WEIGHT = 0.0
        const val DEFAULT_END_WEIGHT = 0.2
        const val DEFAULT_END_POINTS = 2
        const val DEFAULT_SHAPE_WEIGHT = 1.0
        const val DEFAULT_LOCATION_WEIGHT = 0.0
        const val DEFAULT_SHAPE_SAMPLES = 32

        private const val GEOMETRY_STEP = 0.25
        private const val SHAPE_MIN_SIZE = 1.0
        private const val TOP_ROW_KEY_SPANS = 10.0
        private const val ROW_SPANS = 2.0

        private val FOLDED_LETTERS = mapOf('ё' to 'е')
        private val KEY_SUBSTITUTES = mapOf('ъ' to 'ь')

        /** Builds key-pitch geometry for [layout], or null if its Russian anchor keys are absent. */
        fun forLayout(
            layout: KeyboardLayout,
            startWeight: Double = DEFAULT_START_WEIGHT,
            endWeight: Double = DEFAULT_END_WEIGHT,
            endPoints: Int = DEFAULT_END_POINTS,
            shapeWeight: Double = DEFAULT_SHAPE_WEIGHT,
            locationWeight: Double = DEFAULT_LOCATION_WEIGHT,
            shapeSamples: Int = DEFAULT_SHAPE_SAMPLES,
        ): GestureGeometry? {
            val left = layout.keyForCharacter('й') ?: return null
            val right = layout.keyForCharacter('х') ?: return null
            val bottom = layout.keyForCharacter('я') ?: return null
            val pitchX = (right.centerX - left.centerX).toDouble() / TOP_ROW_KEY_SPANS
            val pitchY = (bottom.centerY - left.centerY).toDouble() / ROW_SPANS
            if (pitchX <= 0.0 || pitchY <= 0.0) return null
            val centers = HashMap<Char, Point>(layout.keys.size)
            for (key in layout.keys) {
                val letter = key.label.singleOrNull()?.lowercaseChar()
                if (letter != null && letter.isLetter()) {
                    centers[letter] = Point(key.centerX.toDouble() / pitchX, key.centerY.toDouble() / pitchY)
                }
            }
            return GestureGeometry(
                pitchX = pitchX,
                pitchY = pitchY,
                centers = centers,
                startWeight = startWeight,
                endWeight = endWeight,
                endPoints = endPoints,
                shapeWeight = shapeWeight,
                locationWeight = locationWeight,
                shapeSamples = shapeSamples,
            )
        }

        private fun resample(
            path: Path,
            step: Double,
            count: Int,
        ): Path {
            val outX = DoubleArray(count)
            val outY = DoubleArray(count)
            outX[0] = path.xs[0]
            outY[0] = path.ys[0]
            var written = 1
            var left = step
            for (i in 0 until path.size - 1) {
                val x0 = path.xs[i]
                val y0 = path.ys[i]
                val x1 = path.xs[i + 1]
                val y1 = path.ys[i + 1]
                val length = hypot(x1 - x0, y1 - y0)
                if (length == 0.0) continue
                var walked = 0.0
                while (written < count && length - walked >= left) {
                    walked += left
                    val ratio = walked / length
                    outX[written] = x0 + (x1 - x0) * ratio
                    outY[written] = y0 + (y1 - y0) * ratio
                    written++
                    left = step
                }
                left -= length - walked
                if (written == count) return Path(outX, outY)
            }
            val lastX = path.xs[path.size - 1]
            val lastY = path.ys[path.size - 1]
            while (written < count) {
                outX[written] = lastX
                outY[written] = lastY
                written++
            }
            return Path(outX, outY)
        }

        private fun spread(
            path: Path,
            count: Int,
        ): Path {
            val n = path.size
            val lengths = DoubleArray(n)
            for (i in 1 until n) {
                lengths[i] = lengths[i - 1] + hypot(path.xs[i] - path.xs[i - 1], path.ys[i] - path.ys[i - 1])
            }
            val total = lengths[n - 1]
            val outX = DoubleArray(count)
            val outY = DoubleArray(count)
            if (total == 0.0 || count < 2) {
                outX.fill(path.xs[0])
                outY.fill(path.ys[0])
                return Path(outX, outY)
            }
            var segment = 0
            for (i in 0 until count) {
                val walked = total * i / (count - 1)
                while (segment < n - 2 && lengths[segment + 1] < walked) {
                    segment++
                }
                val length = lengths[segment + 1] - lengths[segment]
                val ratio = if (length > 0.0) (walked - lengths[segment]) / length else 0.0
                outX[i] = path.xs[segment] + (path.xs[segment + 1] - path.xs[segment]) * ratio
                outY[i] = path.ys[segment] + (path.ys[segment + 1] - path.ys[segment]) * ratio
            }
            return Path(outX, outY)
        }

        private fun normalized(path: Path): Path {
            val n = path.size
            var sumX = 0.0
            var sumY = 0.0
            var minX = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            for (i in 0 until n) {
                val x = path.xs[i]
                val y = path.ys[i]
                sumX += x
                sumY += y
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
            val cx = sumX / n
            val cy = sumY / n
            val size = maxOf(maxX - minX, maxY - minY, SHAPE_MIN_SIZE)
            return Path(
                DoubleArray(n) { (path.xs[it] - cx) / size },
                DoubleArray(n) { (path.ys[it] - cy) / size },
            )
        }

        private fun meanDistance(
            a: Path,
            b: Path,
        ): Double {
            var sum = 0.0
            for (i in 0 until a.size) {
                sum += hypot(a.xs[i] - b.xs[i], a.ys[i] - b.ys[i])
            }
            return sum / a.size
        }
    }
}
