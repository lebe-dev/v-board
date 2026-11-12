package ru.tinyops.android.vboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import ru.tinyops.android.vboard.emoji.EmojiAssets
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * A run of emoji in the grid, under a small [title] or, without one, under a thin line. In the first
 * section of the grid the first [pinCount] cells are pinned emoji, which can be dragged to another place.
 */
internal data class EmojiSection(
    val cells: List<EmojiCell>,
    val title: String? = null,
    val pinCount: Int = 0,
)

/**
 * A scrolling grid of emoji, with a small title or a thin line between sections. It draws only the rows on screen
 * and takes the pictures from [assets], so thousands of emoji cost no views. The last rows clear
 * the buttons floating over the bottom of the grid.
 */
@SuppressLint("ViewConstructor")
internal class EmojiGridView(
    context: Context,
    theme: KeyboardTheme,
    assets: EmojiAssets,
) : View(context) {
    var onPick: ((String) -> Unit)? = null

    /** An emoji was held; [anchor] is its cell in the coordinates of this view. */
    var onHold: ((String, RectF) -> Unit)? = null

    /** The finger holding a pinned emoji started to carry it. */
    var onDragStart: (() -> Unit)? = null

    /** A carried emoji was put down: the pins in their new order. */
    var onPinsReordered: ((List<String>) -> Unit)? = null

    /** Emoji drawn as pinned: with a dot, outside the user's own section. */
    var pinned: Set<String>
        get() = painter.pinned
        set(value) {
            painter.pinned = value
            invalidate()
        }

    /** The section at the top of the grid changed. */
    var onSectionChanged: ((Int) -> Unit)? = null

    /** The section at the top of the grid. */
    var section = 0
        private set

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val scroller = OverScroller(context)
    private val painter = EmojiCellPainter(theme, assets)
    private val linePaint = Paint().apply { color = theme.suggestionDivider }
    private val titlePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.hint
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TITLE_SIZE, resources.displayMetrics)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

    private var sections: List<EmojiSection> = emptyList()

    /** How many emoji fit in a row at the current width. */
    var columns = 1
        private set
    private var cellSize = 0f

    // Row r starts at rowTop[r]; a title or a line has rowStart -1, otherwise the index of its first cell.
    private var rowSection = IntArray(0)
    private var rowStart = IntArray(0)
    private var rowTop = FloatArray(0)
    private var contentHeight = 0f
    private var scrollPosition = 0f

    // The pin under a holding finger, -1 when it holds anything else, and where the finger is.
    private var heldSlot = -1
    private var fingerX = 0f
    private var fingerY = 0f
    private var drag: EmojiPinDrag? = null

    private val autoScroll =
        object : Runnable {
            override fun run() {
                scrollWhileDragging()
                if (drag != null) postOnAnimation(this)
            }
        }

    private val gestures =
        GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean {
                    scroller.forceFinished(true)
                    return true
                }

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float,
                ): Boolean {
                    scrollTo(scrollPosition + distanceY)
                    return true
                }

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float,
                ): Boolean {
                    val start = scrollPosition.toInt()
                    scroller.fling(0, start, 0, -velocityY.toInt(), 0, 0, 0, maxScroll().toInt())
                    postInvalidateOnAnimation()
                    return true
                }

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    emojiAt(e.x, e.y)?.let { onPick?.invoke(it.text) }
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    val hit = emojiAt(e.x, e.y) ?: return
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    heldSlot = hit.pinSlot
                    fingerX = e.x
                    fingerY = e.y
                    onHold?.invoke(hit.text, hit.bounds)
                }
            },
        )

    /** Replaces the content; with [keepScroll] the grid stays where it was, as far as the new content allows. */
    fun setSections(
        value: List<EmojiSection>,
        keepScroll: Boolean = false,
    ) {
        finishDrag(commit = false)
        sections = value
        scroller.forceFinished(true)
        relayout()
        scrollPosition = if (keepScroll) scrollPosition.coerceIn(0f, maxScroll()) else 0f
        section = sectionAtTop()
        invalidate()
    }

    /** Scrolls so that the first emoji of [index] is at the top. */
    fun scrollToSection(index: Int) {
        val row = rowSection.indices.firstOrNull { rowSection[it] == index && rowStart[it] >= 0 } ?: return
        scroller.forceFinished(true)
        scrollTo(rowTop[row])
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout()
    }

    override fun onDetachedFromWindow() {
        finishDrag(commit = false)
        super.onDetachedFromWindow()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        trackHeldPin(event)
        return gestures.onTouchEvent(event)
    }

    override fun computeScroll() {
        if (!scroller.computeScrollOffset()) return
        scrollTo(scroller.currY.toFloat())
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        if (rowTop.isEmpty()) return
        var row = firstVisibleRow()
        while (row < rowTop.size && rowTop[row] - scrollPosition < height) {
            val top = rowTop[row] - scrollPosition
            if (rowStart[row] < 0) {
                drawDivider(canvas, sections[rowSection[row]].title, top)
            } else {
                drawRow(canvas, rowSection[row], rowStart[row], top)
            }
            row++
        }
        drag?.let { drawDrag(canvas, it) }
    }

    private fun drawDivider(
        canvas: Canvas,
        title: String?,
        top: Float,
    ) {
        if (title != null) {
            canvas.drawText(title, SIDE * density, top + TITLE_BASELINE * density, titlePaint)
            return
        }
        val y = top + SEPARATOR * density / 2f
        canvas.drawRect(SIDE * density, y, width - SIDE * density, y + density, linePaint)
    }

    private fun drawRow(
        canvas: Canvas,
        sectionIndex: Int,
        start: Int,
        top: Float,
    ) {
        val current = sections[sectionIndex]
        val end = minOf(start + columns, current.cells.size)
        // While a pin is carried, the pins are drawn by the drag.
        val first = if (drag != null && sectionIndex == 0) max(start, current.pinCount) else start
        for (index in first until end) {
            painter.draw(canvas, current.cells[index], (index - start) * cellSize, top, cellSize)
        }
    }

    private fun drawDrag(
        canvas: Canvas,
        drag: EmojiPinDrag,
    ) {
        for (index in 0 until sections[0].pinCount) {
            val x = drag.drawnX(index)
            val y = drag.drawnY(index) - scrollPosition
            if (index == drag.slot) {
                painter.drawPlate(canvas, x, y, cellSize)
            } else {
                painter.draw(canvas, drag.cells[index], x, y, cellSize)
            }
        }
        // The tile stays whole: this view clips what it draws, and in the first row the finger is near the edge.
        val half = cellSize * EmojiCellPainter.LIFT_SCALE / 2f
        val centerX = fingerX.coerceIn(half, max(half, width - half))
        val centerY = (fingerY - cellSize * LIFT_ABOVE_FINGER).coerceIn(half, max(half, height - half))
        painter.drawLifted(canvas, drag.carried, centerX, centerY, cellSize)
    }

    // region Dragging a pin

    private fun trackHeldPin(event: MotionEvent) {
        if (heldSlot < 0) return
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> carry(event.x, event.y)
            MotionEvent.ACTION_UP -> finishDrag(commit = true)
            MotionEvent.ACTION_CANCEL -> finishDrag(commit = false)
        }
    }

    private fun carry(
        x: Float,
        y: Float,
    ) {
        if (drag == null) {
            if (hypot(x - fingerX, y - fingerY) < touchSlop) return
            val pins = sections[0]
            drag = EmojiPinDrag(pins.cells, pins.pinCount, heldSlot, columns, cellSize, pinsTop(), ::invalidate)
            onDragStart?.invoke()
            postOnAnimation(autoScroll)
        }
        fingerX = x
        fingerY = y
        followFinger()
    }

    private fun followFinger() {
        val current = drag ?: return
        current.moveTo(current.slotAt(fingerX, fingerY + scrollPosition))
        invalidate()
    }

    /** With the finger at an edge the grid moves on, but never further down than the last pin needs. */
    private fun scrollWhileDragging() {
        val current = drag ?: return
        val edge = cellSize
        val step = AUTO_SCROLL * density
        val pinsBottom = pinsTop() + ceil(current.pins.size / columns.toFloat()) * cellSize
        val lowest = pinsBottom + BOTTOM_INSET * density - height
        val next =
            when {
                fingerY < edge -> scrollPosition - step
                fingerY > height - edge -> min(scrollPosition + step, max(scrollPosition, lowest))
                else -> return
            }
        scrollTo(next)
        followFinger()
    }

    private fun finishDrag(commit: Boolean) {
        heldSlot = -1
        val current = drag ?: return
        drag = null
        current.stop()
        removeCallbacks(autoScroll)
        if (commit) {
            sections = listOf(sections[0].copy(cells = current.cells)) + sections.drop(1)
            onPinsReordered?.invoke(current.pins)
        }
        invalidate()
    }

    // endregion

    /** An emoji under a touch, its cell in the coordinates of this view, and its slot if it is a pin. */
    private class Hit(
        val text: String,
        val bounds: RectF,
        val pinSlot: Int,
    )

    /** The emoji under a touch, or null on a title, on a line or past the last emoji. */
    private fun emojiAt(
        x: Float,
        y: Float,
    ): Hit? {
        val row = rowAt(y + scrollPosition)
        if (row < 0 || rowStart[row] < 0 || y + scrollPosition >= rowTop[row] + cellSize) return null
        val column = (x / cellSize).toInt()
        val current = sections[rowSection[row]]
        val index = rowStart[row] + column
        if (x < 0 || x >= cellSize * columns || index >= current.cells.size) return null
        val top = rowTop[row] - scrollPosition
        val pinSlot = if (rowSection[row] == 0 && index < current.pinCount) index else -1
        return Hit(
            current.cells[index].text,
            RectF(column * cellSize, top, (column + 1) * cellSize, top + cellSize),
            pinSlot,
        )
    }

    private fun relayout(available: Int = width) {
        if (available == 0) return
        columns = max(MIN_COLUMNS, (available / (CELL * density)).toInt())
        cellSize = available.toFloat() / columns
        val sectionOf = ArrayList<Int>()
        val startOf = ArrayList<Int>()
        val tops = ArrayList<Float>()
        var y = 0f
        for ((index, current) in sections.withIndex()) {
            if (current.title != null || index > 0) {
                sectionOf += index
                startOf += -1
                tops += y
                y += (if (current.title != null) TITLE_ROW else SEPARATOR) * density
            }
            for (start in current.cells.indices step columns) {
                sectionOf += index
                startOf += start
                tops += y
                y += cellSize
            }
        }
        rowSection = sectionOf.toIntArray()
        rowStart = startOf.toIntArray()
        rowTop = tops.toFloatArray()
        contentHeight = y + BOTTOM_INSET * density
        scrollPosition = scrollPosition.coerceIn(0f, maxScroll())
    }

    /** Where the pins start in the content: under the title of their section, if it has one. */
    private fun pinsTop(): Float = if (sections[0].title != null) TITLE_ROW * density else 0f

    private fun maxScroll() = max(0f, contentHeight - height)

    private fun scrollTo(position: Float) {
        val next = position.coerceIn(0f, maxScroll())
        if (next == scrollPosition) return
        scrollPosition = next
        val top = sectionAtTop()
        if (top != section) {
            section = top
            onSectionChanged?.invoke(top)
        }
        invalidate()
    }

    private fun sectionAtTop(): Int = rowSection.getOrNull(rowAt(scrollPosition + 1f).coerceAtLeast(0)) ?: 0

    private fun firstVisibleRow(): Int = rowAt(scrollPosition).coerceAtLeast(0)

    /** The row that contains content position [y], or -1 when there are no rows. */
    private fun rowAt(y: Float): Int {
        var low = 0
        var high = rowTop.size - 1
        var found = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (rowTop[middle] <= y) {
                found = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return found
    }

    private companion object {
        const val CELL = 44
        const val MIN_COLUMNS = 5
        const val SEPARATOR = 21
        const val TITLE_ROW = 26
        const val TITLE_BASELINE = 19
        const val TITLE_SIZE = 11f
        const val SIDE = 12
        const val BOTTOM_INSET = 64
        const val AUTO_SCROLL = 4
        const val LIFT_ABOVE_FINGER = 0.5f
    }
}
