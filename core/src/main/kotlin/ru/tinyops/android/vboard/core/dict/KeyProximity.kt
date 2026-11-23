package ru.tinyops.android.vboard.core.dict

import ru.tinyops.android.vboard.core.layout.KeyboardGeometry
import ru.tinyops.android.vboard.core.layout.KeyboardSpec
import kotlin.math.hypot

/** A touch or key center position in normalized key units (column width by row height). */
data class TapPoint(
    val x: Float,
    val y: Float,
)

/**
 * Spatial layout of letter keys for weighing typing corrections. Distances are measured in
 * key-width / row-height units so adjacent keys sit about 1.0 unit apart on any screen size.
 */
class KeyProximity(
    private val centers: Map<Char, TapPoint>,
) {
    private val neighbors: Map<Char, CharArray> =
        centers.keys.associateWith { letter -> computeNear(centers.getValue(letter), exclude = letter) }

    /** Euclidean distance between the centers of [a] and [b], or [FAR_DISTANCE] if unknown. */
    fun distance(
        a: Char,
        b: Char,
    ): Float {
        if (a == b) return 0f
        val pa = pointOf(a) ?: return FAR_DISTANCE
        val pb = pointOf(b) ?: return FAR_DISTANCE
        return hypot(pa.x - pb.x, pa.y - pb.y)
    }

    /** Euclidean distance from [tap] to the center of [letter], or [FAR_DISTANCE] if unknown. */
    fun tapDistance(
        tap: TapPoint,
        letter: Char,
    ): Float {
        val center = pointOf(letter) ?: return FAR_DISTANCE
        return hypot(tap.x - center.x, tap.y - center.y)
    }

    /** Letters whose key centers sit within [NEIGHBOR_RADIUS] of [letter], nearest first. */
    fun nearKeys(letter: Char): CharArray = neighbors[letter] ?: neighbors[fold(letter)] ?: CharArray(0)

    /** Letters whose key centers sit within [NEIGHBOR_RADIUS] of [tap], nearest first. */
    fun nearKeys(tap: TapPoint): CharArray = computeNear(tap, exclude = null)

    private fun pointOf(letter: Char): TapPoint? = centers[letter] ?: centers[fold(letter)]

    private fun computeNear(
        origin: TapPoint,
        exclude: Char?,
    ): CharArray =
        centers.entries
            .asSequence()
            .filter { (ch, _) -> ch != exclude }
            .map { (ch, pt) -> ch to hypot(pt.x - origin.x, pt.y - origin.y) }
            .filter { (_, dist) -> dist <= NEIGHBOR_RADIUS }
            .sortedBy { it.second }
            .map { it.first }
            .toList()
            .toCharArray()

    private fun fold(letter: Char): Char = if (letter == 'ё') 'е' else letter

    companion object {
        const val NEIGHBOR_RADIUS = 1.55f
        const val FAR_DISTANCE = 4.0f
        private const val HALF = 0.5f

        /** Builds key centers from a measured [KeyboardGeometry]. */
        fun fromGeometry(geometry: KeyboardGeometry): KeyProximity {
            val unitX = unitWidth(geometry) ?: return fromSpec(geometry.spec)
            val unitY = rowHeight(geometry) ?: return fromSpec(geometry.spec)
            val map = LinkedHashMap<Char, TapPoint>()
            for (placed in geometry.keys) {
                if (!placed.spec.isLetter) continue
                val ch = placed.spec.output[0].lowercaseChar()
                map[ch] = TapPoint(placed.bounds.centerX / unitX, placed.bounds.centerY / unitY)
            }
            return KeyProximity(map)
        }

        /** Normalizes a view-pixel touch coordinate on [geometry] into key units. */
        fun tapPoint(
            geometry: KeyboardGeometry,
            x: Float,
            y: Float,
        ): TapPoint? {
            val unitX = unitWidth(geometry) ?: return null
            val unitY = rowHeight(geometry) ?: return null
            return TapPoint(x / unitX, y / unitY)
        }

        /** Builds key centers directly from a [KeyboardSpec], without screen dimensions. */
        fun fromSpec(spec: KeyboardSpec): KeyProximity {
            val map = LinkedHashMap<Char, TapPoint>()
            val columns = spec.columns
            spec.rows.forEachIndexed { rowIndex, row ->
                var x = (columns - row.totalWidth) * HALF + row.leftPadding
                val y = rowIndex + HALF
                for (key in row.keys) {
                    if (key.isLetter) {
                        val ch = key.output[0].lowercaseChar()
                        map[ch] = TapPoint(x + key.width * HALF, y)
                    }
                    x += key.width
                }
            }
            return KeyProximity(map)
        }

        private fun unitWidth(geometry: KeyboardGeometry): Float? {
            if (geometry.width <= 0f || geometry.spec.columns <= 0f) return null
            return geometry.width / geometry.spec.columns
        }

        private fun rowHeight(geometry: KeyboardGeometry): Float? {
            if (geometry.height <= 0f || geometry.spec.rows.isEmpty()) return null
            return geometry.height / geometry.spec.rows.size
        }
    }
}
