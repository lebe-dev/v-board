package ru.tinyops.android.vboard.core.layout

/** An axis-aligned rectangle in view pixels. */
data class Bounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun contains(
        x: Float,
        y: Float,
    ): Boolean = x >= left && x < right && y >= top && y < bottom
}

/**
 * A key placed on screen.
 *
 * @param bounds the visible key shape (inset by the key gaps)
 * @param hitBox the touch target: it covers the gaps around the key and, for keys on the
 * keyboard's border, extends to the edge, so every point of the keyboard maps to a key
 */
data class PlacedKey(
    val spec: KeySpec,
    val bounds: Bounds,
    val hitBox: Bounds,
)

/** Spacing applied when placing keys, in pixels. */
data class GeometryMetrics(
    val horizontalGap: Float,
    val verticalGap: Float,
    val horizontalPadding: Float,
    val topPadding: Float,
    val bottomPadding: Float,
)

/** Places the keys of a [KeyboardSpec] in a `width x height` pixel area. */
class KeyboardGeometry(
    val spec: KeyboardSpec,
    val width: Float,
    val height: Float,
    metrics: GeometryMetrics,
) {
    val keys: List<PlacedKey> = place(metrics)

    /** The key under the point, or null when the point is outside the keyboard. */
    fun keyAt(
        x: Float,
        y: Float,
    ): PlacedKey? = keys.firstOrNull { it.hitBox.contains(x, y) }

    private fun place(metrics: GeometryMetrics): List<PlacedKey> {
        if (width <= 0f || height <= 0f || spec.rows.isEmpty()) return emptyList()

        val unit = (width - 2 * metrics.horizontalPadding) / spec.columns
        val rowHeight = (height - metrics.topPadding - metrics.bottomPadding) / spec.rows.size
        val result = ArrayList<PlacedKey>()

        spec.rows.forEachIndexed { rowIndex, row ->
            val rowTop = metrics.topPadding + rowIndex * rowHeight
            val hitTop = if (rowIndex == 0) 0f else rowTop
            val hitBottom = if (rowIndex == spec.rows.lastIndex) height else rowTop + rowHeight
            // Narrower rows are centered.
            var x = metrics.horizontalPadding + ((spec.columns - row.totalWidth) / 2f + row.leftPadding) * unit

            row.keys.forEachIndexed { keyIndex, key ->
                val keyWidth = key.width * unit
                val bounds =
                    Bounds(
                        left = x + metrics.horizontalGap / 2f,
                        top = rowTop + metrics.verticalGap / 2f,
                        right = x + keyWidth - metrics.horizontalGap / 2f,
                        bottom = rowTop + rowHeight - metrics.verticalGap / 2f,
                    )
                val hitBox =
                    Bounds(
                        left = if (keyIndex == 0) 0f else x,
                        top = hitTop,
                        right = if (keyIndex == row.keys.lastIndex) width else x + keyWidth,
                        bottom = hitBottom,
                    )
                result += PlacedKey(key, bounds, hitBox)
                x += keyWidth
            }
        }
        return result
    }
}
