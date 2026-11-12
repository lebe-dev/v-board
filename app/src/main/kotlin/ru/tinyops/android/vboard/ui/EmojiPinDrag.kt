package ru.tinyops.android.vboard.ui

import android.animation.ValueAnimator

/**
 * One pinned emoji being carried among the others. The pins are the first cells of their section,
 * starting [top] pixels into the content, so a slot is also a cell index. The other pins step aside
 * at once in [cells] and glide there on screen.
 */
internal class EmojiPinDrag(
    cells: List<EmojiCell>,
    private val count: Int,
    start: Int,
    private val columns: Int,
    private val cellSize: Float,
    private val top: Float,
    private val onFrame: () -> Unit,
) {
    /** All the cells of the section, in the order they are shown now. */
    val cells: MutableList<EmojiCell> = cells.toMutableList()

    /** The slot the carried emoji would land in. */
    var slot = start
        private set

    /** The pins in the order they are shown now. */
    val pins: List<String>
        get() = cells.take(count).map { it.text }

    val carried: EmojiCell
        get() = cells[slot]

    private var startX = List(count) { slotX(it) }
    private var startY = List(count) { slotY(it) }
    private var remaining = 0f

    private val glide =
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = GLIDE_MILLIS
            addUpdateListener {
                remaining = it.animatedValue as Float
                onFrame()
            }
        }

    /** The slot under a point of the content, as near as there is one. */
    fun slotAt(
        x: Float,
        y: Float,
    ): Int {
        val column = (x / cellSize).toInt().coerceIn(0, columns - 1)
        val row = ((y - top) / cellSize).toInt().coerceAtLeast(0)
        return (row * columns + column).coerceAtMost(count - 1)
    }

    fun moveTo(target: Int) {
        if (target == slot) return
        startX = stepAside(List(count, ::drawnX), target)
        startY = stepAside(List(count, ::drawnY), target)
        cells.add(target, cells.removeAt(slot))
        slot = target
        remaining = 1f
        glide.cancel()
        glide.start()
    }

    fun drawnX(index: Int) = slotX(index) + (startX[index] - slotX(index)) * remaining

    fun drawnY(index: Int) = slotY(index) + (startY[index] - slotY(index)) * remaining

    fun stop() = glide.cancel()

    private fun stepAside(
        values: List<Float>,
        target: Int,
    ) = values.toMutableList().also { it.add(target, it.removeAt(slot)) }

    private fun slotX(index: Int) = index % columns * cellSize

    private fun slotY(index: Int) = top + index / columns * cellSize

    private companion object {
        const val GLIDE_MILLIS = 120L
    }
}
