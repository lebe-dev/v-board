package ru.tinyops.android.vboard.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.ColorUtils
import ru.tinyops.android.vboard.emoji.EmojiAssets

/**
 * One emoji of the picker: the text to commit and the name of its picture, if it has one. A
 * [plated] emoji stands on a tinted plate, which sets the pinned emoji apart from the recent ones.
 */
internal data class EmojiCell(
    val text: String,
    val image: String?,
    val plated: Boolean = false,
)

/**
 * Draws a single emoji cell of the grid: the picture (or the system glyph when there is none), a
 * plate under a plated cell, and a dot on a pinned emoji anywhere else in the grid.
 */
internal class EmojiCellPainter(
    private val theme: KeyboardTheme,
    private val assets: EmojiAssets,
) {
    /** Emoji drawn as pinned. */
    var pinned: Set<String> = emptySet()

    private val destination = RectF()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val platePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(theme.popupSelected, PLATE_ALPHA) }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.popupSelected }
    private val liftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.raised }

    fun draw(
        canvas: Canvas,
        cell: EmojiCell,
        left: Float,
        top: Float,
        size: Float,
    ) {
        if (cell.plated) drawPlate(canvas, left, top, size)
        drawEmoji(canvas, cell, left, top, size)
        if (!cell.plated && cell.text in pinned) drawDot(canvas, left, top, size)
    }

    /** The place a dragged emoji left: only its plate. */
    fun drawPlate(
        canvas: Canvas,
        left: Float,
        top: Float,
        size: Float,
    ) {
        val inset = size * PLATE_INSET
        destination.set(left + inset, top + inset, left + size - inset, top + size - inset)
        canvas.drawRoundRect(destination, size * PLATE_RADIUS, size * PLATE_RADIUS, platePaint)
    }

    /** A dragged emoji: on a raised tile, a little larger than in its cell. */
    fun drawLifted(
        canvas: Canvas,
        cell: EmojiCell,
        centerX: Float,
        centerY: Float,
        size: Float,
    ) {
        val lifted = size * LIFT_SCALE
        val left = centerX - lifted / 2f
        val top = centerY - lifted / 2f
        destination.set(left, top, left + lifted, top + lifted)
        canvas.drawRoundRect(destination, lifted * PLATE_RADIUS, lifted * PLATE_RADIUS, liftPaint)
        drawEmoji(canvas, cell, left, top, lifted)
    }

    private fun drawEmoji(
        canvas: Canvas,
        cell: EmojiCell,
        left: Float,
        top: Float,
        size: Float,
    ) {
        val bitmap = cell.image?.let(assets::picture)
        if (bitmap == null) {
            glyphPaint.textSize = size * GLYPH_SIZE
            canvas.drawText(cell.text, left + size / 2f, top + size * GLYPH_BASELINE, glyphPaint)
            return
        }
        val inset = size * PICTURE_INSET
        destination.set(left + inset, top + inset, left + size - inset, top + size - inset)
        canvas.drawBitmap(bitmap, null, destination, bitmapPaint)
    }

    private fun drawDot(
        canvas: Canvas,
        left: Float,
        top: Float,
        size: Float,
    ) {
        val margin = size * DOT_MARGIN
        canvas.drawCircle(left + size - margin, top + size - margin, size * DOT_RADIUS, dotPaint)
    }

    companion object {
        /** How much larger a dragged emoji is than its cell. */
        const val LIFT_SCALE = 1.25f

        private const val PICTURE_INSET = 0.12f
        private const val GLYPH_SIZE = 0.6f
        private const val GLYPH_BASELINE = 0.7f
        private const val PLATE_INSET = 0.04f
        private const val PLATE_RADIUS = 0.26f
        private const val PLATE_ALPHA = 0x38
        private const val DOT_MARGIN = 0.16f
        private const val DOT_RADIUS = 0.065f
    }
}
