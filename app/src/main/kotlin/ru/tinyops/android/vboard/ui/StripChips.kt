package ru.tinyops.android.vboard.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.text.TextUtils
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.clipboard.ClipChip
import ru.tinyops.android.vboard.core.clipboard.ClipKind
import ru.tinyops.android.vboard.core.layout.KeyStyle

/** The stretch of the strip the chips may take: from [start] to [end] of a row [rowHeight] high, [unit] apart. */
internal data class ChipRoom(
    val start: Float,
    val end: Float,
    val unit: Float,
    val rowHeight: Int,
)

/**
 * The chips of the recently copied clip on the strip: a text, an image or the links found in it. They are
 * centered as a group in the room the buttons leave; when they do not fit, the longest ones are shortened first.
 * The layout is kept while the chips and their room stay the same.
 */
internal class StripChips(
    private val context: Context,
    private val theme: KeyboardTheme,
) {
    var chips: List<ClipChip> = emptyList()
        private set

    private var thumbnail: Bitmap? = null
    private var thumbnailShader: BitmapShader? = null
    private var layout: Layout? = null

    private class Layout(
        val room: ChipRoom,
        val rects: List<RectF>,
        val labels: List<CharSequence>,
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.keyColor(KeyStyle.LETTER, false) }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.keyColor(KeyStyle.LETTER, true) }
    private val textPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.LEFT
            textSize = theme.suggestionTextSize * TEXT_SCALE
            color = theme.suggestion
        }
    private val thumbnailMatrix = Matrix()
    private val thumbnailPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconSize: Float get() = theme.stripIconSize * ICON_SCALE

    /** Shows [next], with [image] as the thumbnail of an image chip once it is loaded; false when nothing changed. */
    fun set(
        next: List<ClipChip>,
        image: Bitmap?,
    ): Boolean {
        if (next == chips && image === thumbnail) return false
        chips = next
        thumbnail = image
        thumbnailShader = image?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        layout = null
        return true
    }

    /** The index of the chip under [x], a little beyond its edges too, or -1. */
    fun indexAt(
        x: Float,
        room: ChipRoom,
    ): Int {
        val slack = room.unit / 2f
        return layoutIn(room).rects.indexOfFirst { x >= it.left - slack && x <= it.right + slack }
    }

    /** Draws the chips, the one at [pressed] (or none for -1) in the pressed color. */
    fun draw(
        canvas: Canvas,
        room: ChipRoom,
        pressed: Int,
    ) {
        val layout = layoutIn(room)
        val baseline = textPaint.baselineIn(room.rowHeight)
        for ((index, chip) in chips.withIndex()) {
            val rect = layout.rects[index]
            canvas.drawRoundRect(
                rect,
                rect.height() / 2f,
                rect.height() / 2f,
                if (index ==
                    pressed
                ) {
                    pressedPaint
                } else {
                    paint
                },
            )
            drawIcon(canvas, chip, rect.left + room.unit * PAD_START + iconSize / 2f, rect.centerY())
            val label = layout.labels[index]
            val textLeft = rect.left + room.unit * PAD_START + iconSize + room.unit
            canvas.drawText(label, 0, label.length, textLeft, baseline, textPaint)
        }
    }

    private fun layoutIn(room: ChipRoom): Layout {
        layout?.takeIf { it.room == room }?.let { return it }
        val unit = room.unit
        val available = room.end - room.start - 2f * unit
        val labels = chips.map { it.shownLabel(context) }
        val chromeWidth = unit * (PAD_START + PAD_END) + iconSize + unit
        val desired = labels.map { chromeWidth + textPaint.measureText(it, 0, it.length) }
        val widths = fit(desired, available - unit * (chips.size - 1))
        val total = widths.sum() + unit * (widths.size - 1)
        var left = room.start + (room.end - room.start - total) / 2f
        val chipHeight = room.rowHeight * HEIGHT
        val top = (room.rowHeight - chipHeight) / 2f
        val rects =
            widths.map { width ->
                RectF(left, top, left + width, top + chipHeight).also { left += width + unit }
            }
        val shown =
            labels.mapIndexed { index, label ->
                val space = rects[index].width() - chromeWidth
                TextUtils.ellipsize(label, textPaint, maxOf(space, 0f), TextUtils.TruncateAt.END)
            }
        return Layout(room, rects, shown).also { layout = it }
    }

    private fun drawIcon(
        canvas: Canvas,
        chip: ClipChip,
        centerX: Float,
        centerY: Float,
    ) {
        val bitmap = thumbnail
        if (chip.kind == ClipKind.IMAGE && bitmap != null) {
            drawThumbnail(canvas, bitmap, centerX, centerY, iconSize * THUMBNAIL_SCALE / 2f)
            return
        }
        val id =
            when (chip.kind) {
                ClipKind.LINK -> R.drawable.ic_link
                ClipKind.EMAIL -> R.drawable.ic_mail
                ClipKind.PHONE -> R.drawable.ic_phone
                ClipKind.IMAGE -> R.drawable.ic_image
                ClipKind.TEXT -> R.drawable.ic_assignment
            }
        val half = (iconSize / 2f).toInt()
        theme.icon(id, theme.suggestion).apply {
            setBounds(centerX.toInt() - half, centerY.toInt() - half, centerX.toInt() + half, centerY.toInt() + half)
            draw(canvas)
        }
    }

    /** A round center-cropped thumbnail. */
    private fun drawThumbnail(
        canvas: Canvas,
        bitmap: Bitmap,
        centerX: Float,
        centerY: Float,
        radius: Float,
    ) {
        val side = minOf(bitmap.width, bitmap.height).toFloat()
        val scale = radius * 2f / side
        thumbnailMatrix.setScale(scale, scale)
        thumbnailMatrix.postTranslate(
            centerX - bitmap.width * scale / 2f,
            centerY - bitmap.height * scale / 2f,
        )
        val shader = thumbnailShader ?: return
        shader.setLocalMatrix(thumbnailMatrix)
        thumbnailPaint.shader = shader
        canvas.drawCircle(centerX, centerY, radius, thumbnailPaint)
    }

    private companion object {
        const val TEXT_SCALE = 0.85f
        const val HEIGHT = 0.78f
        const val PAD_START = 1.5f
        const val PAD_END = 1.75f
        const val ICON_SCALE = 0.85f
        const val THUMBNAIL_SCALE = 1.25f
    }
}

/** Shares [available] width among the chips: short ones keep their width, long ones split the rest. */
private fun fit(
    desired: List<Float>,
    available: Float,
): List<Float> {
    val widths = desired.toMutableList()
    var remaining = available
    val order = desired.indices.sortedBy { desired[it] }
    for ((position, index) in order.withIndex()) {
        widths[index] = minOf(desired[index], remaining / (order.size - position))
        remaining -= widths[index]
    }
    return widths
}

private fun ClipChip.shownLabel(context: Context): CharSequence =
    if (kind == ClipKind.IMAGE) context.getString(R.string.clip_image) else label

/** The baseline that centers the text of this paint in a row [height] high. */
internal fun Paint.baselineIn(height: Int) = height / 2f - (descent() + ascent()) / 2f
