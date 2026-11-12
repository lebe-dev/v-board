package ru.tinyops.android.vboard.core.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An editor with a text and a selection; [arrows] records the keys it was sent. */
private class FakeCursorEditor(
    private val text: String,
    private var start: Int,
    private var end: Int = start,
    private val reportsOffset: Boolean = true,
) : CursorEditor {
    val arrows = mutableListOf<ArrowKey>()
    val cursor: Int get() = end

    override fun cursorWindow(length: Int): CursorWindow {
        val from = maxOf(0, start - length)
        val to = minOf(text.length, end + length)
        val offset = if (reportsOffset) from else -1
        return CursorWindow(text.substring(from, to), start - from, end - from, offset)
    }

    override fun setCursor(offset: Int) {
        start = offset
        end = offset
    }

    override fun sendArrow(key: ArrowKey) {
        arrows += key
    }
}

class CursorMotionTest {
    @Test
    fun `steps move the cursor by characters`() {
        val editor = FakeCursorEditor("hello", 2)
        CursorMotion.moveHorizontally(editor, 2)
        assertEquals(4, editor.cursor)
        CursorMotion.moveHorizontally(editor, -3)
        assertEquals(1, editor.cursor)
    }

    @Test
    fun `the cursor walks across lines as one sequence`() {
        val editor = FakeCursorEditor("ab\ncd", 2)
        CursorMotion.moveHorizontally(editor, 2)
        assertEquals(4, editor.cursor)
        CursorMotion.moveHorizontally(editor, -3)
        assertEquals(1, editor.cursor)
    }

    @Test
    fun `the cursor stops at the ends of the text`() {
        val editor = FakeCursorEditor("abc", 1)
        CursorMotion.moveHorizontally(editor, 50)
        assertEquals(3, editor.cursor)
        CursorMotion.moveHorizontally(editor, -50)
        assertEquals(0, editor.cursor)
    }

    @Test
    fun `a move within the line stops at its line feeds`() {
        val editor = FakeCursorEditor("ab\ncd\nef", 4)
        assertEquals(CursorStep(4, 3), CursorMotion.moveHorizontally(editor, -50, withinLine = true))
        assertEquals(3, editor.cursor)
        assertEquals(null, CursorMotion.moveHorizontally(editor, -1, withinLine = true))
        assertEquals(3, editor.cursor)
        CursorMotion.moveHorizontally(editor, 50, withinLine = true)
        assertEquals(5, editor.cursor)
        assertEquals(null, CursorMotion.moveHorizontally(editor, 1, withinLine = true))
        assertEquals(5, editor.cursor)
    }

    @Test
    fun `a move stops at a known end of the line`() {
        val editor = FakeCursorEditor("x".repeat(100), 50)
        CursorMotion.moveHorizontally(editor, -30, withinLine = true, lowEdge = 45)
        assertEquals(45, editor.cursor)
        CursorMotion.moveHorizontally(editor, 30, withinLine = true, highEdge = 52)
        assertEquals(52, editor.cursor)
    }

    @Test
    fun `a move within the line takes a windows line break as an end too`() {
        val editor = FakeCursorEditor("ab\r\ncd", 2)
        assertEquals(null, CursorMotion.moveHorizontally(editor, 1, withinLine = true))
        assertEquals(2, editor.cursor)
    }

    @Test
    fun `offsets are absolute when the window starts inside the text`() {
        val text = "x".repeat(500)
        val editor = FakeCursorEditor(text, 300)
        CursorMotion.moveHorizontally(editor, 5)
        assertEquals(305, editor.cursor)
    }

    @Test
    fun `an emoji and a combining mark are one step`() {
        val thumbsUp = "👍"
        val editor = FakeCursorEditor("a${thumbsUp}éb", 1)
        CursorMotion.moveHorizontally(editor, 1)
        assertEquals(3, editor.cursor)
        CursorMotion.moveHorizontally(editor, 1)
        assertEquals(5, editor.cursor)
        CursorMotion.moveHorizontally(editor, -2)
        assertEquals(1, editor.cursor)
    }

    @Test
    fun `a selection collapses in the direction of travel`() {
        val right = FakeCursorEditor("abcdef", 1, 4)
        CursorMotion.moveHorizontally(right, 1)
        assertEquals(5, right.cursor)
        val left = FakeCursorEditor("abcdef", 2, 4)
        CursorMotion.moveHorizontally(left, -1)
        assertEquals(1, left.cursor)
    }

    @Test
    fun `an editor that hides offsets gets arrow keys, no more than the text allows`() {
        val editor = FakeCursorEditor("abc", 1, reportsOffset = false)
        CursorMotion.moveHorizontally(editor, 5)
        assertEquals(listOf(ArrowKey.RIGHT, ArrowKey.RIGHT), editor.arrows)
        assertEquals(1, editor.cursor)
    }
}

/**
 * Lines broken at line feeds, with the arrow keys of Android's `Selection.moveUp` and `moveDown`: the
 * column is kept between lines, while on the outer lines the cursor goes to the end of the text.
 */
private class LinesEditor(
    private val text: String,
    var position: Int,
) : CursorEditor {
    val arrows = mutableListOf<ArrowKey>()

    override fun cursorWindow(length: Int): CursorWindow {
        val from = maxOf(0, position - length)
        val to = minOf(text.length, position + length)
        return CursorWindow(text.substring(from, to), position - from, position - from, from)
    }

    override fun setCursor(offset: Int) {
        position = offset
    }

    override fun sendArrow(key: ArrowKey) {
        arrows += key
        position = if (key == ArrowKey.UP) above() else below()
    }

    private fun above(): Int {
        val start = text.lastIndexOf('\n', position - 1) + 1
        if (start == 0) return 0
        val lineAbove = text.lastIndexOf('\n', start - 2) + 1
        return minOf(lineAbove + position - start, start - 1)
    }

    private fun below(): Int {
        val next = text.indexOf('\n', position)
        if (next < 0) return text.length
        val column = position - (text.lastIndexOf('\n', position - 1) + 1)
        val end = text.indexOf('\n', next + 1).let { if (it < 0) text.length else it }
        return minOf(next + 1 + column, end)
    }
}

class CursorLinesTest {
    private val text = "first line\nsecond line\nlast one"
    private val lines = CursorLines()
    private var now = 1_000L

    /** Moves once, with the editor answering at once: the selection first, then the visible lines. */
    private fun step(
        editor: LinesEditor,
        steps: Int,
    ): Boolean {
        now += 1_000
        val sent = lines.move(editor, steps, now)
        if (sent) lines.onSelectionChanged()
        return sent
    }

    @Test
    fun `lines separated by line feeds are moved with arrow keys`() {
        val editor = LinesEditor(text, 15)
        assertTrue(step(editor, -1))
        assertEquals(4, editor.position)
        assertTrue(step(editor, 1))
        assertEquals(15, editor.position)
    }

    @Test
    fun `no arrow key is sent past the ends of the text`() {
        val atStart = LinesEditor(text, 0)
        assertEquals(false, step(atStart, -1))
        val atEnd = LinesEditor(text, text.length)
        assertEquals(false, step(atEnd, 1))
        assertTrue(atStart.arrows.isEmpty() && atEnd.arrows.isEmpty())
    }

    @Test
    fun `without a line feed and without knowing the lines no arrow key risks leaving the field`() {
        val first = LinesEditor(text, 5)
        assertEquals(false, step(first, -1))
        val last = LinesEditor(text, 27)
        assertEquals(false, step(last, 1))
        assertTrue(first.arrows.isEmpty() && last.arrows.isEmpty())
    }

    @Test
    fun `a visible line next to the cursor lets a wrapped paragraph be walked`() {
        val editor = LinesEditor(text, 27)
        lines.onVisibleLines(hasAbove = true, hasBelow = true)
        assertTrue(step(editor, 1))
    }

    @Test
    fun `the last visible line of the field keeps the cursor from going down`() {
        val editor = LinesEditor(text, 27)
        lines.onVisibleLines(hasAbove = true, hasBelow = false)
        assertEquals(false, step(editor, 1))
    }

    @Test
    fun `the first visible line of the field keeps the cursor from going up`() {
        val editor = LinesEditor(text, 5)
        lines.onVisibleLines(hasAbove = false, hasBelow = true)
        assertEquals(false, step(editor, -1))
    }

    @Test
    fun `lines touching each other count as lines above and below the cursor`() {
        val paragraph = "a".repeat(30)
        val visible = listOf(LineSpan(0f, 20f), LineSpan(20f, 40f), LineSpan(40f, 60f))

        val second = LinesEditor(paragraph, 15)
        lines.onVisibleLines(baseline = 35f, visible)
        assertTrue(step(second, -1))

        val alsoSecond = LinesEditor(paragraph, 15)
        lines.reset()
        lines.onVisibleLines(baseline = 35f, visible)
        assertTrue(step(alsoSecond, 1))

        val first = LinesEditor(paragraph, 5)
        lines.reset()
        lines.onVisibleLines(baseline = 15f, visible)
        assertEquals(false, step(first, -1))

        val last = LinesEditor(paragraph, 25)
        lines.reset()
        lines.onVisibleLines(baseline = 55f, visible)
        assertEquals(false, step(last, 1))
    }

    @Test
    fun `text far beyond the visible lines is reachable when scrolled away`() {
        val long = "x".repeat(500)
        val down = LinesEditor(long, 100)
        lines.onVisibleLines(hasAbove = true, hasBelow = false)
        assertTrue(step(down, 1))
        val up = LinesEditor(long, 400)
        lines.onVisibleLines(hasAbove = false, hasBelow = true)
        assertTrue(step(up, -1))
    }

    @Test
    fun `the next arrow key waits for the answer to the last one`() {
        val editor = LinesEditor(text, 4)
        assertTrue(lines.move(editor, 1, 1_000))
        assertEquals(false, lines.move(editor, 1, 1_010))
        lines.onSelectionChanged()
        assertTrue(lines.move(editor, 1, 1_020))
        assertEquals(2, editor.arrows.size)
    }

    @Test
    fun `an editor that does not answer does not hold the cursor forever`() {
        val editor = LinesEditor(text, 4)
        assertTrue(lines.move(editor, 1, 1_000))
        assertTrue(lines.move(editor, 1, 2_000))
    }

    @Test
    fun `a report about the line before the arrow key is not believed`() {
        val editor = LinesEditor("a".repeat(30), 15)
        lines.onVisibleLines(hasAbove = true, hasBelow = true)
        assertTrue(lines.move(editor, 1, 1_000))
        lines.onVisibleLines(hasAbove = true, hasBelow = true)
        lines.onSelectionChanged()
        lines.onVisibleLines(hasAbove = true, hasBelow = false)
        assertEquals(false, lines.move(editor, 1, 1_100))
    }

    @Test
    fun `once lines are reported the selection alone does not release the next arrow key`() {
        val editor = LinesEditor("a".repeat(30), 15)
        lines.onVisibleLines(hasAbove = true, hasBelow = true)
        assertTrue(lines.move(editor, 1, 1_000))
        lines.onSelectionChanged()
        assertEquals(false, lines.move(editor, 1, 1_020))
    }
}

class CursorDragTest {
    private fun drag(maxGain: Float = 1f) =
        CursorDrag(stepX = 10f, stepY = 30f, slowSpeed = 1f, fastSpeed = 5f, maxGain = maxGain).also {
            it.start(100f, 100f, 0)
        }

    @Test
    fun `travel turns into steps and the remainder is kept`() {
        val drag = drag()
        drag.moveTo(109f, 100f, 10)
        assertEquals(0, drag.takeChars())
        drag.moveTo(112f, 100f, 20)
        assertEquals(1, drag.takeChars())
        drag.moveTo(119f, 100f, 30)
        assertEquals(0, drag.takeChars())
        drag.moveTo(120f, 100f, 40)
        assertEquals(1, drag.takeChars())
        drag.moveTo(100f, 100f, 50)
        assertEquals(-2, drag.takeChars())
    }

    @Test
    fun `at most one line is moved per update and the rest is kept for the next one`() {
        val drag = drag()
        drag.moveTo(100f, 30f, 10)
        assertEquals(-1, drag.takeLines())
        assertEquals(-1, drag.takeLines())
        assertEquals(0, drag.takeLines())
        drag.moveTo(100f, 70f, 20)
        assertEquals(1, drag.takeLines())
    }

    @Test
    fun `lines do not pile up behind a fast finger, so turning back answers at once`() {
        val drag = drag(maxGain = 4f)
        drag.moveTo(100f, 1_100f, 10)
        assertEquals(1, drag.takeLines())
        drag.moveTo(100f, 1_100f, 20)
        assertEquals(1, drag.takeLines())
        assertEquals(0, drag.takeLines())
    }

    @Test
    fun `a slow finger is precise whatever the acceleration`() {
        val drag = drag(maxGain = 4f)
        drag.moveTo(150f, 100f, 100)
        assertEquals(5, drag.takeChars())
    }

    @Test
    fun `a fast finger goes further, up to the limit`() {
        val drag = drag(maxGain = 4f)
        drag.moveTo(150f, 100f, 10)
        assertEquals(20, drag.takeChars())
    }

    @Test
    fun `speeds in between get part of the acceleration`() {
        val drag = drag(maxGain = 4f)
        drag.moveTo(130f, 100f, 10)
        assertEquals(7, drag.takeChars())
    }

    @Test
    fun `the axes accelerate separately`() {
        val drag = drag(maxGain = 4f)
        drag.moveTo(150f, 101f, 10)
        assertEquals(20, drag.takeChars())
        assertEquals(0, drag.takeLines())
    }
}

class CursorEdgeScrollTest {
    private val scroll = CursorEdgeScroll(marginX = 30f, marginY = 20f)

    private fun startInMiddle() = scroll.start(500f, 200f, width = 1000f, height = 400f)

    /** Runs [millis] of scrolling in small ticks, returning the characters and lines moved. */
    private fun run(millis: Int): Pair<Int, Int> {
        var chars = 0
        var lines = 0
        repeat(millis / 10) {
            scroll.advance(10)
            chars += scroll.takeChars()
            lines += scroll.takeLines()
        }
        return chars to lines
    }

    @Test
    fun `nothing scrolls away from the edges`() {
        startInMiddle()
        assertEquals(false, scroll.active)
        assertEquals(0 to 0, run(1_000))
    }

    @Test
    fun `a finger resting at a side keeps the cursor going that way`() {
        startInMiddle()
        scroll.moveTo(990f, 200f)
        assertTrue(scroll.active)
        assertTrue(run(1_000).first > 0)
        scroll.moveTo(10f, 200f)
        assertTrue(run(1_000).first < 0)
    }

    @Test
    fun `a finger past the keyboard scrolls at full speed, closer to the middle it is slower`() {
        startInMiddle()
        scroll.moveTo(975f, 200f)
        val slow = run(1_000).first
        scroll.moveTo(1_100f, 200f)
        val fast = run(1_000).first
        assertTrue(fast > slow * 2, "slow=$slow fast=$fast")
    }

    @Test
    fun `a finger at the top or bottom moves between lines, slower than along them`() {
        startInMiddle()
        scroll.moveTo(500f, -50f)
        val (chars, up) = run(1_000)
        assertEquals(0, chars)
        assertTrue(up in -15..-3, "up=$up")
        scroll.moveTo(500f, 395f)
        assertTrue(run(1_000).second > 0)
    }

    @Test
    fun `leaving the zone stops the scroll and leaves nothing over`() {
        startInMiddle()
        scroll.moveTo(990f, 200f)
        scroll.advance(5)
        scroll.moveTo(500f, 200f)
        assertEquals(false, scroll.active)
        assertEquals(0 to 0, run(1_000))
    }

    @Test
    fun `a press that starts in a zone does not scroll until the finger has left it`() {
        scroll.start(500f, 395f, width = 1000f, height = 400f)
        assertEquals(false, scroll.active)
        assertEquals(0 to 0, run(1_000))
        scroll.moveTo(500f, 397f)
        assertEquals(false, scroll.active)
        scroll.moveTo(500f, 300f)
        scroll.moveTo(500f, 395f)
        assertTrue(scroll.active)
        assertTrue(run(1_000).second > 0)
    }

    @Test
    fun `a press in a zone also scrolls once the finger pushes towards the edge`() {
        scroll.start(500f, 390f, width = 1000f, height = 400f)
        scroll.moveTo(500f, 396f)
        assertTrue(scroll.active)
        assertTrue(run(1_000).second > 0)
    }

    @Test
    fun `moving across a zone stops its scroll until the finger leaves the zone`() {
        startInMiddle()
        scroll.moveTo(500f, 395f)
        assertTrue(scroll.active)
        scroll.moveTo(490f, 395f)
        assertEquals(false, scroll.active)
        scroll.moveTo(400f, 395f)
        assertEquals(0 to 0, run(1_000))
        scroll.moveTo(400f, 200f)
        scroll.moveTo(400f, 395f)
        assertTrue(scroll.active)
    }

    @Test
    fun `backing away from the edge stops the scroll`() {
        startInMiddle()
        scroll.moveTo(995f, 200f)
        scroll.moveTo(991f, 200f)
        assertTrue(scroll.active)
        scroll.moveTo(987f, 200f)
        assertEquals(false, scroll.active)
        scroll.moveTo(995f, 200f)
        assertEquals(false, scroll.active)
    }

    @Test
    fun `jitter of a resting finger keeps the scroll going`() {
        startInMiddle()
        scroll.moveTo(990f, 200f)
        scroll.moveTo(992f, 205f)
        scroll.moveTo(990f, 196f)
        assertTrue(scroll.active)
    }
}
