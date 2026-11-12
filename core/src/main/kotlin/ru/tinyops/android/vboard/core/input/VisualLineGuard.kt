package ru.tinyops.android.vboard.core.input

import kotlin.math.abs

/** A cursor move within the text, in absolute offsets. */
data class CursorStep(
    val from: Int,
    val to: Int,
)

/**
 * Keeps a cursor going along a line from wrapping to another one. Where a paragraph wraps only the
 * editor knows. With the bounds of the current line from the editor, the cursor stops at their ends
 * and slows down near them, as steps must not outrun what is known of the line: a step off it shows.
 *
 * Without them, or with a step that did go off, it can only be noticed afterwards: the cursor has a
 * new baseline and went against the direction of travel (the latter tells it from the scrolling of
 * the text). It is then put back on the last place known to be on the line, which may be some
 * characters away from the end, so the rest of the way is walked by single steps. The end found this
 * way is remembered, so that a jittering finger does not step off it again.
 */
class VisualLineGuard {
    private class Sent(
        val to: Int,
        val steps: Int,
    )

    private var markerX = Float.NaN
    private var baseline = Float.NaN
    private var lineLeft = Float.NaN
    private var lineRight = Float.NaN
    private var maxCharWidth = Float.NaN
    private val sent = ArrayDeque<Sent>()
    private var position = NONE
    private var confirmed = NONE
    private var awaited = NONE
    private var direction = 0
    private var walkDirection = 0

    /** The start of the line, as found by stepping off it; negative when unknown. */
    var lowEdge = NONE
        private set

    /** The end of the line, as found by stepping off it; negative when unknown. */
    var highEdge = NONE
        private set

    /** The cursor went or is about to go to another line: everything known about this one is void. */
    fun reset() {
        markerX = Float.NaN
        baseline = Float.NaN
        lineLeft = Float.NaN
        lineRight = Float.NaN
        maxCharWidth = Float.NaN
        sent.clear()
        position = NONE
        confirmed = NONE
        awaited = NONE
        walkDirection = 0
        lowEdge = NONE
        highEdge = NONE
    }

    /** How many of the [steps] the finger asks for may be made now. */
    fun allowedSteps(steps: Int): Int {
        if (steps == 0) return 0
        val wanted = if (steps > 0) 1 else -1
        if (wanted == walkDirection) return 0
        walkDirection = 0
        val edge = if (wanted > 0) highEdge else lowEdge
        if (edge != NONE && edge == position) return 0
        return limitedByLine(steps, wanted)
    }

    private fun limitedByLine(
        steps: Int,
        wanted: Int,
    ): Int {
        val fits = markerX >= lineLeft - EDGE_TOLERANCE && markerX <= lineRight + EDGE_TOLERANCE
        if (!fits) return steps
        val room = abs(markerX - if (wanted > 0) lineRight else lineLeft)
        if (room <= EDGE_TOLERANCE) return 0
        val onTheWay = abs(sent.sumOf { it.steps })
        val safe = (room / maxCharWidth).toInt() - onTheWay
        return when {
            safe >= 1 -> wanted * minOf(abs(steps), safe)
            sent.isEmpty() -> wanted
            else -> 0
        }
    }

    fun onMoved(
        step: CursorStep,
        steps: Int,
    ) {
        position = step.to
        if (baseline.isNaN()) return
        if (sent.isEmpty()) confirmed = step.from
        sent.addLast(Sent(step.to, steps))
        direction = if (steps > 0) 1 else -1
    }

    /**
     * Reacts to the editor reporting where the cursor is.
     *
     * @param selectionStart the position the report is about, negative when the editor does not tell
     * @param lineLeft where the line of the cursor starts, NaN when the editor does not tell
     * @param lineRight where the line of the cursor ends, NaN when the editor does not tell
     * @param maxCharWidth a width no character of this line goes over
     * @return the next step of the walk to the end of the line, 0 if there is none to make now
     */
    fun onMarker(
        editor: CursorEditor,
        x: Float,
        baseline: Float,
        selectionStart: Int,
        lineLeft: Float = Float.NaN,
        lineRight: Float = Float.NaN,
        maxCharWidth: Float = Float.NaN,
    ): Int {
        if (x.isNaN() || baseline.isNaN()) {
            sent.clear()
            return 0
        }
        if (awaited != NONE) {
            if (selectionStart >= 0 && selectionStart != awaited) return 0
            awaited = NONE
        } else if (sent.isNotEmpty()) {
            val index = if (selectionStart < 0) sent.lastIndex else sent.indexOfFirst { it.to == selectionStart }
            // A report about something else, such as a place the cursor has already left, says nothing.
            if (index < 0) return 0
            if (leftTheLine(x, baseline)) {
                stepBack(editor, index)
                return 0
            }
            confirmed = sent[index].to
            repeat(index + 1) { sent.removeFirst() }
        }
        markerX = x
        this.baseline = baseline
        this.lineLeft = lineLeft
        this.lineRight = lineRight
        this.maxCharWidth = maxCharWidth
        return if (sent.isEmpty()) walkDirection else 0
    }

    private fun stepBack(
        editor: CursorEditor,
        answered: Int,
    ) {
        val steps = sent.take(answered + 1).sumOf { it.steps }
        sent.clear()
        editor.setCursor(confirmed)
        position = confirmed
        awaited = confirmed
        if (abs(steps) > 1) {
            walkDirection = direction
            return
        }
        walkDirection = 0
        if (direction > 0) highEdge = confirmed else lowEdge = confirmed
    }

    private fun leftTheLine(
        x: Float,
        baseline: Float,
    ): Boolean {
        if (abs(baseline - this.baseline) < BASELINE_TOLERANCE) return false
        return if (direction > 0) x < markerX else x > markerX
    }

    private companion object {
        const val NONE = -1
        const val BASELINE_TOLERANCE = 0.5f
        const val EDGE_TOLERANCE = 1f
    }
}
