package ru.tinyops.android.vboard.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import ru.tinyops.android.vboard.EnterAction
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.input.ShiftState
import ru.tinyops.android.vboard.core.layout.Bounds
import ru.tinyops.android.vboard.core.layout.KeySpec
import ru.tinyops.android.vboard.core.layout.KeyStyle
import ru.tinyops.android.vboard.core.layout.KeyType
import ru.tinyops.android.vboard.core.layout.PlacedKey
import ru.tinyops.android.vboard.stt.model.TouchPoint
import java.util.Locale

/** Shape of the alternates popup: [rows] counted from the bottom row, the one next to the key. */
internal data class PopupGrid(
    val columns: Int,
    val rows: Int,
) {
    companion object {
        private const val MAX_SINGLE_ROW = 5

        /** One row for a short list, otherwise two rows of nearly equal length. */
        fun of(count: Int): PopupGrid {
            if (count <= MAX_SINGLE_ROW) return PopupGrid(columns = count, rows = 1)
            return PopupGrid(columns = (count + 1) / 2, rows = 2)
        }
    }
}

/**
 * Long-press alternates shown above a key; [selected] follows the finger. Cells are filled row by
 * row starting from the bottom row, so the first alternate sits right above the key.
 */
internal class AlternatesPopup(
    val pointerId: Int,
    val alternates: List<String>,
    val bounds: RectF,
    val grid: PopupGrid,
    val cellWidth: Float,
    val cellHeight: Float,
) {
    var selected = 0

    fun cell(index: Int): Bounds {
        val left = bounds.left + (index % grid.columns) * cellWidth
        val bottom = bounds.bottom - (index / grid.columns) * cellHeight
        return Bounds(left, bottom - cellHeight, left + cellWidth, bottom)
    }

    /** The cell nearest to the point; a finger outside the popup is clamped to its edge. */
    fun indexAt(
        x: Float,
        y: Float,
    ): Int {
        val column = ((x - bounds.left) / cellWidth).toInt().coerceIn(0, grid.columns - 1)
        val row = ((bounds.bottom - y) / cellHeight).toInt().coerceIn(0, grid.rows - 1)
        return (row * grid.columns + column).coerceAtMost(alternates.lastIndex)
    }
}

/** Draws keys, key previews, long-press popups and the gesture trail. Allocation-free per frame. */
internal class KeyRenderer(
    private val theme: KeyboardTheme,
) {
    var shiftState: ShiftState = ShiftState.OFF
    var enterAction: EnterAction = EnterAction.NEWLINE
    var locale: Locale = Locale.ENGLISH
    var emojiEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            uppercaseLabels.clear()
        }

    private val uppercaseLabels = HashMap<String, String>()

    private val rect = RectF()
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hintPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, HINT_WEIGHT, false)
        }
    private val trailPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = theme.trail
        }
    private var labelSize = 0f

    // Label offsets from the key's center to the baseline, as fractions of the text size. Font
    // metrics scale with the size, so they are measured once instead of asking the native paint
    // for them on every key of every frame.
    private val xHeightOffset = glyphCenterOffset(X_HEIGHT_REFERENCE)
    private val capHeightOffset = glyphCenterOffset(CAP_HEIGHT_REFERENCE)
    private val lineBoxOffset =
        run {
            labelPaint.textSize = METRICS_TEXT_SIZE
            -(labelPaint.descent() + labelPaint.ascent()) / 2f / METRICS_TEXT_SIZE
        }

    /** Scales text to the current key height. */
    fun setKeyHeight(keyHeight: Float) {
        labelSize = keyHeight * LABEL_SIZE
        hintPaint.textSize = keyHeight * HINT_SIZE
    }

    fun drawKey(
        canvas: Canvas,
        key: PlacedKey,
        pressed: Boolean,
    ) {
        val spec = key.spec
        val b = key.bounds
        val pill = spec.style == KeyStyle.MODE || spec.style == KeyStyle.ACTION
        val radius = if (pill) b.height / 2f else theme.keyRadius
        // A pressed key sinks: its shadow disappears.
        if (!pressed) {
            rect.set(b.left, b.top + theme.keyShadowOffset, b.right, b.bottom + theme.keyShadowOffset)
            keyPaint.color = theme.keyShadow
            canvas.drawRoundRect(rect, radius, radius, keyPaint)
        }
        fillRoundRect(canvas, b, theme.keyColor(spec.style, pressed), radius)

        when (spec.type) {
            KeyType.SHIFT -> drawIcon(canvas, b, shiftIcon(), theme.keyIcon)
            KeyType.BACKSPACE -> drawIcon(canvas, b, R.drawable.ic_backspace, theme.keyIcon)
            KeyType.LANGUAGE -> drawIcon(canvas, b, R.drawable.ic_language, theme.keyIcon)
            KeyType.ENTER -> drawIcon(canvas, b, enterAction.icon, theme.actionIcon)
            KeyType.SPACE -> drawLabel(canvas, b, spec.label, b.height * SPACE_LABEL_SIZE, theme.label)
            KeyType.SYMBOLS, KeyType.ALPHABET, KeyType.MORE_SYMBOLS ->
                drawLabel(canvas, b, spec.label, b.height * MODE_LABEL_SIZE, theme.label)
            KeyType.CHARACTER -> {
                drawLabel(canvas, b, displayLabel(spec), labelSize, theme.label)
                spec.hint?.let { drawHint(canvas, b, it) }
                if (emojiEnabled && spec.showsEmojiHint) drawEmojiHint(canvas, b)
            }
        }
    }

    /** The outline of a key while the keyboard works as a trackpad: its shape, in one color, with no label. */
    fun drawKeySkeleton(
        canvas: Canvas,
        key: PlacedKey,
    ) {
        val b = key.bounds
        val pill = key.spec.style == KeyStyle.MODE || key.spec.style == KeyStyle.ACTION
        fillRoundRect(canvas, b, theme.keyColor(KeyStyle.LETTER, false), if (pill) b.height / 2f else theme.keyRadius)
    }

    /** Enlarged copy of a pressed character key, drawn above it. */
    fun drawPreview(
        canvas: Canvas,
        key: PlacedKey,
    ) {
        val spec = key.spec
        if (spec.type != KeyType.CHARACTER) return
        val b = key.bounds
        val padding = b.width * PREVIEW_WIDTH_PADDING
        val top = b.top - b.height * PREVIEW_HEIGHT
        val preview = Bounds(b.left - padding, top, b.right + padding, b.top - b.height * GAP)
        fillRoundRect(canvas, preview, theme.preview, theme.keyRadius)
        drawLabel(canvas, preview, displayLabel(spec), labelSize * PREVIEW_LABEL_SCALE, theme.label)
    }

    fun drawPopup(
        canvas: Canvas,
        popup: AlternatesPopup,
    ) {
        keyPaint.color = theme.preview
        canvas.drawRoundRect(popup.bounds, theme.keyRadius, theme.keyRadius, keyPaint)
        popup.alternates.forEachIndexed { index, text ->
            val cell = popup.cell(index)
            val selected = index == popup.selected
            if (selected) fillRoundRect(canvas, cell, theme.popupSelected, theme.keyRadius)
            val color = if (selected) theme.popupSelectedLabel else theme.label
            if (text == KeySpec.EMOJI_ALTERNATE) {
                drawIcon(canvas, cell, R.drawable.ic_emoji, color)
            } else {
                drawLabel(canvas, cell, if (shiftState.isActive) uppercase(text) else text, labelSize, color)
            }
        }
    }

    /** Popup bounds above [key], kept inside `[minX, maxX]`. */
    fun popupBounds(
        key: PlacedKey,
        grid: PopupGrid,
        minX: Float,
        maxX: Float,
    ): RectF {
        val b = key.bounds
        val width = b.width * grid.columns
        val left = b.left.coerceAtMost(maxX - width).coerceAtLeast(minX)
        val bottom = b.top - b.height * GAP
        return RectF(left, bottom - b.height * POPUP_HEIGHT * grid.rows, left + width, bottom)
    }

    /**
     * The gesture trail: an opaque polyline that tapers towards its tail. Fading is applied to
     * the whole trail through one layer, so overlapping segment caps do not show up as dots.
     * Touch sampling far outpaces what is visible: points closer than the stroke is wide are
     * skipped, which keeps the cost per frame bounded by the length of the path.
     */
    fun drawTrail(
        canvas: Canvas,
        points: List<TouchPoint>,
        alpha: Float,
    ) {
        val count = points.size
        val layer = if (alpha < 1f) canvas.saveLayerAlpha(null, (MAX_ALPHA * alpha).toInt()) else NO_LAYER
        val minSpacingSq = theme.trailWidth * theme.trailWidth
        var from = points[0]
        for (i in 1 until count) {
            val to = points[i]
            val dx = to.x - from.x
            val dy = to.y - from.y
            if (i < count - 1 && dx * dx + dy * dy < minSpacingSq) continue
            val progress = i.toFloat() / (count - 1)
            trailPaint.strokeWidth = theme.trailWidth * lerp(TRAIL_MIN_WIDTH, progress)
            canvas.drawLine(from.x, from.y, to.x, to.y, trailPaint)
            from = to
        }
        if (layer != NO_LAYER) canvas.restoreToCount(layer)
    }

    private fun lerp(
        min: Float,
        progress: Float,
    ): Float = min + (1f - min) * progress

    private fun shiftIcon(): Int =
        when (shiftState) {
            ShiftState.OFF -> R.drawable.ic_shift
            ShiftState.ONCE -> R.drawable.ic_shift_on
            ShiftState.LOCKED -> R.drawable.ic_shift_lock
        }

    private fun displayLabel(spec: KeySpec): String =
        if (spec.isLetter && shiftState.isActive) uppercase(spec.label) else spec.label

    private fun uppercase(text: String): String = uppercaseLabels.getOrPut(text) { text.uppercase(locale) }

    private fun fillRoundRect(
        canvas: Canvas,
        b: Bounds,
        color: Int,
        radius: Float,
    ) {
        rect.set(b.left, b.top, b.right, b.bottom)
        keyPaint.color = color
        canvas.drawRoundRect(rect, radius, radius, keyPaint)
    }

    private fun drawLabel(
        canvas: Canvas,
        b: Bounds,
        text: String,
        size: Float,
        color: Int,
    ) {
        labelPaint.textSize = size
        labelPaint.color = color
        canvas.drawText(text, b.centerX, b.centerY + baselineOffset(text) * size, labelPaint)
    }

    /**
     * Centers the visible body of the label rather than the font's line box, which reserves
     * room for accents and descenders and therefore pushes letters below the key's center:
     * lowercase letters are centered on the x-height, other letters and digits on the cap
     * height. Punctuation keeps the line-box centering so that "," and "." sit low as usual.
     */
    private fun baselineOffset(text: String): Float =
        when {
            text.any { it.isLetter() } && text.none { it.isUpperCase() || it.isDigit() } -> xHeightOffset
            text.any { it.isLetterOrDigit() } -> capHeightOffset
            else -> lineBoxOffset
        }

    private fun glyphCenterOffset(reference: String): Float {
        val bounds = Rect()
        labelPaint.textSize = METRICS_TEXT_SIZE
        labelPaint.getTextBounds(reference, 0, 1, bounds)
        return bounds.height() / 2f / METRICS_TEXT_SIZE
    }

    private fun drawHint(
        canvas: Canvas,
        b: Bounds,
        hint: String,
    ) {
        hintPaint.color = theme.hint
        val x = b.right - hintPaint.textSize * HINT_INSET
        canvas.drawText(hint, x, b.top + hintPaint.textSize * HINT_BASELINE, hintPaint)
    }

    /** The small smiley above the comma: holding the key opens the emoji picker. */
    private fun drawEmojiHint(
        canvas: Canvas,
        b: Bounds,
    ) {
        val size = (b.height * EMOJI_HINT_SIZE).toInt()
        val left = (b.centerX - size / 2f).toInt()
        val top = (b.top + b.height * EMOJI_HINT_TOP).toInt()
        theme.icon(R.drawable.ic_emoji, theme.hint).apply {
            setBounds(left, top, left + size, top + size)
            draw(canvas)
        }
    }

    private fun drawIcon(
        canvas: Canvas,
        b: Bounds,
        icon: Int,
        tint: Int,
    ) {
        val size = (b.height * ICON_SIZE).toInt()
        val left = (b.centerX - size / 2f).toInt()
        val top = (b.centerY - size / 2f).toInt()
        theme.icon(icon, tint).apply {
            setBounds(left, top, left + size, top + size)
            draw(canvas)
        }
    }

    private companion object {
        // Sizes relative to the key height.
        const val LABEL_SIZE = 0.53f
        const val HINT_SIZE = 0.21f
        const val SPACE_LABEL_SIZE = 0.3f
        const val MODE_LABEL_SIZE = 0.36f
        const val ICON_SIZE = 0.5f
        const val EMOJI_HINT_SIZE = 0.26f
        const val EMOJI_HINT_TOP = 0.1f
        const val HINT_WEIGHT = 500
        const val X_HEIGHT_REFERENCE = "x"
        const val CAP_HEIGHT_REFERENCE = "H"
        const val METRICS_TEXT_SIZE = 1000f
        const val PREVIEW_HEIGHT = 1.25f
        const val POPUP_HEIGHT = 1.1f
        const val GAP = 0.08f

        const val PREVIEW_WIDTH_PADDING = 0.08f
        const val PREVIEW_LABEL_SCALE = 1.2f

        // Hint placement relative to the hint text size.
        const val HINT_INSET = 0.8f
        const val HINT_BASELINE = 1.05f

        const val TRAIL_MIN_WIDTH = 0.3f
        const val MAX_ALPHA = 255
        const val NO_LAYER = -1
    }
}
