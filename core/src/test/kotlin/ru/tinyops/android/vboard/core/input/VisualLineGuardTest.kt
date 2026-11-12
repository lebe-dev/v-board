package ru.tinyops.android.vboard.core.input

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class RecordingEditor : CursorEditor {
    val restored = mutableListOf<Int>()

    override fun cursorWindow(length: Int): CursorWindow? = null

    override fun setCursor(offset: Int) {
        restored += offset
    }

    override fun sendArrow(key: ArrowKey) = Unit
}

class VisualLineGuardTest {
    private val editor = RecordingEditor()
    private val guard = VisualLineGuard()

    @BeforeTest
    fun onLineAtTheMiddleOfTheScreen() {
        guard.onMarker(editor, x = 100f, baseline = 50f, selectionStart = 20)
    }

    @Test
    fun `steps along the line are left alone`() {
        guard.onMoved(CursorStep(20, 19), -1)
        guard.onMarker(editor, 90f, 50f, 19)
        assertEquals(emptyList(), editor.restored)
        assertEquals(-3, guard.allowedSteps(-3))
    }

    @Test
    fun `a step off the left end of the line is taken back and that way is closed`() {
        guard.onMoved(CursorStep(20, 19), -1)
        guard.onMarker(editor, 900f, 20f, 19)
        assertEquals(listOf(20), editor.restored)
        assertEquals(0, guard.allowedSteps(-1))
    }

    @Test
    fun `a step off the right end of the line is taken back and that way is closed`() {
        guard.onMoved(CursorStep(20, 21), 1)
        guard.onMarker(editor, 5f, 80f, 21)
        assertEquals(listOf(20), editor.restored)
        assertEquals(0, guard.allowedSteps(1))
    }

    @Test
    fun `a jittering finger does not step off a line end it has found`() {
        guard.onMoved(CursorStep(20, 19), -1)
        guard.onMarker(editor, 900f, 20f, 19)
        assertEquals(2, guard.allowedSteps(2))
        guard.onMoved(CursorStep(20, 21), 1)
        guard.onMarker(editor, 110f, 50f, 21)
        assertEquals(-1, guard.allowedSteps(-1))
        assertEquals(20, guard.lowEdge)
        guard.onMoved(CursorStep(21, 20), -1)
        guard.onMarker(editor, 100f, 50f, 20)
        assertEquals(0, guard.allowedSteps(-1))
        assertEquals(listOf(20), editor.restored)
    }

    @Test
    fun `the line bounds from the editor stop the cursor before it leaves the line`() {
        guard.onMarker(editor, 10f, 50f, 20, lineLeft = 10f, lineRight = 900f, maxCharWidth = 20f)
        assertEquals(0, guard.allowedSteps(-1))
        assertEquals(5, guard.allowedSteps(5))
        guard.onMarker(editor, 900f, 50f, 60, lineLeft = 10f, lineRight = 900f, maxCharWidth = 20f)
        assertEquals(0, guard.allowedSteps(1))
        assertEquals(-5, guard.allowedSteps(-5))
    }

    @Test
    fun `near the line end steps get shorter so that they cannot overshoot`() {
        guard.onMarker(editor, 70f, 50f, 20, lineLeft = 10f, lineRight = 900f, maxCharWidth = 20f)
        assertEquals(-3, guard.allowedSteps(-30))
    }

    @Test
    fun `steps on their way count against the room left on the line`() {
        guard.onMarker(editor, 70f, 50f, 20, lineLeft = 10f, lineRight = 900f, maxCharWidth = 20f)
        guard.onMoved(CursorStep(20, 18), -2)
        assertEquals(-1, guard.allowedSteps(-30))
        guard.onMoved(CursorStep(18, 17), -1)
        assertEquals(0, guard.allowedSteps(-30))
    }

    @Test
    fun `the last characters go one at a time, each after the answer to the last`() {
        guard.onMarker(editor, 25f, 50f, 20, lineLeft = 10f, lineRight = 900f, maxCharWidth = 20f)
        assertEquals(-1, guard.allowedSteps(-30))
        guard.onMoved(CursorStep(20, 19), -1)
        assertEquals(0, guard.allowedSteps(-30))
        guard.onMarker(editor, 10f, 50f, 19, lineLeft = 10f, lineRight = 900f, maxCharWidth = 20f)
        assertEquals(0, guard.allowedSteps(-30))
    }

    @Test
    fun `line bounds that do not fit the cursor are not believed`() {
        guard.onMarker(editor, 5f, 50f, 20, lineLeft = 40f, lineRight = 900f, maxCharWidth = 20f)
        assertEquals(-3, guard.allowedSteps(-3))
    }

    @Test
    fun `a long step that overshoots comes back and walks up to the end by itself`() {
        guard.onMoved(CursorStep(20, 12), -8)
        guard.onMarker(editor, 900f, 20f, 12)
        assertEquals(listOf(20), editor.restored)
        assertEquals(0, guard.allowedSteps(-8))

        assertEquals(-1, guard.onMarker(editor, 100f, 50f, 20))
        guard.onMoved(CursorStep(20, 19), -1)
        assertEquals(-1, guard.onMarker(editor, 90f, 50f, 19))
        guard.onMoved(CursorStep(19, 18), -1)
        assertEquals(0, guard.onMarker(editor, 900f, 20f, 18))
        assertEquals(listOf(20, 19), editor.restored)
        assertEquals(0, guard.onMarker(editor, 90f, 50f, 19))
        assertEquals(0, guard.allowedSteps(-8))
    }

    @Test
    fun `turning back stops the walk`() {
        guard.onMoved(CursorStep(20, 12), -8)
        guard.onMarker(editor, 900f, 20f, 12)
        assertEquals(3, guard.allowedSteps(3))
        assertEquals(0, guard.onMarker(editor, 100f, 50f, 20))
    }

    @Test
    fun `steps sent before the editor answered go back to the last place it confirmed`() {
        guard.onMoved(CursorStep(20, 19), -1)
        guard.onMoved(CursorStep(19, 18), -1)
        guard.onMarker(editor, 90f, 50f, 19)
        assertEquals(emptyList(), editor.restored)
        guard.onMarker(editor, 900f, 20f, 18)
        assertEquals(listOf(19), editor.restored)
    }

    @Test
    fun `an answer that skips some of the steps confirms them all`() {
        guard.onMoved(CursorStep(20, 19), -1)
        guard.onMoved(CursorStep(19, 18), -1)
        guard.onMoved(CursorStep(18, 17), -1)
        guard.onMarker(editor, 70f, 50f, 17)
        guard.onMoved(CursorStep(17, 16), -1)
        guard.onMarker(editor, 900f, 20f, 16)
        assertEquals(listOf(17), editor.restored)
    }

    @Test
    fun `a late report about the line left behind is not taken for the line`() {
        guard.onMoved(CursorStep(20, 12), -8)
        guard.onMarker(editor, 900f, 20f, 12)
        assertEquals(0, guard.onMarker(editor, 880f, 20f, 13))
        assertEquals(-1, guard.onMarker(editor, 100f, 50f, 20))
        guard.onMoved(CursorStep(20, 19), -1)
        guard.onMarker(editor, 900f, 20f, 19)
        assertEquals(listOf(20, 20), editor.restored)
    }

    @Test
    fun `a cursor that overshoots after a long way returns to the last place known to be on the line`() {
        // The editor answers about each step only after the next ones have been sent.
        var at = 20
        repeat(10) {
            guard.onMoved(CursorStep(at, at - 1), -1)
            at--
            guard.onMarker(editor, 90f - it, 50f, at + 1)
        }
        guard.onMoved(CursorStep(at, at - 1), -1)
        guard.onMarker(editor, 80f, 50f, at + 1)
        guard.onMarker(editor, 900f, 20f, at - 1)
        assertEquals(listOf(at + 1), editor.restored)
    }

    @Test
    fun `scrolling alone is not a change of line`() {
        guard.onMoved(CursorStep(20, 19), -1)
        guard.onMarker(editor, 90f, 10f, 19)
        assertEquals(emptyList(), editor.restored)
    }

    @Test
    fun `a change of line opens both ways and learns the new line`() {
        guard.onMoved(CursorStep(20, 19), -1)
        guard.onMarker(editor, 900f, 20f, 19)
        guard.reset()
        assertEquals(-1, guard.allowedSteps(-1))
        guard.onMarker(editor, 300f, 20f, 40)
        guard.onMoved(CursorStep(40, 39), -1)
        guard.onMarker(editor, 290f, 20f, 39)
        assertEquals(listOf(20), editor.restored)
    }

    @Test
    fun `a cursor the editor cannot place changes nothing`() {
        guard.onMoved(CursorStep(20, 19), -1)
        guard.onMarker(editor, Float.NaN, Float.NaN, 19)
        assertEquals(emptyList(), editor.restored)
        assertEquals(-1, guard.allowedSteps(-1))
    }

    @Test
    fun `an editor that does not report the selection is judged by the marker alone`() {
        guard.onMoved(CursorStep(20, 19), -1)
        guard.onMarker(editor, 900f, 20f, -1)
        assertEquals(listOf(20), editor.restored)
    }
}
