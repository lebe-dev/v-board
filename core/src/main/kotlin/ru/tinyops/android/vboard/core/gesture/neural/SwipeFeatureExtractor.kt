package ru.tinyops.android.vboard.core.gesture.neural

import ru.tinyops.android.vboard.stt.model.Key
import ru.tinyops.android.vboard.stt.model.KeyboardLayout
import ru.tinyops.android.vboard.stt.model.TouchPoint

/**
 * Turns gesture points into model input, computing the same features the model saw in training.
 *
 * The keyboard on screen has the same rows as the training grid but its own size and proportions,
 * so coordinates are mapped by the centers of the corner keys. The nearest key comes from the
 * layout on screen: what counts is the key the user sees under the finger.
 */
class SwipeFeatureExtractor(
    private val spec: SwipeModelSpec,
) {
    private class Mapping(
        val scaleX: Float,
        val offsetX: Float,
        val scaleY: Float,
        val offsetY: Float,
        val keys: List<Key>,
        val tokens: LongArray,
    )

    private var mappedLayout: KeyboardLayout? = null
    private var mapping: Mapping? = null

    /** The features of [points] (view coordinates), or null when they cannot be computed. */
    fun extract(
        points: List<TouchPoint>,
        layout: KeyboardLayout,
    ): SwipeFeatures? {
        val kept = thinOut(points)
        if (kept.size < 2) return null
        return features(kept, layout)
    }

    /** The features of exactly these [kept] points. */
    internal fun features(
        kept: List<TouchPoint>,
        layout: KeyboardLayout,
    ): SwipeFeatures? {
        val mapping = mappingFor(layout) ?: return null
        val count = kept.size
        val xs = FloatArray(count) { mapping.scaleX * kept[it].x + mapping.offsetX }
        val ys = FloatArray(count) { mapping.scaleY * kept[it].y + mapping.offsetY }
        val ts = FloatArray(count) { kept[it].timeMillis.toFloat() }
        val velocityX = centralDifference(xs, ts)
        val velocityY = centralDifference(ys, ts)
        val accelerationX = centralDifference(velocityX, ts)
        val accelerationY = centralDifference(velocityY, ts)

        val trajectory = FloatArray(count * SwipeFeatures.TRAJECTORY_SIZE)
        var at = 0
        for (i in 0 until count) {
            trajectory[at++] = (xs[i] - spec.xMin) / (spec.xMax - spec.xMin)
            trajectory[at++] = (ys[i] - spec.yMin) / (spec.yMax - spec.yMin)
            trajectory[at++] = spec.velocityX.apply(velocityX[i])
            trajectory[at++] = spec.velocityY.apply(velocityY[i])
            trajectory[at++] = spec.accelerationX.apply(accelerationX[i])
            trajectory[at++] = spec.accelerationY.apply(accelerationY[i])
        }
        val keys = LongArray(count) { nearestToken(mapping, kept[it].x, kept[it].y) }
        return SwipeFeatures(trajectory, keys, count)
    }

    private fun mappingFor(layout: KeyboardLayout): Mapping? {
        if (layout === mappedLayout) return mapping
        mappedLayout = layout
        mapping = buildMapping(layout)
        return mapping
    }

    private fun buildMapping(layout: KeyboardLayout): Mapping? {
        val left = layout.keyForCharacter('й') ?: return null
        val right = layout.keyForCharacter('х') ?: return null
        val bottom = layout.keyForCharacter('я') ?: return null
        val spanX = right.centerX - left.centerX
        val spanY = bottom.centerY - left.centerY
        if (spanX <= 0f || spanY <= 0f) return null
        val scaleX = (spec.anchorRight - spec.anchorLeft) / spanX
        val scaleY = (spec.anchorBottom - spec.anchorTop) / spanY
        // Aliases such as `ё` share a key with their base letter and do not exist on the training grid.
        // In token order, because training resolves a point equally far from two keys to the lower token.
        val keys =
            layout.keys
                .filter { key -> key.label.singleOrNull()?.let { it in spec.gridKeys } == true }
                .sortedBy { spec.keyLabels.indexOf(it.label.single()) }
        val tokens = LongArray(keys.size) { spec.keyLabels.indexOf(keys[it].label.single()).toLong() }
        return Mapping(
            scaleX = scaleX,
            offsetX = spec.anchorLeft - scaleX * left.centerX,
            scaleY = scaleY,
            offsetY = spec.anchorTop - scaleY * left.centerY,
            keys = keys,
            tokens = tokens,
        )
    }

    private fun nearestToken(
        mapping: Mapping,
        x: Float,
        y: Float,
    ): Long {
        var best = 0
        var bestDistance = Float.MAX_VALUE
        for (index in mapping.keys.indices) {
            val key = mapping.keys[index]
            val dx = key.centerX - x
            val dy = key.centerY - y
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return mapping.tokens[best]
    }

    /**
     * Brings the points to the sampling of the training data (a point about every 16 ms) and within
     * the length the model accepts. Timestamps become strictly increasing; the last point stays.
     */
    private fun thinOut(points: List<TouchPoint>): List<TouchPoint> {
        if (points.isEmpty()) return points
        val kept = ArrayList<TouchPoint>(points.size)
        kept += points.first()
        for (index in 1 until points.lastIndex) {
            if (points[index].timeMillis - kept.last().timeMillis >= MIN_INTERVAL_MILLIS) kept += points[index]
        }
        val last = points.last()
        when {
            points.size == 1 -> Unit
            last.timeMillis > kept.last().timeMillis -> kept += last
            kept.size > 1 -> kept[kept.lastIndex] = last
        }
        if (kept.size <= MAX_POINTS) return kept
        val step = (kept.size - 1).toDouble() / (MAX_POINTS - 1)
        return List(MAX_POINTS) { kept[(it * step).toInt()] }
    }

    /** `f'[i] = (f[i + 1] - f[i - 1]) / (t[i + 1] - t[i - 1])`, zero at both ends. */
    private fun centralDifference(
        values: FloatArray,
        times: FloatArray,
    ): FloatArray {
        val result = FloatArray(values.size)
        for (i in 1 until values.lastIndex) {
            result[i] = (values[i + 1] - values[i - 1]) / (times[i + 1] - times[i - 1])
        }
        return result
    }

    companion object {
        /** The positional encoding of the model ends here. */
        const val MAX_POINTS = 299

        /** Keeps a screen of 120 Hz (8 ms) at every other point, and 60 Hz (16 ms) untouched. */
        private const val MIN_INTERVAL_MILLIS = 12.0
    }
}
