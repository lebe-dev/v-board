package ru.tinyops.android.vboard.ui

import android.app.Activity
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import ru.tinyops.android.vboard.core.input.ArrowKey
import ru.tinyops.android.vboard.core.input.CursorEditor
import ru.tinyops.android.vboard.core.input.CursorLines
import ru.tinyops.android.vboard.core.input.CursorMotion
import ru.tinyops.android.vboard.core.input.CursorWindow

/** Moves the cursor of a real [EditText] the way the keyboard does, through its input connection. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-420dpi")
class CursorInEditTextTest {
    private lateinit var field: EditText
    private lateinit var connection: InputConnection
    private val lines = CursorLines()
    private var now = 0L

    private val editor =
        object : CursorEditor {
            override fun cursorWindow(length: Int): CursorWindow? =
                connection.getSurroundingText(length, length, 0)?.let {
                    CursorWindow(it.text, it.selectionStart, it.selectionEnd, it.offset)
                }

            override fun setCursor(offset: Int) {
                connection.setSelection(offset, offset)
            }

            override fun sendArrow(key: ArrowKey) {
                val code =
                    when (key) {
                        ArrowKey.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
                        ArrowKey.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
                        ArrowKey.UP -> KeyEvent.KEYCODE_DPAD_UP
                        ArrowKey.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
                    }
                field.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                field.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                ShadowLooper.idleMainLooper()
            }
        }

    @Before
    fun setUp() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        field = EditText(activity)
        field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        activity.setContentView(field)
        field.requestFocus()
        layOut("")
        connection = requireNotNull(field.onCreateInputConnection(EditorInfo()))
    }

    private fun layOut(text: String) {
        field.setText(text)
        val width = field.resources.displayMetrics.widthPixels
        field.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        field.layout(0, 0, field.measuredWidth, field.measuredHeight)
    }

    private fun place(
        text: String,
        cursor: Int,
    ) {
        layOut(text)
        field.setSelection(cursor)
    }

    private fun moveLines(steps: Int) {
        now += 1_000
        if (lines.move(editor, steps, now)) lines.onSelectionChanged()
    }

    @Test
    fun `an empty last line lets the cursor go back up`() {
        place("paragraph\n", 4)
        moveLines(1)
        assertEquals(10, field.selectionStart)
        // The editor remembers the column the cursor came from.
        moveLines(-1)
        assertEquals(4, field.selectionStart)
    }

    @Test
    fun `an empty last line lets the cursor go back left outside the trackpad mode`() {
        place("paragraph\n", 10)
        CursorMotion.moveHorizontally(editor, -1)
        assertEquals(9, field.selectionStart)
    }

    @Test
    fun `an empty line in the middle lets the cursor go on both ways`() {
        place("one\n\ntwo", 4)
        moveLines(1)
        assertEquals(5, field.selectionStart)
        place("one\n\ntwo", 4)
        moveLines(-1)
        assertEquals(0, field.selectionStart)
    }

    @Test
    fun `the last line of a field keeps the focus in it`() {
        place("one line", 4)
        moveLines(1)
        moveLines(-1)
        assertEquals(4, field.selectionStart)
        assertTrue(field.hasFocus())
    }

    @Test
    fun `a move within the line stays on an empty line`() {
        place("one\n\ntwo", 4)
        assertEquals(null, CursorMotion.moveHorizontally(editor, -1, withinLine = true))
        assertEquals(null, CursorMotion.moveHorizontally(editor, 1, withinLine = true))
        assertEquals(4, field.selectionStart)
    }
}
