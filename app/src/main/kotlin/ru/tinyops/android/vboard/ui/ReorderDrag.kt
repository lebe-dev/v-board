package ru.tinyops.android.vboard.ui

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Lets the rows of a vertical [column] inside [scroller] be dragged by a handle to a new place. The row
 * follows the finger and trades places with a neighbor once its middle passes the neighbor's; near an edge
 * of [scroller] the list scrolls along. [onDrop] gets the keys of the rows in their new order, only when it
 * changed. Main thread only.
 */
internal class ReorderDrag<K>(
    private val column: LinearLayout,
    private val scroller: ScrollView,
    private val lift: Float,
    private val edge: Int,
    private val onDrop: (List<K>) -> Unit,
) {
    private var row: View? = null
    private var startRawY = 0f
    private var startScroll = 0
    private var startTop = 0
    private var startOrder: List<K> = emptyList()

    /** A touch on [handle] drags [row], which must be a child of the column and carry its key as its tag. */
    @SuppressLint("ClickableViewAccessibility")
    fun attach(
        handle: View,
        row: View,
    ) {
        handle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> start(view, row, event)
                MotionEvent.ACTION_MOVE -> move(event)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finish()
            }
            true
        }
    }

    private fun start(
        handle: View,
        dragged: View,
        event: MotionEvent,
    ) {
        handle.parent?.requestDisallowInterceptTouchEvent(true)
        row = dragged
        startRawY = event.rawY
        startScroll = scroller.scrollY
        startTop = tops()[column.indexOfChild(dragged)]
        startOrder = keys()
        dragged.translationZ = lift
    }

    private fun move(event: MotionEvent) {
        val dragged = row ?: return
        scrollNearEdge(event.rawY)
        val travel = event.rawY - startRawY + (scroller.scrollY - startScroll)
        val middle = startTop + travel + dragged.height / 2f
        while (true) {
            val index = column.indexOfChild(dragged)
            val tops = tops()
            val next = column.getChildAt(index + 1)
            val previous = column.getChildAt(index - 1)
            when {
                next != null && middle > tops[index + 1] + next.height / 2f -> {
                    column.removeViewAt(index + 1)
                    column.addView(next, index)
                }
                previous != null && middle < tops[index - 1] + previous.height / 2f -> {
                    column.removeViewAt(index - 1)
                    column.addView(previous, index)
                }
                else -> break
            }
        }
        // The layout of the new order comes before the next frame, together with this offset.
        val shift = tops()[column.indexOfChild(dragged)] - startTop
        dragged.translationY = travel - shift
    }

    private fun finish() {
        val dragged = row ?: return
        row = null
        dragged.translationY = 0f
        dragged.translationZ = 0f
        val order = keys()
        if (order != startOrder) onDrop(order)
    }

    private fun scrollNearEdge(rawY: Float) {
        val place = IntArray(2)
        scroller.getLocationOnScreen(place)
        val y = rawY - place[1]
        when {
            y < edge -> scroller.scrollBy(0, -edge / SCROLL_STEPS)
            y > scroller.height - edge -> scroller.scrollBy(0, edge / SCROLL_STEPS)
        }
    }

    /** Where every row stands in the column for the current order, whether or not it was laid out yet. */
    private fun tops(): IntArray {
        val tops = IntArray(column.childCount)
        var y = column.paddingTop
        for (index in 0 until column.childCount) {
            val child = column.getChildAt(index)
            val params = child.layoutParams as LinearLayout.LayoutParams
            y += params.topMargin
            tops[index] = y
            y += child.height + params.bottomMargin
        }
        return tops
    }

    @Suppress("UNCHECKED_CAST")
    private fun keys(): List<K> = (0 until column.childCount).map { column.getChildAt(it).tag as K }

    private companion object {
        const val SCROLL_STEPS = 4
    }
}
