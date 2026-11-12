package ru.tinyops.android.vboard.core.input

import java.text.BreakIterator
import kotlin.math.abs

/** How a finger on the space bar moves the cursor. */
enum class SpaceDragMode(
    val code: String,
) {
    /** Through the text as one sequence, left or right, passing from line to line. */
    LINEAR("linear"),

    /** In any direction: the keyboard turns into a trackpad, and its edges keep the cursor going. */
    TRACKPAD("trackpad"),
    ;

    companion object {
        fun fromCode(code: String?): SpaceDragMode = entries.firstOrNull { it.code == code } ?: TRACKPAD
    }
}

enum class ArrowKey { LEFT, RIGHT, UP, DOWN }

/**
 * A piece of the editor's text around the selection.
 *
 * @param selectionStart start of the selection in [text]
 * @param selectionEnd end of the selection in [text]
 * @param offset position of [text] in the whole text, negative when the editor does not tell
 */
class CursorWindow(
    val text: CharSequence,
    val selectionStart: Int,
    val selectionEnd: Int,
    val offset: Int,
)

/** The editing operations that move the cursor; implemented over an `InputConnection`. */
interface CursorEditor {
    fun cursorWindow(length: Int): CursorWindow?

    fun setCursor(offset: Int)

    fun sendArrow(key: ArrowKey)
}

/**
 * Moves the cursor of an editor along the text. Characters are walked over the text, so a step never
 * lands inside an emoji and line breaks are ordinary characters.
 */
object CursorMotion {
    internal const val WINDOW_LENGTH = 64

    /**
     * Moves by [steps] characters: to the right when positive, otherwise to the left.
     *
     * @param withinLine stop at a line break instead of passing it
     * @param lowEdge do not go before this offset; negative when there is no such limit
     * @param highEdge do not go after this offset; negative when there is no such limit
     * @return where the cursor went, if the editor tells offsets and there was somewhere to go
     */
    fun moveHorizontally(
        editor: CursorEditor,
        steps: Int,
        withinLine: Boolean = false,
        lowEdge: Int = -1,
        highEdge: Int = -1,
    ): CursorStep? {
        if (steps == 0) return null
        val window = editor.cursorWindow(WINDOW_LENGTH) ?: return null
        val forward = steps > 0
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(window.text.toString())
        val start = if (forward) window.selectionEnd else window.selectionStart
        var position = start
        var walked = 0
        while (walked < abs(steps)) {
            val next =
                when {
                    withinLine && atLineBreak(window.text, position, forward) -> BreakIterator.DONE
                    window.offset + position == if (forward) highEdge else lowEdge -> BreakIterator.DONE
                    forward -> iterator.following(position)
                    else -> iterator.preceding(position)
                }
            if (next == BreakIterator.DONE) break
            position = next
            walked++
        }
        if (walked == 0) return null
        if (window.offset < 0) {
            repeat(walked) { editor.sendArrow(if (forward) ArrowKey.RIGHT else ArrowKey.LEFT) }
            return null
        }
        editor.setCursor(window.offset + position)
        return CursorStep(window.offset + start, window.offset + position)
    }

    private fun atLineBreak(
        text: CharSequence,
        position: Int,
        forward: Boolean,
    ): Boolean {
        val next = (if (forward) position else position - 1).takeIf { it in text.indices } ?: return false
        return text[next] == '\n' || text[next] == '\r'
    }
}

/** The vertical extent of a line of text. */
data class LineSpan(
    val top: Float,
    val bottom: Float,
)

/**
 * Moves the cursor between lines. Lines depend on how the editor laid out its text, which only the
 * editor knows, so they are arrow keys. An arrow key with no line to go to is dangerous: it may move
 * the focus to the next field of the form, which an input method cannot undo, or send the cursor to
 * an end of the text. So one is sent only with a line known to be there: past a line feed, among the
 * lines the editor reports as visible, or when so much text is left that it cannot all be one line.
 * Each one waits for the answer to the last, as everything known about the lines is about to change.
 */
class CursorLines {
    private var hasAbove: Boolean? = null
    private var hasBelow: Boolean? = null
    private var reportsLines = false
    private var sentAt = NOT_SENT
    private var selectionAnswered = false

    fun reset() {
        hasAbove = null
        hasBelow = null
        reportsLines = false
        sentAt = NOT_SENT
        selectionAnswered = false
    }

    /**
     * The editor reports the [visible] lines and the [baseline] of the cursor. The baseline is inside
     * the line of the cursor, which tells it from the lines right next to it, touching it.
     */
    fun onVisibleLines(
        baseline: Float,
        visible: List<LineSpan>,
    ) = onVisibleLines(
        hasAbove = visible.any { it.bottom < baseline },
        hasBelow = visible.any { it.top > baseline },
    )

    /** The editor reports whether there are visible lines above and below the cursor. */
    fun onVisibleLines(
        hasAbove: Boolean,
        hasBelow: Boolean,
    ) {
        reportsLines = true
        // Until the selection has changed, this is still about the line the arrow key left.
        if (sentAt != NOT_SENT && !selectionAnswered) return
        this.hasAbove = hasAbove
        this.hasBelow = hasBelow
        sentAt = NOT_SENT
        selectionAnswered = false
    }

    fun onSelectionChanged() {
        if (sentAt == NOT_SENT) return
        selectionAnswered = true
        if (!reportsLines) sentAt = NOT_SENT
    }

    /**
     * Moves by [steps] lines: down when positive, otherwise up.
     *
     * @return whether an arrow key was sent
     */
    fun move(
        editor: CursorEditor,
        steps: Int,
        nowMillis: Long,
    ): Boolean {
        if (steps == 0) return false
        if (sentAt != NOT_SENT && nowMillis - sentAt < ANSWER_MILLIS) return false
        val window = editor.cursorWindow(FAR_LENGTH) ?: return false
        val down = steps > 0
        val rest =
            if (down) {
                window.text.subSequence(window.selectionEnd, window.text.length)
            } else {
                window.text.subSequence(0, window.selectionStart)
            }
        val lineThere = '\n' in rest || rest.length >= FAR_LENGTH || (if (down) hasBelow else hasAbove) == true
        if (rest.isEmpty() || !lineThere) return false
        hasAbove = null
        hasBelow = null
        sentAt = nowMillis
        selectionAnswered = false
        repeat(abs(steps)) { editor.sendArrow(if (down) ArrowKey.DOWN else ArrowKey.UP) }
        return true
    }

    private companion object {
        const val NOT_SENT = -1L
        const val ANSWER_MILLIS = 250L

        /** More characters than any line of a phone screen holds. */
        const val FAR_LENGTH = 200
    }
}

/**
 * Turns the travel of a finger into cursor steps of [stepX] and [stepY] pixels; the remainder carries
 * over. Like on a trackpad, a fast finger goes further: below [slowSpeed] (pixels per millisecond) a
 * step takes its full travel, which keeps slow moves precise, and at [fastSpeed] it is [maxGain] times
 * shorter. Each axis accelerates by its own speed, so a slight drift does not jump to another line.
 */
class CursorDrag(
    private val stepX: Float,
    private val stepY: Float,
    private val slowSpeed: Float = 1f,
    private val fastSpeed: Float = 2f,
    private val maxGain: Float = 1f,
) {
    private var lastX = 0f
    private var lastY = 0f
    private var lastTime = 0L
    private var pendingX = 0f
    private var pendingY = 0f

    fun start(
        x: Float,
        y: Float,
        time: Long,
    ) {
        lastX = x
        lastY = y
        lastTime = time
        pendingX = 0f
        pendingY = 0f
    }

    fun moveTo(
        x: Float,
        y: Float,
        time: Long,
    ) {
        val elapsed = (time - lastTime).coerceAtLeast(1L).toFloat()
        val dx = x - lastX
        val dy = y - lastY
        pendingX += dx * gain(abs(dx) / elapsed) / stepX
        pendingY = (pendingY + dy * gain(abs(dy) / elapsed) / stepY).coerceIn(-MAX_PENDING_LINES, MAX_PENDING_LINES)
        lastX = x
        lastY = y
        lastTime = time
    }

    /** Characters to move. */
    fun takeChars(): Int {
        val steps = pendingX.toInt()
        pendingX -= steps
        return steps
    }

    /**
     * Lines to move, one at a time: arrow keys are applied after a delay, so the check that the next
     * one still has somewhere to go is reliable only for a single key.
     */
    fun takeLines(): Int {
        val steps = pendingY.toInt().coerceIn(-1, 1)
        pendingY -= steps
        return steps
    }

    private fun gain(speed: Float): Float {
        val progress = ((speed - slowSpeed) / (fastSpeed - slowSpeed)).coerceIn(0f, 1f)
        return 1f + (maxGain - 1f) * progress
    }

    private companion object {
        /** Lines go one per update: more than this would keep going after the finger turned back. */
        const val MAX_PENDING_LINES = 2f
    }
}

/**
 * Keeps the cursor going while the finger rests near an edge of the keyboard, so that the size of
 * the keyboard does not limit how far the cursor can go and the finger never has to be put back.
 * The closer to the edge, the faster; lines go slower than characters, to be readable one by one.
 *
 * Only a finger that means it scrolls. A press inside a zone waits until the finger has pushed towards
 * the edge or been outside: the space bar is at the bottom of the keyboard, and holding it should not
 * move the cursor down. Backing away or moving across the zone stops the scroll until the finger
 * leaves the zone: otherwise a finger at the bottom could not move along a line, as every step away
 * from the last line would be undone.
 */
class CursorEdgeScroll(
    marginX: Float,
    marginY: Float,
) {
    private val axisX = EdgeAxis(marginX)
    private val axisY = EdgeAxis(marginY)
    private var pendingX = 0f
    private var pendingY = 0f

    val active: Boolean get() = axisX.depth != 0f || axisY.depth != 0f

    fun start(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
    ) {
        axisX.start(x, width)
        axisY.start(y, height)
        pendingX = 0f
        pendingY = 0f
    }

    fun moveTo(
        x: Float,
        y: Float,
    ) {
        axisX.moveTo(x, y)
        axisY.moveTo(y, x)
        if (axisX.depth == 0f) pendingX = 0f
        if (axisY.depth == 0f) pendingY = 0f
    }

    /** Lets [millis] of scrolling pass; the steps are then read with [takeChars] and [takeLines]. */
    fun advance(millis: Long) {
        pendingX += rate(axisX.depth, MIN_CHARS_PER_SECOND, MAX_CHARS_PER_SECOND) * millis / MILLIS_PER_SECOND
        pendingY += rate(axisY.depth, MIN_LINES_PER_SECOND, MAX_LINES_PER_SECOND) * millis / MILLIS_PER_SECOND
    }

    fun takeChars(): Int {
        val steps = pendingX.toInt()
        pendingX -= steps
        return steps
    }

    fun takeLines(): Int {
        val steps = pendingY.toInt()
        pendingY -= steps
        return steps
    }

    private fun rate(
        depth: Float,
        min: Float,
        max: Float,
    ): Float {
        if (depth == 0f) return 0f
        val speed = min + (max - min) * abs(depth)
        return if (depth > 0f) speed else -speed
    }

    private companion object {
        const val MIN_CHARS_PER_SECOND = 15f
        const val MAX_CHARS_PER_SECOND = 80f
        const val MIN_LINES_PER_SECOND = 5f
        const val MAX_LINES_PER_SECOND = 15f
        const val MILLIS_PER_SECOND = 1000f
    }
}

/** The two zones of one axis of [CursorEdgeScroll]. */
private class EdgeAxis(
    private val margin: Float,
) {
    private var size = 0f
    private var press = 0f
    private var armedLow = false
    private var armedHigh = false
    private var inZone = 0f
    private var stopped = false
    private var deepest = 0f
    private var entryAcross = 0f

    /** How deep in a zone a scrolling finger is: from -1 at the low edge to 1 at the high one. */
    var depth = 0f
        private set

    fun start(
        position: Float,
        size: Float,
    ) {
        this.size = size
        press = position
        armedLow = false
        armedHigh = false
        inZone = 0f
        stopped = false
        depth = 0f
        moveTo(position, 0f)
    }

    /** @param across the position of the finger on the other axis */
    fun moveTo(
        position: Float,
        across: Float,
    ) {
        val low = ((margin - position) / margin).coerceIn(0f, 1f)
        val high = ((margin - (size - position)) / margin).coerceIn(0f, 1f)
        val slop = margin / SLOP_DIVIDER
        if (low == 0f || position <= press - slop) armedLow = true
        if (high == 0f || position >= press + slop) armedHigh = true
        val next =
            when {
                low > 0f && armedLow -> -low
                high > 0f && armedHigh -> high
                else -> 0f
            }
        if (next == 0f || next > 0f != inZone > 0f || inZone == 0f) {
            stopped = false
            deepest = position
            entryAcross = across
        }
        inZone = next
        if (next != 0f) followFinger(position, across, next > 0f, slop)
        depth = if (stopped) 0f else next
    }

    private fun followFinger(
        position: Float,
        across: Float,
        towardsHigh: Boolean,
        slop: Float,
    ) {
        deepest = if (towardsHigh) maxOf(deepest, position) else minOf(deepest, position)
        val backedAway = abs(deepest - position) >= slop
        val movedAcross = abs(across - entryAcross) >= margin / ACROSS_DIVIDER
        if (backedAway || movedAcross) stopped = true
    }

    private companion object {
        /** A resting finger jitters by less than this part of the margin. */
        const val SLOP_DIVIDER = 4f

        /** Moving across the zone by this part of the margin means going along the edge. */
        const val ACROSS_DIVIDER = 2f
    }
}
